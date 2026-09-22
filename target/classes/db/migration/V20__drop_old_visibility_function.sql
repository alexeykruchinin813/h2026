-- V20. Drop old versions of build_visibility_graph, then create the fresh one.
-- Reason: V19 added a new signature, but the V18 version was still present,
-- causing "function build_visibility_graph(uuid, integer, integer) is not unique".

-- 1. Drop ALL existing signatures of the function (both old and new)
DROP FUNCTION IF EXISTS build_visibility_graph(UUID, INT, INT, NUMERIC);
DROP FUNCTION IF EXISTS build_visibility_graph(UUID, INT, INT, NUMERIC, INT);
DROP FUNCTION IF EXISTS build_visibility_graph(UUID, INT, INT);

-- 2. Recreate only the latest version (with p_r_max and p_max_corners)
CREATE OR REPLACE FUNCTION build_visibility_graph(
p_task_id        UUID,
p_cluster_id     INT,
p_new_diameter   INT,
p_r_max          NUMERIC DEFAULT 200.0,
p_max_corners    INT DEFAULT 1500
) RETURNS TABLE(inserted_vertices BIGINT, inserted_edges BIGINT, elapsed_ms INT) AS $$
DECLARE
v_start        TIMESTAMPTZ := clock_timestamp();
v_vertices     BIGINT;
v_edges        BIGINT;
v_oks_buffer   NUMERIC(6,2);
v_bbox         GEOMETRY;
v_centroid     GEOMETRY;
BEGIN
DELETE FROM visibility_edge   WHERE task_id = p_task_id AND cluster_id = p_cluster_id;
DELETE FROM visibility_vertex WHERE task_id = p_task_id AND cluster_id = p_cluster_id;

v_oks_buffer := CASE
WHEN p_new_diameter < 500  THEN 5.0
WHEN p_new_diameter <= 800 THEN 7.0
ELSE                            9.0
END;

SELECT ST_SetSRID(ST_Extent(geom_utm)::geometry, 32637),
ST_Centroid(ST_Collect(geom_utm))
INTO v_bbox, v_centroid
FROM input_feature
WHERE task_id = p_task_id
AND object_type = 'oks_connection_point'
AND geom_utm IS NOT NULL
AND feature_id IN (
    SELECT feature_id FROM (
    SELECT feature_id,
    ST_ClusterDBSCAN(geom_utm, eps := 150, minpoints := 1) OVER () AS cid
FROM input_feature
WHERE task_id = p_task_id
AND object_type = 'oks_connection_point'
) t WHERE cid = p_cluster_id
);

IF v_bbox IS NULL THEN
RETURN QUERY SELECT 0::BIGINT, 0::BIGINT, 0;
RETURN;
END IF;

v_bbox := ST_Expand(v_bbox, 500);

-- 4.1. OKS vertices
INSERT INTO visibility_vertex (task_id, cluster_id, vertex_type, ref_id, geom)
SELECT p_task_id, p_cluster_id, 'oks', feature_id, geom_utm
FROM (
    SELECT feature_id, geom_utm,
    ST_ClusterDBSCAN(geom_utm, eps := 150, minpoints := 1) OVER () AS cid
FROM input_feature
WHERE task_id = p_task_id
AND object_type = 'oks_connection_point'
AND geom_utm IS NOT NULL
) t
WHERE cid = p_cluster_id;

-- 4.2. Candidate vertices
INSERT INTO visibility_vertex (task_id, cluster_id, vertex_type, ref_id, geom)
SELECT p_task_id, p_cluster_id, 'candidate', existing_object_id, geom
FROM tie_in_candidate
WHERE task_id = p_task_id
AND cluster_id = p_cluster_id;

