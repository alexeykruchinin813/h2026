-- V26. Fix missing railway restriction and add crossing_angle_max validation.
-- Issue: ТЗ Table 2 requires 'railway' type (Пересечение запрещено, 1.0м)
-- Also adds angle validation for special passes (road/tram_tracks: ≥45°)

-- Add railway to restriction_rules
INSERT INTO restriction_rules (type, crossing_forbidden, special_pass_allowed, min_horizontal_dist, crossing_angle_max, special_k, special_zone_buffer, depth_rule)
VALUES ('railway', TRUE, FALSE, 1.0, NULL, NULL, NULL, NULL)
ON CONFLICT (type) DO UPDATE SET
    crossing_forbidden = EXCLUDED.crossing_forbidden,
    min_horizontal_dist = EXCLUDED.min_horizontal_dist;

-- Update road and tram_tracks with crossing_angle_max (45 degrees per ТЗ)
UPDATE restriction_rules
SET crossing_angle_max = 45.0
WHERE type IN ('road', 'tram_tracks');

COMMENT ON COLUMN restriction_rules.crossing_angle_max IS
'Минимальный угол пересечения (градусы) для special_pass_allowed=TRUE.
По ТЗ: road и tram_tracks требуют угол не менее 45°.
Проверяется при построении специального прохода.';

-- NEW FUNCTION: validate_crossing_angle
-- Returns TRUE if edge crosses restriction at valid angle (≥45° for road/tram)
CREATE OR REPLACE FUNCTION validate_crossing_angle(
    p_edge_geom GEOMETRY,
    p_restriction_geom GEOMETRY,
    p_restriction_type TEXT
) RETURNS BOOLEAN AS $$
DECLARE
    v_angle NUMERIC;
    v_intersection GEOMETRY;
    v_restriction_line GEOMETRY;
BEGIN
    -- Only validate for road and tram_tracks
    IF p_restriction_type NOT IN ('road', 'tram_tracks') THEN
        RETURN TRUE;
    END IF;

    -- Get intersection point
    v_intersection := ST_Intersection(p_edge_geom, p_restriction_geom);
    
    IF ST_IsEmpty(v_intersection) OR v_intersection IS NULL THEN
        RETURN TRUE; -- No intersection, angle not applicable
    END IF;

    -- For polygon restrictions, use boundary at intersection point
    IF GeometryType(p_restriction_geom) IN ('POLYGON', 'MULTIPOLYGON') THEN
        v_restriction_line := ST_Boundary(p_restriction_geom);
    ELSE
        v_restriction_line := p_restriction_geom;
    END IF;

    -- Calculate angle between edge and restriction boundary
    -- Simplified: use azimuth difference at intersection point
    v_angle := ABS(
        degrees(
            ST_Azimuth(
                ST_StartPoint(p_edge_geom),
                ST_EndPoint(p_edge_geom)
            ) -
            ST_Azimuth(
                ST_StartPoint(v_restriction_line),
                ST_EndPoint(v_restriction_line)
            )
        ) % 180
    );

    -- Normalize to 0-90 range
    IF v_angle > 90 THEN
        v_angle := 180 - v_angle;
    END IF;

    -- Check against crossing_angle_max from rules
    RETURN v_angle >= (
        SELECT crossing_angle_max 
        FROM restriction_rules 
        WHERE type = p_restriction_type
    );
END;
$$ LANGUAGE plpgsql IMMUTABLE STRICT;

COMMENT ON FUNCTION validate_crossing_angle IS
'Validate crossing angle for special passes (road/tram_tracks).
Returns TRUE if angle ≥ crossing_angle_max (45° per ТЗ).
For polygon restrictions, uses boundary geometry.';
