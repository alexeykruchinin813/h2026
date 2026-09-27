-- ============================================================================
-- V51. Физическая топология из графа видимости с планаризацией сырых рёбер.
--
-- Диагноз V50: bad_crossings = 15. Граф видимости непланарен (V40/V41
-- строят мостовые рёбра между OKS-полигонами без взаимной проверки
-- пересечений, precise OKS buffer check делегирован Java, но Java
-- проверяет только crossing_forbidden-зоны, не пересечения рёбер друг
-- с другом). V50 строит цепочки из visibility_edge как из атомов и
-- наследует эти пересечения.
--
-- Решение V51: ST_Node по СЫРЫМ использованным рёбрам ДО построения
-- цепочек. Куски становятся атомами графа, walk строит цепочки уже по
-- плоскому графу. Инвариант «нет пересечений вне endpoints» — по построению.
--
-- Сигнатура функции совпадает с V46/V48/V49/V50 (UUID, TEXT) → Java не
-- меняется.
-- ============================================================================

DROP FUNCTION IF EXISTS build_physical_network(UUID, TEXT);

CREATE OR REPLACE FUNCTION build_physical_network(
    p_task_id    UUID,
    p_variant_id TEXT
) RETURNS TABLE(segments INT, nodes INT) AS $$
DECLARE
v_segments INT := 0;
v_nodes    INT := 0;
v_raw      INT := 0;
v_noded    INT := 0;
v_pieces   INT := 0;
v_chains   INT := 0;
v_multi    INT := 0;
SNAP_TOL   DOUBLE PRECISION := 0.5;
BEGIN
SET LOCAL statement_timeout = '180s';

DELETE FROM physical_segment WHERE task_id = p_task_id AND variant_id = p_variant_id;
DELETE FROM physical_node    WHERE task_id = p_task_id AND variant_id = p_variant_id;

DROP TABLE IF EXISTS _pn_edges_raw;
DROP TABLE IF EXISTS _pn_noded;
DROP TABLE IF EXISTS _pn_pieces;
DROP TABLE IF EXISTS _pn_epoints;
DROP TABLE IF EXISTS _pn_edges_canon;
DROP TABLE IF EXISTS _pn_deg;
DROP TABLE IF EXISTS _pn_anchors;
DROP TABLE IF EXISTS _pn_walk;
DROP TABLE IF EXISTS _pn_chains;
DROP TABLE IF EXISTS _pn_seg_geom;
DROP TABLE IF EXISTS _pn_nodesrc;
DROP TABLE IF EXISTS _pn_vid_to_node;

-- 1. Сырые рёбра + flow через edge_ids (точный topological join).
CREATE TEMP TABLE _pn_edges_raw ON COMMIT DROP AS
SELECT ve.id AS eid,
ve.source_vertex, ve.target_vertex,
ve.geom, ve.length_m,
COALESCE(ve.is_special, false) AS is_special,
COALESCE(ve.special_k, 1.0)    AS special_k,
COALESCE(SUM((f.properties->>'flow_tph')::double precision), 0) AS flow_tph
FROM path_result pr
CROSS JOIN LATERAL unnest(pr.edge_ids) AS eid_ref
JOIN visibility_edge   ve ON ve.id = eid_ref
JOIN visibility_vertex vv ON vv.id = pr.oks_vertex_id
JOIN input_feature     f
ON f.task_id = vv.task_id
AND f.feature_id::text = vv.ref_id
AND f.object_type = 'oks_connection_point'
WHERE pr.task_id = p_task_id AND pr.variant_id = p_variant_id
GROUP BY ve.id, ve.source_vertex, ve.target_vertex, ve.geom, ve.length_m,
ve.is_special, ve.special_k;

SELECT count(*) INTO v_raw FROM _pn_edges_raw;
RAISE NOTICE '[physical] % raw edges', v_raw;

IF v_raw = 0 THEN
RETURN QUERY SELECT 0, 0; RETURN;
END IF;

-- 2. Планаризация. ST_Node разрезает пересечения крест-накрест.
CREATE TEMP TABLE _pn_noded ON COMMIT DROP AS
SELECT (ST_Dump(ST_Node(ST_Collect(geom)))).geom AS geom
FROM _pn_edges_raw
WHERE GeometryType(geom) = 'LINESTRING' AND ST_Length(geom) > 0.05;

SELECT count(*) INTO v_noded FROM _pn_noded;
RAISE NOTICE '[physical] % noded pieces', v_noded;

