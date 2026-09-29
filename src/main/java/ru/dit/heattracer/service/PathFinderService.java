package ru.dit.heattracer.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import ru.dit.heattracer.model.PathResult;

import java.math.BigDecimal;
import java.util.*;

@Service
public class PathFinderService {

    private static final Logger log = LoggerFactory.getLogger(PathFinderService.class);

    private final JdbcTemplate jdbc;

    @Autowired
    public PathFinderService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * V72: multi-target Dijkstra от виртуального корня группы.
     *
     * <p>Все OKS группы идут через ОДНО дерево кратчайших путей от набора
     * target-вершин группы. Рёбра дерева автоматически шарятся между OKS
     * (устраняет радиальную топологию 17 независимых A*).
     *
     * <p>V55: SQL-функция исключает пути, проходящие через чужой OKS.
     * Для отброшенных OKS Java-слой вызывает {@link #findBestPathFromOks} для fallback.
     *
     * @param targetVertices все target-вершины группы (edge projections одной камеры)
     * @param oksVertices    все OKS, назначенные SSP на эту группу
     * @return по одному пути на каждую OKS; валидные шарят общие рёбра,
     *         невалидные (транзит через чужой OKS) — fallback на одиночный A*
     */
    public List<PathResult> findSharedPathsFromGroup(
            UUID taskId, int clusterId,
            List<Long> targetVertices, List<Long> oksVertices) {

        if (targetVertices == null || targetVertices.isEmpty()) return Collections.emptyList();
        if (oksVertices == null || oksVertices.isEmpty())        return Collections.emptyList();

        Long[] targetsArr = targetVertices.toArray(new Long[0]);
        Long[] oksArr     = oksVertices.toArray(new Long[0]);

        long start = System.currentTimeMillis();
        List<PathResult> results = new ArrayList<>();
        Set<Long> covered = new HashSet<>();

        jdbc.query(
                con -> {
                    java.sql.PreparedStatement ps = con.prepareStatement(
                            "SELECT oks_vertex_id, target_vertex_id, total_cost, total_length, " +
                                    "       edge_count, ST_AsText(path_geom) AS path_wkt, edge_ids " +
                                    "  FROM find_shared_paths_from_group(?, ?, ?, ?)");
                    ps.setObject(1, taskId);
                    ps.setInt(2, clusterId);
                    ps.setArray(3, con.createArrayOf("bigint", targetsArr));
                    ps.setArray(4, con.createArrayOf("bigint", oksArr));
                    return ps;
                },
                rs -> {
                    try {
                        long oks = rs.getLong("oks_vertex_id");
                        long target = rs.getLong("target_vertex_id");
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
                                oks, target,
                                rs.getDouble("total_cost"),
                                toDouble(rs.getBigDecimal("total_length")),
                                rs.getInt("edge_count"),
                                wkt, ids, true));
                        covered.add(oks);
                    } catch (java.sql.SQLException ex) {
                        throw new RuntimeException(ex);
                    }
                });

        // V55 fallback: для OKS, для которых shared paths отброшен (транзит через
        // чужой OKS) или путь не найден, вызываем одиночный A*.
        for (Long oks : oksVertices) {
            if (covered.contains(oks)) continue;
            PathResult fallback = findBestPathFromOks(taskId, clusterId, oks);
            if (fallback != null) {
                results.add(fallback);
            }
        }

        long elapsed = System.currentTimeMillis() - start;
        log.info("[{}] Cluster {}: shared paths for {} OKS (targets={}) in {} ms — {} via tree, {} fallback",
                taskId, clusterId, results.size(), targetVertices.size(), elapsed,
                covered.size(), results.size() - covered.size());
        return results;
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
                            rs.getDouble("total_cost"),
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
                                rs.getDouble("total_cost"),
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

    // ========================================================================
    // V76: SPH (Greedy Steiner) — примитивы
    // ========================================================================

