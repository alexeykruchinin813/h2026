-- ============================================================================
-- V47. Фикс V46: устранение вложенных агрегатов в build_physical_network.
--
-- Проблема: PostgreSQL отклоняет конструкцию ST_Node(ST_Collect(geom)) в
-- одном SELECT — «aggregate function calls cannot be nested». То же с
-- ST_LineMerge(ST_Collect(geom)) и вторым ST_Node.
--
-- Решение: собираем коллекцию во внутреннем подзапросе, применяем
-- пространственную функцию во внешнем. ST_Union(geometry) за один шаг
-- делает dissolve: устраняет коллинеарные наложения и разрезает
-- пересечения крест-накрест в общие узлы.
--
-- Схема (physical_node, physical_segment) из V46 не меняется — только
-- тело функции. DROP + CREATE — единственный способ заменить PL/pgSQL
-- функцию без пересоздания зависимых объектов (их нет).
-- ============================================================================

DROP FUNCTION IF EXISTS build_physical_network(UUID, TEXT);

CREATE OR REPLACE FUNCTION build_physical_network(
    p_task_id UUID,
    p_variant_id TEXT
) RETURNS TABLE(segments INT, nodes INT) AS $$
DECLARE
v_segments INT := 0;
v_nodes    INT := 0;
BEGIN
SET LOCAL statement_timeout = '180s';

DELETE FROM physical_segment WHERE task_id = p_task_id AND variant_id = p_variant_id;
DELETE FROM physical_node    WHERE task_id = p_task_id AND variant_id = p_variant_id;

-- 1. Flow OKS по visibility_edge.
CREATE TEMP TABLE _pn_eflow ON COMMIT DROP AS
SELECT ve.id AS eid,
ve.geom,
COALESCE(ve.is_special, false) AS is_special,
COALESCE(ve.special_k, 1.0)    AS special_k,
SUM((f.properties->>'flow_tph')::double precision) AS flow_tph
FROM path_result pr
CROSS JOIN LATERAL unnest(pr.edge_ids) AS eid_ref
JOIN visibility_edge   ve ON ve.id = eid_ref
JOIN visibility_vertex vv ON vv.id = pr.oks_vertex_id
JOIN input_feature     f
ON f.task_id = vv.task_id
AND f.feature_id::text = vv.ref_id
AND f.object_type = 'oks_connection_point'
WHERE pr.task_id = p_task_id AND pr.variant_id = p_variant_id
GROUP BY ve.id, ve.geom, ve.is_special, ve.special_k;

CREATE INDEX ON _pn_eflow USING GIST(geom);

IF (SELECT count(*) FROM _pn_eflow) = 0 THEN
RETURN QUERY SELECT 0, 0; RETURN;
END IF;

-- 2. ST_Union — dissolve набора линий.
--    ST_Collect выполняется во внутреннем подзапросе,
--    ST_Union/ST_Dump — во внешнем. Вложенный агрегат запрещён PostgreSQL.
CREATE TEMP TABLE _pn_unioned ON COMMIT DROP AS
SELECT (ST_Dump(ST_Union(geom))).geom AS geom
FROM (SELECT ST_Collect(g.geom) AS geom FROM _pn_eflow g) t;

-- 3. Атрибуция flow / спец-параметров по серединам кусков.
CREATE TEMP TABLE _pn_attr ON COMMIT DROP AS
SELECT n.geom,
COALESCE(SUM(ef.flow_tph), 0)            AS flow_tph,
COALESCE(bool_or(ef.is_special), false)  AS is_special,
COALESCE(MAX(ef.special_k), 1.0)         AS special_k
FROM _pn_unioned n
LEFT JOIN _pn_eflow ef
ON ef.geom && n.geom
AND ST_DWithin(ef.geom, ST_LineInterpolatePoint(n.geom, 0.5), 0.05)
WHERE GeometryType(n.geom) = 'LINESTRING'
AND ST_Length(n.geom) > 0.05
GROUP BY n.geom;