-- 3. Атрибуция кусков + дедуп + synthetic pid.
CREATE TEMP TABLE _pn_pieces ON COMMIT DROP AS
SELECT ROW_NUMBER() OVER () AS pid,
sub.geom, sub.flow_tph, sub.is_special, sub.special_k
FROM (
    SELECT DISTINCT ON (ST_AsBinary(ST_Normalize(n.geom)))
ST_SnapToGrid(n.geom, 0.01) AS geom,
COALESCE((SELECT SUM(ef.flow_tph) FROM _pn_edges_raw ef
WHERE ef.geom && n.geom
AND ST_DWithin(ef.geom,
ST_LineInterpolatePoint(n.geom, 0.5),
0.05)), 0) AS flow_tph,
COALESCE((SELECT bool_or(ef.is_special) FROM _pn_edges_raw ef
WHERE ef.geom && n.geom
AND ST_DWithin(ef.geom,
    ST_LineInterpolatePoint(n.geom, 0.5),
0.05)), false) AS is_special,
COALESCE((SELECT MAX(ef.special_k) FROM _pn_edges_raw ef
WHERE ef.geom && n.geom
AND ST_DWithin(ef.geom,
ST_LineInterpolatePoint(n.geom, 0.5),
0.05)), 1.0) AS special_k
FROM _pn_noded n
WHERE GeometryType(n.geom) = 'LINESTRING'
AND ST_Length(n.geom) > 0.05
) sub;

CREATE INDEX ON _pn_pieces USING GIST(geom);

SELECT count(*) INTO v_pieces FROM _pn_pieces;
RAISE NOTICE '[physical] % unique pieces', v_pieces;

-- 4. Канонизация близких endpoint'ов через DBSCAN (OKS исключены).
CREATE TEMP TABLE _pn_epoints ON COMMIT DROP AS
WITH endpoints AS (
    SELECT DISTINCT ST_SnapToGrid(g, 0.01) AS pt
FROM (
    SELECT ST_StartPoint(geom) AS g FROM _pn_pieces
UNION
SELECT ST_EndPoint(geom)   AS g FROM _pn_pieces
) t
),
clustered AS (
    SELECT pt,
    ST_ClusterDBSCAN(
    CASE WHEN EXISTS (
SELECT 1 FROM visibility_vertex vv
WHERE vv.task_id = p_task_id
AND vv.vertex_type = 'oks'
AND ST_DWithin(vv.geom, pt, 0.05)
) THEN NULL ELSE pt END,
eps := SNAP_TOL, minpoints := 2
) OVER () AS cid
FROM endpoints
),
with_ids AS (
    SELECT pt, cid, ROW_NUMBER() OVER (ORDER BY ST_AsBinary(pt)) AS pid
FROM clustered
)
SELECT wi.pid, wi.pt AS geom, COALESCE(m.canon, wi.pid) AS canon
FROM with_ids wi
LEFT JOIN (
    SELECT cid, MIN(pid) AS canon
FROM with_ids WHERE cid IS NOT NULL GROUP BY cid
) m ON m.cid = wi.cid;

CREATE INDEX ON _pn_epoints(geom);

-- 5. Рёбра в канонических вершинах + дедуп параллельных.
CREATE TEMP TABLE _pn_edges_canon ON COMMIT DROP AS
WITH raw AS (
    SELECT p.pid, p.geom, p.flow_tph, p.is_special, p.special_k,
ea.canon AS va, eb.canon AS vb,
ST_Length(p.geom) AS length_m
FROM _pn_pieces p
JOIN _pn_epoints ea ON ea.geom = ST_SnapToGrid(ST_StartPoint(p.geom), 0.01)
JOIN _pn_epoints eb ON eb.geom = ST_SnapToGrid(ST_EndPoint(p.geom),   0.01)
WHERE ea.canon <> eb.canon
)
SELECT (array_agg(pid ORDER BY pid))[1]              AS pid,
(array_agg(geom ORDER BY length_m DESC))[1]   AS geom,
va, vb,
MAX(length_m)                                 AS length_m,
SUM(flow_tph)                                 AS flow_tph,
bool_or(is_special)                           AS is_special,
MAX(special_k)                                AS special_k
FROM raw
GROUP BY va, vb;

CREATE INDEX ON _pn_edges_canon(va);
CREATE INDEX ON _pn_edges_canon(vb);

-- 6. Степени.
CREATE TEMP TABLE _pn_deg ON COMMIT DROP AS
SELECT vid, COUNT(*) AS deg FROM (
    SELECT va AS vid FROM _pn_edges_canon
UNION ALL
SELECT vb AS vid FROM _pn_edges_canon
) t GROUP BY vid;
CREATE INDEX ON _pn_deg(vid);

