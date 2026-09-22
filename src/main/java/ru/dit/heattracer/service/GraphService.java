package ru.dit.heattracer.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import ru.dit.heattracer.model.GraphResult;

import java.util.UUID;

@Service
public class GraphService {

    private static final Logger log = LoggerFactory.getLogger(GraphService.class);

    private final JdbcTemplate jdbc;

    public GraphService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Вызывает SQL-функцию build_graph(task_id).
     * Она наполняет graph_node и graph_edge для задачи.
     */
    public GraphResult build(UUID taskId) {
        long start = System.currentTimeMillis();

        GraphResult result = jdbc.queryForObject(
                "SELECT inserted_nodes, inserted_edges, source_node_id FROM build_graph(?)",
                (rs, i) -> new GraphResult(
                        rs.getLong("inserted_nodes"),
                        rs.getLong("inserted_edges"),
                        rs.getObject("source_node_id", Long.class)
                ),
                taskId
        );

        if (result == null) {
            throw new RuntimeException("build_graph вернул null для task_id=" + taskId);
        }

        log.info("[{}] Graph built: {} ({} ms)",
                taskId, result, System.currentTimeMillis() - start);

        if (result.getSourceNodeId() == null) {
            log.warn("[{}] Source node not found — граф без источника", taskId);
        }
        if (result.getEdges() == 0) {
            log.warn("[{}] No edges built — heat_network отсутствует или все self-loops", taskId);
        }

        return result;
    }
}