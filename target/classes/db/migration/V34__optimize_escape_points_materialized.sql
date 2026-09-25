-- Оптимизация create_escape_points:
-- 1) MATERIALIZED для тяжёлых CTE (PG12+ иначе инлайнит и пересчитывает ST_Union/ST_Buffer
--    для каждой пары — это давало 117 секунд на 16 escape-точек).
-- 2) own_polygon_id тянем в CTE pairs, чтобы не делать коррелированный подзапрос
--    в финальном NOT EXISTS.
-- 3) Пара вспомогательных индексов для ST_DWithin-джойнов.

CREATE INDEX IF NOT EXISTS idx_visibility_vertex_task_cluster_type
ON visibility_vertex (task_id, cluster_id, vertex_type);

CREATE INDEX IF NOT EXISTS idx_visibility_vertex_geom_gist
ON visibility_vertex USING GIST (geom);

CREATE INDEX IF NOT EXISTS idx_input_feature_task_type
ON input_feature (task_id, object_type);

CREATE OR REPLACE FUNCTION create_escape_points(
    p_task_id UUID,
    p_cluster_id INT,
p_buffer_dist DOUBLE PRECISION DEFAULT 6.0
) RETURNS TABLE(vertex_id BIGINT, oks_id TEXT, geom GEOMETRY) AS $$
#variable_conflict use_column
BEGIN
RETURN QUERY
WITH oks_polygons AS MATERIALIZED (
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
escape_rings AS MATERIALIZED (
    SELECT oks_id,
    ST_ExteriorRing(ST_Buffer(poly_geom, p_buffer_dist::NUMERIC)) AS ring_geom
FROM oks_polygons
),
escape_pts AS MATERIALIZED (
    SELECT er.oks_id,
ST_LineInterpolatePoint(er.ring_geom, frac) AS geom
FROM escape_rings er,
LATERAL generate_series(0, 7) AS i,
LATERAL (SELECT (i::DOUBLE PRECISION / 8.0) AS frac) f
WHERE ST_NPoints(er.ring_geom) > 3
),
inserted AS (
    INSERT INTO visibility_vertex
(task_id, cluster_id, vertex_type, ref_id, geom, own_polygon_id)
SELECT DISTINCT ON (
    p_task_id, p_cluster_id, eps.oks_id,
round(ST_X(eps.geom)::NUMERIC, 3),
round(ST_Y(eps.geom)::NUMERIC, 3))
p_task_id, p_cluster_id, 'escape_point',
eps.oks_id, eps.geom, eps.oks_id
FROM escape_pts eps
RETURNING id, ref_id::TEXT AS oks_id, geom
)
SELECT i.id, i.oks_id, i.geom FROM inserted i;

-----------------------------------------------------------------
-- Мосты escape -> внешние вершины
-----------------------------------------------------------------
WITH esc AS MATERIALIZED (
    SELECT v.id, v.geom, v.own_polygon_id AS own_poly
FROM visibility_vertex v
WHERE v.task_id = p_task_id AND v.cluster_id = p_cluster_id
AND v.vertex_type = 'escape_point'
),
targets AS MATERIALIZED (
    SELECT v.id, v.geom
FROM visibility_vertex v
WHERE v.task_id = p_task_id AND v.cluster_id = p_cluster_id
AND v.vertex_type IN ('polygon_corner', 'candidate')
),
-- ВАЖНО: MATERIALIZED. Без него ST_Union пересчитывается на каждую пару.
forbidden_no_oks AS MATERIALIZED (
    SELECT ST_Union(buf) AS geom FROM (
    SELECT ST_Buffer(r.geom_utm, rr.min_horizontal_dist) AS buf
FROM input_feature r
JOIN restriction_rules rr ON rr.type = r.properties->>'restriction_type'
WHERE r.task_id = p_task_id
AND r.object_type = 'restriction'
AND rr.crossing_forbidden = TRUE
AND rr.type <> 'oks'
AND r.geom_utm IS NOT NULL
) t
),
-- ВАЖНО: MATERIALIZED. Иначе ST_Buffer пересчитывается на каждую пару.
oks_zones AS MATERIALIZED (
SELECT r.feature_id AS oks_id,
ST_Buffer(r.geom_utm, COALESCE(p_buffer_dist::NUMERIC, 6.0) - 0.5) AS buf
FROM input_feature r
WHERE r.task_id = p_task_id
AND r.object_type = 'restriction'
AND r.properties->>'restriction_type' = 'oks'
AND r.geom_utm IS NOT NULL
),
pairs AS MATERIALIZED (
    SELECT e.id AS a_id, t.id AS b_id,
e.own_poly AS own_poly,
ST_MakeLine(e.geom, t.geom) AS line,
ST_Distance(e.geom, t.geom) AS len
FROM esc e
JOIN targets t ON ST_DWithin(e.geom, t.geom, 500.0)
UNION ALL
SELECT a.id, b.id, a.own_poly,
ST_MakeLine(a.geom, b.geom),
ST_Distance(a.geom, b.geom)
FROM esc a
JOIN esc b ON b.id > a.id AND ST_DWithin(a.geom, b.geom, 500.0)
),
valid AS MATERIALIZED (
    SELECT p.a_id, p.b_id, p.own_poly, p.line, p.len
FROM pairs p
WHERE p.len > 0.1
AND ((SELECT geom FROM forbidden_no_oks) IS NULL
OR NOT ST_Intersects(p.line, (SELECT geom FROM forbidden_no_oks)))
)
INSERT INTO visibility_edge (
    task_id, cluster_id, source_vertex, target_vertex,
geom, length_m, is_special, cost, reverse_cost
)
SELECT DISTINCT ON (v.a_id, v.b_id)
p_task_id, p_cluster_id, v.a_id, v.b_id,
v.line, v.len, FALSE, v.len, v.len
FROM valid v
WHERE NOT EXISTS (
SELECT 1 FROM oks_zones oz
WHERE oz.oks_id IS DISTINCT FROM v.own_poly
AND ST_Intersects(v.line, oz.buf)
);

-----------------------------------------------------------------
-- Зеркальные рёбра
-----------------------------------------------------------------
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
AND sv.vertex_type = 'escape_point'
AND NOT EXISTS (
    SELECT 1 FROM visibility_edge x
    WHERE x.task_id = ve.task_id
AND x.cluster_id = ve.cluster_id
AND x.source_vertex = ve.target_vertex
AND x.target_vertex = ve.source_vertex
);
END;
$$ LANGUAGE plpgsql;