-- ============================================================================
-- V77. Разрыв циклов физической сети удалением оптимального ребра.
--
-- Контекст: ST_Node в V51 создаёт фиктивные узлы в точках геометрических
-- пересечений рёбер SPH-дерева. Через них образуются циклы — нарушение
-- ТЗ 2.1 / разъяснения 5 (замкнутые маршруты к одной OKS).
--
-- Критерий выбора ребра для удаления:
--   benefit = (length × price/м × K_special)
--           + 5M × [start_node — degree-3 branch_chamber]
--           + 5M × [end_node   — degree-3 branch_chamber]
--
-- Удаление ребра с degree-3 камерой на конце понижает её degree до 2 →
-- node_type переклассифицируется в technical_node → chamber_cost → 0.
-- На типичных данных это 3–12M ₽ экономии — существенно больше, чем
-- экономия на длине трубы (0.3–1M ₽).
--
-- Гарантия связности: удаление ребра из цикла не разрывает граф
-- (endpoints цикла остаются связаны через вторую половину цикла).
-- Каждый OKS сохраняет ≥1 путь до точки врезки.
--
-- Degree вычисляется динамически (CTE node_deg) — после каждого удаления
-- на следующей итерации degree уже актуален.
-- ============================================================================

CREATE OR REPLACE FUNCTION prune_physical_cycles(
    p_task_id    UUID,
    p_variant_id TEXT
) RETURNS INT AS $$
DECLARE
v_deleted INT := 0;
v_iter    INT;
v_edge_id BIGINT;
BEGIN
FOR v_iter IN 1..20 LOOP
v_edge_id := NULL;

WITH RECURSIVE
adj AS (
    SELECT start_node_id AS a, end_node_id AS b, id AS eid
FROM physical_segment
WHERE task_id = p_task_id AND variant_id = p_variant_id
UNION ALL
SELECT end_node_id, start_node_id, id
FROM physical_segment
WHERE task_id = p_task_id AND variant_id = p_variant_id
),
walk AS (
    SELECT a AS start_v, b AS cur_v,
ARRAY[a, b]::bigint[] AS path,
ARRAY[eid] AS eids
FROM adj
UNION ALL
SELECT w.start_v, a.b,
w.path || a.b,
w.eids || a.eid
FROM walk w
JOIN adj a ON a.a = w.cur_v
WHERE cardinality(w.path) < 12
AND NOT (a.eid = ANY(w.eids))
AND (a.b = w.start_v
OR a.b <> ALL(w.path[2:cardinality(w.path)]))
),
cycle_edges AS (
    SELECT DISTINCT unnest(eids) AS eid
FROM (SELECT eids FROM walk
WHERE cur_v = start_v AND cardinality(eids) >= 3
LIMIT 1) t
),
node_deg AS (
    SELECT n.id, n.node_type,
COUNT(ps.id)::int AS deg
FROM physical_node n
LEFT JOIN physical_segment ps
ON ps.task_id = n.task_id
AND ps.variant_id = n.variant_id
AND (ps.start_node_id = n.id OR ps.end_node_id = n.id)
WHERE n.task_id = p_task_id AND n.variant_id = p_variant_id
GROUP BY n.id, n.node_type
),
edge_benefit AS (
    SELECT ps.id,
ps.length_m
* (CASE
WHEN ps.diameter <= 100  THEN 3780.0
WHEN ps.diameter <= 125  THEN 4050.0
WHEN ps.diameter <= 150  THEN 4590.0
WHEN ps.diameter <= 200  THEN 5580.0
WHEN ps.diameter <= 250  THEN 6840.0
WHEN ps.diameter <= 300  THEN 8370.0
WHEN ps.diameter <= 400  THEN 11070.0
WHEN ps.diameter <= 500  THEN 13860.0
WHEN ps.diameter <= 600  THEN 16740.0
WHEN ps.diameter <= 700  THEN 19530.0
WHEN ps.diameter <= 800  THEN 22410.0
WHEN ps.diameter <= 900  THEN 25200.0
WHEN ps.diameter <= 1000 THEN 28080.0
WHEN ps.diameter <= 1200 THEN 33660.0
ELSE 39330.0
END)
* COALESCE(ps.special_k, 1.0)
+ 5000000.0 * (
    CASE WHEN sn.node_type = 'branch_chamber' AND sn.deg = 3
THEN 1 ELSE 0 END
+ CASE WHEN en.node_type = 'branch_chamber' AND en.deg = 3
THEN 1 ELSE 0 END
) AS benefit
FROM physical_segment ps
JOIN node_deg sn ON sn.id = ps.start_node_id
JOIN node_deg en ON en.id = ps.end_node_id
WHERE ps.id IN (SELECT eid FROM cycle_edges)
)
SELECT id INTO v_edge_id
FROM edge_benefit
ORDER BY benefit DESC
LIMIT 1;

IF v_edge_id IS NULL THEN EXIT; END IF;

DELETE FROM physical_segment WHERE id = v_edge_id;
v_deleted := v_deleted + 1;
END LOOP;

-- Пересчёт degree + переклассификация узлов после удалений.
-- degree=2 → technical_node (chamber_cost = 0).
-- degree≥3 → branch_chamber. OKS/existing_tie_in не трогаем.
UPDATE physical_node pn
SET degree = sub.nd,
node_type = CASE
WHEN sub.nd >= 3 THEN 'branch_chamber'
WHEN sub.nd = 2  THEN 'technical_node'
WHEN sub.nd = 1  THEN 'new_terminal_chamber'
ELSE pn.node_type
END,
chamber_cost = CASE WHEN sub.nd < 3 THEN 0 ELSE pn.chamber_cost END
FROM (
    SELECT n.id, COUNT(ps.id)::int AS nd
FROM physical_node n
LEFT JOIN physical_segment ps
ON ps.task_id = n.task_id
AND ps.variant_id = n.variant_id
AND (ps.start_node_id = n.id OR ps.end_node_id = n.id)
WHERE n.task_id = p_task_id
AND n.variant_id = p_variant_id
AND n.node_type NOT IN ('oks', 'existing_tie_in')
GROUP BY n.id
) sub
WHERE pn.id = sub.id;

-- Удалить осиротевшие узлы, оставшиеся без сегментов.
DELETE FROM physical_node pn
WHERE pn.task_id = p_task_id
AND pn.variant_id = p_variant_id
AND pn.node_type NOT IN ('oks', 'existing_tie_in')
AND NOT EXISTS (
    SELECT 1 FROM physical_segment ps
    WHERE ps.task_id = pn.task_id
AND ps.variant_id = pn.variant_id
AND (ps.start_node_id = pn.id OR ps.end_node_id = pn.id)
);

RETURN v_deleted;
END;
$$ LANGUAGE plpgsql;

COMMENT ON FUNCTION prune_physical_cycles(UUID, TEXT) IS
'V77: разрыв циклов физической сети. Критерий удаления — max(benefit) =
стоимость трубы + 5M за каждую degree-3 branch_chamber, которая после
удаления станет degree-2. Гарантия связности: удаление ребра из цикла
не разрывает граф. После — пересчёт degree и переклассификация узлов.';