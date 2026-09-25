-- V27. Hybrid visibility graph: SQL for coarse structure, JTS for fine validation.
-- Critical fix for disconnected graph in dense urban areas.
-- 
-- Problem: In dense areas (14 OKS per 500x500m), oks buffers merge into one
-- continuous forbidden zone, even at 1m. Visibility graph becomes disconnected.
--
-- Solution: 
--   1. Build coarse graph with minimal buffers (0.5m) via SQL
--   2. Export vertices/edges to Java
--   3. JTS validates each edge against TRUE buffers (5/7/9m) per-polygon
--   4. Allow OKS to exit own buffer (U6 rule extension)
--   5. Add "escape edges" from OKS to buffer boundary points
--
-- This migration creates helper functions for hybrid approach.

-- Helper: Create escape points around OKS polygon (points on buffer boundary)
CREATE OR REPLACE FUNCTION create_escape_points(
    p_task_id UUID,
    p_cluster_id INT,
    p_buffer_dist NUMERIC DEFAULT 6.0  -- 5m + 1m margin
) RETURNS TABLE(vertex_id BIGINT, oks_id BIGINT, geom GEOMETRY) AS $$
BEGIN
    RETURN QUERY
    WITH oks_polygons AS (
        SELECT r.feature_id AS oks_id,
               r.geom_utm AS poly_geom
        FROM input_feature r
        WHERE r.task_id = p_task_id
          AND r.object_type = 'restriction'
          AND r.properties->>'restriction_type' = 'oks'
          AND r.geom_utm IS NOT NULL
          AND EXISTS (
              SELECT 1 FROM visibility_vertex vv
              WHERE vv.task_id = p_task_id
                AND vv.cluster_id = p_cluster_id
                AND vv.vertex_type = 'oks'
                AND ST_Contains(r.geom_utm, vv.geom)
          )
    ),
    escape_rings AS (
        SELECT oks_id,
               ST_ExteriorRing(ST_Buffer(poly_geom, p_buffer_dist)) AS ring_geom
        FROM oks_polygons
    ),
    escape_pts AS (
        SELECT er.oks_id,
               ST_PointN(er.ring_geom, n) AS geom
        FROM escape_rings er,
             LATERAL generate_series(1, ST_NPoints(er.ring_geom) - 1) AS n
        WHERE ST_NPoints(er.ring_geom) > 3
    )
    INSERT INTO visibility_vertex (task_id, cluster_id, vertex_type, ref_id, geom, own_polygon_id)
    SELECT p_task_id, p_cluster_id, 'escape_point', eps.oks_id, eps.geom, eps.oks_id
    FROM escape_pts eps
    ON CONFLICT DO NOTHING
    RETURNING id, oks_id, geom;
END;
$$ LANGUAGE plpgsql;

-- Helper: Validate edge against individual polygons (not merged)
-- Returns TRUE if edge is valid (does not intersect forbidden zones)
CREATE OR REPLACE FUNCTION validate_edge_jts(
    p_task_id UUID,
    p_edge_id BIGINT,
    p_new_diameter INT
) RETURNS BOOLEAN AS $$
DECLARE
    v_line GEOMETRY;
    v_oks_id BIGINT;
    v_is_valid BOOLEAN := TRUE;
BEGIN
    -- Get edge geometry and source OKS (if any)
    SELECT ve.geom, sv.own_polygon_id
    INTO v_line, v_oks_id
    FROM visibility_edge ve
    JOIN visibility_vertex sv ON sv.id = ve.source_vertex
    WHERE ve.id = p_edge_id;
    
    IF v_line IS NULL THEN
        RETURN FALSE;
    END IF;
    
    -- Check against all forbidden restrictions EXCEPT own OKS polygon
    SELECT EXISTS (
        SELECT 1
        FROM input_feature r
        JOIN restriction_rules rr ON rr.type = r.properties->>'restriction_type'
        WHERE r.task_id = p_task_id
          AND r.object_type = 'restriction'
          AND rr.crossing_forbidden = TRUE
          AND r.geom_utm IS NOT NULL
          AND ST_Intersects(v_line, ST_Buffer(r.geom_utm,
                CASE
                    WHEN rr.type = 'oks' AND r.feature_id = v_oks_id THEN 0  -- Allow own OKS
                    WHEN rr.type = 'oks' THEN 5.0  -- Will be refined in Java based on DU
                    ELSE rr.min_horizontal_dist
                END))
          AND (rr.type <> 'oks' OR r.feature_id <> v_oks_id)  -- Exclude own OKS
    ) INTO v_is_valid;
    
    RETURN NOT v_is_valid;  -- TRUE if no intersections found
END;
$$ LANGUAGE plpgsql;

-- Mark edges for JTS re-validation
-- Sets flag needs_jts_validation = TRUE for edges crossing OKS buffers
-- Only execute if attributes column exists
DO $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM information_schema.columns 
        WHERE table_name = 'visibility_edge' AND column_name = 'attributes'
    ) THEN
        UPDATE visibility_edge ve
        SET attributes = jsonb_set(
            COALESCE(ve.attributes, '{}'::jsonb),
            '{needs_jts_validation}',
            'true'::jsonb
        )
        FROM visibility_vertex sv,
             visibility_vertex tv,
             input_feature r
        WHERE ve.source_vertex = sv.id
          AND ve.target_vertex = tv.id
          AND sv.task_id = tv.task_id
          AND sv.task_id = r.task_id
          AND r.object_type = 'restriction'
          AND r.properties->>'restriction_type' = 'oks'
          AND ST_Intersects(ve.geom, ST_Buffer(r.geom_utm, 1.0))
          AND r.feature_id IS DISTINCT FROM sv.own_polygon_id;
    END IF;
END $$;

COMMENT ON FUNCTION create_escape_points IS
'Creates escape points on OKS buffer boundary (6m = 5m + 1m margin).
These points allow OKS to "escape" their own buffer and connect to external graph.
U6 rule extension: OKS can exit own buffer via straight segment to boundary.';

COMMENT ON FUNCTION validate_edge_jts IS
'Validates single edge against individual polygons (not merged union).
Used by Java JTS service for precise validation with DU-specific buffers (5/7/9m).
Returns TRUE if edge is valid (no forbidden intersections).';
