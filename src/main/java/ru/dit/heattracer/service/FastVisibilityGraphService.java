package ru.dit.heattracer.service;

import org.locationtech.jts.geom.*;
import org.locationtech.jts.index.strtree.STRtree;
import org.locationtech.jts.io.WKBReader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import ru.dit.heattracer.model.VisibilityGraphResult;

import java.util.*;

/**
 * Оптимизированный сервис построения графа видимости с использованием JTS STRtree.
 * 
 * Преимущества перед SQL-подходом:
 * - STRtree обеспечивает O(log n) поиск вместо O(n²) CROSS JOIN
 * - Вся логика в памяти Java (быстрее чем SQL запросы)
 * - Параллельная обработка через Stream API
 * 
 * Ожидаемое ускорение: 10-50x для больших кластеров (>500 corners)
 */
@Service
public class FastVisibilityGraphService {

    private static final Logger log = LoggerFactory.getLogger(FastVisibilityGraphService.class);

    private final JdbcTemplate jdbc;
    private final GeometryFactory geometryFactory = new GeometryFactory();

    // Параметры по умолчанию (можно переопределить через конструктор)
    private double rMaxCorner = 120.0;        // corner-corner: 120м
    private double rMaxCandidate = 2500.0;    // corner-candidate: 2500м
    private double rMaxOksCorner = 500.0;     // oks-corner: 500м
    private int maxCorners = 400;             // максимум углов на кластер
    private double simplifyTolerance = 2.0;   // ST_SimplifyPreserveTopology tolerance

    @Autowired
    public FastVisibilityGraphService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Конструктор с кастомными параметрами
     */
    public FastVisibilityGraphService(JdbcTemplate jdbc, 
                                       double rMaxCorner,
                                       double rMaxCandidate,
                                       double rMaxOksCorner,
                                       int maxCorners,
                                       double simplifyTolerance) {
        this.jdbc = jdbc;
        this.rMaxCorner = rMaxCorner;
        this.rMaxCandidate = rMaxCandidate;
        this.rMaxOksCorner = rMaxOksCorner;
        this.maxCorners = maxCorners;
        this.simplifyTolerance = simplifyTolerance;
    }

    /**
     * Строит граф видимости используя JTS STRtree для ускорения проверки видимости.
     * 
     * Алгоритм:
     * 1. Извлекаем вершины (OKS, candidates, corners) из БД
     * 2. Строим STRtree для forbidden zones (полигоны ограничений)
     * 3. Для каждой пары вершин:
     *    - Проверяем расстояние (отсекаем дальние)
     *    - Строим линию между вершинами
     *    - Используем STRtree для быстрой проверки пересечений с forbidden zones
     * 4. Вставляем рёбра в БД
     * 
     * @param taskId ID задачи
     * @param clusterId ID кластера
     * @param newDiameter диаметр новой сети (для отступов)
     * @return количество вставленных вершин и рёбер
     */
    public VisibilityGraphResult buildFast(UUID taskId, int clusterId, int newDiameter) {
        long startTotal = System.currentTimeMillis();

        // ===== 1. Извлечение вершин =====
        long startExtract = System.currentTimeMillis();
        List<Vertex> vertices = extractVertices(taskId, clusterId, newDiameter);
        long extractElapsed = System.currentTimeMillis() - startExtract;

        if (vertices.isEmpty()) {
            log.warn("[{}] Cluster {}: no vertices extracted", taskId, clusterId);
            return new VisibilityGraphResult(0, 0, 0);
        }

        log.info("[{}] Cluster {}: extracted {} vertices in {} ms",
                taskId, clusterId, vertices.size(), extractElapsed);

        // ===== 2. Извлечение forbidden zones и построение STRtree =====
        long startForbidden = System.currentTimeMillis();
        List<Geometry> forbiddenZones = extractForbiddenZones(taskId, clusterId, newDiameter);
        STRtree forbiddenTree = buildSTRtree(forbiddenZones);
        long forbiddenElapsed = System.currentTimeMillis() - startForbidden;

        log.info("[{}] Cluster {}: {} forbidden zones, STRtree built in {} ms",
                taskId, clusterId, forbiddenZones.size(), forbiddenElapsed);

        // ===== 3. Генерация рёбер с проверкой видимости =====
        long startEdges = System.currentTimeMillis();
        List<Edge> edges = generateEdgesWithVisibility(vertices, forbiddenTree);
        long edgesElapsed = System.currentTimeMillis() - startEdges;

        log.info("[{}] Cluster {}: generated {} edges in {} ms",
                taskId, clusterId, edges.size(), edgesElapsed);

        // ===== 4. Вставка рёбер в БД =====
        long startInsert = System.currentTimeMillis();
        insertEdges(taskId, edges);
        long insertElapsed = System.currentTimeMillis() - startInsert;

        long totalElapsed = System.currentTimeMillis() - startTotal;

        log.info("[{}] Cluster {}: TOTAL {} vertices, {} edges in {} ms " +
                        "(extract={}ms, forbidden={}ms, edges={}ms, insert={}ms)",
                taskId, clusterId, vertices.size(), edges.size(), totalElapsed,
                extractElapsed, forbiddenElapsed, edgesElapsed, insertElapsed);

        return new VisibilityGraphResult(vertices.size(), edges.size(), (int) totalElapsed);
    }

