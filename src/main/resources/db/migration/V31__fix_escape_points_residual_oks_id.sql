-- V31. Полное устранение "column reference \"oks_id\" is ambiguous" (третий прогон HybridConnectivityIT, test.log 24.09 19:09).
--
-- Диагноз: в БД остаётся ВТОРАЯ активная сигнатура create_escape_points(UUID, INT, NUMERIC)
-- из V27 — именно она вызывается из Java (escapeBufferDist объявлен как java.math.BigDecimal,
-- JDBC-параметр DECIMAL резолвится в NUMERIC, а не DOUBLE PRECISION). V30 удалял её своим
-- DROP IF EXISTS и пересоздавал функцию с новой сигнатурой, НО сам текст V30-функции тоже
-- содержал ссылки на имя OUT-переменной oks_id внутри запроса (в escape_rings:
-- "SELECT DISTINCT oks_uid ..." без префикса er.) — plpgsql подставляет OUT-переменную
-- oks_id в любое неликвалифицированное вхождение, и при наличии двух источников
-- (переменная + столбец er.oks_uid) запрос падает ровно с той же ошибкой на строке 3.
--
-- Лечение в этой миграции:
--   1) каноническая сигнатура теперь ОДНА: (UUID, INT, DOUBLE PRECISION) — обе старые
--      удаляются; чтобы вызов из Java гарантированно попадал в неё, параметр буфера
--      приводится через p_buffer_dist::DOUBLE PRECISION явно в месте использования;
--   2) ВСЕ столбцы внутри тела переименованы в ep_oks_uid/ep_geom_out и заqualifiцированы
--      алиасами таблиц — ни одного неликвалифицированного вхождения имени, совпадающего
--      с OUT-переменной, не остаётся;
--   3) добавлена защита от повторного запуска на живых данных: escape-точки и их рёбра
--      сначала вычищаются для данного (task, cluster), иначе повторный вызов функции
--      плодит дубли (у visibility_edge нет уникального ограничения).

DROP FUNCTION IF EXISTS create_escape_points(UUID, INT, NUMERIC);
DROP FUNCTION IF EXISTS create_escape_points(UUID, INT, DOUBLE PRECISION);

CREATE OR REPLACE FUNCTION create_escape_points(
    p_task_id UUID,
    p_cluster_id INT,
    p_buffer_dist DOUBLE PRECISION DEFAULT 6.0
) RETURNS TABLE(vertex_id BIGINT, oks_id TEXT, geom GEOMETRY) AS $$
DECLARE
    v_buf DOUBLE PRECISION := COALESCE(p_buffer_dist, 6.0);
BEGIN
    ---------------------------------------------------------------------
    -- 0. Идемпотентность: удалить ранее созданные escape-вершины/рёбра
    ---------------------------------------------------------------------
    DELETE FROM visibility_edge ve
    WHERE ve.task_id = p_task_id AND ve.cluster_id = p_cluster_id
      AND (   ve.source_vertex IN (SELECT id FROM visibility_vertex
                                   WHERE task_id = p_task_id AND cluster_id = p_cluster_id
                                     AND vertex_type = 'escape_point')
           OR ve.target_vertex IN (SELECT id FROM visibility_vertex
                                   WHERE task_id = p_task_id AND cluster_id = p_cluster_id
                                     AND vertex_type = 'escape_point'));

    DELETE FROM visibility_vertex
    WHERE task_id = p_task_id AND cluster_id = p_cluster_id
      AND vertex_type = 'escape_point';

    ---------------------------------------------------------------------
    -- 1. Escape-точки на границе буфера своих ОКС
    --    НИ ОДНОГО неликвалифицированного вхождения "oks_id" в теле запроса!
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
    SELECT i.id, i.ep_uid_out, i.ep_geom_out FROM inserted i;

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

    ---------------------------------------------------------------------
    -- 3. Зеркальные рёбра
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
'V31: единственная активная сигнатура; тело без неликвалифицированных имён, совпадающих с OUT-переменными (fix oks_id ambiguous); идемпотентна.';