-- 7. Якоря: degree <> 2 ИЛИ точка — OKS/candidate.
CREATE TEMP TABLE _pn_anchors ON COMMIT DROP AS
SELECT d.vid
FROM _pn_deg d
LEFT JOIN LATERAL (
    SELECT bool_or(vv.vertex_type IN ('oks','candidate')) AS is_anchor
FROM _pn_epoints a
JOIN visibility_vertex vv
ON vv.task_id = p_task_id
AND ST_DWithin(vv.geom, a.geom, 0.1)
WHERE a.canon = d.vid
) at ON true
WHERE d.deg <> 2 OR COALESCE(at.is_anchor, false);
CREATE INDEX ON _pn_anchors(vid);

-- 8. Рекурсивный обход degree-2 цепочек между якорями.
CREATE TEMP TABLE _pn_walk ON COMMIT DROP AS
WITH RECURSIVE walk AS (
    SELECT a.vid AS start_vid,
    a.vid AS cur_vid,
(CASE WHEN e.va = a.vid THEN e.vb ELSE e.va END) AS next_vid,
ARRAY[e.pid] AS pids,
ARRAY[CASE WHEN e.va = a.vid THEN e.geom
ELSE ST_Reverse(e.geom) END] AS geoms,
ARRAY[a.vid] AS visited,
1 AS depth
FROM _pn_anchors a
JOIN _pn_edges_canon e ON e.va = a.vid OR e.vb = a.vid

UNION ALL

SELECT w.start_vid,
w.next_vid,
(CASE WHEN e.va = w.next_vid THEN e.vb ELSE e.va END),
w.pids || e.pid,
w.geoms || (CASE WHEN e.va = w.next_vid THEN e.geom
ELSE ST_Reverse(e.geom) END),
w.visited || w.next_vid,
w.depth + 1
FROM walk w
JOIN _pn_deg d2 ON d2.vid = w.next_vid
LEFT JOIN _pn_anchors a2 ON a2.vid = w.next_vid
JOIN _pn_edges_canon e
ON (e.va = w.next_vid OR e.vb = w.next_vid)
AND NOT (e.pid = ANY (w.pids))
WHERE a2.vid IS NULL
AND d2.deg = 2
AND NOT (CASE WHEN e.va = w.next_vid THEN e.vb ELSE e.va END) = ANY (w.visited)
AND w.depth < 500
)
SELECT * FROM walk
WHERE EXISTS (SELECT 1 FROM _pn_anchors a WHERE a.vid = walk.next_vid);

-- 9. Нормализация и дедуп walk'ов.
CREATE TEMP TABLE _pn_chains ON COMMIT DROP AS
WITH normalized AS (
    SELECT
    LEAST(start_vid, next_vid)    AS v_a,
GREATEST(start_vid, next_vid) AS v_b,
CASE WHEN start_vid <= next_vid THEN pids
ELSE (SELECT array_agg(p ORDER BY ord DESC)
FROM unnest(pids) WITH ORDINALITY AS t(p, ord))
END AS pids,
CASE WHEN start_vid <= next_vid THEN geoms
ELSE (SELECT array_agg(g ORDER BY ord DESC)
FROM unnest(geoms) WITH ORDINALITY AS t(g, ord))
END AS geoms
FROM _pn_walk
),
keyed AS (
SELECT v_a, v_b, pids, geoms,
(SELECT MIN(p) FROM unnest(pids) AS p) AS key
FROM normalized
)
SELECT DISTINCT ON (v_a, v_b, key) v_a, v_b, pids, geoms
FROM keyed;

-- 10. Одиночные рёбра, не попавшие в walk.
INSERT INTO _pn_chains (v_a, v_b, pids, geoms)
SELECT LEAST(e.va, e.vb), GREATEST(e.va, e.vb),
ARRAY[e.pid],
ARRAY[CASE WHEN e.va <= e.vb THEN e.geom ELSE ST_Reverse(e.geom) END]
FROM _pn_edges_canon e
WHERE NOT EXISTS (
    SELECT 1 FROM _pn_chains c WHERE e.pid = ANY (c.pids)
);

SELECT count(*) INTO v_chains FROM _pn_chains;
RAISE NOTICE '[physical] % chains total', v_chains;

-- 11. Геометрия сегментов.
CREATE TEMP TABLE _pn_seg_geom ON COMMIT DROP AS
SELECT c.ctid AS chain_id, c.v_a, c.v_b,
ST_LineMerge(ST_Collect(g ORDER BY ord)) AS geom,
(SELECT MAX(ec.flow_tph)     FROM _pn_edges_canon ec WHERE ec.pid = ANY (c.pids)) AS flow_tph,
(SELECT bool_or(ec.is_special) FROM _pn_edges_canon ec WHERE ec.pid = ANY (c.pids)) AS is_special,
(SELECT MAX(ec.special_k)    FROM _pn_edges_canon ec WHERE ec.pid = ANY (c.pids)) AS special_k
FROM _pn_chains c
CROSS JOIN LATERAL unnest(c.geoms) WITH ORDINALITY AS t(g, ord)
GROUP BY c.ctid, c.v_a, c.v_b, c.pids;

