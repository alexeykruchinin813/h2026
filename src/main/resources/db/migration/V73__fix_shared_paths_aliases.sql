-- ============================================================================
-- V73. Фикс алиасов source_vertex/target_vertex в find_shared_paths_from_group.
--
-- Проблема V72: SQL-строка графа обращалась к несуществующим колонкам
-- "source"/"target" в visibility_edge. Реальные колонки — source_vertex
-- и target_vertex (DDL V14). pgr_dijkstra ждёт именно source/target,
-- поэтому нужны явные алиасы (как сделано в V28 find_visibility_path).
--
-- Симптом: ERROR: column "source" does not exist при первом вызове функции.
--
-- Изменение: только строка SELECT в v_graph_sql. Логика, сигнатура,
-- фильтры V55 и roots_for_oks — без изменений относительно V72.
-- ============================================================================

DROP FUNCTION IF EXISTS find_shared_paths_from_group(UUID, INT, BIGINT[], BIGINT[]);

CREATE OR REPLACE FUNCTION find_shared_paths_from_group(
    p_task_id     UUID,
    p_cluster_id  INT,
p_target_arr  BIGINT[],
p_oks_arr     BIGINT[]
) RETURNS TABLE(
    oks_vertex_id    BIGINT,
    target_vertex_id BIGINT,
    total_cost       DOUBLE PRECISION,
total_length     NUMERIC,
edge_count       INT,
path_geom        GEOMETRY,
edge_ids         BIGINT[]
) AS $$
DECLARE
v_targets_sql TEXT;
v_graph_sql   TEXT;
BEGIN
IF p_target_arr IS NULL OR array_length(p_target_arr, 1) IS NULL THEN
RETURN;
END IF;
IF p_oks_arr IS NULL OR array_length(p_oks_arr, 1) IS NULL THEN
RETURN;
END IF;

v_targets_sql := array_to_string(p_target_arr, ',');

-- Критично: колонки visibility_edge — source_vertex/target_vertex,
-- а pgr_dijkstra ждёт source/target → явные алиасы (как в V28).
v_graph_sql :=
'WITH virtual_root AS ( '
|| '  SELECT (1000000000000000::bigint + t) AS id, '
|| '         -1::bigint  AS source, '
|| '         t           AS target, '
|| '         0.0::double precision AS cost, '
|| '         0.0::double precision AS reverse_cost '
|| '  FROM unnest(ARRAY[' || v_targets_sql || ']::bigint[]) AS t '
|| ') '
|| 'SELECT id, '
|| '       source_vertex AS source, '
|| '       target_vertex AS target, '
|| '       cost::double precision         AS cost, '
|| '       reverse_cost::double precision AS reverse_cost '
|| '  FROM visibility_edge '
|| ' WHERE task_id = ' || quote_literal(p_task_id::text)
|| '   AND cluster_id = ' || p_cluster_id
|| ' UNION ALL '
|| 'SELECT id, source, target, cost, reverse_cost FROM virtual_root';

RETURN QUERY
WITH dijkstra_raw AS (
    SELECT d.seq, d.path_seq, d.start_vid, d.end_vid,
d.node, d.edge,
d.cost::double precision      AS cost,
d.agg_cost::double precision  AS agg_cost
FROM pgr_dijkstra(
    v_graph_sql,
-1::bigint,
p_oks_arr,
directed := false
) d
),
roots_for_oks AS (
    SELECT DISTINCT ON (d.end_vid)
d.end_vid AS oks_id,
d.node    AS target_vid
FROM dijkstra_raw d
WHERE d.path_seq = 2
AND d.node = ANY(p_target_arr)
ORDER BY d.end_vid
),
grouped AS (
    SELECT d.end_vid AS oks_id,
r.target_vid,
array_agg(d.edge ORDER BY d.path_seq) AS eids,
SUM(d.cost)::double precision         AS sum_cost
FROM dijkstra_raw d
JOIN roots_for_oks r ON r.oks_id = d.end_vid
WHERE d.edge > 0
AND d.edge < 1000000000000000
GROUP BY d.end_vid, r.target_vid
),
bad_paths AS (
    SELECT DISTINCT g.oks_id
    FROM grouped g
CROSS JOIN LATERAL unnest(g.eids) AS eid
JOIN visibility_edge ve ON ve.id = eid
JOIN visibility_vertex vv2
ON vv2.id IN (ve.source_vertex, ve.target_vertex)
WHERE vv2.vertex_type = 'oks'
AND vv2.id <> g.oks_id
),
geom_agg AS (
    SELECT g.oks_id,
g.target_vid,
g.eids,
g.sum_cost,
ST_LineMerge(ST_Collect(ve.geom
ORDER BY array_position(g.eids, ve.id))) AS path_geom,
COALESCE(SUM(ve.length_m), 0)::NUMERIC        AS total_len,
array_length(g.eids, 1)                       AS ecount
FROM grouped g
JOIN visibility_edge ve ON ve.id = ANY(g.eids)
WHERE g.oks_id NOT IN (SELECT oks_id FROM bad_paths)
GROUP BY g.oks_id, g.target_vid, g.eids, g.sum_cost
)
SELECT ga.oks_id,
ga.target_vid,
ga.sum_cost,
ga.total_len,
ga.ecount,
ga.path_geom,
ga.eids
FROM geom_agg ga;
END;
$$ LANGUAGE plpgsql;

COMMENT ON FUNCTION find_shared_paths_from_group(UUID, INT, BIGINT[], BIGINT[]) IS
'V73: multi-target pgr_dijkstra от виртуального корня target-группы.
Возвращает дерево путей, шарящих общие рёбра. Исключает транзит через чужой OKS (V55).
Для отброшенных OKS Java-слой делает fallback на find_visibility_path_geom.
Исправлены алиасы source_vertex/target_vertex → source/target для pgr_dijkstra.';