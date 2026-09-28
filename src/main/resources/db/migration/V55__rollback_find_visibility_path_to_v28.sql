-- ============================================================================
-- V55. Откат find_visibility_path к форме V28.
--
-- V52 (directed:=true, reverse_cost:=-1) сломал A*: 17 OKS → 4 с путями.
-- V54 (фильтр чужих OKS в графе) воспроизвёл ту же деградацию: граф
-- фильтруется, но A* теряет транзитные маршруты, использовавшиеся для
-- достижения кандидатов.
--
-- Откат: возвращаем форму V28 (directed:=false, без фильтра).
-- Запрет транзита через чужой OKS реализован пост-фактум в Java
-- (PathFinderService.findPathsFromOks) — фильтр по edge_ids после A*.
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
BEGIN
RETURN QUERY
SELECT r.seq,
r.path_seq,
r.node,
r.edge,
r.cost::DOUBLE PRECISION,
r.agg_cost::DOUBLE PRECISION
FROM pgr_astar(
    format(
    'SELECT id, source_vertex AS source, target_vertex AS target, ' ||
    '       cost::DOUBLE PRECISION, reverse_cost::DOUBLE PRECISION, ' ||
'       ST_X(ST_StartPoint(geom))::DOUBLE PRECISION AS x1, ' ||
'       ST_Y(ST_StartPoint(geom))::DOUBLE PRECISION AS y1, ' ||
'       ST_X(ST_EndPoint(geom))::DOUBLE PRECISION AS x2, ' ||
'       ST_Y(ST_EndPoint(geom))::DOUBLE PRECISION AS y2 ' ||
'  FROM visibility_edge ' ||
' WHERE task_id = %L AND cluster_id = %s',
p_task_id, p_cluster_id
),
p_from_vertex::BIGINT,
p_to_vertex::BIGINT,
directed := false
) r;
END;
$$ LANGUAGE plpgsql;

COMMENT ON FUNCTION find_visibility_path(UUID, INT, BIGINT, BIGINT) IS
'V55: откат к форме V28. directed := false, без фильтра.
Запрет транзита через чужой OKS — в Java (PathFinderService).';