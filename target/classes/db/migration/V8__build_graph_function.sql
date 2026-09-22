CREATE OR REPLACE FUNCTION build_graph(p_task_id UUID)
RETURNS TABLE(inserted_nodes BIGINT, inserted_edges BIGINT, source_node_id BIGINT) AS $$
DECLARE
v_nodes  BIGINT;
v_edges  BIGINT;
v_source BIGINT;
BEGIN
-- 1. Очистка старых данных
DELETE FROM graph_edge WHERE task_id = p_task_id;
DELETE FROM graph_node WHERE task_id = p_task_id;

-- 2. Построение узлов:
--    - концы LineString (start + end)
--    - точки камер
--    - точка источника
--    всё это кластеризуется с eps=0.5 м и заменяется центроидом
WITH points AS (
    SELECT ST_StartPoint(geom_utm) AS geom
FROM input_feature
WHERE task_id = p_task_id AND object_type = 'heat_network' AND geom_utm IS NOT NULL
UNION ALL
SELECT ST_EndPoint(geom_utm)
FROM input_feature
WHERE task_id = p_task_id AND object_type = 'heat_network' AND geom_utm IS NOT NULL
UNION ALL
SELECT geom_utm
FROM input_feature
WHERE task_id = p_task_id AND object_type = 'heat_chamber' AND geom_utm IS NOT NULL
UNION ALL
SELECT geom_utm
FROM input_feature
WHERE task_id = p_task_id AND object_type = 'source' AND geom_utm IS NOT NULL
),
clustered AS (
    SELECT ST_ClusterDBSCAN(geom, eps := 0.5, minpoints := 1) OVER () AS cid,
geom
FROM points
),
centroids AS (
SELECT cid, ST_Centroid(ST_Collect(geom)) AS geom
FROM clustered
GROUP BY cid
)
INSERT INTO graph_node (task_id, node_type, geom)
SELECT p_task_id, 'junction', geom FROM centroids;

GET DIAGNOSTICS v_nodes = ROW_COUNT;

-- 3. Определяем узел-источник: ближайший к точке source
SELECT gn.id INTO v_source
FROM graph_node gn
WHERE gn.task_id = p_task_id
ORDER BY gn.geom <-> (
    SELECT geom_utm FROM input_feature
WHERE task_id = p_task_id AND object_type = 'source' LIMIT 1
)
LIMIT 1;

IF v_source IS NOT NULL THEN
UPDATE graph_node SET node_type = 'source' WHERE id = v_source;
END IF;

-- 4. Построение рёбер из heat_network
--    source_node = узел, ближайший к ST_StartPoint(geom)
--    target_node = узел, ближайший к ST_EndPoint(geom)
WITH edges AS (
    SELECT
    hn.feature_id AS ext_id,
hn.geom_utm   AS geom,
COALESCE((hn.properties->>'diameter')::int, 0) AS diameter,
COALESCE((hn.properties->>'flow_tph')::numeric, 0) AS flow_tph,
ST_Length(hn.geom_utm) AS length_m,
(SELECT gn1.id FROM graph_node gn1
WHERE gn1.task_id = hn.task_id
ORDER BY gn1.geom <-> ST_StartPoint(hn.geom_utm) LIMIT 1) AS src_id,
(SELECT gn2.id FROM graph_node gn2
WHERE gn2.task_id = hn.task_id
ORDER BY gn2.geom <-> ST_EndPoint(hn.geom_utm) LIMIT 1) AS tgt_id
FROM input_feature hn
WHERE hn.task_id = p_task_id
AND hn.object_type = 'heat_network'
AND hn.geom_utm IS NOT NULL
)
INSERT INTO graph_edge (
task_id, source_node, target_node, ext_id, geom,
diameter, flow_tph, length_m, cost_forward, cost_reverse
)
SELECT
p_task_id, src_id, tgt_id, ext_id, geom,
diameter, flow_tph, length_m, length_m, length_m
FROM edges
WHERE src_id IS NOT NULL
AND tgt_id IS NOT NULL
AND src_id <> tgt_id;

GET DIAGNOSTICS v_edges = ROW_COUNT;

RETURN QUERY SELECT v_nodes, v_edges, v_source;
END;
$$ LANGUAGE plpgsql;