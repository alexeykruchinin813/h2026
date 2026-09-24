-- V30. Фикс сигнатуры create_escape_points: UUID, INT, DOUBLE PRECISION.
--
-- Проблема (test.log 24.09, HybridConnectivityIT):
--   ERROR: function create_escape_points(uuid, integer, double precision) does not exist
-- Java (HybridVisibilityGraphService.addEscapePoints) вызывает:
--   SELECT count(*) FROM create_escape_points(?, ?, ?)  -- UUID, int, double
-- pgjdbc отправляет double как DOUBLE PRECISION (float8), а в V27 параметр объявлен NUMERIC —
-- PostgreSQL не выполняет неявный implicit cast float8 -> numeric во вызове функции, отсюда PSQLException.
--
-- Архитектурная ловушка (осознанное решение): escape-точки добавляются ПОСЛЕ build_visibility_graph,
-- поэтому рёбра escape->corner/candidate SQL-графом уже не строятся; escape-вершины остаются
-- "мостами" только через U6-рёбра ОКС. Если метрика P0-4 (<10 связных ОКС) не сойдётся после этого
-- фикса, следующий шаг — достройка рёбер escape-точек до внешних вершин валидатором (Java/JTS),
-- а НЕ усложнение этой функции.

DROP FUNCTION IF EXISTS create_escape_points(UUID, INT, NUMERIC);
DROP FUNCTION IF EXISTS create_escape_points(UUID, INT, DOUBLE PRECISION);

CREATE OR REPLACE FUNCTION create_escape_points(
    p_task_id UUID,
    p_cluster_id INT,
    p_buffer_dist DOUBLE PRECISION DEFAULT 6.0  -- 5m + 1m margin (типы точно под JDBC-вызов)
) RETURNS TABLE(vertex_id BIGINT, oks_id TEXT, geom GEOMETRY) AS $$
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
               ST_ExteriorRing(ST_Buffer(poly_geom, p_buffer_dist::NUMERIC)) AS ring_geom
        FROM oks_polygons
    ),
    -- Равномерно 8 точек по периметру (escapePointsPerPolygon=8 в Java).
    escape_pts AS (
        SELECT er.oks_id,
               ST_LineInterpolatePoint(er.ring_geom, frac) AS geom
        FROM escape_rings er,
             LATERAL generate_series(0, 7) AS i,
             LATERAL (SELECT (i::DOUBLE PRECISION / 8.0) AS frac) f
        WHERE ST_NPoints(er.ring_geom) > 3
    )
    inserted AS (
        INSERT INTO visibility_vertex (task_id, cluster_id, vertex_type, ref_id, geom, own_polygon_id)
        SELECT DISTINCT ON (p_task_id, p_cluster_id, eps.oks_id, round(ST_X(eps.geom)::NUMERIC, 3), round(ST_Y(eps.geom)::NUMERIC, 3))
               p_task_id, p_cluster_id, 'escape_point', eps.oks_id, eps.geom, eps.oks_id
        FROM escape_pts eps
        RETURNING id, oks_id, geom
    )
    SELECT i.id, i.oks_id, i.geom FROM inserted i;

    -- ==== Мосты escape -> внешние вершины (см. "архитектурная ловушка" выше) ====
    -- Строим рёбра от каждой escape-точки к polygon_corner/candidate/other escape,
    -- валидируя их против ЧУЖИХ oks-зон (rough buffer из V25) и forbidden_no_oks.
    -- Собственный буфер escape-точке не мешает (аналог U6): точка лежит НА границе своего буфера.
    WITH esc AS (
        SELECT v.id, v.geom, v.own_polygon_id
        FROM visibility_vertex v
        WHERE v.task_id = p_task_id AND v.cluster_id = p_cluster_id
          AND v.vertex_type = 'escape_point'
    ),
    targets AS (
        SELECT v.id, v.geom
        FROM visibility_vertex v
        WHERE v.task_id = p_task_id AND v.cluster_id = p_cluster_id
          AND v.vertex_type IN ('polygon_corner', 'candidate')
    ),
    forbidden_no_oks AS (
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
    oks_zones AS (
        SELECT r.feature_id,
               ST_Buffer(r.geom_utm, COALESCE(p_buffer_dist::NUMERIC, 6.0) - 0.5) AS buf
        FROM input_feature r
        WHERE r.task_id = p_task_id
          AND r.object_type = 'restriction'
          AND r.properties->>'restriction_type' = 'oks'
          AND r.geom_utm IS NOT NULL
    ),
    pairs AS (
        SELECT e.id AS a_id, t.id AS b_id,
               e.own_polygon_id AS own_poly,
               ST_MakeLine(e.geom, t.geom) AS line,
               ST_Distance(e.geom, t.geom) AS len
        FROM esc e
        JOIN targets t ON ST_DWithin(e.geom, t.geom, 500.0)
        UNION ALL
        SELECT a.id, b.id, a.own_polygon_id,
               ST_MakeLine(a.geom, b.geom),
               ST_Distance(a.geom, b.geom)
        FROM esc a
        JOIN esc b ON b.id > a.id AND ST_DWithin(a.geom, b.geom, 500.0)
    ),
    valid AS (
        SELECT p.a_id, p.b_id, p.line, p.len
        FROM pairs p
        WHERE p.len > 0.1
          -- NULL-safe: если forbidden_no_oks пуст/NULL, рёбра не отсекаются
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
        WHERE oz.feature_id IS DISTINCT FROM (
                  SELECT own_polygon_id FROM visibility_vertex WHERE id = v.a_id)
          AND ST_Intersects(v.line, oz.buf)
    );

    -- Зеркальные рёбра (pgr_astar вызывается с directed:=false, но симметричные
    -- дубли создаются и в build_visibility_graph — следуем тому же инварианту)
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

COMMENT ON FUNCTION create_escape_points(UUID, INT, DOUBLE PRECISION) IS
'Escape points on OKS buffer boundary (default 6m = 5m + 1m margin), 8 evenly spaced per polygon,
PLUS bridge edges escape->corner/candidate/escape validated against foreign OKS zones (U6 analog).
Signature matches JDBC call (UUID, INT, DOUBLE PRECISION). Called AFTER build_visibility_graph from
HybridVisibilityGraphService step 2, BEFORE JTS validation step 5.';
