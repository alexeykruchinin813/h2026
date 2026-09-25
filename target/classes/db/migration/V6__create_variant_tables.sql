CREATE TABLE variant (
    id                         TEXT NOT NULL,
    task_id                    UUID NOT NULL,
    rank                       INT,
    construction_cost          NUMERIC(18,2),
    chamber_construction_cost  NUMERIC(18,2),
    tie_in_cost                NUMERIC(18,2),
    reconstruction_cost        NUMERIC(18,2),
    chamber_reconstruction_cost NUMERIC(18,2),
    unconnected_penalty        NUMERIC(18,2),
    calculated_cost            NUMERIC(18,2),
    new_network_length         NUMERIC(12,2),
    reconstruction_length      NUMERIC(12,2),
    length                     NUMERIC(12,2),
    score                      NUMERIC(12,6),
    unconnected_oks_ids        TEXT[],
    PRIMARY KEY (task_id, id)
);

CREATE TABLE variant_feature (
    id           BIGSERIAL PRIMARY KEY,
    task_id      UUID NOT NULL,
    variant_id   TEXT NOT NULL,
    feature_id   TEXT,
    object_type  TEXT NOT NULL,
    properties   JSONB,
    geom_4326    GEOMETRY(GEOMETRY, 4326),
    geom_utm     GEOMETRY(GEOMETRY, 32637)
);

CREATE INDEX idx_vfeat_variant ON variant_feature(task_id, variant_id, object_type);
CREATE INDEX idx_vfeat_geom    ON variant_feature USING GIST(geom_utm);

CREATE TABLE technical_node (
    id           BIGSERIAL PRIMARY KEY,
    task_id      UUID NOT NULL,
    variant_id   TEXT NOT NULL,
    geom         GEOMETRY(POINT, 32637) NOT NULL
);

CREATE INDEX idx_tnode_variant ON technical_node(task_id, variant_id);