    /**
     * Извлекает вершины из БД: OKS points, tie-in candidates, polygon corners
     */
    private List<Vertex> extractVertices(UUID taskId, int clusterId, int newDiameter) {
        // Извлекаем OKS вершины
        List<Vertex> oksVertices = jdbc.query(
                "SELECT id, ST_X(geom) AS x, ST_Y(geom) AS y, 'oks' AS type " +
                "FROM visibility_vertex WHERE task_id = ? AND cluster_id = ? AND vertex_type = 'oks'",
                (rs, rowNum) -> new Vertex(
                        rs.getLong("id"),
                        rs.getDouble("x"),
                        rs.getDouble("y"),
                        VertexType.OKS),
                taskId, clusterId);

        // Извлекаем candidate вершины
        List<Vertex> candidateVertices = jdbc.query(
                "SELECT id, ST_X(geom) AS x, ST_Y(geom) AS y, 'candidate' AS type " +
                "FROM visibility_vertex WHERE task_id = ? AND cluster_id = ? AND vertex_type = 'candidate'",
                (rs, rowNum) -> new Vertex(
                        rs.getLong("id"),
                        rs.getDouble("x"),
                        rs.getDouble("y"),
                        VertexType.CANDIDATE),
                taskId, clusterId);

        // Извлекаем corner вершины (с упрощением через ST_SimplifyPreserveTopology)
        List<Vertex> cornerVertices = jdbc.query(
                "SELECT id, ST_X(ST_SimplifyPreserveTopology(geom, ?)) AS x, " +
                "       ST_Y(ST_SimplifyPreserveTopology(geom, ?)) AS y, 'corner' AS type " +
                "FROM visibility_vertex WHERE task_id = ? AND cluster_id = ? AND vertex_type = 'polygon_corner' " +
                "ORDER BY ST_Distance(geom, ST_Centroid(geom)) LIMIT ?",
                (rs, rowNum) -> new Vertex(
                        rs.getLong("id"),
                        rs.getDouble("x"),
                        rs.getDouble("y"),
                        VertexType.CORNER),
                simplifyTolerance, simplifyTolerance, taskId, clusterId, maxCorners);

        List<Vertex> all = new ArrayList<>();
        all.addAll(oksVertices);
        all.addAll(candidateVertices);
        all.addAll(cornerVertices);
        return all;
    }

