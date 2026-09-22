package ru.dit.heattracer.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import ru.dit.heattracer.model.OksCluster;
import ru.dit.heattracer.model.VisibilityGraphResult;

import java.util.UUID;

@Service
public class VisibilityGraphService {

    private static final Logger log = LoggerFactory.getLogger(VisibilityGraphService.class);

    private static final double DEFAULT_R_MAX = 2000.0;

    private final JdbcTemplate jdbc;

    public VisibilityGraphService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Строит граф видимости для кластера.
     *
     * @param taskId      ID задачи
     * @param clusterId   ID кластера
     * @param newDiameter предполагаемый ДУ новой сети (влияет на отступ для oks)
     */
    public VisibilityGraphResult build(UUID taskId, int clusterId, int newDiameter) {
        long start = System.currentTimeMillis();

        VisibilityGraphResult result = jdbc.queryForObject(
                "SELECT inserted_vertices, inserted_edges, elapsed_ms " +
                        "FROM build_visibility_graph(?, ?, ?)",
                (rs, i) -> new VisibilityGraphResult(
                        rs.getLong("inserted_vertices"),
                        rs.getLong("inserted_edges"),
                        rs.getInt("elapsed_ms")),
                taskId, clusterId, newDiameter);

        long wallElapsed = System.currentTimeMillis() - start;

        if (result == null) {
            log.warn("[{}] Cluster {}: build_visibility_graph вернул null",
                    taskId, clusterId);
            return new VisibilityGraphResult(0, 0, (int) wallElapsed);
        }

        log.info("[{}] Cluster {}: {} vertices, {} edges ({} ms in DB, {} ms wall)",
                taskId, clusterId,
                result.getVertices(), result.getEdges(),
                result.getElapsedMs(), wallElapsed);

        if (result.getEdges() == 0) {
            log.warn("[{}] Cluster {}: пустой граф видимости — все пути закрыты " +
                    "ограничениями или нет кандидатов", taskId, clusterId);
        }

        if (wallElapsed > 60_000) {
            log.warn("[{}] Cluster {}: граф строился > 60 сек — проверьте сложность",
                    taskId, clusterId);
        }

        return result;
    }
}