SELECT count(*) INTO v_multi FROM _pn_seg_geom
WHERE GeometryType(geom) = 'MULTILINESTRING';
IF v_multi > 0 THEN
RAISE NOTICE '[physical] WARN: % chains returned MULTILINESTRING (skipped)', v_multi;
END IF;

-- 12. Узлы.
CREATE TEMP TABLE _pn_nodesrc ON COMMIT DROP AS
WITH node_vids AS (
SELECT v_a AS vid FROM _pn_seg_geom
UNION
SELECT v_b AS vid FROM _pn_seg_geom
),
with_geom AS (
    SELECT nv.vid, ST_Centroid(ST_Collect(ep.geom)) AS geom
FROM node_vids nv
JOIN _pn_epoints ep ON ep.canon = nv.vid
GROUP BY nv.vid
),
with_type AS (
    SELECT wg.vid, wg.geom,
(array_agg(vv.vertex_type
ORDER BY CASE vv.vertex_type
WHEN 'oks' THEN 0
WHEN 'candidate' THEN 1
ELSE 2 END))[1] AS vertex_type,
(array_agg(vv.ref_id
    ORDER BY CASE vv.vertex_type
WHEN 'oks' THEN 0
WHEN 'candidate' THEN 1
ELSE 2 END))[1] AS ref_id
FROM with_geom wg
LEFT JOIN visibility_vertex vv
ON vv.task_id = p_task_id
AND ST_DWithin(vv.geom, wg.geom, 0.5)
GROUP BY wg.vid, wg.geom
),
node_deg AS (
    SELECT vid, COUNT(*) AS deg FROM (
    SELECT v_a AS vid FROM _pn_seg_geom
UNION ALL
SELECT v_b AS vid FROM _pn_seg_geom
) t GROUP BY vid
)
SELECT wt.vid, wt.geom, wt.vertex_type, wt.ref_id, nd.deg
FROM with_type wt
JOIN node_deg nd ON nd.vid = wt.vid;

INSERT INTO physical_node (task_id, variant_id, geom, node_type, degree, ref_id)
SELECT p_task_id, p_variant_id, n.geom,
CASE
WHEN n.vertex_type = 'oks'       THEN 'oks'
WHEN n.vertex_type = 'candidate' THEN 'existing_tie_in'
WHEN n.deg >= 3                  THEN 'branch_chamber'
WHEN n.deg = 1                   THEN 'new_terminal_chamber'
ELSE 'technical_node'
END,
n.deg::int,
n.ref_id
FROM _pn_nodesrc n;

CREATE TEMP TABLE _pn_vid_to_node ON COMMIT DROP AS
SELECT nr.vid, pn.id AS node_id
FROM _pn_nodesrc nr
JOIN physical_node pn
ON pn.task_id = p_task_id
AND pn.variant_id = p_variant_id
AND pn.geom = nr.geom;

-- 13. Сегменты.
INSERT INTO physical_segment (
    task_id, variant_id, start_node_id, end_node_id,
geom, flow_tph, length_m, laying_method, special_k)
SELECT p_task_id, p_variant_id, m1.node_id, m2.node_id,
sg.geom, sg.flow_tph, ST_Length(sg.geom),
CASE WHEN sg.is_special THEN 'special' ELSE 'base' END,
sg.special_k
FROM _pn_seg_geom sg
JOIN _pn_vid_to_node m1 ON m1.vid = sg.v_a
JOIN _pn_vid_to_node m2 ON m2.vid = sg.v_b
WHERE sg.geom IS NOT NULL
AND NOT ST_IsEmpty(sg.geom)
AND GeometryType(sg.geom) = 'LINESTRING'
AND ST_Length(sg.geom) > 0.05;

SELECT count(*) INTO v_segments FROM physical_segment
WHERE task_id = p_task_id AND variant_id = p_variant_id;
SELECT count(*) INTO v_nodes FROM physical_node
WHERE task_id = p_task_id AND variant_id = p_variant_id;

RAISE NOTICE '[physical] final: % segments, % nodes', v_segments, v_nodes;

RETURN QUERY SELECT v_segments, v_nodes;
END;
$$ LANGUAGE plpgsql;

COMMENT ON FUNCTION build_physical_network(UUID, TEXT) IS
'V51: планаризация сырых рёбер графа видимости (ST_Node) + построение
физической топологии. Куски ST_Node — атомы графа; walk degree-2 цепочек
между якорями; ST_LineMerge(ORDER BY ord). Инвариант non-crossing — по
построению. Сигнатура совпадает с V46/V48/V49/V50 → Java не меняется.';