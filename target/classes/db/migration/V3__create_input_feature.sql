CREATE TABLE input_feature (
    id            BIGSERIAL PRIMARY KEY,
    task_id       UUID NOT NULL,
    feature_id    TEXT,
    object_type   TEXT NOT NULL,
    properties    JSONB,
    geom_4326     GEOMETRY(GEOMETRY, 4326),
    geom_utm      GEOMETRY(GEOMETRY, 32637)
);

CREATE INDEX idx_input_task_type ON input_feature(task_id, object_type);
CREATE INDEX idx_input_geom_utm  ON input_feature USING GIST(geom_utm);
CREATE INDEX idx_input_geom_4326 ON input_feature USING GIST(geom_4326);
CREATE INDEX idx_input_props     ON input_feature USING GIN(properties jsonb_path_ops);