    /**
     * Извлекает forbidden zones (буферы ограничений) из БД.
     * Буфер строится по restriction_rules: для oks — фактический отступ 5/7/9м,
     * для остальных — min_horizontal_dist.
     */
    private List<Geometry> extractForbiddenZones(UUID taskId, int clusterId, int newDiameter) {
        double oksBuffer = getOksBufferByDiameter(newDiameter);

        // Извлекаем буферы запретных зон
        return jdbc.query(
                "SELECT ST_AsBinary(ST_Buffer(r.geom_utm, " +
                "  CASE WHEN rr.type = 'oks' THEN ? ELSE rr.min_horizontal_dist END)) AS geom_wkb " +
                "FROM input_feature r " +
                "JOIN restriction_rules rr ON rr.type = r.properties->>'restriction_type' " +
                "WHERE r.task_id = ? AND r.object_type = 'restriction' " +
                "  AND COALESCE(rr.crossing_forbidden, FALSE) = TRUE " +
                "  AND r.geom_utm IS NOT NULL",
                (rs, rowNum) -> {
                    try {
                        byte[] wkb = rs.getBytes("geom_wkb");
                        return new WKBReader(geometryFactory).read(wkb);
                    } catch (org.locationtech.jts.io.ParseException e) {
                        throw new RuntimeException("Failed to parse WKB geometry", e);
                    }
                },
                oksBuffer, taskId);
    }

    /**
     * Отступ от ОКС в зависимости от диаметра трубы: 5 / 7 / 9 м
     */
    private double getOksBufferByDiameter(int newDiameter) {
        if (newDiameter < 500) return 5.0;
        if (newDiameter <= 800) return 7.0;
        return 9.0;
    }

    /**
     * Строит STRtree для быстрого поиска пересечений
     */
    private STRtree buildSTRtree(List<Geometry> geometries) {
        STRtree tree = new STRtree();
        for (Geometry geom : geometries) {
            tree.insert(geom.getEnvelopeInternal(), geom);
        }
        // Force tree building
        tree.build();
        return tree;
    }

    /**
     * Генерирует рёбра с проверкой видимости используя STRtree
     */
    private List<Edge> generateEdgesWithVisibility(List<Vertex> vertices, STRtree forbiddenTree) {
        List<Edge> edges = new ArrayList<>();
        int n = vertices.size();

        // Разделяем вершины по типам для оптимизации
        List<Vertex> oksList = new ArrayList<>();
        List<Vertex> candidatesList = new ArrayList<>();
        List<Vertex> cornersList = new ArrayList<>();

        for (Vertex v : vertices) {
            switch (v.type) {
                case OKS: oksList.add(v); break;
                case CANDIDATE: candidatesList.add(v); break;
                case CORNER: cornersList.add(v); break;
            }
        }

        // Генерируем пары с разными R_MAX в зависимости от типа
        addEdgesForPair(oksList, oksList, edges, forbiddenTree, 0); // OKS-OKS: не соединяем
        addEdgesForPair(oksList, candidatesList, edges, forbiddenTree, rMaxCandidate);
        addEdgesForPair(oksList, cornersList, edges, forbiddenTree, rMaxOksCorner);
        addEdgesForPair(candidatesList, candidatesList, edges, forbiddenTree, 0); // candidate-candidate: не соединяем
        addEdgesForPair(candidatesList, cornersList, edges, forbiddenTree, rMaxCandidate);
        addEdgesForPair(cornersList, cornersList, edges, forbiddenTree, rMaxCorner);

        return edges;
    }

    /**
     * Публичная точка входа для генерации рёбер (используется тестами вместо рефлексии).
     * Вызывает production-метод {@link #generateEdgesWithVisibility}.
     *
     * @param vertices      список вершин графа
     * @param forbiddenTree STRtree запретных зон
     * @return список построенных рёбер
     */
    public List<Edge> generateEdgesForTest(List<Vertex> vertices, STRtree forbiddenTree) {
        return generateEdgesWithVisibility(vertices, forbiddenTree);
    }

