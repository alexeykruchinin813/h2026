-- V38. Фикс: escape-точки для MultiPolygon-полигонов, у которых после буферизации
-- остаётся несколько частей.
--
-- ДИАГНОЗ (2026-09-25): ST_ExteriorRing(MULTIPOLYGON) возвращает NULL (PostGIS
-- поддерживает только POLYGON). Полигон feature_id=92 имеет 5 частей, после
-- ST_Buffer(..., 6) — 3 части (не сливаются). escape_rings V37 давал 0 строк
-- для poly 92 → 2 OKS (1431, 1434) без выхода из буфера.
--
-- ДОКАЗАТЕЛЬСТВО:
--   feature_id=72: n_orig=1, gtype_buf=POLYGON    → ring_is_null=false  ✓
--   feature_id=92: n_orig=5, gtype_buf=MULTIPOLYGON → ring_is_null=true ✗
--
-- ФИКС: заменяем ST_ExteriorRing(ST_Buffer(...)) на ST_Dump(ST_Boundary(...)).
-- Для POLYGON работает как раньше (один ринг), для MULTIPOLYGON даёт по одной
-- LineString на каждую часть. Для poly 92 получим 3 ринга × 8 точек = 24 точки
-- (вместо 8). Каждая OKS получит рёбра к escape-точкам своей части.
--
-- Остальное тело функции — идентично V37.

DROP FUNCTION IF EXISTS create_escape_points(UUID, INT, NUMERIC);
DROP FUNCTION IF EXISTS create_escape_points(UUID, INT, DOUBLE PRECISION);

CREATE INDEX IF NOT EXISTS idx_vv_geom ON visibility_vertex USING GIST(geom);
CREATE INDEX IF NOT EXISTS idx_vv_task_cluster_type ON visibility_vertex(task_id, cluster_id, vertex_type);
CREATE INDEX IF NOT EXISTS idx_ve_source ON visibility_edge(source_vertex);
CREATE INDEX IF NOT EXISTS idx_ve_target ON visibility_edge(target_vertex);
CREATE INDEX IF NOT EXISTS idx_if_task_type ON input_feature(task_id, object_type);

CREATE OR REPLACE FUNCTION create_escape_points(
p_task_id UUID,
p_cluster_id INT,
p_buffer_dist DOUBLE PRECISION DEFAULT 6.0
) RETURNS TABLE(vertex_id BIGINT, oks_id TEXT, geom GEOMETRY) AS $$
#variable_conflict use_column
DECLARE
v_buf DOUBLE PRECISION := COALESCE(p_buffer_dist, 6.0);
BEGIN
SET LOCAL statement_timeout = '120s';

-- 0. Идемпотентность.
DELETE FROM visibility_edge ve WHERE ve.source_vertex IN (
SELECT id FROM visibility_vertex
WHERE task_id = p_task_id AND cluster_id = p_cluster_id
AND vertex_type = 'escape_point');
DELETE FROM visibility_edge ve WHERE ve.target_vertex IN (
    SELECT id FROM visibility_vertex
WHERE task_id = p_task_id AND cluster_id = p_cluster_id
AND vertex_type = 'escape_point');
DELETE FROM visibility_vertex
WHERE task_id = p_task_id AND cluster_id = p_cluster_id
AND vertex_type = 'escape_point';

