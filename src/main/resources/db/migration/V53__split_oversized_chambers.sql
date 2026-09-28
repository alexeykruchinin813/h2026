-- ============================================================================
-- V53. Универсальный split узлов с degree > 4.
--
-- Диагноз: после планаризации и терминальности OKS некоторые branch_chamber
-- имеют degree = 5 (или больше). ТЗ 2.3: «к одной тепловой камере может
-- примыкать не более четырёх участков». Нарушение устраняется расщеплением
-- узла на два: P (4 примыкания) + P' (K-4 примыкания), соединённых
-- коротким сегментом.
--
-- Алгоритм не зависит от данных:
--   1. Инцидентные сегменты сортируются по азимуту от P.
--   2. У P остаются 4 «якорных»: индексы 0, K/4, K/2, 3K/4 в сортировке.
--   3. Остальные K-4 идут в P' — новый узел в направлении среднего
--      азимута перенесённых труб.
--   4. P↔P' соединены новым сегментом (flow = сумма, diameter = max).
--   5. Итерации до стабилизации degree ≤ 4 (не более 10 проходов).
--
-- Применяется только к branch_chamber / new_terminal_chamber.
-- OKS и existing_tie_in не трогаются (для них — свои механизмы).
-- ============================================================================

CREATE OR REPLACE FUNCTION split_oversized_chambers(
    p_task_id    UUID,
    p_variant_id TEXT,
p_max_iters  INT DEFAULT 10
) RETURNS INT AS $$
DECLARE
v_iter         INT := 0;
v_total_ops    INT := 0;
v_oversized    INT;
v_split_len_m  CONSTANT DOUBLE PRECISION := 5.0;
BEGIN
LOOP
v_iter := v_iter + 1;
EXIT WHEN v_iter > p_max_iters;

SELECT COUNT(*) INTO v_oversized
FROM physical_node
WHERE task_id = p_task_id
AND variant_id = p_variant_id
AND degree > 4
AND node_type IN ('branch_chamber','new_terminal_chamber');

EXIT WHEN v_oversized = 0;

-- ====================================================================
-- Собираем инцидентные сегменты для каждого превышающего узла,
-- сортируем по азимуту.
-- ====================================================================
DROP TABLE IF EXISTS _split_incident;
CREATE TEMP TABLE _split_incident ON COMMIT DROP AS
WITH oversized AS (
    SELECT id AS node_id, geom AS node_geom, degree
FROM physical_node
WHERE task_id = p_task_id
AND variant_id = p_variant_id
AND degree > 4
AND node_type IN ('branch_chamber','new_terminal_chamber')
)
SELECT o.node_id, o.node_geom, o.degree,
ps.id AS seg_id,
ps.start_node_id, ps.end_node_id,
ps.flow_tph, ps.laying_method, ps.special_k,
ps.diameter,
CASE
WHEN ps.start_node_id = o.node_id
THEN ST_Azimuth(o.node_geom, ST_StartPoint(ps.geom))
ELSE ST_Azimuth(o.node_geom, ST_EndPoint(ps.geom))
END AS azimuth
FROM oversized o
JOIN physical_segment ps
ON (ps.start_node_id = o.node_id OR ps.end_node_id = o.node_id)
AND ps.task_id = p_task_id
AND ps.variant_id = p_variant_id;

-- ====================================================================
-- Ранжируем сегменты внутри узла по азимуту.
-- Anchor_rank: 1..4 для тех, что остаются у P.
-- ====================================================================
DROP TABLE IF EXISTS _split_ranked;
CREATE TEMP TABLE _split_ranked ON COMMIT DROP AS
WITH sorted AS (
    SELECT si.*,
ROW_NUMBER() OVER (PARTITION BY node_id ORDER BY azimuth) AS rn,
COUNT(*) OVER (PARTITION BY node_id) AS total
FROM _split_incident si
)
SELECT s.*,
-- 4 якоря: индексы 1, floor(K/4)+1, floor(K/2)+1, floor(3K/4)+1
(s.rn = 1
OR s.rn = floor(s.total::float / 4.0) + 1
OR s.rn = floor(s.total::float / 2.0) + 1
OR s.rn = floor(s.total::float * 3.0 / 4.0) + 1) AS is_anchor
FROM sorted s;

-- ====================================================================
-- Готовим операции:
--  * move: сегменты, переносимые в новый узел;
--  * new_node: описания новых узлов (по одному на исходный P).
-- ====================================================================
DROP TABLE IF EXISTS _split_moves;
CREATE TEMP TABLE _split_moves ON COMMIT DROP AS
SELECT node_id, node_geom, degree,
seg_id,
(start_node_id = node_id) AS from_p,
azimuth
FROM _split_ranked
WHERE NOT is_anchor;

DROP TABLE IF EXISTS _split_new_nodes;
CREATE TEMP TABLE _split_new_nodes ON COMMIT DROP AS
WITH agg AS (
    SELECT node_id, node_geom, degree,
COUNT(*) AS moved_count,
-- средний азимут через векторную сумму
ST_Azimuth(
    ST_MakePoint(0, 0),
ST_MakePoint(
    SUM(cos(azimuth)),
SUM(sin(azimuth))
)
) AS avg_azimuth
FROM _split_moves
GROUP BY node_id, node_geom, degree
HAVING COUNT(*) > 0
)
SELECT node_id AS orig_node_id,
node_geom AS orig_geom,
degree AS orig_degree,
moved_count,
-- новая точка: сдвиг на split_len в направлении avg_azimuth
ST_Project(node_geom::geography, 5.0, avg_azimuth)::geometry AS new_geom
FROM agg;

