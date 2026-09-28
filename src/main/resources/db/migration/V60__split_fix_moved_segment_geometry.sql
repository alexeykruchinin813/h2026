-- ============================================================================
-- V60. Фикс «Проблемы A» (bad_crossings=1, ТЗ 2.1).
--
-- Диагноз (подтверждён SQL A на варианте v1):
--   Пересекающаяся пара:
--     A=1104  start=1086/branch_chamber/ref=split_i1_1052 -> end=1067/oks  len=98.22
--     B=1138  start=1052/branch_chamber                   -> end=1086        len=5.00
--   Узел 1086 создан split-ом из 1052 (маркер ref='split_i1_1052').
--
-- Механизм: в шагах 7/7b функция меняет у переносимого сегмента
-- start_node_id / end_node_id на новый узел P', но геометрию НЕ трогает.
-- Сегмент топологически «начинается» в P', а геометрически всё ещё выходит из
-- старой точки P, поэтому его геометрия пересекает соединительный коннектор
-- P->P' не в общем узле, а в середине (ST_Intersects AND NOT ST_Touches).
--
-- Правка (минимальная, только шаги 7/7b): при переносе заменить ту концевую
-- точку геометрии, которая лежала в P, на геометрию P' (ST_SetPoint) и
-- пересчитать length_m. Тогда перенесённый сегмент и коннектор имеют общей
-- только точку P' (это ST_Touches, в bad_crossings не попадает).
--   from_p = true  -> сегмент начинался в P  -> меняем точку 0;
--   from_p = false -> сегмент заканчивался в P -> меняем последнюю точку.
-- Вся остальная логика функции без изменений относительно V59.
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

-- 1. Инцидентные сегменты + азимут в ДАЛЬНИЙ конец.
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
THEN ST_Azimuth(o.node_geom, ST_EndPoint(ps.geom))
ELSE ST_Azimuth(o.node_geom, ST_StartPoint(ps.geom))
END AS azimuth
FROM oversized o
JOIN physical_segment ps
ON (ps.start_node_id = o.node_id OR ps.end_node_id = o.node_id)
AND ps.task_id = p_task_id
AND ps.variant_id = p_variant_id;

-- 2. Ранжирование по азимуту. 3 якоря + 1 соединительный = degree 4.
DROP TABLE IF EXISTS _split_ranked;
CREATE TEMP TABLE _split_ranked ON COMMIT DROP AS
WITH sorted AS (
    SELECT si.*,
ROW_NUMBER() OVER (PARTITION BY node_id ORDER BY azimuth NULLS LAST) AS rn,
COUNT(*) OVER (PARTITION BY node_id) AS total
FROM _split_incident si
)
SELECT s.*,
(s.rn = 1
OR s.rn = floor(s.total::float / 3.0) + 1
OR s.rn = floor(s.total::float * 2.0 / 3.0) + 1) AS is_anchor
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

-- 4a. Candidate new_geom по среднему азимуту.
DROP TABLE IF EXISTS _split_candidates;
CREATE TEMP TABLE _split_candidates ON COMMIT DROP AS
WITH agg AS (
    SELECT node_id, node_geom, degree,
COUNT(*) AS moved_count,
SUM(cos(azimuth)) AS sum_cos,
SUM(sin(azimuth)) AS sum_sin,
(array_agg(azimuth ORDER BY seg_id)
FILTER (WHERE azimuth IS NOT NULL))[1] AS first_azimuth
FROM _split_moves
GROUP BY node_id, node_geom, degree
HAVING COUNT(*) > 0
),
dir AS (
SELECT a.*,
COALESCE(
    CASE
    WHEN abs(COALESCE(a.sum_cos, 0)) < 1e-9
AND abs(COALESCE(a.sum_sin, 0)) < 1e-9
THEN a.first_azimuth
ELSE atan2(a.sum_sin, a.sum_cos)
END,
0.0
) AS avg_azimuth
FROM agg a
)
SELECT node_id, node_geom, degree, moved_count, avg_azimuth,
ST_SetSRID(
    ST_MakePoint(
    ST_X(node_geom) + v_split_len_m * sin(avg_azimuth),
ST_Y(node_geom) + v_split_len_m * cos(avg_azimuth)
),
ST_SRID(node_geom)
) AS candidate_geom
FROM dir;

