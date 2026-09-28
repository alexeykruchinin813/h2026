CREATE OR REPLACE FUNCTION dedup_parallel_segments(p_task_id UUID, p_variant_id TEXT)
RETURNS INTEGER AS $$
DECLARE v_removed INTEGER;
BEGIN
-- Канонизация концов: co-located узлы (OFFSET=0) схлопываются в один cid.
CREATE TEMP TABLE _dup ON COMMIT DROP AS
WITH canon AS (
    SELECT id,
    FIRST_VALUE(id) OVER (
    PARTITION BY ST_AsText(ST_SnapToGrid(geom, 0.001))
ORDER BY id) AS cid
FROM physical_node
WHERE task_id = p_task_id AND variant_id = p_variant_id
),
seg AS (
    SELECT s.id, s.flow_tph,
LEAST(ca.cid, cb.cid)     AS a,
GREATEST(ca.cid, cb.cid)  AS b
FROM physical_segment s
JOIN canon ca ON ca.id = s.start_node_id
JOIN canon cb ON cb.id = s.end_node_id
WHERE s.task_id = p_task_id AND s.variant_id = p_variant_id
),
pair AS (
SELECT a, b, SUM(flow_tph) AS tot
FROM seg GROUP BY a, b HAVING COUNT(*) > 1
),
ranked AS (
SELECT s.id, p.tot,
ROW_NUMBER() OVER (PARTITION BY s.a, s.b
ORDER BY s.flow_tph DESC, s.id) AS rn
FROM seg s JOIN pair p ON p.a = s.a AND p.b = s.b
)
SELECT id, rn, tot FROM ranked;

-- Слить суммарный расход в оставляемого (rn=1).
UPDATE physical_segment k SET flow_tph = d.tot
FROM _dup d WHERE d.id = k.id AND d.rn = 1;

-- Удалить дубликаты.
DELETE FROM physical_segment k USING _dup d
WHERE d.id = k.id AND d.rn > 1;
GET DIAGNOSTICS v_removed = ROW_COUNT;

RETURN v_removed;
END;
$$ LANGUAGE plpgsql;