CREATE OR REPLACE FUNCTION cluster_oks(p_task_id UUID, p_eps NUMERIC DEFAULT 150.0)
RETURNS TABLE(
    cluster_id     INT,
    feature_id     TEXT,
flow_tph       NUMERIC,
geom           GEOMETRY(POINT, 32637),
cluster_size   BIGINT,
cluster_flow   NUMERIC,
cluster_lon    NUMERIC,
cluster_lat    NUMERIC
) AS $$
BEGIN
RETURN QUERY
WITH raw AS (
    SELECT
    cp.feature_id AS fid,
COALESCE((cp.properties->>'flow_tph')::numeric, 0) AS flow,
cp.geom_utm AS g
FROM input_feature cp
WHERE cp.task_id = p_task_id
AND cp.object_type = 'oks_connection_point'
AND cp.geom_utm IS NOT NULL
),
clustered AS (
    SELECT
    ST_ClusterDBSCAN(g, eps := p_eps, minpoints := 1) OVER () AS cid,
fid,
flow,
g
FROM raw
),
stats AS (
    SELECT
    cid,
    count(*) AS sz,
sum(flow) AS fl,
ST_Centroid(ST_Collect(g)) AS centroid
FROM clustered
GROUP BY cid
)
SELECT
c.cid::INT,
c.fid,
c.flow,
c.g,
s.sz,
s.fl,
ST_X(ST_Transform(s.centroid, 4326)),
ST_Y(ST_Transform(s.centroid, 4326))
FROM clustered c
JOIN stats s ON s.cid = c.cid
ORDER BY c.cid, c.fid;
END;
$$ LANGUAGE plpgsql;