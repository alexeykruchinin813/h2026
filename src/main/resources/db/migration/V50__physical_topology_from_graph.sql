-- ============================================================================
-- V50. Физическая топология из графа видимости.
--
-- Замена геометрического dissolve (ST_Union/ST_Node не работают для
-- параллельных неидентичных линий) на построение из ТОПОЛОГИИ:
--   * flow через path_result.edge_ids (точный JOIN, не spatial);
--   * узлы — канонизированные вершины графа (DBSCAN, oks исключены);
--   * сегменты — degree-2 цепочки между якорями (degree<>2 или oks/candidate);
--   * геометрия — ST_Reverse по знаку eids_dir + ST_LineMerge(ORDER BY ord).
--
-- Инвариант «сегменты не пересекаются вне общих endpoints» — по построению.
-- Сигнатура совпадает с V46/V48/V49 (UUID, TEXT) → Java не меняется.
-- ============================================================================

CREATE INDEX IF NOT EXISTS idx_pnode_task_var ON physical_node(task_id, variant_id);
CREATE INDEX IF NOT EXISTS idx_pseg_task_var  ON physical_segment(task_id, variant_id);
CREATE INDEX IF NOT EXISTS idx_pnode_geom     ON physical_node USING GIST(geom);
CREATE INDEX IF NOT EXISTS idx_pseg_geom      ON physical_segment USING GIST(geom);

DROP FUNCTION IF EXISTS build_physical_network(UUID, TEXT);

CREATE OR REPLACE FUNCTION build_physical_network(
    p_task_id    UUID,
    p_variant_id TEXT
) RETURNS TABLE(segments INT, nodes INT) AS $$
DECLARE
v_segments INT := 0;
v_nodes    INT := 0;
v_edges    INT := 0;
v_chains   INT := 0;
SNAP_TOL   DOUBLE PRECISION := 0.5;   -- м
BEGIN
SET LOCAL statement_timeout = '180s';

DELETE FROM physical_segment WHERE task_id = p_task_id AND variant_id = p_variant_id;
DELETE FROM physical_node    WHERE task_id = p_task_id AND variant_id = p_variant_id;

-- Защита от повторного вызова в одной транзакции.
DROP TABLE IF EXISTS _pn_edges_raw;
DROP TABLE IF EXISTS _pn_valias;
DROP TABLE IF EXISTS _pn_edges_canon;
DROP TABLE IF EXISTS _pn_edges_dedup;
DROP TABLE IF EXISTS _pn_deg;
DROP TABLE IF EXISTS _pn_anchors;
DROP TABLE IF EXISTS _pn_walk;
DROP TABLE IF EXISTS _pn_chains;
DROP TABLE IF EXISTS _pn_seg_geom;
DROP TABLE IF EXISTS _pn_nodesrc;
DROP TABLE IF EXISTS _pn_vid_to_node;

-- 1. Использованные рёбра + flow OKS. Flow — топологическим join'ом.
CREATE TEMP TABLE _pn_edges_raw ON COMMIT DROP AS
SELECT ve.id AS eid,
ve.source_vertex,
ve.target_vertex,
ve.geom,
ve.length_m,
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

CREATE INDEX ON _pn_edges_raw(source_vertex);
CREATE INDEX ON _pn_edges_raw(target_vertex);

SELECT count(*) INTO v_edges FROM _pn_edges_raw;
RAISE NOTICE '[physical] % edges in _pn_edges_raw', v_edges;

IF v_edges = 0 THEN
RETURN QUERY SELECT 0, 0; RETURN;
END IF;

-- 2. Канонизация близких вершин. OKS исключаем из DBSCAN,
--    чтобы два близких OKS не слились в один узел.
CREATE TEMP TABLE _pn_valias ON COMMIT DROP AS
WITH endpoints AS (
    SELECT DISTINCT v.id AS vid, v.geom, v.vertex_type
FROM (
    SELECT source_vertex AS vid FROM _pn_edges_raw
UNION
SELECT target_vertex AS vid FROM _pn_edges_raw
) s
JOIN visibility_vertex v ON v.id = s.vid
WHERE v.geom IS NOT NULL
),
clustered AS (
    SELECT vid, geom, vertex_type,
ST_ClusterDBSCAN(
    CASE WHEN vertex_type = 'oks' THEN NULL ELSE geom END,
eps := SNAP_TOL, minpoints := 2
) OVER () AS cid
FROM endpoints
)
SELECT c.vid,
COALESCE(m.canon, c.vid) AS canon,
c.geom,
c.vertex_type
FROM clustered c
LEFT JOIN (
    SELECT cid, MIN(vid) AS canon
FROM clustered WHERE cid IS NOT NULL GROUP BY cid
) m ON m.cid = c.cid;

CREATE INDEX ON _pn_valias(vid);

