-- V21. A* path finding over visibility graph (D2).
-- Wraps pgr_astar with our visibility_edge structure.

-- 1. Raw path: sequence of nodes/edges from pgr_astar
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
cost      NUMERIC,
agg_cost  NUMERIC
) AS $$
BEGIN
RETURN QUERY
SELECT r.seq, r.path_seq, r.node, r.edge, r.cost, r.agg_cost
FROM pgr_astar(
    format(
    'SELECT id, source_vertex AS source, target_vertex AS target, ' ||
    '       cost, reverse_cost, ' ||
    '       ST_X(ST_StartPoint(geom)) AS x1, ST_Y(ST_StartPoint(geom)) AS y1, ' ||
    '       ST_X(ST_EndPoint(geom))   AS x2, ST_Y(ST_EndPoint(geom))   AS y2 ' ||
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


-- 2. Aggregated path: geometry, total cost, edge IDs
CREATE OR REPLACE FUNCTION find_visibility_path_geom(
    p_task_id     UUID,
p_cluster_id  INT,
p_from_vertex BIGINT,
p_to_vertex   BIGINT
) RETURNS TABLE(
    total_cost   NUMERIC,
    total_length NUMERIC,
    edge_count   INT,
path_geom    GEOMETRY,
edge_ids     BIGINT[]
) AS $$
DECLARE
v_edges  BIGINT[];
v_geom   GEOMETRY;
v_len    NUMERIC;
v_cost   NUMERIC;
BEGIN
-- Collect edge IDs (in order) from pgr_astar result
SELECT array_agg(edge ORDER BY seq)
INTO v_edges
FROM find_visibility_path(p_task_id, p_cluster_id, p_from_vertex, p_to_vertex)
WHERE edge <> -1;

IF v_edges IS NULL OR array_length(v_edges, 1) = 0 THEN
RETURN QUERY SELECT NULL::NUMERIC, NULL::NUMERIC, 0, NULL::GEOMETRY, NULL::BIGINT[];
RETURN;
END IF;

-- Build geometry: LineMerge of ordered edges
SELECT ST_LineMerge(ST_Collect(ve.geom ORDER BY array_position(v_edges, ve.id))),
SUM(ve.length_m)
INTO v_geom, v_len
FROM visibility_edge ve
WHERE ve.id = ANY(v_edges);

-- Total cost from pgr_astar
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


-- 3. Best path from an OKS to any candidate (min cost)
CREATE OR REPLACE FUNCTION find_best_path_from_oks(
    p_task_id   UUID,
    p_cluster_id INT,
    p_oks_vertex BIGINT
) RETURNS TABLE(
    target_vertex BIGINT,
    target_type   TEXT,
target_ref_id TEXT,
total_cost    NUMERIC,
total_length  NUMERIC,
edge_count    INT,
path_geom     GEOMETRY,
edge_ids      BIGINT[]
) AS $$
BEGIN
RETURN QUERY
SELECT
vv.id AS target_vertex,
vv.vertex_type AS target_type,
vv.ref_id AS target_ref_id,
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