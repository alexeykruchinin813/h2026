-- V36. Фактическая реализация фикса V35 (V35 применилась пустой — checksum=0 в history).
-- Два дефекта, обнаруженных DIAG (прогон 2026-09-25):
--   (A) Секция 3 (зеркало) в V34 вставила 0 рёбер — доказательство: 2669 уникальных
--       пар vs 3083 рёбер, однонаправленных 2255. Constraint, блокирующих дубли, нет
--       (DIAG 4b), т.е. причина внутри V34 — не отлаживаем её, а архитектурно
--       исключаем: оба направления вставляются одним INSERT...UNION ALL.
--   (B) Рёбра oks -> own escape_point отсутствуют полностью (DIAG 1: 17 из 17).
--       Архитектурная ловушка V30: escape-точки создаются ПОСЛЕ build_visibility_graph,
--       явного шага "подключить oks к своим escape_point" не было.
--
-- Секцию 1.5 делаем независимой от own_polygon_id у oks-вершин:
-- привязка через ST_Contains(oks_polygon.geom, oks_vertex.geom), не через столбец.

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

-- 0. Идемпотентность (восстановлена из V33; V34 её потерял).
DELETE FROM visibility_edge ve
WHERE ve.source_vertex IN (
SELECT id FROM visibility_vertex
WHERE task_id = p_task_id AND cluster_id = p_cluster_id
AND vertex_type = 'escape_point');
DELETE FROM visibility_edge ve
WHERE ve.target_vertex IN (
    SELECT id FROM visibility_vertex
WHERE task_id = p_task_id AND cluster_id = p_cluster_id
AND vertex_type = 'escape_point');
DELETE FROM visibility_vertex
WHERE task_id = p_task_id AND cluster_id = p_cluster_id
AND vertex_type = 'escape_point';

-- 1. Escape-точки (идентично V34).
RETURN QUERY
WITH oks_polygons AS MATERIALIZED (
    SELECT r.feature_id AS oks_id, r.geom_utm AS poly_geom
FROM input_feature r
WHERE r.task_id = p_task_id
AND r.object_type = 'restriction'
AND r.properties->>'restriction_type' = 'oks'
AND r.geom_utm IS NOT NULL
AND EXISTS (SELECT 1 FROM visibility_vertex vv
    WHERE vv.task_id = p_task_id AND vv.cluster_id = p_cluster_id
AND vv.vertex_type = 'oks'
AND ST_Contains(r.geom_utm, vv.geom))
),
escape_rings AS MATERIALIZED (
    SELECT oks_id,
    ST_ExteriorRing(ST_Buffer(poly_geom, v_buf::NUMERIC)) AS ring_geom
FROM oks_polygons
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

-- 1.5. Явные рёбра oks -> own escape_point.
--      Привязка через ST_Contains(polygon.geom, oks_vertex.geom) НЕЗАВИСИМО
--      от того, заполнен ли own_polygon_id у oks-вершины.
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
JOIN input_feature r
ON  r.task_id = ov.task_id
AND r.object_type = 'restriction'
AND r.properties->>'restriction_type' = 'oks'
AND r.geom_utm IS NOT NULL
AND ST_Contains(r.geom_utm, ov.geom)
JOIN visibility_vertex ep
ON  ep.task_id = ov.task_id
AND ep.cluster_id = ov.cluster_id
AND ep.vertex_type = 'escape_point'
AND ep.own_polygon_id::TEXT = r.feature_id::TEXT
WHERE ov.task_id = p_task_id
AND ov.cluster_id = p_cluster_id
AND ov.vertex_type = 'oks'
AND ST_Distance(ov.geom, ep.geom) > 0.1;

-- 2. Мосты escape -> внешние вершины. Оба направления одним INSERT.
--    Секция 3 (зеркало) из V34 больше не нужна.
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
FROM valid v
UNION ALL
SELECT p_task_id, p_cluster_id, v.b_id, v.a_id, v.line, v.len, FALSE, v.len, v.len
FROM valid v;
END;
$$ LANGUAGE plpgsql;

COMMENT ON FUNCTION create_escape_points(UUID, INT, DOUBLE PRECISION) IS
'V36 (fix of empty V35): (A) both directions inserted in section 2, section 3 removed; (B) explicit oks->own escape_point edges in section 1.5 (via ST_Contains); (C) idempotency + statement_timeout restored.';