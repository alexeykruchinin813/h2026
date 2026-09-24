package ru.dit.heattracer.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import ru.dit.heattracer.model.PathResult;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
public class PathFinderService {

    private static final Logger log = LoggerFactory.getLogger(PathFinderService.class);

    private final JdbcTemplate jdbc;

    @Autowired
    public PathFinderService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Ищет оптимальный путь между двумя вершинами графа видимости.
     * Возвращает PathResult.notFound(...) если пути нет.
     */
    public PathResult findPath(UUID taskId, int clusterId,
                               long fromVertex, long toVertex) {
        try {
            return jdbc.queryForObject(
                    "SELECT total_cost, total_length, edge_count, " +
                            "       ST_AsText(path_geom) AS path_wkt, edge_ids " +
                            "  FROM find_visibility_path_geom(?, ?, ?, ?)",
                    (rs, i) -> {
                        Double cost = rs.getObject("total_cost", Double.class);
                        if (cost == null || rs.getInt("edge_count") == 0) {
                            return PathResult.notFound(fromVertex, toVertex);
                        }
                        String wkt = rs.getString("path_wkt");

                        // edge_ids — bigint[]
                        java.sql.Array arr = rs.getArray("edge_ids");
                        List<Long> ids = new ArrayList<>();
                        if (arr != null) {
                            Object raw = arr.getArray();
                            if (raw instanceof Long[]) {
                                for (Long id : (Long[]) raw) ids.add(id);
                            } else if (raw instanceof Object[]) {
                                for (Object id : (Object[]) raw) ids.add(((Number) id).longValue());
                            }
                        }

                        return new PathResult(
                                fromVertex, toVertex,
                                cost,
                                rs.getDouble("total_length"),
                                rs.getInt("edge_count"),
                                wkt, ids, true);
                    },
                    taskId, clusterId, fromVertex, toVertex);
        } catch (Exception e) {
            log.warn("[{}] findPath failed from={} to={}: {}",
                    taskId, fromVertex, toVertex, e.getMessage());
            return PathResult.notFound(fromVertex, toVertex);
        }
    }

    /**
     * Ищет пути от ОКС до всех кандидатов в кластере.
     * Возвращает только успешные пути (не found отбрасываются).
     */
    public List<PathResult> findPathsFromOks(UUID taskId, int clusterId, long oksVertex) {
        long start = System.currentTimeMillis();

        List<PathResult> results = new ArrayList<>();

        jdbc.query(
                "SELECT vv.id AS target_vertex, " +
                        "       ST_AsText(p.path_geom) AS path_wkt, " +
                        "       p.total_cost, p.total_length, p.edge_count, p.edge_ids " +
                        "  FROM visibility_vertex vv " +
                        "  CROSS JOIN LATERAL find_visibility_path_geom(?, ?, ?, vv.id) p " +
                        " WHERE vv.task_id = ? " +
                        "   AND vv.cluster_id = ? " +
                        "   AND vv.vertex_type = 'candidate' " +
                        "   AND p.edge_count > 0",
                rs -> {
                    long target = rs.getLong("target_vertex");
                    String wkt = rs.getString("path_wkt");

                    java.sql.Array arr = rs.getArray("edge_ids");
                    List<Long> ids = new ArrayList<>();
                    if (arr != null) {
                        Object raw = arr.getArray();
                        if (raw instanceof Long[]) {
                            for (Long id : (Long[]) raw) ids.add(id);
                        } else if (raw instanceof Object[]) {
                            for (Object id : (Object[]) raw) ids.add(((Number) id).longValue());
                        }
                    }

                    results.add(new PathResult(
                            oksVertex, target,
                            rs.getDouble("total_cost"),
                            rs.getDouble("total_length"),
                            rs.getInt("edge_count"),
                            wkt, ids, true));
                },
                // Аргументы идут по порядку появления ? в SQL: сначала 4 параметра LATERAL-функции
                // (task_id, cluster_id, from_vertex, to_vertex=vv.id — привязан к строке, не передаём),
                // затем task_id и cluster_id для WHERE.
                taskId, clusterId, oksVertex,
                taskId, clusterId);

        long elapsed = System.currentTimeMillis() - start;
        log.info("[{}] Cluster {}: OKS vertex {} → {} paths found ({} ms)",
                taskId, clusterId, oksVertex, results.size(), elapsed);

        return results;
    }

    /**
     * Ищет ЛУЧШИЙ (минимальной стоимости) путь от ОКС до любого кандидата.
     * Возвращает null, если пути нет.
     */
    public PathResult findBestPathFromOks(UUID taskId, int clusterId, long oksVertex) {
        try {
            return jdbc.queryForObject(
                    "SELECT target_vertex, total_cost, total_length, " +
                            "       edge_count, ST_AsText(path_geom) AS path_wkt, edge_ids " +
                            "  FROM find_best_path_from_oks(?, ?, ?)",
                    (rs, i) -> {
                        long target = rs.getLong("target_vertex");
                        String wkt = rs.getString("path_wkt");

                        java.sql.Array arr = rs.getArray("edge_ids");
                        List<Long> ids = new ArrayList<>();
                        if (arr != null) {
                            Object raw = arr.getArray();
                            if (raw instanceof Long[]) {
                                for (Long id : (Long[]) raw) ids.add(id);
                            } else if (raw instanceof Object[]) {
                                for (Object id : (Object[]) raw) ids.add(((Number) id).longValue());
                            }
                        }

                        return new PathResult(
                                oksVertex, target,
                                rs.getDouble("total_cost"),
                                rs.getDouble("total_length"),
                                rs.getInt("edge_count"),
                                wkt, ids, true);
                    },
                    taskId, clusterId, oksVertex);
        } catch (Exception e) {
            log.warn("[{}] findBestPathFromOks failed for vertex {}: {}",
                    taskId, oksVertex, e.getMessage());
            return null;
        }
    }
}