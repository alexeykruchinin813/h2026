-- V45. Фикс V44: "invalid UNION/INTERSECT/EXCEPT ORDER BY clause".
-- Причина: в PL/pgSQL RETURN QUERY ... UNION ALL ... ORDER BY distance_m
-- парсер видит distance_m как OUT-параметр, а не column reference.
-- Решение: обернуть UNION в подзапрос, сортировать по t.distance_m снаружи.
-- Логика V44 (правило 10 м + лимит ≤4 примыканий) сохраняется.

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
    -- 1. Существующие камеры
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

-- 2. Edge projections: если ≤10 м heat_chamber с capacity → вернуть её
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

-- 3. Graph nodes с тем же правилом
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
) t
ORDER BY t.distance_m;
END;
$$ LANGUAGE plpgsql;

COMMENT ON FUNCTION find_tie_in_candidates(UUID, GEOMETRY, DOUBLE PRECISION) IS
'V45: fix union ORDER BY (wrap in subquery); rule 10m + ≤4 attachments preserved.';