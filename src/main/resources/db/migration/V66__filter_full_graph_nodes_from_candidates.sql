-- V66: Раннее отсечение graph_node, у которых в базовом графе уже degree >= 4.
-- Логика V45 (правило 10 м + capacity <= 4) сохраняется; Java-фикс V66
-- в TieInCoordinationService всё равно нужен как второй рубеж.

DROP FUNCTION IF EXISTS find_tie_in_candidates(UUID, GEOMETRY, DOUBLE PRECISION);

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
SELECT * FROM (
    SELECT 'heat_chamber'::TEXT AS candidate_type,
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

SELECT
CASE WHEN nc.nearby_id IS NOT NULL THEN 'heat_chamber'::TEXT
ELSE 'edge_projection'::TEXT END,
COALESCE(nc.nearby_id, ge.ext_id),
CASE WHEN nc.nearby_id IS NOT NULL THEN nc.nearby_geom
ELSE ST_ClosestPoint(ge.geom, p_centroid)::GEOMETRY(POINT, 32637) END,
CASE WHEN nc.nearby_id IS NOT NULL THEN ST_Distance(nc.nearby_geom, p_centroid)
ELSE ST_Distance(ge.geom, p_centroid) END,
ge.diameter, 0
FROM graph_edge ge
LEFT JOIN LATERAL (
    SELECT hc.feature_id AS nearby_id, hc.geom_utm AS nearby_geom
FROM input_feature hc
WHERE hc.task_id = p_task_id
AND hc.object_type = 'heat_chamber'
AND hc.geom_utm IS NOT NULL
AND ST_DWithin(ST_ClosestPoint(ge.geom, p_centroid), hc.geom_utm, 10.0)
AND count_chamber_attachments(p_task_id, hc.feature_id) + 1 <= 4
ORDER BY ST_Distance(ST_ClosestPoint(ge.geom, p_centroid), hc.geom_utm)
LIMIT 1
) nc ON TRUE
WHERE ge.task_id = p_task_id
AND ST_DWithin(ge.geom, p_centroid, p_radius_m)

UNION ALL

SELECT
CASE WHEN nc.nearby_id IS NOT NULL THEN 'heat_chamber'::TEXT
ELSE 'graph_node'::TEXT END,
COALESCE(nc.nearby_id, gn.ext_id),
CASE WHEN nc.nearby_id IS NOT NULL THEN nc.nearby_geom ELSE gn.geom END,
CASE WHEN nc.nearby_id IS NOT NULL THEN ST_Distance(nc.nearby_geom, p_centroid)
ELSE ST_Distance(gn.geom, p_centroid) END,
0, 0
FROM graph_node gn
LEFT JOIN LATERAL (
    SELECT hc.feature_id AS nearby_id, hc.geom_utm AS nearby_geom
FROM input_feature hc
WHERE hc.task_id = p_task_id
AND hc.object_type = 'heat_chamber'
AND hc.geom_utm IS NOT NULL
AND ST_DWithin(gn.geom, hc.geom_utm, 10.0)
AND count_chamber_attachments(p_task_id, hc.feature_id) + 1 <= 4
ORDER BY ST_Distance(gn.geom, hc.geom_utm)
LIMIT 1
) nc ON TRUE
WHERE gn.task_id = p_task_id
AND gn.node_type = 'junction'
AND ST_DWithin(gn.geom, p_centroid, p_radius_m)
-- V66: не предлагаем узлы, у которых уже degree >= MAX_ATTACHMENTS
AND (SELECT COUNT(*) FROM graph_edge ge2
WHERE ge2.task_id = gn.task_id
AND ST_DWithin(ge2.geom, gn.geom, 0.1)) < 4
) t
ORDER BY t.distance_m;
END;
$$ LANGUAGE plpgsql;

COMMENT ON FUNCTION find_tie_in_candidates(UUID, GEOMETRY, DOUBLE PRECISION) IS
'V66: +фильтр graph_node с existing degree >= 4; правило 10м и <=4 примыканий сохранены.';