-- 4b. Проверка конфликта коннектора; при пересечении — разворот 180°.
DROP TABLE IF EXISTS _split_new_nodes;
CREATE TEMP TABLE _split_new_nodes ON COMMIT DROP AS
WITH conn AS (
    SELECT c.node_id, c.node_geom, c.degree, c.moved_count,
c.avg_azimuth, c.candidate_geom,
ST_MakeLine(c.node_geom, c.candidate_geom) AS line
FROM _split_candidates c
),
conflicts AS (
    SELECT c.node_id
    FROM conn c
WHERE EXISTS (
    SELECT 1 FROM physical_segment ps
    WHERE ps.task_id = p_task_id
AND ps.variant_id = p_variant_id
AND ps.start_node_id <> c.node_id
AND ps.end_node_id   <> c.node_id
AND ST_Intersects(c.line, ps.geom)
AND NOT ST_Touches(c.line, ps.geom)
)
)
SELECT c.node_id AS orig_node_id,
c.node_geom AS orig_geom,
c.degree AS orig_degree,
c.moved_count,
CASE
WHEN cf.node_id IS NULL THEN c.candidate_geom
ELSE ST_SetSRID(
    ST_MakePoint(
    ST_X(c.node_geom) + v_split_len_m * sin(c.avg_azimuth + pi()),
ST_Y(c.node_geom) + v_split_len_m * cos(c.avg_azimuth + pi())
),
ST_SRID(c.node_geom)
)
END AS new_geom
FROM conn c
LEFT JOIN conflicts cf ON cf.node_id = c.node_id;

IF (SELECT COUNT(*) FROM _split_new_nodes) = 0 THEN
EXIT;
END IF;

-- 5. INSERT новых узлов с уникальным маркером.
INSERT INTO physical_node
(task_id, variant_id, geom, node_type, degree, ref_id, chamber_cost)
SELECT p_task_id, p_variant_id, sn.new_geom,
'branch_chamber',
0,
'split_i' || v_iter || '_' || sn.orig_node_id::text,
3000000
FROM _split_new_nodes sn;

-- 6. Маппинг orig -> new по маркеру.
DROP TABLE IF EXISTS _split_map;
CREATE TEMP TABLE _split_map ON COMMIT DROP AS
SELECT sn.orig_node_id, pn.id AS new_node_id,
sn.new_geom, sn.orig_geom, sn.moved_count
FROM _split_new_nodes sn
JOIN physical_node pn
ON pn.task_id = p_task_id
AND pn.variant_id = p_variant_id
AND pn.ref_id = 'split_i' || v_iter || '_' || sn.orig_node_id::text;

-- 7. Перенос сегментов: start_node_id + ПЕРЕСОЗДАНИЕ ГЕОМЕТРИИ.
--    Сегмент начинался в P (from_p=true): заменяем точку 0 на P'.
UPDATE physical_segment ps
SET start_node_id = m.new_node_id,
geom          = ST_SetPoint(ps.geom, 0, m.new_geom),
length_m      = ST_Length(ST_SetPoint(ps.geom, 0, m.new_geom))
FROM _split_moves mv
JOIN _split_map   m ON m.orig_node_id = mv.node_id
WHERE ps.id = mv.seg_id
AND mv.from_p = true
AND ps.task_id = p_task_id
AND ps.variant_id = p_variant_id;

-- 7b. Перенос сегментов: end_node_id + ПЕРЕСОЗДАНИЕ ГЕОМЕТРИИ.
--     Сегмент заканчивался в P (from_p=false): меняем последнюю точку на P'.
UPDATE physical_segment ps
SET end_node_id = m.new_node_id,
geom        = ST_SetPoint(ps.geom, ST_NumPoints(ps.geom) - 1, m.new_geom),
length_m    = ST_Length(ST_SetPoint(ps.geom, ST_NumPoints(ps.geom) - 1, m.new_geom))
FROM _split_moves mv
JOIN _split_map   m ON m.orig_node_id = mv.node_id
WHERE ps.id = mv.seg_id
AND mv.from_p = false
AND ps.task_id = p_task_id
AND ps.variant_id = p_variant_id;

-- 8. Соединительный сегмент P <-> P'.
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
    SELECT COALESCE(SUM(ps2.flow_tph), 0)    AS flow_sum,
COALESCE(MAX(ps2.diameter), 100)  AS max_diam
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

IF v_oversized > 0 THEN
RAISE WARNING '[split] p_max_iters (%) exhausted; % oversized nodes remain',
p_max_iters, v_oversized;
END IF;

RETURN v_total_ops;
END;
$$ LANGUAGE plpgsql;

COMMENT ON FUNCTION split_oversized_chambers(UUID, TEXT, INT) IS
'V60: фикс bad_crossings — при переносе сегмента с P на P'' пересоздаётся его
концевая точка (ST_SetPoint) и пересчитывается length_m, чтобы геометрия
совпадала с новыми start/end_node_id и не пересекала коннектор P→P''.
Изменены только шаги 7/7b; остальное идентично V59.';