-- ====================================================================
-- Если новых узлов нет — выходим (нет что переносить).
-- ====================================================================
IF (SELECT COUNT(*) FROM _split_new_nodes) = 0 THEN
EXIT;
END IF;

-- ====================================================================
-- Вставляем новые узлы.
-- ====================================================================
INSERT INTO physical_node
(task_id, variant_id, geom, node_type, degree, ref_id, chamber_cost)
SELECT p_task_id, p_variant_id, sn.new_geom,
'branch_chamber',
0,  -- пересчитается ниже
NULL,
CASE
WHEN sn.moved_count >= 3 THEN 3000000
WHEN sn.moved_count = 2  THEN 3000000
ELSE 3000000
END
FROM _split_new_nodes sn;

-- ====================================================================
-- Связь orig_node_id → id нового узла.
-- Сопоставление по геометрии (ST_Equals после ST_SnapToGrid).
-- ====================================================================
DROP TABLE IF EXISTS _split_map;
CREATE TEMP TABLE _split_map ON COMMIT DROP AS
SELECT sn.orig_node_id, pn.id AS new_node_id,
sn.new_geom AS new_geom, sn.orig_geom,
sn.moved_count
FROM _split_new_nodes sn
JOIN physical_node pn
ON pn.task_id = p_task_id
AND pn.variant_id = p_variant_id
AND ST_Equals(ST_SnapToGrid(pn.geom, 0.01),
ST_SnapToGrid(sn.new_geom, 0.01));

-- ====================================================================
-- Переносим сегменты: если сегмент начинался в P → start_node_id = P';
-- если заканчивался в P → end_node_id = P'.
-- ====================================================================
UPDATE physical_segment ps
SET start_node_id = m.new_node_id
FROM _split_moves mv
JOIN _split_map   m ON m.orig_node_id = mv.node_id
WHERE ps.id = mv.seg_id
AND mv.from_p = true
AND ps.task_id = p_task_id
AND ps.variant_id = p_variant_id;

UPDATE physical_segment ps
SET end_node_id = m.new_node_id
FROM _split_moves mv
JOIN _split_map   m ON m.orig_node_id = mv.node_id
WHERE ps.id = mv.seg_id
AND mv.from_p = false
AND ps.task_id = p_task_id
AND ps.variant_id = p_variant_id;

-- ====================================================================
-- Соединительные сегменты P ↔ P'.
-- Геометрия — прямая линия; flow = сумма flow перенесённых;
-- diameter = max среди перенесённых; laying_method = base.
-- ====================================================================
INSERT INTO physical_segment
(task_id, variant_id, start_node_id, end_node_id, geom,
flow_tph, length_m, laying_method, special_k, diameter, cost)
SELECT p_task_id, p_variant_id,
sm.orig_node_id,
sm.new_node_id,
ST_MakeLine(sm.orig_geom, sm.new_geom),
agg.flow_sum,
ST_Distance(sm.orig_geom, sm.new_geom),
'base',
1.0,
agg.max_diam,
0.0
FROM _split_map sm
JOIN LATERAL (
    SELECT COALESCE(SUM(mv_flow.flow_tph), 0)  AS flow_sum,
COALESCE(MAX(mv_flow.diameter), 100) AS max_diam
FROM _split_moves mv_flow
JOIN physical_segment ps2 ON ps2.id = mv_flow.seg_id
WHERE mv_flow.node_id = sm.orig_node_id
) agg ON true;

-- ====================================================================
-- Пересчёт degree для всех затронутых узлов.
-- ====================================================================
UPDATE physical_node pn
SET degree = sub.deg
FROM (
SELECT v.id,
(SELECT COUNT(*) FROM physical_segment s
WHERE s.task_id = p_task_id
AND s.variant_id = p_variant_id
AND (s.start_node_id = v.id OR s.end_node_id = v.id)
)::int AS deg
FROM physical_node v
WHERE v.task_id = p_task_id
AND v.variant_id = p_variant_id
) sub
WHERE pn.id = sub.id;

v_total_ops := v_total_ops + (SELECT COUNT(*) FROM _split_map);

RAISE NOTICE '[split] iter %: % oversized nodes processed',
v_iter, (SELECT COUNT(*) FROM _split_map);
END LOOP;

RETURN v_total_ops;
END;
$$ LANGUAGE plpgsql;

COMMENT ON FUNCTION split_oversized_chambers(UUID, TEXT, INT) IS
'V53: универсальный split узлов branch_chamber/new_terminal_chamber с degree > 4.
Инцидентные сегменты сортируются по азимуту; 4 якорных остаются у P,
остальные переносятся в новый узел P'', P↔P'' соединены сегментом 5 м.
Не зависит от распределения tie-in. Итерации до degree ≤ 4.';