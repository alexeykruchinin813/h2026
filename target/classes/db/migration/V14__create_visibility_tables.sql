-- D1. Visibility graph tables

-- Drop old visibility_edge table from V5 if exists (has different structure)
DROP TABLE IF EXISTS visibility_edge CASCADE;

CREATE TABLE IF NOT EXISTS visibility_vertex (
    id              BIGSERIAL PRIMARY KEY,
    task_id         UUID NOT NULL,
    cluster_id      INT NOT NULL,
    vertex_type     TEXT NOT NULL,
    ref_id          TEXT,
    own_polygon_id  TEXT,
    geom            GEOMETRY(POINT, 32637) NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_vv_task_cluster ON visibility_vertex(task_id, cluster_id);
CREATE INDEX IF NOT EXISTS idx_vv_geom         ON visibility_vertex USING GIST(geom);
CREATE INDEX IF NOT EXISTS idx_vv_type         ON visibility_vertex(task_id, cluster_id, vertex_type);

CREATE TABLE IF NOT EXISTS visibility_edge (
    id              BIGSERIAL PRIMARY KEY,
    task_id         UUID NOT NULL,
    cluster_id      INT NOT NULL,
    source_vertex   BIGINT NOT NULL REFERENCES visibility_vertex(id) ON DELETE CASCADE,
    target_vertex   BIGINT NOT NULL REFERENCES visibility_vertex(id) ON DELETE CASCADE,
    geom            GEOMETRY(LINESTRING, 32637) NOT NULL,
    length_m        NUMERIC(12,3) NOT NULL,
    is_special      BOOLEAN NOT NULL DEFAULT FALSE,
    special_k       NUMERIC(6,3),
    crossings       JSONB,
    cost            NUMERIC(12,3) NOT NULL,
    reverse_cost    NUMERIC(12,3) NOT NULL,
    attributes      JSONB DEFAULT '{}'::jsonb
);

CREATE INDEX IF NOT EXISTS idx_ve_task_cluster ON visibility_edge(task_id, cluster_id);
CREATE INDEX IF NOT EXISTS idx_ve_geom         ON visibility_edge USING GIST(geom);
CREATE INDEX IF NOT EXISTS idx_ve_source       ON visibility_edge(source_vertex);
CREATE INDEX IF NOT EXISTS idx_ve_target       ON visibility_edge(target_vertex);

-- ============================================================
-- Function build_visibility_graph
-- ============================================================
CREATE OR REPLACE FUNCTION build_visibility_graph(
    p_task_id        UUID,
    p_cluster_id     INT,
p_new_diameter   INT,
p_r_max          NUMERIC DEFAULT 2000.0
) RETURNS TABLE(inserted_vertices BIGINT, inserted_edges BIGINT, elapsed_ms INT) AS $$
DECLARE
v_start        TIMESTAMPTZ := clock_timestamp();
v_vertices     BIGINT;
v_edges        BIGINT;
v_oks_buffer   NUMERIC(6,2);
v_bbox         GEOMETRY;
BEGIN
-- 1. Очистка
DELETE FROM visibility_edge   WHERE task_id = p_task_id AND cluster_id = p_cluster_id;
DELETE FROM visibility_vertex WHERE task_id = p_task_id AND cluster_id = p_cluster_id;

-- 2. Отступ для oks (5 / 7 / 9 м)
v_oks_buffer := CASE
WHEN p_new_diameter < 500  THEN 5.0
WHEN p_new_diameter <= 800 THEN 7.0
ELSE                            9.0
END;

-- 3. BBox кластера + буфер 500 м
SELECT ST_Expand(ST_Extent(geom_utm), 500)
INTO v_bbox
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
RAISE NOTICE 'build_visibility_graph: empty bbox for task_id=%, cluster_id=%',
p_task_id, p_cluster_id;
RETURN QUERY SELECT 0::BIGINT, 0::BIGINT, 0;
RETURN;
END IF;

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

-- 4.3. Polygon corner vertices
INSERT INTO visibility_vertex (task_id, cluster_id, vertex_type, ref_id, geom)
SELECT p_task_id, p_cluster_id, 'polygon_corner', r.feature_id, dump.geom
FROM (
    SELECT feature_id,
    CASE
WHEN ST_NPoints(geom_utm) > 128
THEN ST_Subdivide(geom_utm, 128)
ELSE geom_utm
END AS geom_utm
FROM input_feature r
WHERE r.task_id = p_task_id
AND r.object_type = 'restriction'
AND r.geom_utm IS NOT NULL
AND r.geom_utm && v_bbox
AND EXISTS (
SELECT 1 FROM restriction_rules rr
WHERE rr.type = r.properties->>'restriction_type'
AND rr.crossing_forbidden = TRUE
)
) r,
LATERAL (
    SELECT (ST_DumpPoints(ST_ExteriorRing(
    CASE WHEN GeometryType(r.geom_utm) = 'POLYGON'
THEN r.geom_utm
ELSE ST_GeometryN(r.geom_utm, 1) END
))).geom AS geom
) dump
WHERE dump.geom IS NOT NULL;

-- 4.4. own_polygon_id
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

-- 5.2. OKS pairs (U6: own polygon allowed)
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

-- 6. Special passes (max K)
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

-- 7. Totals
SELECT count(*) INTO v_edges
FROM visibility_edge
WHERE task_id = p_task_id AND cluster_id = p_cluster_id;

RETURN QUERY SELECT
v_vertices,
v_edges,
EXTRACT(MILLISECOND FROM (clock_timestamp() - v_start))::INT;
END;
$$ LANGUAGE plpgsql;