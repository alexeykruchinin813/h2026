-- V40. Архитектурный фикс связности OKS-полигонов.
--
-- ДИАГНОЗ (лог 2026-09-25):
--   corner_77 → *          = 0 строк (тупик)
--   corner_(75,77,88,72) → * = 0 строк (у ВСЕХ OKS-полигонов)
--   escape↔escape межполигонально = 0 (V39 не помогает)
--   totalPaths = 6/17
--
-- КОРЕНЬ: V25 строит corner'ы с фильтром forbidden_main (буферы OKS 1м).
-- Corner на границе полигона → линия наружу пересекает свой 1м буфер → отброшено.
-- own_polygon_id у corner'ов NULL → исключить свой буфер нельзя.
--
-- ФИКС (архитектурный):
--   1. Убрать oks_zones из SQL. SQL строит COARSE-граф.
--      Точная проверка 5/7/9м по диаметру — задача JTS-валидатора в Java.
--   2. UPDATE corner.own_polygon_id для OKS-полигонов.
--   3. Мосты: source = escape ∪ corner_OKS; target = candidate ∪ escape ∪ corner_OKS.
--      Фильтр: только forbidden_no_oks (не-OKS ограничения).
--   4. Java U6-extended: пропускать буфер source И target полигона.

DROP FUNCTION IF EXISTS create_escape_points(UUID, INT, NUMERIC);
DROP FUNCTION IF EXISTS create_escape_points(UUID, INT, DOUBLE PRECISION);

CREATE INDEX IF NOT EXISTS idx_vv_geom ON visibility_vertex USING GIST(geom);
CREATE INDEX IF NOT EXISTS idx_vv_task_cluster_type ON visibility_vertex(task_id, cluster_id, vertex_type);
CREATE INDEX IF NOT EXISTS idx_vv_ref ON visibility_vertex(task_id, ref_id, vertex_type);
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

-- 0.5. V40: UPDATE corner.own_polygon_id для OKS-полигонов.
UPDATE visibility_vertex vv
SET own_polygon_id = vv.ref_id
WHERE vv.task_id = p_task_id
AND vv.cluster_id = p_cluster_id
AND vv.vertex_type = 'polygon_corner'
AND vv.own_polygon_id IS NULL
AND EXISTS (
    SELECT 1 FROM input_feature r
    WHERE r.task_id = vv.task_id
AND r.object_type = 'restriction'
AND r.properties->>'restriction_type' = 'oks'
AND r.feature_id::TEXT = vv.ref_id::TEXT
);

-- 1. Escape-точки (V38: ST_Boundary + ST_Dump для MultiPolygon).
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

-- 1.5. OKS → own escape_point.
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

-- 2. V40: МОСТЫ (переписаны).
--    source = escape_point ∪ {polygon_corner с own_polygon_id}
--    target = candidate ∪ escape_point ∪ {polygon_corner с own_polygon_id}
--    Фильтр: ТОЛЬКО forbidden_no_oks (без oks_zones).
WITH src AS MATERIALIZED (
    SELECT v.id, v.geom
FROM visibility_vertex v
WHERE v.task_id = p_task_id AND v.cluster_id = p_cluster_id
AND (v.vertex_type = 'escape_point'
OR (v.vertex_type = 'polygon_corner' AND v.own_polygon_id IS NOT NULL))
),
tgt AS MATERIALIZED (
    SELECT v.id, v.geom
FROM visibility_vertex v
WHERE v.task_id = p_task_id AND v.cluster_id = p_cluster_id
AND (v.vertex_type = 'candidate'
    OR v.vertex_type = 'escape_point'
OR (v.vertex_type = 'polygon_corner' AND v.own_polygon_id IS NOT NULL))
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
pairs AS MATERIALIZED (
    SELECT s.id AS a_id, t.id AS b_id,
ST_MakeLine(s.geom, t.geom) AS line,
ST_Distance(s.geom, t.geom) AS len
FROM src s
JOIN tgt t ON t.id <> s.id
AND s.id < t.id
AND ST_DWithin(s.geom, t.geom, 500.0)
),
valid AS MATERIALIZED (
    SELECT p.a_id, p.b_id, p.line, p.len
FROM pairs p
WHERE p.len > 0.1
AND ((SELECT geom FROM forbidden_no_oks) IS NULL
OR NOT ST_Intersects(p.line, (SELECT geom FROM forbidden_no_oks)))
)
INSERT INTO visibility_edge (
    task_id, cluster_id, source_vertex, target_vertex,
geom, length_m, is_special, cost, reverse_cost
)
SELECT p_task_id, p_cluster_id, v.a_id, v.b_id, v.line, v.len, FALSE, v.len, v.len
FROM valid v
WHERE NOT EXISTS (
    SELECT 1 FROM visibility_edge e
    WHERE e.task_id = p_task_id
AND LEAST(e.source_vertex, e.target_vertex) = LEAST(v.a_id, v.b_id)
AND GREATEST(e.source_vertex, e.target_vertex) = GREATEST(v.a_id, v.b_id)
);
END;
$$ LANGUAGE plpgsql;

COMMENT ON FUNCTION create_escape_points(UUID, INT, DOUBLE PRECISION) IS
'V40: coarse bridge graph without oks_zones filter; sources = escape ∪ corner_OKS; targets = candidate ∪ escape ∪ corner_OKS; own_polygon_id set for OKS-polygon corners. Precise OKS buffer check delegated to Java JTS validator.';