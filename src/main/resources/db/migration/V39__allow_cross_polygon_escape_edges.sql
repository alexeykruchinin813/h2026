-- V39. Фикс: escape_points разных полигонов не связаны друг с другом.
--
-- ДИАГНОЗ (лог 14:18:55 + SQL 2026-09-25):
--   esc_to_esc у полигонов = n × (n−1) / 2 (полный граф ВНУТРИ полигона):
--     n=8  → 28   (poly 75, 77, 74, 82, 81, 87, 90, 91, 89)
--     n=16 → 120  (poly 71, 72, 88)
--     n=32 → 496  (poly 92)
--   Рёбер МЕЖДУ полигонами — ноль. Граф распадается на компоненты:
--   3 OKS (1497, 1501, 1505) имеют путь к candidate 1512, остальные 11 — нет.
--
-- ПРИЧИНА: фильтр oks_zones в секции 2 V37/V38 отбрасывает пары escape↔escape
-- разных полигонов, если линия между ними пересекает буфер ЛЮБОГО чужого
-- полигона. В плотном кластере 0 (14 OKS в 150 м) это отсекает все
-- межполигональные рёбра.
--
-- ФИКС: разрешить пары escape↔escape, если линия пересекает только буферы
-- source и target полигонов. Третьи полигоны по-прежнему блокируют линию.
-- Логика: escape_A → escape_B легально проходит через свои буферы (это концы
-- пути), но не должен пересекать промежуточные здания.
--
-- Для пар escape → candidate (target_poly = NULL) фильтр остаётся прежним.

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

-- 1.5. oks -> own escape_point.
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

-- 2. Мосты escape -> внешние вершины.
--    V39: у pairs добавлен target_poly (own_polygon_id целевой вершины).
--    Фильтр oks_zones теперь игнорирует буферы обоих концов пары.
WITH esc AS MATERIALIZED (
    SELECT v.id, v.geom, v.own_polygon_id AS own_poly
FROM visibility_vertex v
WHERE v.task_id = p_task_id AND v.cluster_id = p_cluster_id
AND v.vertex_type = 'escape_point'
),
targets AS MATERIALIZED (
    SELECT v.id, v.geom, v.own_polygon_id AS tgt_poly
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
    -- escape -> target (candidate / polygon_corner). target_poly = own_polygon_id цели.
    SELECT e.id AS a_id, t.id AS b_id, e.own_poly AS own_poly,
t.tgt_poly AS target_poly,
ST_MakeLine(e.geom, t.geom) AS line,
ST_Distance(e.geom, t.geom) AS len
FROM esc e
JOIN targets t ON ST_DWithin(e.geom, t.geom, 500.0)
UNION ALL
-- escape -> escape. target_poly = own_polygon_id цели (нужен для V39-фильтра).
SELECT a.id, b.id, a.own_poly, b.own_poly,
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
-- V39 FIX: игнорируем буферы source-полигона И target-полигона.
-- Для escape -> candidate target_poly = NULL, фильтр не меняется.
AND NOT EXISTS (
    SELECT 1 FROM oks_zones oz
    WHERE oz.oks_id IS DISTINCT FROM p.own_poly
AND oz.oks_id IS DISTINCT FROM p.target_poly
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
'V39: allow escape<->escape edges between different polygons (oks_zones filter ignores both source and target polygon buffers); rest = V38.';