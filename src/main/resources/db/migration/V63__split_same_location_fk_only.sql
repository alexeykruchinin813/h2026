-- ============================================================================
-- V63. split_oversized_chambers: разделение без изменения геометрии.
--
-- Диагноз по прогону (task=b610e26d, PL/pgSQL работает, V62 alias fix
-- успешен): split фиксит degree_gt_4, но создаёт 3 bad_crossings.
-- Причина — та же, что была в V60/V61: пересоздание геометрии перенесённого
-- сегмента (P'→far_end) режет якорные сегменты, остающиеся на P.
-- Coherence с V63-координацией: теперь split вызывается чаще, чем раньше
-- (14 OKS распределены по разным tie-in, их пути сходятся в других
-- branch_chamber), и каждое split-действие даёт +1 bad_crossing.
--
-- Решение (Variant A1, согласовано с задачей о слиянии камер):
--   P' создаётся в ТОЙ ЖЕ точке, что и P. Меняется только FK. Геометрия
--   сегментов не трогается. Коннектор не создаётся (нулевой длины нет).
--   Планарность сохраняется тождественно: множество геометрий до и после
--   split идентично, значит идентично и множество пересечений.
--
--   Цена: два physical_node в одной точке. Две камеры на местности
--   ставятся в одном котловане; стоимости пересчитываются независимо
--   (каждая по max ДУ своих примыканий).
--
--   Split по-прежнему итеративен: если P' получает > 4 (возможно при
--   degree ≥ 8), следующий виток разобьёт и его.
--
-- Интерфейс функции совпадает с V59/V60/V61/V62 → Java не меняется.
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

-- 2. Ранжирование по азимуту: 3 якоря остаются на P,
--    остальные переносятся на P'. (Порядок выбора не важен для
--    планарности — все сдвиги теперь без геометрии.)
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
start_node_id, end_node_id,
(start_node_id = node_id) AS from_p
FROM _split_ranked
WHERE NOT is_anchor;

-- 4. Множество узлов для разделения. new_geom = orig_geom (V63).
DROP TABLE IF EXISTS _split_new_nodes;
CREATE TEMP TABLE _split_new_nodes ON COMMIT DROP AS
SELECT DISTINCT node_id AS orig_node_id,
node_geom AS orig_geom,
degree   AS orig_degree
FROM _split_moves;

IF (SELECT COUNT(*) FROM _split_new_nodes) = 0 THEN
EXIT;
END IF;

-- 5. INSERT новых узлов В ТОЙ ЖЕ ТОЧКЕ, что и оригинал.
INSERT INTO physical_node
(task_id, variant_id, geom, node_type, degree, ref_id, chamber_cost)
SELECT p_task_id, p_variant_id, sn.orig_geom,
'branch_chamber',
0,
'split_i' || v_iter || '_' || sn.orig_node_id::text,
3000000
FROM _split_new_nodes sn;

-- 6. Маппинг orig -> new по маркеру.
DROP TABLE IF EXISTS _split_map;
CREATE TEMP TABLE _split_map ON COMMIT DROP AS
SELECT sn.orig_node_id, pn.id AS new_node_id,
sn.orig_geom, sn.orig_degree
FROM _split_new_nodes sn
JOIN physical_node pn
ON pn.task_id = p_task_id
AND pn.variant_id = p_variant_id
AND pn.ref_id = 'split_i' || v_iter || '_' || sn.orig_node_id::text;

-- 7. Перенос FK БЕЗ изменения геометрии (from_p=true).
--    ST_MakeLine/ST_SetPoint больше не используются. Сегмент
--    геометрически всё ещё начинается в точке P = P', поэтому
--    никаких новых пересечений не возникает.
UPDATE physical_segment ps
SET start_node_id = m.new_node_id
FROM _split_moves mv
JOIN _split_map m ON m.orig_node_id = mv.node_id
WHERE ps.id = mv.seg_id
AND mv.from_p = true
AND ps.task_id = p_task_id
AND ps.variant_id = p_variant_id;

-- 7b. Перенос FK БЕЗ изменения геометрии (from_p=false).
UPDATE physical_segment ps
SET end_node_id = m.new_node_id
FROM _split_moves mv
JOIN _split_map m ON m.orig_node_id = mv.node_id
WHERE ps.id = mv.seg_id
AND mv.from_p = false
AND ps.task_id = p_task_id
AND ps.variant_id = p_variant_id;

-- 8. Соединительный сегмент НЕ создаётся: P и P' в одной точке,
--    нулевой длины не бывает. Участок существующей сети между
--    P и P' не нужен — это один и тот же физический узел.

-- 9. Пересчёт degree (для обеих камер — P и P').
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

RAISE NOTICE '[split] iter %: % oversized nodes split (same location)',
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
'V63: split без изменения геометрии. P'' создаётся в той же точке, что P,
меняется только FK перенесённых сегментов. Планарность сохраняется
тождественно. Коннектор не создаётся. Итеративно, до degree ≤ 4.';