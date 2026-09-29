-- ============================================================================
-- V76. Две функции для SPH (Greedy Steiner):
--   1) find_paths_to_set       — multi-source Dijkstra (treeNodes → uncovered OKS)
--   2) find_paths_through_tree — Dijkstra root → все OKS по рёбрам дерева
--
-- Отменяет V74/V75 (virtual_root больше не используется в основном пути).
-- V74-функция остаётся в БД, но не вызывается (идемпотентна).
-- ============================================================================

DROP FUNCTION IF EXISTS find_paths_to_set(UUID, INT, BIGINT[], BIGINT[]);
DROP FUNCTION IF EXISTS find_paths_through_tree(UUID, INT, BIGINT, BIGINT[], BIGINT[]);

-- ----------------------------------------------------------------------------
-- 1) Multi-source Dijkstra: для каждой непокрытой OKS — ближайшая вершина
--    из растущего множества treeNodes. V55-фильтр: путь не проходит через
--    чужой OKS (аналог V73/V74).
-- ----------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION find_paths_to_set(
    p_task_id     UUID,
    p_cluster_id  INT,
p_source_arr  BIGINT[],   -- treeNodes (растущее множество)
p_target_arr  BIGINT[]    -- uncovered OKS
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
v_graph_sql TEXT;
BEGIN
IF p_source_arr IS NULL OR array_length(p_source_arr, 1) IS NULL THEN RETURN; END IF;
IF p_target_arr IS NULL OR array_length(p_target_arr, 1) IS NULL THEN RETURN; END IF;

-- Критично: колонки visibility_edge — source_vertex/target_vertex,
-- pgr_dijkstra ждёт source/target → явные алиасы.
v_graph_sql :=
'SELECT id, '
|| '       source_vertex AS source, '
|| '       target_vertex AS target, '
|| '       cost::double precision         AS cost, '
|| '       reverse_cost::double precision AS reverse_cost '
|| '  FROM visibility_edge '
|| ' WHERE task_id = ' || quote_literal(p_task_id::text)
|| '   AND cluster_id = ' || p_cluster_id;

RETURN QUERY
WITH raw AS (
    SELECT d.seq, d.path_seq, d.start_vid, d.end_vid,
d.node, d.edge,
d.cost::double precision     AS cost,
d.agg_cost::double precision AS agg_cost
FROM pgr_dijkstra(v_graph_sql,
    p_source_arr, p_target_arr,
    directed := false) d
),
-- терминальные строки: edge = -1, node = end_vid, agg_cost = полная стоимость
terminals AS (
SELECT start_vid, end_vid, agg_cost
FROM raw
WHERE edge = -1 AND node = end_vid
),
-- для каждой OKS — ближайший source
best AS (
    SELECT DISTINCT ON (end_vid)
    end_vid, start_vid, agg_cost
FROM terminals
ORDER BY end_vid, agg_cost
),
-- строки пути для выбранных пар (source, oks)
chosen AS (
    SELECT r.seq, r.path_seq, r.start_vid, r.end_vid, r.node, r.edge,
r.cost, r.agg_cost
FROM raw r
JOIN best b
ON b.end_vid = r.end_vid AND b.start_vid = r.start_vid
),
-- V55: путь не должен проходить через чужой OKS
bad_paths AS (
    SELECT DISTINCT c.end_vid AS oks_id
FROM chosen c
WHERE c.edge > 0
AND EXISTS (
    SELECT 1 FROM visibility_edge ve
    JOIN visibility_vertex vv2
ON vv2.id IN (ve.source_vertex, ve.target_vertex)
WHERE ve.id = c.edge
AND vv2.vertex_type = 'oks'
AND vv2.id <> c.end_vid
)
),
grouped AS (
    SELECT c.end_vid AS oks_id,
b.start_vid AS source_id,
array_agg(c.edge ORDER BY c.path_seq) AS eids,
MAX(c.agg_cost) AS sum_cost
FROM chosen c
JOIN best b ON b.end_vid = c.end_vid AND b.start_vid = c.start_vid
WHERE c.edge > 0
AND c.end_vid NOT IN (SELECT oks_id FROM bad_paths)
GROUP BY c.end_vid, b.start_vid
)
SELECT g.oks_id,
g.source_id,
g.sum_cost,
COALESCE(SUM(ve.length_m), 0)::NUMERIC          AS total_length,
array_length(g.eids, 1)                         AS edge_count,
ST_LineMerge(ST_Collect(ve.geom
    ORDER BY array_position(g.eids, ve.id)))    AS path_geom,
g.eids
FROM grouped g
JOIN visibility_edge ve ON ve.id = ANY(g.eids)
GROUP BY g.oks_id, g.source_id, g.sum_cost, g.eids;
END;
$$ LANGUAGE plpgsql;

-- ----------------------------------------------------------------------------
-- 2) Путь от корня дерева до каждой OKS — только по рёбрам дерева.
--    Дерево ациклическое (SPH), поэтому путь единственный.
-- ----------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION find_paths_through_tree(
    p_task_id     UUID,
    p_cluster_id  INT,
    p_root        BIGINT,
p_oks_arr     BIGINT[],
p_edge_arr    BIGINT[]
) RETURNS TABLE(
    oks_vertex_id BIGINT,
    total_cost    DOUBLE PRECISION,
total_length  NUMERIC,
edge_count    INT,
path_geom     GEOMETRY,
edge_ids      BIGINT[]
) AS $$
DECLARE
v_graph_sql TEXT;
BEGIN
IF p_edge_arr IS NULL OR array_length(p_edge_arr, 1) IS NULL THEN RETURN; END IF;
IF p_oks_arr  IS NULL OR array_length(p_oks_arr,  1) IS NULL THEN RETURN; END IF;

