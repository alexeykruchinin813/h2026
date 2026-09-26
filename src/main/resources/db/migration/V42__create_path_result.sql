-- V42. Персистенция найденных путей A* (D2).
--
-- Зачем: сейчас PathResult живёт только в log.info в TaskService.
-- Для экспорта GeoJSON (раздел 7 ТЗ) нужен доступ к путям из SQL:
--   - union edge_ids всех путей → множество участков новой сети;
--   - flow_tph на каждом участке = сумма OKS, чей путь его использует;
--   - диаметр по расходу;
--   - стоимость по таблице 1 техприложения.
--
-- Уникальность (task_id, oks_vertex_id) — каждый OKS имеет ровно один
-- кратчайший путь до своего tie-in (ТЗ 2.2: "от точки врезки до каждого
-- подключённого ОКС должен быть только один путь").

CREATE TABLE path_result (
    id                BIGSERIAL PRIMARY KEY,
    task_id           UUID NOT NULL,
    cluster_id        INT  NOT NULL,
    oks_vertex_id     BIGINT NOT NULL,
    target_vertex_id  BIGINT NOT NULL,
    path_geom         GEOMETRY(LineString, 32637),
    total_cost        DOUBLE PRECISION NOT NULL,
    total_length_m    DOUBLE PRECISION NOT NULL,
    edge_count        INT NOT NULL,
    edge_ids          BIGINT[] NOT NULL,
    created_at        TIMESTAMPTZ DEFAULT now()
);

CREATE INDEX idx_path_result_task ON path_result(task_id, cluster_id);
CREATE UNIQUE INDEX idx_path_result_oks ON path_result(task_id, oks_vertex_id);
CREATE INDEX idx_path_result_target ON path_result(task_id, target_vertex_id);

COMMENT ON TABLE path_result IS
'V42: путь A* от OKS до tie-in candidate. Один OKS = одна строка.';