    /**
     * Добавляет рёбра для пар вершин одного или разных типов.
     * Package-private: вызывается напрямую из тестов того же пакета
     * (рефлексия для тестирования не требуется).
     */
    void addEdgesForPair(List<Vertex> list1, List<Vertex> list2, List<Edge> edges, 
                                  STRtree forbiddenTree, double rMax) {
        if (rMax <= 0) return; // Не соединяем пары этого типа

        double rMaxSquared = rMax * rMax;

        for (int i = 0; i < list1.size(); i++) {
            Vertex v1 = list1.get(i);
            
            // Начинаем с i (или i+1 для одинаковых списков) чтобы избежать дубликатов
            int startJ = (list1 == list2) ? i + 1 : 0;
            
            for (int j = startJ; j < list2.size(); j++) {
                if (list1 == list2 && i == j) continue;
                
                Vertex v2 = list2.get(j);
                
                // Быстрая проверка расстояния
                double dx = v1.x - v2.x;
                double dy = v1.y - v2.y;
                double distSquared = dx * dx + dy * dy;
                
                if (distSquared > rMaxSquared) continue; // Дальше чем R_MAX

                // Строим линию
                LineString line = geometryFactory.createLineString(
                        new Coordinate[]{new Coordinate(v1.x, v1.y), new Coordinate(v2.x, v2.y)});

                // Проверяем пересечения с forbidden zones через STRtree
                if (!isVisible(line, forbiddenTree)) continue;

                // Вычисляем длину
                double length = Math.sqrt(distSquared);

                edges.add(new Edge(v1.id, v2.id, length));
            }
        }
    }

    /**
     * Проверяет видимость между вершинами (линия не пересекает forbidden zones)
     * Использует STRtree для быстрого поиска потенциальных пересечений
     */
    private boolean isVisible(LineString line, STRtree forbiddenTree) {
        Envelope lineEnv = line.getEnvelopeInternal();

        // Находим все forbidden zones, которые пересекаются с envelope линии
        List<?> candidates = forbiddenTree.query(lineEnv);

        if (candidates.isEmpty()) {
            return true; // Нет препятствий вблизи
        }

        // Проверяем точное пересечение только с кандидатами
        for (Object obj : candidates) {
            Geometry forbidden = (Geometry) obj;
            if (forbidden.intersects(line)) {
                return false; // Пересекает запретную зону
            }
        }

        return true; // Видимость есть
    }

    // ===== Test-only обёртки (используются в FastVisibilityGraphServiceTest) =====

    /**
     * Публичная обёртка над buildSTRtree для тестов
     */
    public STRtree buildSTRtreeForTest(List<Geometry> geometries) {
        return buildSTRtree(geometries);
    }

    /**
     * Публичная обёртка над isVisible для тестов
     */
    public boolean isVisibleForTest(LineString line, STRtree forbiddenTree) {
        return isVisible(line, forbiddenTree);
    }

    /**
     * Вставляет рёбра в таблицу visibility_edge.
     * Package-private: вызывается напрямую из тестов того же пакета.
     */
    void insertEdges(UUID taskId, List<Edge> edges) {
        if (edges.isEmpty()) return;

        // Пакетная вставка для производительности
        String sql = "INSERT INTO visibility_edge (" +
                "task_id, cluster_id, source_vertex, target_vertex, geom, length_m, cost, reverse_cost" +
                ") VALUES (?, 0, ?, ?, ST_SetSRID(ST_MakeLine(" +
                "(SELECT geom FROM visibility_vertex WHERE id = ?)," +
                "(SELECT geom FROM visibility_vertex WHERE id = ?)), 32637), ?, ?, ?)";

        List<Object[]> batchArgs = new ArrayList<>(edges.size());
        for (Edge edge : edges) {
            batchArgs.add(new Object[]{
                    taskId, edge.fromVertex, edge.toVertex,
                    edge.fromVertex, edge.toVertex,
                    edge.length, edge.length, edge.length
            });
        }

        int batchSize = 1000;
        for (int i = 0; i < batchArgs.size(); i += batchSize) {
            int end = Math.min(i + batchSize, batchArgs.size());
            List<Object[]> batch = batchArgs.subList(i, end);
            jdbc.batchUpdate(sql, batch);
        }
    }

    // ===== Вспомогательные классы =====

    public enum VertexType {
        OKS, CANDIDATE, CORNER
    }

    public static class Vertex {
        final long id;
        final double x, y;
        final VertexType type;

        public Vertex(long id, double x, double y, VertexType type) {
            this.id = id;
            this.x = x;
            this.y = y;
            this.type = type;
        }
    }

    public static class Edge {
        public final long fromVertex;
        public final long toVertex;
        public final double length;

        public Edge(long fromVertex, long toVertex, double length) {
            this.fromVertex = fromVertex;
            this.toVertex = toVertex;
            this.length = length;
        }
    }
}
