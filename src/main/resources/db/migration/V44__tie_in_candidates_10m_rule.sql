-- V44. Правило 10 м (ТЗ §2.4 / Разъяснения п.11) и лимит ≤4 примыканий (п.12).
--
-- find_tie_in_candidates теперь для edge_projection/graph_node проверяет:
-- если точка в радиусе 10 м от существующей heat_chamber, и к камере
-- примыкает ≤3 линейных участка heat_network (тогда после подключения
-- станет ≤4) — возвращает саму камеру вместо projection/node.
-- Это соответствует «используется существующая камера» (Разъяснения п.11).

CREATE OR REPLACE FUNCTION count_chamber_attachments(p_task_id UUID, p_chamber_id TEXT)
RETURNS INT AS $$
DECLARE
v_geom GEOMETRY;
v_count INT;
BEGIN
SELECT geom_utm INTO v_geom
FROM input_feature
WHERE task_id = p_task_id AND feature_id = p_chamber_id
AND object_type = 'heat_chamber'
LIMIT 1;

IF v_geom IS NULL THEN
RETURN 0;
END IF;

SELECT COUNT(*) INTO v_count
FROM input_feature hn
WHERE hn.task_id = p_task_id
AND hn.object_type = 'heat_network'
AND hn.geom_utm IS NOT NULL
AND (
    ST_DWithin(ST_StartPoint(hn.geom_utm), v_geom, 1.0)
OR ST_DWithin(ST_EndPoint(hn.geom_utm), v_geom, 1.0)
);

RETURN COALESCE(v_count, 0);
END;
$$ LANGUAGE plpgsql;

DROP FUNCTION IF EXISTS find_tie_in_candidates(UUID, GEOMETRY, DOUBLE PRECISION);

CREATE OR REPLACE FUNCTION find_tie_in_candidates(
    p_task_id     UUID,
    p_centroid    GEOMETRY,
    p_radius_m    DOUBLE PRECISION DEFAULT 300.0
)
RETURNS TABLE(
    candidate_type        TEXT,
    existing_object_id    TEXT,
geom                  GEOMETRY(POINT, 32637),
distance_m            DOUBLE PRECISION,
existing_diameter     INT,
required_diameter     INT
) AS $$
BEGIN
RETURN QUERY
-- 1. Существующие камеры (без изменений)
SELECT 'heat_chamber'::TEXT, hc.feature_id, hc.geom_utm,
ST_Distance(hc.geom_utm, p_centroid),
COALESCE((hc.properties->>'diameter')::int, 0), 0
FROM input_feature hc
WHERE hc.task_id = p_task_id
AND hc.object_type = 'heat_chamber'
AND hc.geom_utm IS NOT NULL
AND ST_DWithin(hc.geom_utm, p_centroid, p_radius_m)

UNION ALL

-- 2. Edge projections. Если рядом камера с capacity → вернуть её
SELECT
CASE WHEN nc.nearby_id IS NOT NULL THEN 'heat_chamber'::TEXT
ELSE 'edge_projection'::TEXT END,
COALESCE(nc.nearby_id, ge.ext_id),
CASE WHEN nc.nearby_id IS NOT NULL THEN nc.nearby_geom
ELSE ST_ClosestPoint(ge.geom, p_centroid)::GEOMETRY(POINT, 32637) END,
CASE WHEN nc.nearby_id IS NOT NULL THEN ST_Distance(nc.nearby_geom, p_centroid)
ELSE ST_Distance(ge.geom, p_centroid) END,
ge.diameter, 0
FROM graph_edge ge
LEFT JOIN LATERAL (
    SELECT hc.feature_id AS nearby_id, hc.geom_utm AS nearby_geom
FROM input_feature hc
WHERE hc.task_id = p_task_id
AND hc.object_type = 'heat_chamber'
AND hc.geom_utm IS NOT NULL
AND ST_DWithin(ST_ClosestPoint(ge.geom, p_centroid), hc.geom_utm, 10.0)
AND count_chamber_attachments(p_task_id, hc.feature_id) + 1 <= 4
ORDER BY ST_Distance(ST_ClosestPoint(ge.geom, p_centroid), hc.geom_utm)
LIMIT 1
) nc ON TRUE
WHERE ge.task_id = p_task_id
AND ST_DWithin(ge.geom, p_centroid, p_radius_m)

UNION ALL

-- 3. Graph nodes. То же правило
SELECT
CASE WHEN nc.nearby_id IS NOT NULL THEN 'heat_chamber'::TEXT
ELSE 'graph_node'::TEXT END,
COALESCE(nc.nearby_id, gn.ext_id),
CASE WHEN nc.nearby_id IS NOT NULL THEN nc.nearby_geom
ELSE gn.geom END,
CASE WHEN nc.nearby_id IS NOT NULL THEN ST_Distance(nc.nearby_geom, p_centroid)
ELSE ST_Distance(gn.geom, p_centroid) END,
0, 0
FROM graph_node gn
LEFT JOIN LATERAL (
    SELECT hc.feature_id AS nearby_id, hc.geom_utm AS nearby_geom
FROM input_feature hc
WHERE hc.task_id = p_task_id
AND hc.object_type = 'heat_chamber'
AND hc.geom_utm IS NOT NULL
AND ST_DWithin(gn.geom, hc.geom_utm, 10.0)
AND count_chamber_attachments(p_task_id, hc.feature_id) + 1 <= 4
ORDER BY ST_Distance(gn.geom, hc.geom_utm)
LIMIT 1
) nc ON TRUE
WHERE gn.task_id = p_task_id
AND gn.node_type = 'junction'
AND ST_DWithin(gn.geom, p_centroid, p_radius_m)

ORDER BY distance_m;
END;
$$ LANGUAGE plpgsql;

COMMENT ON FUNCTION find_tie_in_candidates(UUID, GEOMETRY, DOUBLE PRECISION) IS
'V44: правило 10 м (Разъяснения п.11) + лимит ≤4 примыканий (п.12) — projection/node внутри 10 м от heat_chamber с capacity заменяются на саму камеру.';