-- 4.3. Polygon corners: take only N closest to centroid
INSERT INTO visibility_vertex (task_id, cluster_id, vertex_type, ref_id, geom)
SELECT p_task_id, p_cluster_id, 'polygon_corner', ref_id, geom
FROM (
    SELECT r.feature_id AS ref_id, dump.geom AS geom,
ST_Distance(dump.geom, v_centroid) AS d
FROM input_feature r,
LATERAL ST_DumpPoints(ST_ExteriorRing(
    CASE WHEN GeometryType(r.geom_utm) = 'POLYGON'
THEN r.geom_utm
ELSE ST_GeometryN(r.geom_utm, 1) END
)) AS dump
WHERE r.task_id = p_task_id
AND r.object_type = 'restriction'
AND r.geom_utm IS NOT NULL
AND r.geom_utm && v_bbox
AND dump.geom IS NOT NULL
AND EXISTS (
    SELECT 1 FROM restriction_rules rr
WHERE rr.type = r.properties->>'restriction_type'
AND rr.crossing_forbidden = TRUE
)
ORDER BY ST_Distance(dump.geom, v_centroid)
LIMIT p_max_corners
) sub;

UPDATE visibility_vertex vv
SET own_polygon_id = r.feature_id
FROM input_feature r
WHERE vv.task_id = p_task_id
AND vv.cluster_id = p_cluster_id
AND vv.vertex_type = 'oks'
AND r.task_id = p_task_id
AND r.object_type = 'restriction'
AND r.properties->>'restriction_type' = 'oks'
AND r.geom_utm IS NOT NULL
AND ST_Contains(r.geom_utm, vv.geom);

SELECT count(*) INTO v_vertices
FROM visibility_vertex
WHERE task_id = p_task_id AND cluster_id = p_cluster_id;

-- 5.1. Non-OKS pairs
WITH forbidden_main AS (
    SELECT ST_Union(buf) AS geom FROM (
    SELECT ST_Buffer(r.geom_utm,
CASE
WHEN rr.type = 'oks' THEN v_oks_buffer
ELSE rr.min_horizontal_dist
END) AS buf
FROM input_feature r
JOIN restriction_rules rr
ON rr.type = r.properties->>'restriction_type'
WHERE r.task_id = p_task_id
AND r.object_type = 'restriction'
AND rr.crossing_forbidden = TRUE
AND r.geom_utm IS NOT NULL
AND r.geom_utm && v_bbox
) t
),
pairs AS (
    SELECT a.id AS a_id, b.id AS b_id,
ST_MakeLine(a.geom, b.geom) AS line,
ST_Distance(a.geom, b.geom) AS len
FROM visibility_vertex a
JOIN visibility_vertex b
ON b.task_id = a.task_id
AND b.cluster_id = a.cluster_id
AND b.id > a.id
WHERE a.task_id = p_task_id
AND a.cluster_id = p_cluster_id
AND a.vertex_type IN ('candidate', 'polygon_corner')
AND b.vertex_type IN ('candidate', 'polygon_corner')
AND NOT (a.vertex_type = 'candidate' AND b.vertex_type = 'candidate')
AND ST_DWithin(a.geom, b.geom, p_r_max)
)
INSERT INTO visibility_edge (
    task_id, cluster_id, source_vertex, target_vertex,
geom, length_m, is_special, cost, reverse_cost
)
SELECT p_task_id, p_cluster_id, a_id, b_id,
line, len, FALSE, len, len
FROM pairs
WHERE NOT ST_Intersects(line, (SELECT geom FROM forbidden_main));

