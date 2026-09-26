-- ============================================================================
-- V49. Фикс V48: микроразрывы в координатах мешают ST_Union делать dissolve.
--
-- Диагноз: V48 применил ST_Union как агрегат по _pn_eflow, но число
-- пересечений почти не изменилось (389 → 387). Значит dissolve не сработал:
-- GEOS считает линии разными, если координаты различаются на микроуровне
-- (например, рёбра одного физического участка пришли из разных escape-цепочек
-- и не совпадают точно).
--
-- Решение V49:
--   1. ST_SnapToGrid(geom, 0.01) — снэп к сетке 1 см убирает микроразрывы.
--   2. ST_Union — dissolve наложений и разрезание пересечений крест-накрест.
--   3. ST_Node — страховка: разрезает всё, что осталось после dissolve.
--   4. ST_Dump — распаковка MultiLineString.
--
-- Плюс RAISE NOTICE с числом сегментов после каждого шага — для диагностики.
-- ============================================================================

DROP FUNCTION IF EXISTS build_physical_network(UUID, TEXT);

CREATE OR REPLACE FUNCTION build_physical_network(
    p_task_id UUID,
    p_variant_id TEXT
) RETURNS TABLE(segments INT, nodes INT) AS $$
DECLARE
v_segments INT := 0;
v_nodes    INT := 0;
v_before   INT := 0;
v_snapped  INT := 0;
v_dissolv  INT := 0;
v_final    INT := 0;
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

SELECT count(*) INTO v_before FROM _pn_eflow;
RAISE NOTICE '[physical] % edges in _pn_eflow', v_before;

IF v_before = 0 THEN
RETURN QUERY SELECT 0, 0; RETURN;
END IF;

-- 2. Снэп к сетке 1 см: убираем микроразрывы между почти-совпадающими
--    геометриями, иначе ST_Union не dissolve'ит их.
CREATE TEMP TABLE _pn_snapped ON COMMIT DROP AS
SELECT ST_SnapToGrid(geom, 0.01) AS geom,
is_special, special_k, flow_tph
FROM _pn_eflow
WHERE GeometryType(geom) = 'LINESTRING'
AND ST_Length(geom) > 0.05;

SELECT count(*) INTO v_snapped FROM _pn_snapped;
RAISE NOTICE '[physical] % edges after ST_SnapToGrid', v_snapped;

-- 3. ST_Union — dissolve наложений + разрезание пересечений крест-накрест.
CREATE TEMP TABLE _pn_dissolved ON COMMIT DROP AS
SELECT ST_Union(geom) AS geom FROM _pn_snapped;

-- Диагностика: сколько LineString в результате dissolve.
SELECT count(*) INTO v_dissolv FROM (
    SELECT (ST_Dump(geom)).geom AS g FROM _pn_dissolved
) t WHERE GeometryType(g) = 'LINESTRING';
RAISE NOTICE '[physical] % LineString after ST_Union (before ST_Node)', v_dissolv;

-- 4. ST_Node — разрезает оставшиеся пересечения (страховка).
CREATE TEMP TABLE _pn_noded ON COMMIT DROP AS
SELECT (ST_Dump(ST_Node(geom))).geom AS geom
FROM _pn_dissolved
WHERE geom IS NOT NULL AND NOT ST_IsEmpty(geom);

CREATE TEMP TABLE _pn_unioned ON COMMIT DROP AS
SELECT geom FROM _pn_noded
WHERE GeometryType(geom) = 'LINESTRING' AND ST_Length(geom) > 0.05;

SELECT count(*) INTO v_final FROM _pn_unioned;
RAISE NOTICE '[physical] % LineString after ST_Node', v_final;

-- 5. Атрибуция flow / спец-параметров по серединам кусков.
CREATE TEMP TABLE _pn_attr ON COMMIT DROP AS
SELECT n.geom,
COALESCE(SUM(ef.flow_tph), 0)            AS flow_tph,
COALESCE(bool_or(ef.is_special), false)  AS is_special,
COALESCE(MAX(ef.special_k), 1.0)         AS special_k
FROM _pn_unioned n
LEFT JOIN _pn_snapped ef
ON ef.geom && n.geom
AND ST_DWithin(ef.geom, ST_LineInterpolatePoint(n.geom, 0.5), 0.05)
GROUP BY n.geom;

-- 6. Узлы.
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

-- 7. Сегменты.
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

RAISE NOTICE '[physical] final: % segments, % nodes', v_segments, v_nodes;

RETURN QUERY SELECT v_segments, v_nodes;
END;
$$ LANGUAGE plpgsql;

COMMENT ON FUNCTION build_physical_network(UUID, TEXT) IS
'V49: топология новой тепловой сети (ТЗ 2.1, 2.3).
ST_SnapToGrid → ST_Union → ST_Node → ST_Dump. RAISE NOTICE для диагностики.
Диаметр и стоимость заполняет PhysicalNetworkService через DiameterPicker.';