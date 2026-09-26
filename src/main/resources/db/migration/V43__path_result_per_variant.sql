-- V43. P2.2: три варианта — path_result должен хранить пути для каждого variant_id.
-- Раньше UNIQUE (task_id, oks_vertex_id) не давал записать v2/v3.

ALTER TABLE path_result ADD COLUMN variant_id TEXT NOT NULL DEFAULT 'v1';

DROP INDEX IF EXISTS idx_path_result_oks;
DROP INDEX IF EXISTS idx_path_result_task;

CREATE UNIQUE INDEX idx_path_result_var_oks
ON path_result(task_id, variant_id, oks_vertex_id);
CREATE INDEX idx_path_result_task_var
ON path_result(task_id, variant_id);

COMMENT ON COLUMN path_result.variant_id IS
'V43: идентификатор варианта (v1/v2/v3). Позволяет хранить несколько наборов путей для одной задачи.';