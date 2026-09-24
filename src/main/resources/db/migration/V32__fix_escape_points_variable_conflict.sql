-- V32. Финальное устранение "column reference \"geom\" / \"oks_id\" is ambiguous"
-- (четвёртый прогон HybridConnectivityIT, test.log 24.09).
--
-- Диагноз (подтверждён консультантом и независимым разбором): PL/pgSQL подставляет
-- имена OUT-параметров RETURNS TABLE(vertex_id, oks_id, geom) как переменные в ЛЮБОЕ
-- неликвалифицированное вхождение в SQL-теле функции. В V31 оставались спорные имена:
--   * `AS geom` в CTE forbidden_no_oks (строка ~111) — конфликт с OUT-переменной geom;
--   * `RETURNING ... geom AS ep_geom_out` (~91) — неликвалифицированный geom из целевой
--     таблицы visibility_vertex, который тоже может резолвиться в OUT-переменную.
-- Переименование колонок (V30/V31) лечило только oks_id и требовало тотальной
-- квалификации; надёжнее один раз включить режим разрешения конфликта в пользу колонки.
--
-- Лечение: копия тела V31 + директива `#variable_conflict use_column` сразу после `AS $$`
-- (все спорные имена — колонки/алиасы CTE; OUT-параметры заполняются финальным
-- RETURN QUERY по позиции). Дополнительно:
--   1) вставлен отсутствовавший в V31 комментарий-разделитель перед секцией 2;
--   2) `AS geom` -> `AS fb_geom` + `f.geom::GEOMETRY` в RETURN QUERY — явное приведение
--      типа выходного столбца geom (защита от "return type geometry does not match"
--      при любом дрейфе типов);
--   3) сигнатура остаётся единственной активной: (UUID, INT, DOUBLE PRECISION),
--      NUMERIC-сигнатура V27 удаляется; вызов из Java типизирован ?::double precision.
-- Миграция идемпотентна к повторному вызову самой функции (escape-вершины/рёбра
-- вычищаются для данной пары task/cluster перед пересозданием).

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
'V32: #variable_conflict use_column (OUT-параметры oks_id/geom больше не конфликтуют с колонками), fb_geom без AS geom, ::GEOMETRY на выходе; единственная активная сигнатура; идемпотентна.';
