CREATE TABLE task (
    id              UUID PRIMARY KEY,
    status          VARCHAR(20) NOT NULL,
    stage           VARCHAR(50),
    percent         INT DEFAULT 0,
    error_message   TEXT,
    input_path      TEXT,
    result_path     TEXT,
    created_at      TIMESTAMPTZ DEFAULT now(),
    finished_at     TIMESTAMPTZ
);

CREATE INDEX idx_task_status ON task(status);
CREATE INDEX idx_task_created ON task(created_at);