-- ============================================================================
-- V76.3. Фикс V76.2: ANY((SELECT ids FROM oks_ids)) — подзапрос возвращает
-- одну строку типа bigint[], а ANY(subquery) трактует её как набор строк →
-- bigint = bigint[] → operator does not exist.
--
-- Решение: собрать список OKS-вершин в plpgsql-переменную, вставить его
-- в SQL-строку как ЛИТЕРАЛ массива: ARRAY[1,2,3]::bigint[].
-- ============================================================================

DROP FUNCTION IF EXISTS find_paths_to_set(UUID, INT, BIGINT[], BIGINT[]);

CREATE OR REPLACE FUNCTION find_paths_to_set(
    p_task_id     UUID,
    p_cluster_id  INT,
p_source_arr  BIGINT[],
p_target_arr  BIGINT[]
) RETURNS TABLE(
    oks_vertex_id    BIGINT,
    target_source_id BIGINT,
    total_cost       DOUBLE PRECISION,
total_length     NUMERIC,
edge_count       INT,
path_geom        GEOMETRY,
edge_ids         BIGINT[]
) AS $$
DECLARE
v_sources_sql TEXT;
v_oks_arr_sql TEXT;
v_graph_sql   TEXT;
v_oks_ids     BIGINT[];
BEGIN
IF p_source_arr IS NULL OR array_length(p_source_arr, 1) IS NULL THEN RETURN; END IF;
IF p_target_arr IS NULL OR array_length(p_target_arr, 1) IS NULL THEN RETURN; END IF;

-- Список OKS-вершин кластера — в plpgsql-переменную.
-- Пустой массив → 'ARRAY[]::bigint[]', что синтаксически валидно.
SELECT COALESCE(array_agg(id), ARRAY[]::bigint[]) INTO v_oks_ids
FROM visibility_vertex
WHERE task_id = p_task_id
AND cluster_id = p_cluster_id
AND vertex_type = 'oks';

v_oks_arr_sql :=
'ARRAY[' || COALESCE(array_to_string(v_oks_ids, ','), '') || ']::bigint[]';

v_sources_sql :=
'ARRAY[' || array_to_string(p_source_arr, ',') || ']::bigint[]';

-- Граф: реальные рёбра + virtual_root(-1) к каждому treeNode.
-- Рёбра, инцидентные OKS-вершинам, получают +1000 м штрафа.
-- = ANY(ARRAY[...]) — литерал массива, НЕ подзапрос.
v_graph_sql :=
'WITH virtual_root AS ( '
|| '  SELECT (1000000000000000::bigint + t.vertex_id) AS id, '
|| '         -1::bigint       AS source, '
|| '         t.vertex_id      AS target, '
|| '         0.0::double precision AS cost, '
|| '         0.0::double precision AS reverse_cost '
|| '  FROM unnest(' || v_sources_sql || ') AS t(vertex_id) '
|| ') '
|| 'SELECT id, '
|| '       source_vertex AS source, '
|| '       target_vertex AS target, '
|| '       CASE WHEN source_vertex = ANY(' || v_oks_arr_sql || ') '
|| '              OR target_vertex = ANY(' || v_oks_arr_sql || ') '
|| '            THEN cost::double precision + 1000.0 '
|| '            ELSE cost::double precision END AS cost, '
|| '       CASE WHEN source_vertex = ANY(' || v_oks_arr_sql || ') '
|| '              OR target_vertex = ANY(' || v_oks_arr_sql || ') '
|| '            THEN reverse_cost::double precision + 1000.0 '
|| '            ELSE reverse_cost::double precision END AS reverse_cost '
|| '  FROM visibility_edge '
|| ' WHERE task_id = ' || quote_literal(p_task_id::text)
|| '   AND cluster_id = ' || p_cluster_id
|| ' UNION ALL '
|| 'SELECT id, source, target, cost, reverse_cost FROM virtual_root';

RETURN QUERY
WITH dijkstra_raw AS (
    SELECT d.seq, d.path_seq, d.end_vid, d.node, d.edge,
d.cost::double precision     AS cost,
d.agg_cost::double precision AS agg_cost
FROM pgr_dijkstra(
    v_graph_sql,
    -1::bigint,
p_target_arr,
directed := false
) d
),
-- Для каждой OKS — первый реальный treeNode после виртуального корня.
roots_for_oks AS (
    SELECT DISTINCT ON (d.end_vid)
d.end_vid AS oks_id,
d.node    AS source_vid
FROM dijkstra_raw d
WHERE d.path_seq = 2
AND d.node = ANY(p_source_arr)
ORDER BY d.end_vid
),
grouped AS (
    SELECT d.end_vid AS oks_id,
r.source_vid,
array_agg(d.edge ORDER BY d.path_seq) AS eids,
MAX(d.agg_cost) AS sum_cost
FROM dijkstra_raw d
JOIN roots_for_oks r ON r.oks_id = d.end_vid
WHERE d.edge > 0
AND d.edge < 1000000000000000
GROUP BY d.end_vid, r.source_vid
)
SELECT g.oks_id,
g.source_vid,
g.sum_cost,
COALESCE(SUM(ve.length_m), 0)::NUMERIC AS total_length,
array_length(g.eids, 1)                AS edge_count,
ST_LineMerge(ST_Collect(ve.geom
ORDER BY array_position(g.eids, ve.id))) AS path_geom,
g.eids
FROM grouped g
JOIN visibility_edge ve ON ve.id = ANY(g.eids)
GROUP BY g.oks_id, g.source_vid, g.sum_cost, g.eids;
END;
$$ LANGUAGE plpgsql;

COMMENT ON FUNCTION find_paths_to_set(UUID, INT, BIGINT[], BIGINT[]) IS
'V76.3: virtual_root(-1) к каждому treeNode, penalty +1000 м на OKS-инцидентные рёбра.
Список OKS-вершин подставляется литералом ARRAY[...]::bigint[] (не ANY(subquery)).';