-- 4. Узлы: endpoints сегментов + классификация.
CREATE TEMP TABLE _pn_pts ON COMMIT DROP AS
SELECT DISTINCT ST_SnapToGrid(ST_StartPoint(geom), 0.01) AS pt FROM _pn_attr
UNION
SELECT DISTINCT ST_SnapToGrid(ST_EndPoint(geom),   0.01) FROM _pn_attr;

CREATE TEMP TABLE _pn_pts_deg ON COMMIT DROP AS
SELECT p.pt,
(SELECT count(*)::int FROM _pn_attr f
WHERE ST_DWithin(ST_StartPoint(f.geom), p.pt, 0.5)
OR ST_DWithin(ST_EndPoint(f.geom),   p.pt, 0.5)) AS degree
FROM _pn_pts p;

INSERT INTO physical_node(task_id, variant_id, geom, node_type, degree, ref_id)
SELECT p_task_id, p_variant_id, d.pt,
CASE
WHEN vv_oks.id IS NOT NULL THEN 'oks'
WHEN vv_cand.id IS NOT NULL AND tic.existing_object_type = 'heat_chamber'
THEN 'existing_tie_in'
WHEN vv_cand.id IS NOT NULL THEN 'new_terminal_chamber'
WHEN d.degree >= 3          THEN 'branch_chamber'
ELSE 'technical_node'
END,
d.degree,
COALESCE(vv_oks.ref_id, vv_cand.ref_id)
FROM _pn_pts_deg d
LEFT JOIN LATERAL (
    SELECT vv.id, vv.ref_id FROM visibility_vertex vv
WHERE vv.task_id = p_task_id AND vv.vertex_type = 'oks'
AND ST_DWithin(vv.geom, d.pt, 0.5)
ORDER BY ST_Distance(vv.geom, d.pt) LIMIT 1
) vv_oks ON true
LEFT JOIN LATERAL (
    SELECT vv.id, vv.ref_id FROM visibility_vertex vv
WHERE vv.task_id = p_task_id AND vv.vertex_type = 'candidate'
AND ST_DWithin(vv.geom, d.pt, 0.5)
ORDER BY ST_Distance(vv.geom, d.pt) LIMIT 1
) vv_cand ON true
LEFT JOIN tie_in_candidate tic
ON tic.task_id = p_task_id AND tic.existing_object_id = vv_cand.ref_id;

-- 5. Сегменты (без ДУ и стоимости — их ставит PhysicalNetworkService).
INSERT INTO physical_segment(
    task_id, variant_id, start_node_id, end_node_id,
geom, flow_tph, length_m, laying_method, special_k)
SELECT p_task_id, p_variant_id, ns.id, ne.id,
f.geom, f.flow_tph, ST_Length(f.geom),
CASE WHEN f.is_special THEN 'special' ELSE 'base' END,
f.special_k
FROM _pn_attr f
JOIN physical_node ns
ON ns.task_id = p_task_id AND ns.variant_id = p_variant_id
AND ST_DWithin(ns.geom, ST_SnapToGrid(ST_StartPoint(f.geom), 0.01), 0.5)
JOIN physical_node ne
ON ne.task_id = p_task_id AND ne.variant_id = p_variant_id
AND ST_DWithin(ne.geom, ST_SnapToGrid(ST_EndPoint(f.geom), 0.01), 0.5);

SELECT count(*) INTO v_segments FROM physical_segment
WHERE task_id = p_task_id AND variant_id = p_variant_id;
SELECT count(*) INTO v_nodes FROM physical_node
WHERE task_id = p_task_id AND variant_id = p_variant_id;

RETURN QUERY SELECT v_segments, v_nodes;
END;
$$ LANGUAGE plpgsql;

COMMENT ON FUNCTION build_physical_network(UUID, TEXT) IS
'V47: топология новой тепловой сети (ТЗ 2.1, 2.3).
ST_Union dissolve''ит коллинеарные наложения и режет пересечения крест-накрест.
Вложенные агрегаты ST_Node(ST_Collect(...)) устранены через подзапрос.
Диаметр и стоимость заполняет PhysicalNetworkService через DiameterPicker.';