package ru.dit.heattracer.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import ru.dit.heattracer.model.GraphHealthReport;

import java.util.List;
import java.util.UUID;

@Service
public class GraphHealthService {

    private static final Logger log = LoggerFactory.getLogger(GraphHealthService.class);

    private final JdbcTemplate jdbc;

    @Autowired
    public GraphHealthService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public GraphHealthReport check(UUID taskId) {
        long start = System.currentTimeMillis();

        // 1. ANALYZE — обновление статистики
        jdbc.execute("ANALYZE graph_node");
        jdbc.execute("ANALYZE graph_edge");

        // 2. Базовые счётчики
        Long nodes = jdbc.queryForObject(
                "SELECT count(*) FROM graph_node WHERE task_id = ?", Long.class, taskId);
        Long edges = jdbc.queryForObject(
                "SELECT count(*) FROM graph_edge WHERE task_id = ?", Long.class, taskId);

        Long sourceId = jdbc.queryForObject(
                "SELECT COALESCE(MAX(id), 0) FROM graph_node " +
                        "WHERE task_id = ? AND node_type = 'source'", Long.class, taskId);

        Long sourceEdges = jdbc.queryForObject(
                "SELECT count(*) FROM graph_edge " +
                        "WHERE task_id = ? AND (source_node = ? OR target_node = ?)",
                Long.class, taskId, sourceId, sourceId);

        Long reachable = jdbc.queryForObject(
                "WITH RECURSIVE r AS ( " +
                        "  SELECT id AS node_id FROM graph_node " +
                        "    WHERE task_id = ? AND node_type = 'source' " +
                        "  UNION " +
                        "  SELECT CASE WHEN ge.source_node = r.node_id THEN ge.target_node " +
                        "              ELSE ge.source_node END " +
                        "    FROM graph_edge ge JOIN r ON (ge.source_node = r.node_id OR ge.target_node = r.node_id) " +
                        "   WHERE ge.task_id = ? " +
                        ") SELECT count(DISTINCT node_id) FROM r",
                Long.class, taskId, taskId);

        GraphHealthReport report = new GraphHealthReport(
                nodes == null ? 0 : nodes,
                edges == null ? 0 : edges,
                sourceId == null ? 0 : sourceId,
                sourceEdges == null ? 0 : sourceEdges,
                reachable == null ? 0 : reachable);

        // 3. Проверка инвариантов
        checkSelfLoops(taskId, report);
        checkDuplicateEdges(taskId, report);
        checkEmptyGeometry(taskId, report);
        checkMissingSource(taskId, report);
        checkReachability(taskId, report);

        long elapsed = System.currentTimeMillis() - start;
        log.info("[{}] Graph health: {} ({} ms)", taskId, report, elapsed);

        if (!report.getWarnings().isEmpty()) {
            for (String w : report.getWarnings()) {
                log.warn("[{}] GRAPH HEALTH WARNING: {}", taskId, w);
            }
        }

        return report;
    }

    private void checkSelfLoops(UUID taskId, GraphHealthReport r) {
        Long n = jdbc.queryForObject(
                "SELECT count(*) FROM graph_edge " +
                        "WHERE task_id = ? AND source_node = target_node",
                Long.class, taskId);
        if (n != null && n > 0) {
            r.addWarning("Self-loops (source_node = target_node): " + n);
        }
    }

    private void checkDuplicateEdges(UUID taskId, GraphHealthReport r) {
        List<Long> counts = jdbc.query(
                "SELECT count(*) AS c FROM graph_edge WHERE task_id = ? " +
                        "GROUP BY LEAST(source_node, target_node), GREATEST(source_node, target_node) " +
                        "HAVING count(*) > 1",
                (rs, i) -> rs.getLong("c"),
                taskId);
        if (!counts.isEmpty()) {
            long total = counts.stream().mapToLong(Long::longValue).sum();
            r.addWarning("Дублирующиеся рёбра между парами узлов: " + total);
        }
    }

    private void checkEmptyGeometry(UUID taskId, GraphHealthReport r) {
        Long n = jdbc.queryForObject(
                "SELECT count(*) FROM graph_edge " +
                        "WHERE task_id = ? AND (geom IS NULL OR ST_IsEmpty(geom))",
                Long.class, taskId);
        if (n != null && n > 0) {
            r.addWarning("Рёбер без геометрии: " + n);
        }
    }

    private void checkMissingSource(UUID taskId, GraphHealthReport r) {
        if (r.getSourceId() == 0) {
            r.addWarning("Узел-источник не найден (node_type='source')");
        } else if (r.getSourceEdges() == 0) {
            r.addWarning("У узла-источника " + r.getSourceId() + " нет ни одного примыкающего ребра");
        }
    }

    private void checkReachability(UUID taskId, GraphHealthReport r) {
        long isolated = r.getNodes() - r.getReachable();
        if (isolated > 2) {
            r.addWarning("Изолированных узлов: " + isolated
                    + " (достижимо " + r.getReachable() + " из " + r.getNodes() + ")");
        }
    }
}