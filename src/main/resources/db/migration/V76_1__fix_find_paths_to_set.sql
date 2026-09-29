-- ============================================================================
-- V76.1. Фикс find_paths_to_set: array+array pgr_dijkstra возвращает 0 строк
-- на реальном графе. Переписываем через virtual_root + скалярный источник.
--
-- Virtual root -1 соединён с каждым treeNode нулевым ребром (как в V73/V74
-- с targets — только здесь sources). Цели = uncovered OKS. Форма
-- pgr_dijkstra(text, bigint, bigint[], boolean) — проверена V73/V74.
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
v_graph_sql   TEXT;
BEGIN
IF p_source_arr IS NULL OR array_length(p_source_arr, 1) IS NULL THEN RETURN; END IF;
IF p_target_arr IS NULL OR array_length(p_target_arr, 1) IS NULL THEN RETURN; END IF;

-- V74-паттерн: AS t(vertex_id) даёт имя колонке, t.vertex_id резолвится
v_sources_sql := 'ARRAY[' || array_to_string(p_source_arr, ',') || ']::bigint[]';

-- Граф: реальные рёбра + виртуальные -1 → treeNode (cost = 0).
-- Скалярный источник -1 — форма, работающая в V73/V74.
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
|| '       cost::double precision         AS cost, '
|| '       reverse_cost::double precision AS reverse_cost '
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
-1::bigint,        -- скалярный виртуальный корень
p_target_arr,      -- массив целей (uncovered OKS)
directed := false
) d
),
-- Для каждой OKS: первый реальный узел после виртуального корня.
-- path_seq=2 — узел сразу за корнем (это target виртуального ребра = treeNode).
roots_for_oks AS (
    SELECT DISTINCT ON (d.end_vid)
d.end_vid AS oks_id,
d.node    AS source_vid
FROM dijkstra_raw d
WHERE d.path_seq = 2
AND d.node = ANY(p_source_arr)
ORDER BY d.end_vid
),
-- V55 (разъяснение 5): путь к OKS не должен проходить через ЧУЖОЙ OKS.
-- Проверяем реальные рёбра (id < 10^15); виртуальные рёбра автоматически
-- исключаются JOIN'ом с visibility_edge.
bad_paths AS (
    SELECT DISTINCT d.end_vid AS oks_id
FROM dijkstra_raw d
JOIN visibility_edge ve ON ve.id = d.edge
JOIN visibility_vertex vv2
ON vv2.id IN (ve.source_vertex, ve.target_vertex)
WHERE d.edge > 0
AND d.edge < 1000000000000000
AND vv2.vertex_type = 'oks'
AND vv2.id <> d.end_vid
),
-- Полная стоимость (MAX agg_cost по реальным рёбрам) + список рёбер.
grouped AS (
    SELECT d.end_vid AS oks_id,
r.source_vid,
array_agg(d.edge ORDER BY d.path_seq) AS eids,
MAX(d.agg_cost) AS sum_cost
FROM dijkstra_raw d
JOIN roots_for_oks r ON r.oks_id = d.end_vid
WHERE d.edge > 0
AND d.edge < 1000000000000000
AND d.end_vid NOT IN (SELECT oks_id FROM bad_paths)
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
'V76.1: virtual_root (-1) соединён с treeNodes нулевыми рёбрами.
pgr_dijkstra(text, bigint, bigint[], boolean) — проверенная в V73/V74 форма.
Для каждой uncovered OKS возвращает ближайший treeNode и путь к нему.
V55-фильтр: без транзита через чужой OKS.';