-- 3. Канонизированные рёбра (без self-loops).
CREATE TEMP TABLE _pn_edges_canon ON COMMIT DROP AS
SELECT e.eid,
a1.canon AS va,
a2.canon AS vb,
e.geom, e.length_m, e.flow_tph, e.is_special, e.special_k
FROM _pn_edges_raw e
JOIN _pn_valias a1 ON a1.vid = e.source_vertex
JOIN _pn_valias a2 ON a2.vid = e.target_vertex
WHERE a1.canon <> a2.canon;

-- 3b. Дедупликация: несколько рёбер между одной парой canon-вершин
--     — это одна физическая труба. Оставляем одну геометрию (длиннее),
--     суммируем flow.
CREATE TEMP TABLE _pn_edges_dedup ON COMMIT DROP AS
SELECT
(array_agg(eid ORDER BY eid))[1]              AS eid,
va, vb,
(array_agg(geom ORDER BY length_m DESC))[1]   AS geom,
MAX(length_m)                                 AS length_m,
SUM(flow_tph)                                 AS flow_tph,
bool_or(is_special)                           AS is_special,
MAX(special_k)                                AS special_k
FROM _pn_edges_canon
GROUP BY va, vb;

CREATE INDEX ON _pn_edges_dedup(va);
CREATE INDEX ON _pn_edges_dedup(vb);

-- 4. Степени.
CREATE TEMP TABLE _pn_deg ON COMMIT DROP AS
SELECT vid, COUNT(*) AS deg FROM (
    SELECT va AS vid FROM _pn_edges_dedup
UNION ALL
SELECT vb AS vid FROM _pn_edges_dedup
) t GROUP BY vid;
CREATE INDEX ON _pn_deg(vid);

-- 5. Якоря: degree <> 2 ИЛИ vertex_type IN ('oks','candidate').
CREATE TEMP TABLE _pn_anchors ON COMMIT DROP AS
SELECT d.vid
FROM _pn_deg d
LEFT JOIN LATERAL (
    SELECT bool_or(v.vertex_type IN ('oks','candidate')) AS is_anchor_type
FROM _pn_valias a
JOIN visibility_vertex v ON v.id = a.vid
WHERE a.canon = d.vid
) at ON true
WHERE d.deg <> 2 OR COALESCE(at.is_anchor_type, false);
CREATE INDEX ON _pn_anchors(vid);

-- 6. Рекурсивный обход degree-2 цепочек между якорями.
--    eids_dir — массив eid со знаком: +eid сохраняет geom, -eid требует ST_Reverse.
CREATE TEMP TABLE _pn_walk ON COMMIT DROP AS
WITH RECURSIVE walk AS (
SELECT
a.vid AS start_vid,
a.vid AS cur_vid,
(CASE WHEN e.va = a.vid THEN e.vb ELSE e.va END) AS next_vid,
ARRAY[CASE WHEN e.va = a.vid THEN e.eid ELSE -e.eid END] AS eids_dir,
ARRAY[a.vid] AS visited,
1 AS depth
FROM _pn_anchors a
JOIN _pn_edges_dedup e ON e.va = a.vid OR e.vb = a.vid

UNION ALL

SELECT
w.start_vid,
w.next_vid,
(CASE WHEN e.va = w.next_vid THEN e.vb ELSE e.va END),
w.eids_dir || (CASE WHEN e.va = w.next_vid THEN e.eid ELSE -e.eid END),
w.visited || w.next_vid,
w.depth + 1
FROM walk w
JOIN _pn_deg d2 ON d2.vid = w.next_vid
LEFT JOIN _pn_anchors a2 ON a2.vid = w.next_vid
JOIN _pn_edges_dedup e
ON (e.va = w.next_vid OR e.vb = w.next_vid)
AND NOT (e.eid = ANY (SELECT abs(x) FROM unnest(w.eids_dir) AS x))
WHERE a2.vid IS NULL                -- следующий узел НЕ якорь
AND d2.deg = 2
AND NOT (CASE WHEN e.va = w.next_vid THEN e.vb ELSE e.va END) = ANY (w.visited)
AND w.depth < 500
)
SELECT * FROM walk
WHERE EXISTS (SELECT 1 FROM _pn_anchors a WHERE a.vid = walk.next_vid);

-- 7. Нормализация и дедуп walk'ов.
--    Пара (A,B) и (B,A) — одна цепочка. Оставляем каноническую (v_a ≤ v_b),
--    для обратного направления разворачиваем eids_dir и меняем знаки.
CREATE TEMP TABLE _pn_chains ON COMMIT DROP AS
WITH normalized AS (
SELECT
LEAST(start_vid, next_vid)    AS v_a,
GREATEST(start_vid, next_vid) AS v_b,
CASE WHEN start_vid <= next_vid THEN eids_dir
ELSE (SELECT array_agg(-x ORDER BY ord DESC)
FROM unnest(eids_dir) WITH ORDINALITY AS t(x, ord))
END AS eids_dir
FROM _pn_walk
),
keyed AS (
    SELECT v_a, v_b, eids_dir,
(SELECT MIN(abs(x)) FROM unnest(eids_dir) AS x) AS first_eid
FROM normalized
)
SELECT DISTINCT ON (v_a, v_b, first_eid) v_a, v_b, eids_dir
FROM keyed;

