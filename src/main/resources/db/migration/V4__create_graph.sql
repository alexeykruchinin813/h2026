CREATE TABLE graph_node (
    id        BIGSERIAL PRIMARY KEY,
    task_id   UUID NOT NULL,
    ext_id    TEXT,
    node_type TEXT NOT NULL,
    geom      GEOMETRY(POINT, 32637) NOT NULL
);

CREATE INDEX idx_node_task ON graph_node(task_id);
CREATE INDEX idx_node_geom ON graph_node USING GIST(geom);

CREATE TABLE graph_edge (
    id           BIGSERIAL PRIMARY KEY,
    task_id      UUID NOT NULL,
    source_node  BIGINT NOT NULL REFERENCES graph_node(id) ON DELETE CASCADE,
    target_node  BIGINT NOT NULL REFERENCES graph_node(id) ON DELETE CASCADE,
    ext_id       TEXT,
    geom         GEOMETRY(LINESTRING, 32637) NOT NULL,
    diameter     INT,
    flow_tph     NUMERIC(10,3),
    length_m     NUMERIC(12,3),
    cost_forward NUMERIC(12,3),
    cost_reverse NUMERIC(12,3)
);

CREATE INDEX idx_edge_task   ON graph_edge(task_id);
CREATE INDEX idx_edge_geom   ON graph_edge USING GIST(geom);
CREATE INDEX idx_edge_source ON graph_edge(source_node);
CREATE INDEX idx_edge_target ON graph_edge(target_node);