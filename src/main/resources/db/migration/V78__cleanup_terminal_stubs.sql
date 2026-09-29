-- ============================================================================
-- V78. Чистка висячих стубов + переклассификация degree-2 камер.
--
-- Проблема после V77:
--   v1: new_terminal_chamber deg=1 (1), technical_node deg=1 (1),
--       branch_chamber deg=2 (1)
--   v3: new_terminal_chamber deg=1 (1)
--
-- Причина: V77 удаляет рёбра циклов, но не чистит получившиеся висячие узлы.
-- Плюс переклассификация degree-2 в V77 обёрнута в цикл, который не
-- выполняется, если циклов не было.
--
-- Решение — внешняя итерация:
--   1) удалить стуб-сегменты (deg=1 non-OKS/non-tie на конце), ЕСЛИ другой
--      конец тоже non-OKS/non-tie (защита от отрезания OKS);
--   2) удалить осиротевшие узлы;
--   3) пересчитать degree;
--   4) переклассифицировать: deg=2 branch_chamber → technical_node;
--   5) EXIT, если ничего не удалилось.
-- ============================================================================

DROP FUNCTION IF EXISTS cleanup_terminal_stubs(UUID, TEXT);

CREATE OR REPLACE FUNCTION cleanup_terminal_stubs(
    p_task_id    UUID,
    p_variant_id TEXT
) RETURNS TABLE(reclassified INT, deleted_segs INT, deleted_nodes INT) AS $$
DECLARE
v_reclassified  INT := 0;
v_deleted_segs  INT := 0;
v_deleted_nodes INT := 0;
v_iter          INT;
v_iter_deleted  INT;
v_iter_reclass  INT;
BEGIN
FOR v_iter IN 1..10 LOOP
v_iter_deleted := 0;
v_iter_reclass := 0;

-- 1. Удаляем сегменты, инцидентные stub-узлам.
--    Stub = deg=1 non-OKS/non-tie.
--    ЗАЩИТА: не удаляем, если другой конец — OKS или tie-in.
WITH stub_nodes AS (
    SELECT n.id AS node_id
FROM physical_node n
WHERE n.task_id = p_task_id AND n.variant_id = p_variant_id
AND n.node_type NOT IN ('oks', 'existing_tie_in')
AND (SELECT COUNT(*) FROM physical_segment ps
WHERE ps.task_id = n.task_id AND ps.variant_id = n.variant_id
AND (ps.start_node_id = n.id OR ps.end_node_id = n.id)) = 1
)
DELETE FROM physical_segment ps
USING physical_node sn, physical_node en
WHERE ps.task_id = p_task_id
AND ps.variant_id = p_variant_id
AND sn.id = ps.start_node_id
AND en.id = ps.end_node_id
AND (sn.id IN (SELECT node_id FROM stub_nodes)
OR en.id IN (SELECT node_id FROM stub_nodes))
AND sn.node_type NOT IN ('oks', 'existing_tie_in')
AND en.node_type NOT IN ('oks', 'existing_tie_in');

GET DIAGNOSTICS v_iter_deleted = ROW_COUNT;
v_deleted_segs := v_deleted_segs + v_iter_deleted;

-- 2. Удаляем осиротевшие узлы (без единого инцидентного сегмента).
DELETE FROM physical_node pn
WHERE pn.task_id = p_task_id
AND pn.variant_id = p_variant_id
AND pn.node_type NOT IN ('oks', 'existing_tie_in')
AND NOT EXISTS (
    SELECT 1 FROM physical_segment ps
WHERE ps.task_id = pn.task_id AND ps.variant_id = pn.variant_id
AND (ps.start_node_id = pn.id OR ps.end_node_id = pn.id)
);
v_deleted_nodes := v_deleted_nodes
+ (SELECT COUNT(*) FROM physical_node pn
WHERE pn.task_id = p_task_id AND pn.variant_id = p_variant_id
AND pn.node_type NOT IN ('oks', 'existing_tie_in')
AND NOT EXISTS (SELECT 1 FROM physical_segment ps
WHERE ps.task_id = pn.task_id AND ps.variant_id = pn.variant_id
AND (ps.start_node_id = pn.id OR ps.end_node_id = pn.id)));

-- 3. Пересчёт degree по факту.
UPDATE physical_node pn
SET degree = sub.nd
FROM (
SELECT n.id, COUNT(ps.id)::int AS nd
FROM physical_node n
LEFT JOIN physical_segment ps
ON ps.task_id = n.task_id AND ps.variant_id = n.variant_id
AND (ps.start_node_id = n.id OR ps.end_node_id = n.id)
WHERE n.task_id = p_task_id AND n.variant_id = p_variant_id
GROUP BY n.id
) sub
WHERE pn.id = sub.id;

-- 4. Переклассификация deg=2 branch_chamber → technical_node.
--    Идёт ПОСЛЕ удаления (в отличие от V77).
UPDATE physical_node pn
SET node_type = 'technical_node',
chamber_cost = 0
WHERE pn.task_id = p_task_id
AND pn.variant_id = p_variant_id
AND pn.node_type = 'branch_chamber'
AND pn.degree = 2;

GET DIAGNOSTICS v_iter_reclass = ROW_COUNT;
v_reclassified := v_reclassified + v_iter_reclass;

-- 5. Выходим, если ничего не изменилось.
EXIT WHEN v_iter_deleted = 0 AND v_iter_reclass = 0;
END LOOP;

RETURN QUERY SELECT v_reclassified, v_deleted_segs, v_deleted_nodes;
END;
$$ LANGUAGE plpgsql;

COMMENT ON FUNCTION cleanup_terminal_stubs(UUID, TEXT) IS
'V78: чистка висячих стубов (deg=1 non-OKS/non-tie). Защита от отрезания OKS:
сегменты, ведущие к OKS или tie-in, не удаляются. Внешняя итерация —
переклассификация deg=2 branch_chamber в technical_node выполняется после
удаления на каждой итерации.';