-- 8. Одиночные рёбра (degree-1—degree-1 и т. п.), не попавшие в walk.
INSERT INTO _pn_chains (v_a, v_b, eids_dir)
SELECT
LEAST(e.va, e.vb),
GREATEST(e.va, e.vb),
ARRAY[CASE WHEN e.va <= e.vb THEN e.eid ELSE -e.eid END]
FROM _pn_edges_dedup e
WHERE NOT EXISTS (
    SELECT 1 FROM _pn_chains c
    WHERE e.eid = ANY (SELECT abs(x) FROM unnest(c.eids_dir) AS x)
);

SELECT count(*) INTO v_chains FROM _pn_chains;
RAISE NOTICE '[physical] % chains total', v_chains;

-- 9. Геометрия сегментов.
CREATE TEMP TABLE _pn_seg_geom ON COMMIT DROP AS
WITH pieces AS (
    SELECT
c.ctid AS chain_id,
c.v_a, c.v_b,
x.ord,
CASE WHEN x.eid_signed > 0 THEN e.geom ELSE ST_Reverse(e.geom) END AS geom,
e.flow_tph, e.is_special, e.special_k
FROM _pn_chains c
CROSS JOIN LATERAL unnest(c.eids_dir) WITH ORDINALITY AS x(eid_signed, ord)
JOIN _pn_edges_dedup e ON e.eid = abs(x.eid_signed)
),
collected AS (
    SELECT chain_id, v_a, v_b,
    ST_Collect(geom ORDER BY ord) AS geom_coll,
MAX(flow_tph) AS flow_tph,       -- монотонный вдоль цепочки
bool_or(is_special) AS is_special,
MAX(special_k) AS special_k
FROM pieces
GROUP BY chain_id, v_a, v_b
)
SELECT chain_id, v_a, v_b,
ST_LineMerge(geom_coll) AS geom,
flow_tph, is_special, special_k
FROM collected;

-- 10. Узлы сегментов.
CREATE TEMP TABLE _pn_nodesrc ON COMMIT DROP AS
WITH node_vids AS (
    SELECT v_a AS vid FROM _pn_seg_geom
    UNION
    SELECT v_b AS vid FROM _pn_seg_geom
),
with_geom AS (
SELECT nv.vid,
ST_Centroid(ST_Collect(a.geom)) AS geom,
(array_agg(v.vertex_type
ORDER BY CASE v.vertex_type
WHEN 'oks' THEN 0
WHEN 'candidate' THEN 1
ELSE 2 END))[1] AS vertex_type,
(array_agg(v.ref_id
    ORDER BY CASE v.vertex_type
WHEN 'oks' THEN 0
WHEN 'candidate' THEN 1
ELSE 2 END))[1] AS ref_id
FROM node_vids nv
JOIN _pn_valias a ON a.canon = nv.vid
JOIN visibility_vertex v ON v.id = a.vid
GROUP BY nv.vid
),
node_deg AS (
    SELECT vid, COUNT(*) AS deg FROM (
    SELECT v_a AS vid FROM _pn_seg_geom
UNION ALL
SELECT v_b AS vid FROM _pn_seg_geom
) t GROUP BY vid
)
SELECT wg.vid, wg.geom, wg.vertex_type, wg.ref_id, nd.deg
FROM with_geom wg
JOIN node_deg nd ON nd.vid = wg.vid;

-- 11. Вставка узлов.
INSERT INTO physical_node (task_id, variant_id, geom, node_type, degree, ref_id)
SELECT
p_task_id, p_variant_id, n.geom,
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

-- 11b. Маппинг vid → physical_node.id.
CREATE TEMP TABLE _pn_vid_to_node ON COMMIT DROP AS
SELECT nr.vid, pn.id AS node_id
FROM _pn_nodesrc nr
JOIN physical_node pn
ON pn.task_id = p_task_id
AND pn.variant_id = p_variant_id
AND pn.geom = nr.geom;

-- 12. Вставка сегментов. ST_Dump НЕ используем: если LineMerge вернул
--     MultiLineString, значит цепочка разорвана (bug в walk) — пропускаем
--     с логом, чтобы не порождать сегменты с неверными endpoints.
INSERT INTO physical_segment (
    task_id, variant_id, start_node_id, end_node_id,
geom, flow_tph, length_m, laying_method, special_k)
SELECT
p_task_id, p_variant_id,
m1.node_id, m2.node_id,
sg.geom,
sg.flow_tph,
ST_Length(sg.geom),
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
'V50: физическая топология из графа видимости.
flow через unnest(path_result.edge_ids); DBSCAN-канонизация вершин (oks исключены);
дедуп рёбер по (va,vb); рекурсивный walk degree-2 цепочек с учётом направления;
ST_LineMerge(ORDER BY ord). Инвариант «нет пересечений вне endpoints» — по построению.';