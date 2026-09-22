CREATE OR REPLACE FUNCTION find_tie_in_candidates(
    p_task_id     UUID,
    p_centroid    GEOMETRY,
p_radius_m    DOUBLE PRECISION DEFAULT 300.0
)
RETURNS TABLE(
candidate_type        TEXT,
existing_object_id    TEXT,
geom                  GEOMETRY(POINT, 32637),
distance_m            DOUBLE PRECISION,
existing_diameter     INT,
required_diameter     INT
) AS $$
BEGIN
RETURN QUERY
-- 1. Существующие камеры
SELECT
'heat_chamber'::TEXT AS candidate_type,
hc.feature_id AS existing_object_id,
hc.geom_utm AS geom,
ST_Distance(hc.geom_utm, p_centroid) AS distance_m,
COALESCE((hc.properties->>'diameter')::int, 0) AS existing_diameter,
0 AS required_diameter
FROM input_feature hc
WHERE hc.task_id = p_task_id
AND hc.object_type = 'heat_chamber'
AND hc.geom_utm IS NOT NULL
AND ST_DWithin(hc.geom_utm, p_centroid, p_radius_m)

UNION ALL

-- 2. Проекции центроида на рёбра существующей сети
SELECT
'edge_projection'::TEXT AS candidate_type,
ge.ext_id AS existing_object_id,
ST_ClosestPoint(ge.geom, p_centroid)::GEOMETRY(POINT, 32637) AS geom,
ST_Distance(ge.geom, p_centroid) AS distance_m,
ge.diameter AS existing_diameter,
0 AS required_diameter
FROM graph_edge ge
WHERE ge.task_id = p_task_id
AND ST_DWithin(ge.geom, p_centroid, p_radius_m)

UNION ALL

-- 3. Узлы графа (концы участков, junction)
SELECT
'graph_node'::TEXT AS candidate_type,
gn.ext_id AS existing_object_id,
gn.geom AS geom,
ST_Distance(gn.geom, p_centroid) AS distance_m,
0 AS existing_diameter,
0 AS required_diameter
FROM graph_node gn
WHERE gn.task_id = p_task_id
AND gn.node_type = 'junction'
AND ST_DWithin(gn.geom, p_centroid, p_radius_m)

ORDER BY distance_m;
END;
$$ LANGUAGE plpgsql;