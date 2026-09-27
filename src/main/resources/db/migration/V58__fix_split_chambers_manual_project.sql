-- ============================================================================
-- V58. Фикс split_oversized_chambers (V53/V57).
--
-- Диагноз: ST_Project(node_geom, 5.0, avg_azimuth) возвращает NULL —
-- функция либо не находит перегрузку geometry без geography, либо
-- SRID входной геометрии не в ожидаемом диапазоне. Падение: NOT NULL
-- на physical_node.geom.
--
-- Решение: ручная тригонометрия.
--   X' = X + L * sin(azimuth)
--   Y' = Y + L * cos(azimuth)
-- где L — расстояние в метрах (SRID 32637 — метры), azimuth — азимут
-- от оси Y (север), по часовой стрелке. Это стандарт PostGIS ST_Azimuth.
-- SRID сохраняется через ST_SetSRID(ST_MakePoint(...), ST_SRID(node_geom)).
-- ============================================================================

DROP FUNCTION IF EXISTS split_oversized_chambers(UUID, TEXT, INT);

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

-- 1. Инцидентные сегменты каждого превышающего узла + азимут.
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

-- 2. Ранжирование по азимуту.
DROP TABLE IF EXISTS _split_ranked;
CREATE TEMP TABLE _split_ranked ON COMMIT DROP AS
WITH sorted AS (
    SELECT si.*,
ROW_NUMBER() OVER (PARTITION BY node_id ORDER BY azimuth) AS rn,
COUNT(*) OVER (PARTITION BY node_id) AS total
FROM _split_incident si
)
SELECT s.*,
(s.rn = 1
OR s.rn = floor(s.total::float / 4.0) + 1
OR s.rn = floor(s.total::float / 2.0) + 1
OR s.rn = floor(s.total::float * 3.0 / 4.0) + 1) AS is_anchor
FROM sorted s;

-- 3. Переносимые сегменты.
DROP TABLE IF EXISTS _split_moves;
CREATE TEMP TABLE _split_moves ON COMMIT DROP AS
SELECT node_id, node_geom, degree,
seg_id,
(start_node_id = node_id) AS from_p,
azimuth
FROM _split_ranked
WHERE NOT is_anchor;

-- 4. Новые узлы (V58: ручная тригонометрия вместо ST_Project).
DROP TABLE IF EXISTS _split_new_nodes;
CREATE TEMP TABLE _split_new_nodes ON COMMIT DROP AS
WITH agg AS (
    SELECT node_id, node_geom, degree,
    COUNT(*) AS moved_count,
SUM(cos(azimuth)) AS sum_cos,
SUM(sin(azimuth)) AS sum_sin,
(array_agg(azimuth ORDER BY seg_id))[1] AS first_azimuth
FROM _split_moves
GROUP BY node_id, node_geom, degree
HAVING COUNT(*) > 0
),
dir AS (
SELECT a.*,
CASE
WHEN abs(a.sum_cos) < 1e-9 AND abs(a.sum_sin) < 1e-9
THEN a.first_azimuth
ELSE atan2(a.sum_sin, a.sum_cos)
END AS avg_azimuth
FROM agg a
)
SELECT node_id AS orig_node_id,
node_geom AS orig_geom,
degree AS orig_degree,
moved_count,
ST_SetSRID(
    ST_MakePoint(
    ST_X(node_geom) + v_split_len_m * sin(avg_azimuth),
ST_Y(node_geom) + v_split_len_m * cos(avg_azimuth)
),
ST_SRID(node_geom)
) AS new_geom
FROM dir;

IF (SELECT COUNT(*) FROM _split_new_nodes) = 0 THEN
EXIT;
END IF;

-- 5. INSERT новых узлов.
INSERT INTO physical_node
(task_id, variant_id, geom, node_type, degree, ref_id, chamber_cost)
SELECT p_task_id, p_variant_id, sn.new_geom,
'branch_chamber',
0,
NULL,
3000000
FROM _split_new_nodes sn;

-- 6. Маппинг orig_node_id → new_node_id.
DROP TABLE IF EXISTS _split_map;
CREATE TEMP TABLE _split_map ON COMMIT DROP AS
SELECT sn.orig_node_id, pn.id AS new_node_id,
sn.new_geom, sn.orig_geom, sn.moved_count
FROM _split_new_nodes sn
JOIN physical_node pn
ON pn.task_id = p_task_id
AND pn.variant_id = p_variant_id
AND ST_Equals(ST_SnapToGrid(pn.geom, 0.01),
ST_SnapToGrid(sn.new_geom, 0.01));

-- 7. Перенос сегментов: start_node_id.
UPDATE physical_segment ps
SET start_node_id = m.new_node_id
FROM _split_moves mv
JOIN _split_map   m ON m.orig_node_id = mv.node_id
WHERE ps.id = mv.seg_id
AND mv.from_p = true
AND ps.task_id = p_task_id
AND ps.variant_id = p_variant_id;

-- 7b. Перенос сегментов: end_node_id.
UPDATE physical_segment ps
SET end_node_id = m.new_node_id
FROM _split_moves mv
JOIN _split_map   m ON m.orig_node_id = mv.node_id
WHERE ps.id = mv.seg_id
AND mv.from_p = false
AND ps.task_id = p_task_id
AND ps.variant_id = p_variant_id;

-- 8. Соединительный сегмент P ↔ P'.
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

-- 9. Пересчёт degree.
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
'V58: split узлов degree > 4 через ручную тригонометрию.
X'' = X + L*sin(azimuth); Y'' = Y + L*cos(azimuth).
SRID сохраняется через ST_SRID(node_geom).';