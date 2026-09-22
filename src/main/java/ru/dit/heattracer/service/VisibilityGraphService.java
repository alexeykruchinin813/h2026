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

    // OPTIMIZATION: Reduced defaults per V25 migration
    private static final double DEFAULT_R_MAX_CORNER = 120.0;      // Was 200m
    private static final double DEFAULT_R_MAX_CANDIDATE = 2500.0;
    private static final double DEFAULT_R_MAX_OKS_CORNER = 500.0;
    private static final int DEFAULT_MAX_CORNERS = 400;            // Was 1500
    private static final double DEFAULT_SIMPLIFY_TOLERANCE = 2.0;  // NEW: polygon simplification
    private static final double DEFAULT_OKS_BUFFER_ROUGH = 1.0;

    private final JdbcTemplate jdbc;

    public VisibilityGraphService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Строит граф видимости для кластера.
     * Использует оптимизированную функцию V25 с параметрами:
     * - p_max_corners=400 (was 1500)
     * - p_r_max_corner=120м (was 200м)
     * - p_simplify_tolerance=2.0м (ST_SimplifyPreserveTopology)
     *
     * @param taskId      ID задачи
     * @param clusterId   ID кластера
     * @param newDiameter предполагаемый ДУ новой сети (влияет на отступ для oks)
     */
    public VisibilityGraphResult build(UUID taskId, int clusterId, int newDiameter) {
        long start = System.currentTimeMillis();

        // Call optimized V25 function with explicit parameters
        VisibilityGraphResult result = jdbc.queryForObject(
                "SELECT inserted_vertices, inserted_edges, elapsed_ms " +
                        "FROM build_visibility_graph(?, ?, ?, ?, ?, ?, ?, ?, ?)",
                (rs, i) -> new VisibilityGraphResult(
                        rs.getLong("inserted_vertices"),
                        rs.getLong("inserted_edges"),
                        rs.getInt("elapsed_ms")),
                taskId, 
                clusterId, 
                newDiameter,
                DEFAULT_R_MAX_CORNER,
                DEFAULT_R_MAX_CANDIDATE,
                DEFAULT_R_MAX_OKS_CORNER,
                DEFAULT_MAX_CORNERS,
                DEFAULT_OKS_BUFFER_ROUGH,
                DEFAULT_SIMPLIFY_TOLERANCE);

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