-- ============================================================================
-- V56. Фикс V41: мосты escape → candidate создаются всегда.
--
-- Диагноз: V41 использует s.id < t.id для дедупликации пар escape↔escape,
-- но escape-точки создаются в create_escape_points (id 8543–8718), а
-- кандидаты — в build_visibility_graph (id 7280–7342). Все escape.id
-- больше всех candidate.id, поэтому условие s.id < t.id отсекает все
-- пары escape → candidate. В результате 13 из 17 OKS не имеют путей
-- до кандидатов (только транзитом через чужие OKS, который закрыт
-- Java-фильтром V55).
--
-- Решение: сохранить s.id < t.id только для пар escape↔escape (дедуп),
-- для пар escape→candidate — не применять. Кандидат никогда не является
-- источником, обратные рёбра не нужны.
--
-- Тело функции — целиком из V41, изменён только блок мостов.
-- ============================================================================

DROP FUNCTION IF EXISTS create_escape_points(UUID, INT, NUMERIC);
DROP FUNCTION IF EXISTS create_escape_points(UUID, INT, DOUBLE PRECISION);

CREATE OR REPLACE FUNCTION create_escape_points(
    p_task_id UUID,
    p_cluster_id INT,
    p_buffer_dist DOUBLE PRECISION DEFAULT 6.0
) RETURNS TABLE(vertex_id BIGINT, oks_id TEXT, geom GEOMETRY) AS $$
#variable_conflict use_column
DECLARE
v_buf DOUBLE PRECISION := COALESCE(p_buffer_dist, 6.0);
v_bridges_esc_esc INT := 0;
v_bridges_esc_cand INT := 0;
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

-- 0.5. UPDATE corner.own_polygon_id (из V40, оставлено).
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

-- 1. Escape-точки (ST_Boundary + ST_Dump).
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

-- 2. V56: мосты escape ↔ {escape, candidate}.
--    s.id < t.id применяется ТОЛЬКО для пар escape↔escape (дедуп).
--    Для escape→candidate — не применяется (candidate никогда не source).
WITH src AS MATERIALIZED (
    SELECT v.id, v.geom
FROM visibility_vertex v
WHERE v.task_id = p_task_id AND v.cluster_id = p_cluster_id
AND v.vertex_type = 'escape_point'
),
tgt AS MATERIALIZED (
    SELECT v.id, v.geom, v.vertex_type
FROM visibility_vertex v
WHERE v.task_id = p_task_id AND v.cluster_id = p_cluster_id
AND v.vertex_type IN ('escape_point', 'candidate')
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
ST_Distance(s.geom, t.geom) AS len,
t.vertex_type AS t_type
FROM src s
JOIN tgt t
ON ST_DWithin(s.geom, t.geom, 200.0)
AND (
(t.vertex_type = 'escape_point' AND s.id < t.id)
OR t.vertex_type = 'candidate'
)
),
valid AS MATERIALIZED (
    SELECT p.a_id, p.b_id, p.line, p.len, p.t_type
FROM pairs p
WHERE p.len > 0.1
AND ((SELECT geom FROM forbidden_no_oks) IS NULL
OR NOT ST_Intersects(p.line, (SELECT geom FROM forbidden_no_oks)))
),
counted AS (
    SELECT
    COUNT(*) FILTER (WHERE t_type = 'escape_point') AS c_esc_esc,
COUNT(*) FILTER (WHERE t_type = 'candidate')    AS c_esc_cand
FROM valid
),
inserted_edges AS (
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
)
RETURNING 1
)
SELECT c.c_esc_esc, c.c_esc_cand INTO v_bridges_esc_esc, v_bridges_esc_cand
FROM counted c;

RAISE NOTICE '[escape] cluster %: % escape↔escape, % escape→candidate bridges',
p_cluster_id, v_bridges_esc_esc, v_bridges_esc_cand;
END;
$$ LANGUAGE plpgsql;

COMMENT ON FUNCTION create_escape_points(UUID, INT, DOUBLE PRECISION) IS
'V56: мосты escape ↔ {escape, candidate} с дедупом только для escape↔escape.
escape → candidate создаётся всегда (candidate никогда не source).
Фикс id-фильтра V41.';