    /**
     * Multi-source Dijkstra: для каждой OKS из {@code targetVertices} —
     * ближайшая вершина из {@code sourceVertices} и путь к ней.
     *
     * <p>Используется в SPH на каждой итерации: source = растущее множество
     * treeNodes, target = непокрытые OKS.
     */
    public List<PathResult> findPathsToSet(UUID taskId, int clusterId,
                                           List<Long> sourceVertices,
                                           List<Long> targetVertices) {
        if (sourceVertices == null || sourceVertices.isEmpty()) return Collections.emptyList();
        if (targetVertices == null || targetVertices.isEmpty()) return Collections.emptyList();

        Long[] srcArr = sourceVertices.toArray(new Long[0]);
        Long[] tgtArr = targetVertices.toArray(new Long[0]);

        long start = System.currentTimeMillis();
        List<PathResult> results = new ArrayList<>();

        jdbc.query(
                con -> {
                    java.sql.PreparedStatement ps = con.prepareStatement(
                            "SELECT oks_vertex_id, target_source_id, total_cost, total_length, " +
                                    "       edge_count, ST_AsText(path_geom) AS path_wkt, edge_ids " +
                                    "  FROM find_paths_to_set(?, ?, ?, ?)");
                    ps.setObject(1, taskId);
                    ps.setInt(2, clusterId);
                    ps.setArray(3, con.createArrayOf("bigint", srcArr));
                    ps.setArray(4, con.createArrayOf("bigint", tgtArr));
                    return ps;
                },
                rs -> {
                    try {
                        long oks = rs.getLong("oks_vertex_id");
                        long src = rs.getLong("target_source_id");
                        String wkt = rs.getString("path_wkt");
                        List<Long> ids = extractLongArray(rs.getArray("edge_ids"));
                        results.add(new PathResult(
                                oks, src,
                                rs.getDouble("total_cost"),
                                toDouble(rs.getBigDecimal("total_length")),
                                rs.getInt("edge_count"),
                                wkt, ids, true));
                    } catch (java.sql.SQLException ex) {
                        throw new RuntimeException(ex);
                    }
                });

        long elapsed = System.currentTimeMillis() - start;
        log.debug("[{}] Cluster {}: findPathsToSet |S|={} |T|={} → {} paths ({} ms)",
                taskId, clusterId, sourceVertices.size(), targetVertices.size(),
                results.size(), elapsed);
        return results;
    }

    /**
     * Путь от корня дерева до каждой OKS — только по подмножеству рёбер дерева.
     * Дерево ациклическое → путь единственный.
     */
    public List<PathResult> findPathsThroughTree(UUID taskId, int clusterId,
                                                 long root,
                                                 List<Long> oksVertices,
                                                 Collection<Long> treeEdges) {
        if (oksVertices == null || oksVertices.isEmpty()) return Collections.emptyList();
        if (treeEdges == null || treeEdges.isEmpty()) return Collections.emptyList();

        Long[] oksArr = oksVertices.toArray(new Long[0]);
        Long[] edgeArr = treeEdges.toArray(new Long[0]);

        List<PathResult> results = new ArrayList<>();
        jdbc.query(
                con -> {
                    java.sql.PreparedStatement ps = con.prepareStatement(
                            "SELECT oks_vertex_id, total_cost, total_length, " +
                                    "       edge_count, ST_AsText(path_geom) AS path_wkt, edge_ids " +
                                    "  FROM find_paths_through_tree(?, ?, ?, ?, ?)");
                    ps.setObject(1, taskId);
                    ps.setInt(2, clusterId);
                    ps.setLong(3, root);
                    ps.setArray(4, con.createArrayOf("bigint", oksArr));
                    ps.setArray(5, con.createArrayOf("bigint", edgeArr));
                    return ps;
                },
                rs -> {
                    try {
                        long oks = rs.getLong("oks_vertex_id");
                        String wkt = rs.getString("path_wkt");
                        List<Long> ids = extractLongArray(rs.getArray("edge_ids"));
                        // fromVertex = OKS, toVertex = root — как в findPathsFromOks
                        results.add(new PathResult(
                                oks, root,
                                rs.getDouble("total_cost"),
                                toDouble(rs.getBigDecimal("total_length")),
                                rs.getInt("edge_count"),
                                wkt, ids, true));
                    } catch (java.sql.SQLException ex) {
                        throw new RuntimeException(ex);
                    }
                });
        return results;
    }

    /**
     * Все вершины, инцидентные рёбрам {@code edgeIds}.
     * Используется SPH для роста множества treeNodes.
     */
    public Set<Long> verticesOfEdges(UUID taskId, int clusterId, Collection<Long> edgeIds) {
        if (edgeIds == null || edgeIds.isEmpty()) return Collections.emptySet();
        Long[] arr = edgeIds.toArray(new Long[0]);
        Set<Long> result = new HashSet<>();
        jdbc.query(
                con -> {
                    java.sql.PreparedStatement ps = con.prepareStatement(
                            "SELECT source_vertex, target_vertex " +
                                    "  FROM visibility_edge " +
                                    " WHERE task_id = ? AND cluster_id = ? AND id = ANY(?)");
                    ps.setObject(1, taskId);
                    ps.setInt(2, clusterId);
                    ps.setArray(3, con.createArrayOf("bigint", arr));
                    return ps;
                },
                rs -> {
                    result.add(rs.getLong("source_vertex"));
                    result.add(rs.getLong("target_vertex"));
                });
        return result;
    }

    private static List<Long> extractLongArray(java.sql.Array arr) throws java.sql.SQLException {
        List<Long> ids = new ArrayList<>();
        if (arr != null) {
            Object raw = arr.getArray();
            if (raw instanceof Long[]) {
                for (Long id : (Long[]) raw) ids.add(id);
            } else if (raw instanceof Object[]) {
                for (Object id : (Object[]) raw) ids.add(((Number) id).longValue());
            }
        }
        return ids;
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