-- ============================================================================
-- V76.2. Замена V76-фильтра bad_paths на penalty-подход.
--
-- Проблема V76: V55 (bad_paths CTE) жёстко дропает кратчайший путь, если он
-- проходит через чужой OKS-vertex. При единственном source (SPH iter 1 = 1
-- treeNode) это оставляет 2 из 14 OKS — SPH застревает.
--
-- Решение: вместо фильтра добавить штраф +1000 м на рёбра, инцидентные
-- OKS-вершинам. Dijkstra предпочитает обходить чужие OKS-вершины, но если
-- альтернативы нет — путь будет найден (со штрафом в total_cost).
--
-- total_cost в возвращаемой таблице включает штраф (используется SPH для
-- ранжирования). total_length, edge_count, path_geom — реальные.
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

-- V74-паттерн: AS t(vertex_id) даёт колонке явное имя.
v_sources_sql := 'ARRAY[' || array_to_string(p_source_arr, ',') || ']::bigint[]';

-- Граф: реальные рёбра с penalty на OKS-инцидентных рёбрах + virtual_root
-- к каждому treeNode. Форма pgr_dijkstra(text, bigint, bigint[], bool) —
-- проверена V73/V74 (scalar-источник, array-цели).
v_graph_sql :=
'WITH oks_ids AS ( SELECT array_agg(id) AS ids FROM visibility_vertex '
|| '    WHERE task_id = ' || quote_literal(p_task_id::text)
|| '      AND cluster_id = ' || p_cluster_id
|| '      AND vertex_type = ''oks'' ), '
|| 'virtual_root AS ( '
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
|| '       CASE WHEN source_vertex = ANY((SELECT ids FROM oks_ids)) '
|| '              OR target_vertex = ANY((SELECT ids FROM oks_ids)) '
|| '            THEN cost::double precision + 1000.0 '
|| '            ELSE cost::double precision END AS cost, '
|| '       CASE WHEN source_vertex = ANY((SELECT ids FROM oks_ids)) '
|| '              OR target_vertex = ANY((SELECT ids FROM oks_ids)) '
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
-- Реальные рёбра пути + агрегаты. V55-фильтр убран — penalty в graph делает
-- своё дело, а на «нет альтернативы» лучше найти хоть какой-то путь.
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
'V76.2: virtual_root(-1) к каждому treeNode. Штраф +1000 м на рёбра,
инцидентные OKS-вершинам — Dijkstra предпочитает обходить чужие OKS.
V55-фильтр убран, чтобы SPH не застревал с единственным источником.
total_cost включает штраф (для ранжирования SPH); total_length — реальная.';