v_graph_sql :=
'SELECT id, '
|| '       source_vertex AS source, '
|| '       target_vertex AS target, '
|| '       cost::double precision         AS cost, '
|| '       reverse_cost::double precision AS reverse_cost '
|| '  FROM visibility_edge '
|| ' WHERE task_id = ' || quote_literal(p_task_id::text)
|| '   AND cluster_id = ' || p_cluster_id
|| '   AND id = ANY(ARRAY[' || array_to_string(p_edge_arr, ',') || ']::bigint[])';

RETURN QUERY
WITH raw AS (
    SELECT d.seq, d.path_seq, d.start_vid, d.end_vid,
d.node, d.edge,
d.agg_cost::double precision AS agg_cost
FROM pgr_dijkstra(v_graph_sql, p_root, p_oks_arr, directed := false) d
),
grouped AS (
SELECT r.end_vid AS oks_id,
array_agg(r.edge ORDER BY r.path_seq) AS eids,
MAX(r.agg_cost) AS sum_cost
FROM raw r
WHERE r.edge > 0
GROUP BY r.end_vid
)
SELECT g.oks_id,
g.sum_cost,
COALESCE(SUM(ve.length_m), 0)::NUMERIC          AS total_length,
array_length(g.eids, 1)                         AS edge_count,
ST_LineMerge(ST_Collect(ve.geom
    ORDER BY array_position(g.eids, ve.id)))    AS path_geom,
g.eids
FROM grouped g
JOIN visibility_edge ve ON ve.id = ANY(g.eids)
GROUP BY g.oks_id, g.sum_cost, g.eids;
END;
$$ LANGUAGE plpgsql;

COMMENT ON FUNCTION find_paths_to_set(UUID, INT, BIGINT[], BIGINT[]) IS
'V76 (SPH): multi-source pgr_dijkstra от растущего множества treeNodes к непокрытым OKS.
Возвращает для каждой OKS ближайшую вершину дерева и путь к ней. V55-фильтр: без транзита через чужой OKS.';

COMMENT ON FUNCTION find_paths_through_tree(UUID, INT, BIGINT, BIGINT[], BIGINT[]) IS
'V76 (SPH): pgr_dijkstra от корня дерева до всех OKS по подмножеству рёбер (treeEdges).
Дерево ациклическое — путь единственный. Возвращает полные пути для сохранения в path_result.';