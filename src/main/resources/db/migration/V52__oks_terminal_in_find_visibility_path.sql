-- ============================================================================
-- V52. Терминальность OKS в find_visibility_path (A*).
--
-- Диагноз: после V41/V51 три OKS-узла получают degree = 6 в physical_node.
-- A* ходит СКВОЗЬ чужие OKS через mirror-рёбра escape → OKS, созданные V25
-- для работы при directed := false. V41 убрала фильтр oks_zones, mirror-рёбра
-- остались; любой чужой OKS становится транзитным узлом.
--
-- Решение:
--   1. directed := true (иначе pgRouting сам развернёт рёбра и обойдёт запрет).
--   2. reverse_cost = -1 для рёбер, у которых source = OKS (выход разрешён,
--      вход в OKS запрещён).
--   3. Исключаем mirror-рёбра escape → OKS (source ≠ OKS, target = OKS) —
--      они дублируют reverse и опасны.
--   4. find_visibility_path_geom и find_best_path_from_oks НЕ меняются.
--
-- Инварианты, которые сохраняются:
--   * candidate остаётся достижимым (вход и выход разрешены).
--   * мосты escape ↔ escape двунаправленные (reverse_cost = length).
--   * мосты escape → candidate дают вход; выйти тоже можно.
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
    'SELECT ve.id, ' ||
    '       ve.source_vertex AS source, ' ||
'       ve.target_vertex AS target, ' ||
'       ve.cost::DOUBLE PRECISION AS cost, ' ||
'       CASE ' ||
'           WHEN sv.vertex_type = ''oks'' THEN -1.0 ' ||
'           ELSE ve.reverse_cost::DOUBLE PRECISION ' ||
'       END AS reverse_cost, ' ||
'       ST_X(ST_StartPoint(ve.geom))::DOUBLE PRECISION AS x1, ' ||
'       ST_Y(ST_StartPoint(ve.geom))::DOUBLE PRECISION AS y1, ' ||
'       ST_X(ST_EndPoint(ve.geom))::DOUBLE PRECISION   AS x2, ' ||
'       ST_Y(ST_EndPoint(ve.geom))::DOUBLE PRECISION   AS y2 ' ||
'  FROM visibility_edge ve ' ||
'  JOIN visibility_vertex sv ON sv.id = ve.source_vertex ' ||
'  JOIN visibility_vertex tv ON tv.id = ve.target_vertex ' ||
' WHERE ve.task_id = %L ' ||
'   AND ve.cluster_id = %s ' ||
'   AND NOT (tv.vertex_type = ''oks'' AND sv.vertex_type <> ''oks'')',
p_task_id, p_cluster_id
),
p_from_vertex::BIGINT,
p_to_vertex::BIGINT,
directed := true
) r;
END;
$$ LANGUAGE plpgsql;

COMMENT ON FUNCTION find_visibility_path(UUID, INT, BIGINT, BIGINT) IS
'V52: A* с терминальными OKS.
directed := true; reverse_cost = -1 для рёбер source=OKS;
mirror-рёбра escape → OKS отфильтрованы.';