-- 1. Escape-точки.
RETURN QUERY
WITH oks_polygons AS MATERIALIZED (
    SELECT DISTINCT r.feature_id AS oks_id, r.geom_utm AS poly_geom
FROM input_feature r
JOIN visibility_vertex vv
ON  vv.task_id = p_task_id
AND vv.cluster_id = p_cluster_id
AND vv.vertex_type = 'oks'
AND vv.own_polygon_id IS NOT NULL
AND vv.own_polygon_id::TEXT = r.feature_id::TEXT
WHERE r.task_id = p_task_id
AND r.object_type = 'restriction'
AND r.properties->>'restriction_type' = 'oks'
AND r.geom_utm IS NOT NULL
),
-- V38 FIX: ST_Boundary(MULTIPOLYGON) даёт MULTILINESTRING; ST_Dump разложит
-- его на отдельные LineString по одной на каждую часть полигона.
-- ST_ExteriorRing(MULTIPOLYGON) раньше возвращал NULL → escape_rings пустой.
escape_rings AS MATERIALIZED (
    SELECT DISTINCT op.oks_id,
(ST_Dump(ST_Boundary(ST_Buffer(op.poly_geom, v_buf::NUMERIC)))).geom AS ring_geom
FROM oks_polygons op
),
escape_pts AS MATERIALIZED (
    SELECT er.oks_id,
ST_LineInterpolatePoint(er.ring_geom, f.frac) AS geom
FROM escape_rings er,
LATERAL generate_series(0, 7) AS i,
LATERAL (SELECT (i::DOUBLE PRECISION / 8.0) AS frac) f
WHERE ST_NPoints(er.ring_geom) > 3
),
inserted AS (
    INSERT INTO visibility_vertex
(task_id, cluster_id, vertex_type, ref_id, geom, own_polygon_id)
SELECT DISTINCT ON (p_task_id, p_cluster_id, eps.oks_id,
round(ST_X(eps.geom)::NUMERIC, 3),
round(ST_Y(eps.geom)::NUMERIC, 3))
p_task_id, p_cluster_id, 'escape_point',
eps.oks_id, eps.geom, eps.oks_id
FROM escape_pts eps
RETURNING id, ref_id::TEXT AS oks_id, geom
)
SELECT i.id, i.oks_id, i.geom FROM inserted i;

-- 1.5. oks -> own escape_point (id-based match).
INSERT INTO visibility_edge (
    task_id, cluster_id, source_vertex, target_vertex,
geom, length_m, is_special, cost, reverse_cost
)
SELECT p_task_id, p_cluster_id, ov.id, ep.id,
ST_MakeLine(ov.geom, ep.geom),
ST_Distance(ov.geom, ep.geom),
FALSE,
ST_Distance(ov.geom, ep.geom),
ST_Distance(ov.geom, ep.geom)
FROM visibility_vertex ov
JOIN visibility_vertex ep
ON  ep.task_id = ov.task_id
AND ep.cluster_id = ov.cluster_id
AND ep.vertex_type = 'escape_point'
AND ep.own_polygon_id::TEXT = ov.own_polygon_id::TEXT
WHERE ov.task_id = p_task_id
AND ov.cluster_id = p_cluster_id
AND ov.vertex_type = 'oks'
AND ov.own_polygon_id IS NOT NULL
AND ST_Distance(ov.geom, ep.geom) > 0.1;

-- 2. Мосты escape -> внешние вершины (single direction).
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
oks_zones AS MATERIALIZED (
    SELECT r.feature_id AS oks_id,
ST_Buffer(r.geom_utm, (v_buf - 0.5)::NUMERIC) AS buf
FROM input_feature r
WHERE r.task_id = p_task_id
AND r.object_type = 'restriction'
AND r.properties->>'restriction_type' = 'oks'
AND r.geom_utm IS NOT NULL
),
pairs AS MATERIALIZED (
    SELECT e.id AS a_id, t.id AS b_id, e.own_poly AS own_poly,
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
SELECT p.a_id, p.b_id, p.line, p.len
FROM pairs p
WHERE p.len > 0.1
AND ((SELECT geom FROM forbidden_no_oks) IS NULL
OR NOT ST_Intersects(p.line, (SELECT geom FROM forbidden_no_oks)))
AND NOT EXISTS (
    SELECT 1 FROM oks_zones oz
WHERE oz.oks_id IS DISTINCT FROM p.own_poly
AND ST_Intersects(p.line, oz.buf)
)
)
INSERT INTO visibility_edge (
    task_id, cluster_id, source_vertex, target_vertex,
geom, length_m, is_special, cost, reverse_cost
)
SELECT p_task_id, p_cluster_id, v.a_id, v.b_id, v.line, v.len, FALSE, v.len, v.len
FROM valid v;
END;
$$ LANGUAGE plpgsql;

COMMENT ON FUNCTION create_escape_points(UUID, INT, DOUBLE PRECISION) IS
'V38: ST_Boundary+ST_Dump for MultiPolygon escape rings (fixes poly feature_id=92 with 5 parts); body otherwise identical to V37.';