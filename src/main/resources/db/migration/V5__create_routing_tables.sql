CREATE TABLE tie_in_candidate (
    id                   BIGSERIAL PRIMARY KEY,
    task_id              UUID NOT NULL,
    variant_id           TEXT,
    cluster_id           INT,
    existing_object_id   TEXT,
    existing_object_type TEXT,
    geom                 GEOMETRY(POINT, 32637) NOT NULL,
required_diameter    INT
);

CREATE INDEX idx_tie_task ON tie_in_candidate(task_id);
CREATE INDEX idx_tie_geom ON tie_in_candidate USING GIST(geom);

CREATE TABLE visibility_edge (
    id           BIGSERIAL PRIMARY KEY,
    task_id      UUID NOT NULL,
    cluster_id   INT,
    source_node  BIGINT,
    target_node  BIGINT,
    geom         GEOMETRY(LINESTRING, 32637),
    length_m     NUMERIC(12,3),
    cost         NUMERIC(12,3),
    is_special   BOOLEAN DEFAULT FALSE,
    special_k    NUMERIC(6,3)
);

CREATE INDEX idx_vis_task ON visibility_edge(task_id, cluster_id);
CREATE INDEX idx_vis_geom ON visibility_edge USING GIST(geom);