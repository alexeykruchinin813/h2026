-- V28. Фикс несовместимости типов NUMERIC в обёртке pgr_astar (A*).
--
-- Проблема: колонки visibility_edge.cost / reverse_cost имеют тип NUMERIC(12,3) (V14),
-- а pgRouting 4.x (pgr_astar через driver's union) ожидает DOUBLE PRECISION.
-- В конвейере «SQL-запрос графа → plpgsql RETURNS TABLE(cost NUMERIC) → ::NUMERIC»
-- на некоторых строках результата (в т.ч. терминальной с edge = -1, где cost = infinity,
-- и при NULL/empty path) приведение numeric падает с ошибкой
-- «invalid input syntax for type numeric», find_visibility_path_geom бросает исключение,
-- Java-код PathFinderService ловит его и молча возвращает notFound — пути теряются.
--
-- Решение: единая точка — find_visibility_path (её вызывают и find_visibility_path_geom,
-- и find_best_path_from_oks):
--   1) cost/reverse_cost/x1/y1/x2/y2 явно приводим к DOUBLE PRECISION в SQL графа;
--   2) выходной интерфейс функции — DOUBLE PRECISION вместо NUMERIC;
--   3) в агрегате total_cost считаем MAX(agg_cost) без numeric-промежутков.

DROP FUNCTION IF EXISTS find_visibility_path(UUID, INT, BIGINT, BIGINT);
DROP FUNCTION IF EXISTS find_visibility_path_geom(UUID, INT, BIGINT, BIGINT);
DROP FUNCTION IF EXISTS find_best_path_from_oks(UUID, INT, BIGINT);

-- 1. Raw path: sequence of nodes/edges from pgr_astar.
--    Все cost-поля — DOUBLE PRECISION (нативный тип pgRouting).
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

-- 2. Aggregated path: geometry, total cost, edge IDs.
--    total_cost — DOUBLE PRECISION; total_length остаётся NUMERIC (ST_Length по геодезии).
CREATE OR REPLACE FUNCTION find_visibility_path_geom(
    p_task_id     UUID,
    p_cluster_id  INT,
    p_from_vertex BIGINT,
    p_to_vertex   BIGINT
) RETURNS TABLE(
    total_cost   DOUBLE PRECISION,
    total_length NUMERIC,
    edge_count   INT,
    path_geom    GEOMETRY,
    edge_ids     BIGINT[]
) AS $$
DECLARE
    v_edges  BIGINT[];
    v_geom   GEOMETRY;
    v_len    NUMERIC;
    v_cost   DOUBLE PRECISION;
BEGIN
    SELECT array_agg(edge ORDER BY seq)
    INTO v_edges
    FROM find_visibility_path(p_task_id, p_cluster_id, p_from_vertex, p_to_vertex)
    WHERE edge <> -1;

    IF v_edges IS NULL OR array_length(v_edges, 1) = 0 THEN
        RETURN QUERY SELECT NULL::DOUBLE PRECISION, NULL::NUMERIC, 0, NULL::GEOMETRY, NULL::BIGINT[];
        RETURN;
    END IF;

    SELECT ST_LineMerge(ST_Collect(ve.geom ORDER BY array_position(v_edges, ve.id))),
           SUM(ve.length_m)::NUMERIC
    INTO v_geom, v_len
    FROM visibility_edge ve
    WHERE ve.id = ANY(v_edges);

    SELECT MAX(agg_cost) INTO v_cost
    FROM find_visibility_path(p_task_id, p_cluster_id, p_from_vertex, p_to_vertex);

    RETURN QUERY SELECT
        v_cost,
        v_len,
        array_length(v_edges, 1),
        v_geom,
        v_edges;
END;
$$ LANGUAGE plpgsql;

-- 3. Best path from an OKS to any candidate.
CREATE OR REPLACE FUNCTION find_best_path_from_oks(
    p_task_id    UUID,
    p_cluster_id INT,
    p_oks_vertex BIGINT
) RETURNS TABLE(
    target_vertex BIGINT,
    target_type   TEXT,
    target_ref_id TEXT,
    total_cost    DOUBLE PRECISION,
    total_length  NUMERIC,
    edge_count    INT,
    path_geom     GEOMETRY,
    edge_ids      BIGINT[]
) AS $$
BEGIN
RETURN QUERY
SELECT
    vv.id,
    vv.vertex_type,
    vv.ref_id,
    p.total_cost,
    p.total_length,
    p.edge_count,
    p.path_geom,
    p.edge_ids
FROM visibility_vertex vv
CROSS JOIN LATERAL find_visibility_path_geom(
    p_task_id, p_cluster_id, p_oks_vertex, vv.id
) p
WHERE vv.task_id = p_task_id
  AND vv.cluster_id = p_cluster_id
  AND vv.vertex_type = 'candidate'
  AND p.edge_count > 0
ORDER BY p.total_cost ASC
LIMIT 1;
END;
$$ LANGUAGE plpgsql;
