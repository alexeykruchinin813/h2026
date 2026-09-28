package ru.dit.heattracer.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import ru.dit.heattracer.model.PathResult;

import java.math.BigDecimal;
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
                        // total_cost / total_length — PostGIS NUMERIC, читаем через BigDecimal:
                        // JDBC-драйвер не поддерживает getObject(col, Double.class) для numeric.
                        BigDecimal cost = rs.getBigDecimal("total_cost");
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
                                cost.doubleValue(),
                                toDouble(rs.getBigDecimal("total_length")),
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
                // V65: NOT EXISTS removed. В плотных кластерах этот фильтр
                // блокировал все пути к edge_projection, оставляя OKS без
                // reachable candidates. Планарность обеспечивается V63 + V53.
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
                            toDouble(rs.getBigDecimal("total_cost")),
                            toDouble(rs.getBigDecimal("total_length")),
                            rs.getInt("edge_count"),
                            wkt, ids, true));
                },
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
                                toDouble(rs.getBigDecimal("total_cost")),
                                toDouble(rs.getBigDecimal("total_length")),
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

    /**
     * Безопасная конвертация NUMERIC-колонки (BigDecimal) в double.
     * pgjdbc не поддерживает чтение numeric напрямую в double через {@code getDouble}
     * на некоторых путях драйвера, поэтому конверсия выполняется явно.
     */
    private static double toDouble(BigDecimal value) {
        return value == null ? 0.0 : value.doubleValue();
    }
}