-- 5.2. OKS pairs
WITH forbidden_no_oks AS (
SELECT ST_Union(buf) AS geom FROM (
    SELECT ST_Buffer(r.geom_utm, rr.min_horizontal_dist) AS buf
FROM input_feature r
JOIN restriction_rules rr
ON rr.type = r.properties->>'restriction_type'
WHERE r.task_id = p_task_id
AND r.object_type = 'restriction'
AND rr.crossing_forbidden = TRUE
AND rr.type <> 'oks'
AND r.geom_utm IS NOT NULL
AND r.geom_utm && v_bbox
) t
),
oks_zones AS (
    SELECT r.feature_id,
    ST_Buffer(r.geom_utm, v_oks_buffer) AS buf
FROM input_feature r
WHERE r.task_id = p_task_id
AND r.object_type = 'restriction'
AND r.properties->>'restriction_type' = 'oks'
AND r.geom_utm IS NOT NULL
AND r.geom_utm && v_bbox
),
oks_pairs AS (
    SELECT a.id AS a_id, b.id AS b_id,
a.own_polygon_id AS own_poly,
ST_MakeLine(a.geom, b.geom) AS line,
ST_Distance(a.geom, b.geom) AS len
FROM visibility_vertex a
JOIN visibility_vertex b
ON b.task_id = a.task_id
AND b.cluster_id = a.cluster_id
AND b.id > a.id
WHERE a.task_id = p_task_id
AND a.cluster_id = p_cluster_id
AND a.vertex_type = 'oks'
AND b.vertex_type IN ('candidate', 'polygon_corner')
AND ST_DWithin(a.geom, b.geom, p_r_max)
)
INSERT INTO visibility_edge (
    task_id, cluster_id, source_vertex, target_vertex,
geom, length_m, is_special, cost, reverse_cost
)
SELECT p_task_id, p_cluster_id, a_id, b_id,
line, len, FALSE, len, len
FROM oks_pairs p
WHERE NOT ST_Intersects(p.line, (SELECT geom FROM forbidden_no_oks))
AND NOT EXISTS (
    SELECT 1 FROM oks_zones oz
WHERE oz.feature_id IS DISTINCT FROM p.own_poly
AND ST_Intersects(p.line, oz.buf)
);

-- 5.3. Mirror edges for OKS
INSERT INTO visibility_edge (
    task_id, cluster_id, source_vertex, target_vertex,
geom, length_m, is_special, cost, reverse_cost
)
SELECT ve.task_id, ve.cluster_id,
ve.target_vertex, ve.source_vertex,
ve.geom, ve.length_m, ve.is_special,
ve.cost, ve.reverse_cost
FROM visibility_edge ve
JOIN visibility_vertex sv ON sv.id = ve.source_vertex
WHERE ve.task_id = p_task_id
AND ve.cluster_id = p_cluster_id
AND sv.vertex_type = 'oks'
AND NOT EXISTS (
    SELECT 1 FROM visibility_edge x
WHERE x.task_id = ve.task_id
AND x.cluster_id = ve.cluster_id
AND x.source_vertex = ve.target_vertex
AND x.target_vertex = ve.source_vertex
);

-- 6. Special passes
WITH special_crossings AS (
    SELECT ve.id AS edge_id,
    jsonb_agg(
jsonb_build_object(
    'type', r.properties->>'restriction_type',
'feature_id', r.feature_id,
'k', rr.special_k
)
) AS crossings,
MAX(rr.special_k) AS max_k
FROM visibility_edge ve
JOIN input_feature r
ON r.task_id = ve.task_id
AND r.object_type = 'restriction'
AND r.geom_utm IS NOT NULL
AND r.geom_utm && v_bbox
JOIN restriction_rules rr
ON rr.type = r.properties->>'restriction_type'
WHERE ve.task_id = p_task_id
AND ve.cluster_id = p_cluster_id
AND rr.special_pass_allowed = TRUE
AND ST_Intersects(ve.geom, r.geom_utm)
GROUP BY ve.id
)
UPDATE visibility_edge ve
SET is_special = TRUE,
special_k  = sc.max_k,
crossings  = sc.crossings
FROM special_crossings sc
WHERE ve.id = sc.edge_id;

SELECT count(*) INTO v_edges
FROM visibility_edge
WHERE task_id = p_task_id AND cluster_id = p_cluster_id;

RETURN QUERY SELECT
v_vertices,
v_edges,
(EXTRACT(EPOCH FROM (clock_timestamp() - v_start)) * 1000)::INT;
END;
$$ LANGUAGE plpgsql;