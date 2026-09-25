-- V33. Производительность create_escape_points (по логу 24.09 19:19:53).
--
-- Диагноз: ошибка "oks_id is ambiguous" УСТРАНЕНА (V32 отработала): в логе cluster 0
-- проходит весь пайплайн, но этап escape занимает 74682 мс из 78674 мс — тест не
-- укладывается в таймаут 180 с, а cluster 1 падает при shutdown контейнера (I/O error,
-- следствие гонки остановки, а не самостоятельный дефект).
--
-- Причина торможения (план V32): секция 0 (идемпотентность) и секция 3 (зеркальные
-- рёбра) сканируют visibility_edge БЕЗ фильтра по source/target_vertex:
--   * predicate `ve.source_vertex IN (...) OR ve.target_vertex IN (...)` — OR двух
--     полусоединений не использует ни один индекс;
--   * NOT EXISTS x WHERE x.task_id AND x.cluster_id AND x.source_vertex=? AND
--     x.target_vertex=? — составной индекс (task_id, cluster_id) не покрывает, каждый
--     вызов = scan всех рёбер задачи.
-- GIST на visibility_vertex.geom уже есть (V14), поэтому он здесь только страхуется
-- через IF NOT EXISTS.
--
-- Лечение: переписать DELETE/NOT EXISTS на два отдельных index-friendly запроса
-- (UNION по source и target; EXISTS по паре вершин). Тело копируется из V32
-- дословно плюс директива SET LOCAL statement_timeout = '120s' (страховка от
-- зависания: лучше явная ошибка этапа, чем сожжённый бюджет теста).
-- Единственная активная сигнатура сохраняется.

DROP FUNCTION IF EXISTS create_escape_points(UUID, INT, NUMERIC);
DROP FUNCTION IF EXISTS create_escape_points(UUID, INT, DOUBLE PRECISION);

CREATE INDEX IF NOT EXISTS idx_vv_geom ON visibility_vertex USING GIST(geom);
CREATE INDEX IF NOT EXISTS idx_ve_source ON visibility_edge(source_vertex);
CREATE INDEX IF NOT EXISTS idx_ve_target ON visibility_edge(target_vertex);

CREATE OR REPLACE FUNCTION create_escape_points(
    p_task_id UUID,
    p_cluster_id INT,
    p_buffer_dist DOUBLE PRECISION DEFAULT 6.0
) RETURNS TABLE(vertex_id BIGINT, oks_id TEXT, geom GEOMETRY) AS $$
#variable_conflict use_column
DECLARE
    v_buf DOUBLE PRECISION := COALESCE(p_buffer_dist, 6.0);
BEGIN
    -- Страховка от бесконечного зависания этапа (бюджет теста позволяет 120 с).
    SET LOCAL statement_timeout = '120s';

    ---------------------------------------------------------------------
    -- 0. Идемпотентность: удалить ранее созданные escape-вершины/рёбра.
    --    V32: единый DELETE с OR по source/target -> seq scan visibility_edge.
    --    V33: два DELETE c idx_ve_source / idx_ve_target.
    ---------------------------------------------------------------------
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

    ---------------------------------------------------------------------
    -- 1. Escape-точки на границе буфера своих ОКС
    ---------------------------------------------------------------------
    RETURN QUERY
    WITH oks_polygons AS (
        SELECT r.feature_id AS ep_uid,
               r.geom_utm   AS poly_geom
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
        SELECT DISTINCT op.ep_uid,
               ST_ExteriorRing(ST_Buffer(op.poly_geom, v_buf::NUMERIC)) AS ring_geom
        FROM oks_polygons op
    ),
    escape_pts AS (
        SELECT er.ep_uid,
               ST_LineInterpolatePoint(er.ring_geom, f.frac) AS ep_geom
        FROM escape_rings er,
             LATERAL generate_series(0, 7) AS i,
             LATERAL (SELECT (i::DOUBLE PRECISION / 8.0) AS frac) f
        WHERE ST_NPoints(er.ring_geom) > 3
    ),
    inserted AS (
        INSERT INTO visibility_vertex
            (task_id, cluster_id, vertex_type, ref_id, geom, own_polygon_id)
        SELECT DISTINCT ON (eps.ep_uid, round(ST_X(eps.ep_geom)::NUMERIC, 3),
                                           round(ST_Y(eps.ep_geom)::NUMERIC, 3))
               p_task_id, p_cluster_id, 'escape_point', eps.ep_uid, eps.ep_geom, eps.ep_uid
        FROM escape_pts eps
        RETURNING id, ref_id::TEXT AS ep_uid_out, geom AS ep_geom_out
    )
    SELECT i.id, i.ep_uid_out, i.ep_geom_out::GEOMETRY FROM inserted i;

    ---------------------------------------------------------------------
    -- 2. Мосты escape -> внешние вершины (своим ОКС-буфером не блокируемся)
    ---------------------------------------------------------------------
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
        SELECT ST_Union(buf) AS fb_geom FROM (
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
               ST_Buffer(r.geom_utm, (v_buf - 0.5)::NUMERIC) AS buf
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
          AND ((SELECT f.fb_geom FROM forbidden_no_oks f) IS NULL
               OR NOT ST_Intersects(p.line, (SELECT f.fb_geom FROM forbidden_no_oks f)))
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

    ---------------------------------------------------------------------
    -- 3. Зеркальные рёбра.
    --    V32: correlated NOT EXISTS без индекса под (source,target) пары ->
    --         полный scan рёбер задачи на каждую строку.
    --    V33: LEFT JOIN LATERAL по idx_ve_source (точечный поиск) + фильтр
    --         кластера на найденной строке.
    ---------------------------------------------------------------------
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
    LEFT JOIN LATERAL (
        SELECT 1 AS found
        FROM visibility_edge x
        WHERE x.source_vertex = ve.target_vertex
          AND x.target_vertex = ve.source_vertex
          AND x.task_id = ve.task_id
          AND x.cluster_id = ve.cluster_id
        LIMIT 1
    ) dup ON TRUE
    WHERE ve.task_id = p_task_id
      AND ve.cluster_id = p_cluster_id
      AND sv.vertex_type = 'escape_point'
      AND dup.found IS NULL;
END;
$$ LANGUAGE plpgsql;

COMMENT ON FUNCTION create_escape_points(UUID, INT, DOUBLE PRECISION) IS
'V33: performance rewrite of idempotency-delete and mirror-edge NOT EXISTS (index-friendly), GIST/BTREE indexes guaranteed, statement_timeout 120s; body otherwise identical to V32 (#variable_conflict use_column kept).';

ANALYZE visibility_vertex;
ANALYZE visibility_edge;
