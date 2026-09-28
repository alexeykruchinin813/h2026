-- ============================================================================
-- V54. A* с исключением чужих OKS из графа запроса.
--
-- Диагноз V52: `directed := true` + `reverse_cost = -1` для OKS-source рёбер
-- сломали A* (17 OKS с путями → 4). Точная причина не установлена; вероятно,
-- pgRouting 4.0 не так обрабатывает эту комбинацию, как ожидалось.
--
-- Решение V54: НЕ трогать directed (оставить false, как в V28). Вместо этого
-- ДИНАМИЧЕСКИ исключить из графа запроса все чужие OKS-вершины:
--   * оставить только ту OKS, из которой начинается поиск (p_from_vertex);
--   * все рёбра, инцидентные чужим OKS, отбросить.
-- A* физически не может пройти через чужой OKS — его в графе нет.
--
-- directed := false: cost используется для обеих сторон, reverse_cost
-- игнорируется. Семантика достижимости сохраняется (2D-модель ТЗ,
-- разворот одного ребра не даёт преимуществ).
--
-- Инвариант исходных рёбер (V25/V41) не меняется — фильтр работает только
-- на уровне SQL-запроса к pgRouting, физические данные visibility_edge
-- нетронуты.
-- ============================================================================

DROP FUNCTION IF EXISTS find_visibility_path(UUID, INT, BIGINT, BIGINT);

CREATE OR REPLACE FUNCTION find_visibility_path(
p_task_id     UUID,
p_cluster_id  INT,
p_from_vertex BIGINT,
p_to_vertex   BIGINT
) RETURNS TABLE(
    seq       INT,
    path_seq  INT,
node      BIGINT,
edge      BIGINT,
cost      DOUBLE PRECISION,
agg_cost  DOUBLE PRECISION
) AS $$
DECLARE
v_sql         TEXT;
v_edge_count  INT;
BEGIN
v_sql := format(
    'SELECT ve.id, ' ||
    '       ve.source_vertex AS source, ' ||
    '       ve.target_vertex AS target, ' ||
    '       ve.cost::DOUBLE PRECISION AS cost, ' ||
    '       ve.reverse_cost::DOUBLE PRECISION AS reverse_cost, ' ||
'       ST_X(ST_StartPoint(ve.geom))::DOUBLE PRECISION AS x1, ' ||
'       ST_Y(ST_StartPoint(ve.geom))::DOUBLE PRECISION AS y1, ' ||
'       ST_X(ST_EndPoint(ve.geom))::DOUBLE PRECISION   AS x2, ' ||
'       ST_Y(ST_EndPoint(ve.geom))::DOUBLE PRECISION   AS y2 ' ||
'  FROM visibility_edge ve ' ||
'  JOIN visibility_vertex sv ON sv.id = ve.source_vertex ' ||
'  JOIN visibility_vertex tv ON tv.id = ve.target_vertex ' ||
' WHERE ve.task_id = %L ' ||
'   AND ve.cluster_id = %s ' ||
'   AND NOT (sv.vertex_type = ''oks'' AND sv.id <> %s) ' ||
'   AND NOT (tv.vertex_type = ''oks'' AND tv.id <> %s)',
p_task_id, p_cluster_id, p_from_vertex, p_from_vertex
);

-- Диагностика: сколько рёбер в графе от стартовой вершины.
EXECUTE 'SELECT count(*) FROM (' || v_sql || ') g' INTO v_edge_count;
RAISE NOTICE '[astar] from vertex %: % edges in filtered graph',
p_from_vertex, v_edge_count;

RETURN QUERY
SELECT r.seq,
r.path_seq,
r.node,
r.edge,
r.cost::DOUBLE PRECISION,
r.agg_cost::DOUBLE PRECISION
FROM pgr_astar(
    v_sql,
    p_from_vertex::BIGINT,
p_to_vertex::BIGINT,
directed := false
) r;
END;
$$ LANGUAGE plpgsql;

COMMENT ON FUNCTION find_visibility_path(UUID, INT, BIGINT, BIGINT) IS
'V54: A* с исключением чужих OKS из графа запроса.
directed := false; в графе остаётся только p_from_vertex OKS,
все прочие OKS-вершины и их рёбра отфильтрованы.';