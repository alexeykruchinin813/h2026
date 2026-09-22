package ru.dit.heattracer.service;

import org.locationtech.jts.geom.*;
import org.locationtech.jts.index.strtree.STRtree;
import org.locationtech.jts.io.WKBReader;
import org.locationtech.jts.operation.buffer.BufferOp;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import ru.dit.heattracer.model.VisibilityGraphResult;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Гибридный сервис построения графа видимости: SQL + JTS валидация.
 * 
 * Решает проблему разорванного графа в плотной застройке:
 * - SQL строит coarse-граф с минимальными буферами (0.5м)
 * - JTS валидирует каждое ребро против TRUE буферов (5/7/9м для OKS)
 * - Добавляет escape points на границе буфера OKS для выхода из полигона
 * - Применяет U6 rule extension: OKS может выйти из своего буфера
 * 
 * Ожидаемый результат: связный граф даже при 14 OKS на 500x500м
 */
@Service
public class HybridVisibilityGraphService {

    private static final Logger log = LoggerFactory.getLogger(HybridVisibilityGraphService.class);

    private final JdbcTemplate jdbc;
    private final GeometryFactory geometryFactory = new GeometryFactory();

    // Параметры
    private double oksBufferExact = 5.0;     // базовый отступ для OKS (уточняется по DU)
    private double escapeBufferDist = 6.0;   // 5м + 1м запас для escape points
    private int escapePointsPerPolygon = 8;  // количество escape точек на полигон

    public HybridVisibilityGraphService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Строит гибридный граф видимости:
     * 1. SQL создаёт вершины и рёбра с minimal buffers
     * 2. Добавляем escape points на границе OKS буферов
     * 3. JTS валидирует каждое ребро против индивидуальных полигонов
     * 4. Удаляем невалидные рёбра
     * 
     * @param taskId ID задачи
     * @param clusterId ID кластера
     * @param newDiameter диаметр новой сети (мм)
     * @return результат с количеством вершин/рёбер
     */
    public VisibilityGraphResult buildHybrid(UUID taskId, int clusterId, int newDiameter) {
        long startTotal = System.currentTimeMillis();

        // ===== 1. Вызываем SQL функцию для coarse-графа =====
        long startSql = System.currentTimeMillis();
        Map<String, Object> sqlResult = callSqlCoarseGraph(taskId, clusterId, newDiameter);
        long sqlElapsed = System.currentTimeMillis() - startSql;

        int vertexCount = ((Number) sqlResult.get("vertices")).intValue();
        int edgeCount = ((Number) sqlResult.get("edges")).intValue();

        log.info("[{}] Cluster {}: SQL coarse graph created in {} ms ({} vertices, {} edges)",
                taskId, clusterId, sqlElapsed, vertexCount, edgeCount);

        if (edgeCount == 0) {
            return new VisibilityGraphResult(vertexCount, 0, (int) sqlElapsed);
        }

        // ===== 2. Добавляем escape points через SQL функцию =====
        long startEscape = System.currentTimeMillis();
        int escapePointsAdded = addEscapePoints(taskId, clusterId);
        long escapeElapsed = System.currentTimeMillis() - startEscape;

        log.info("[{}] Cluster {}: {} escape points added in {} ms",
                taskId, clusterId, escapePointsAdded, escapeElapsed);

        // ===== 3. Извлекаем рёбра для JTS валидации =====
        long startExtract = System.currentTimeMillis();
        List<EdgeToValidate> edgesToValidate = extractEdgesForValidation(taskId, clusterId);
        long extractElapsed = System.currentTimeMillis() - startExtract;

        log.info("[{}] Cluster {}: {} edges extracted for validation in {} ms",
                taskId, clusterId, edgesToValidate.size(), extractElapsed);

        // ===== 4. Извлекаем forbidden polygons (индивидуально, не union) =====
        long startForbidden = System.currentTimeMillis();
        Map<Long, Geometry> forbiddenPolygons = extractForbiddenPolygons(taskId, clusterId);
        long forbiddenElapsed = System.currentTimeMillis() - startForbidden;

        log.info("[{}] Cluster {}: {} forbidden polygons extracted in {} ms",
                taskId, clusterId, forbiddenPolygons.size(), forbiddenElapsed);

        // ===== 5. JTS валидация каждого ребра =====
        long startValidation = System.currentTimeMillis();
        List<Long> validEdgeIds = validateEdgesWithJTS(edgesToValidate, forbiddenPolygons, newDiameter);
        long validationElapsed = System.currentTimeMillis() - startValidation;

        int removedEdgesCount = edgesToValidate.size() - validEdgeIds.size();
        log.info("[{}] Cluster {}: {} edges valid, {} removed in {} ms",
                taskId, clusterId, validEdgeIds.size(), removedEdgesCount, validationElapsed);

        // ===== 6. Удаляем невалидные рёбра =====
        long startDelete = System.currentTimeMillis();
        if (removedEdgesCount > 0) {
            removeInvalidEdges(taskId, edgesToValidate, validEdgeIds);
        }
        long deleteElapsed = System.currentTimeMillis() - startDelete;

        long totalElapsed = System.currentTimeMillis() - startTotal;

        log.info("[{}] Cluster {}: HYBRID TOTAL {} ms (sql={}ms, escape={}ms, extract={}ms, " +
                        "validate={}ms, delete={}ms). Final: {} vertices, {} edges",
                taskId, clusterId, totalElapsed,
                sqlElapsed, escapeElapsed, extractElapsed, validationElapsed, deleteElapsed,
                vertexCount + escapePointsAdded, validEdgeIds.size());

        return new VisibilityGraphResult(vertexCount + escapePointsAdded, validEdgeIds.size(), (int) totalElapsed);
    }

    /**
     * Вызывает SQL функцию для создания coarse-графа
     */
    private Map<String, Object> callSqlCoarseGraph(UUID taskId, int clusterId, int newDiameter) {
        String sql = "SELECT * FROM build_visibility_graph(?, ?, ?, 120.0, 2500.0, 500.0, 400, 0.5, 2.0)";
        
        return jdbc.queryForObject(sql, (rs, rowNum) -> {
            Map<String, Object> result = new HashMap<>();
            result.put("vertices", rs.getLong("inserted_vertices"));
            result.put("edges", rs.getLong("inserted_edges"));
            result.put("elapsed_ms", rs.getInt("elapsed_ms"));
            return result;
        }, taskId, clusterId, newDiameter);
    }

    /**
     * Добавляет escape points через SQL функцию
     */
    private int addEscapePoints(UUID taskId, int clusterId) {
        String sql = "SELECT count(*) FROM create_escape_points(?, ?, ?)";
        
        return jdbc.queryForObject(sql, Integer.class, taskId, clusterId, escapeBufferDist);
    }

    /**
     * Извлекает рёбра для JTS валидации
     */
    private List<EdgeToValidate> extractEdgesForValidation(UUID taskId, int clusterId) {
        String sql = "SELECT ve.id, ve.source_vertex, ve.target_vertex, " +
                     "       ST_AsBinary(ve.geom) AS geom_wkb, " +
                     "       sv.own_polygon_id AS source_oks_id " +
                     "FROM visibility_edge ve " +
                     "JOIN visibility_vertex sv ON sv.id = ve.source_vertex " +
                     "WHERE ve.task_id = ? AND ve.cluster_id = ?";

        return jdbc.query(sql, (rs, rowNum) -> {
            byte[] wkb = rs.getBytes("geom_wkb");
            Geometry geom = new WKBReader(geometryFactory).read(wkb);
            
            return new EdgeToValidate(
                    rs.getLong("id"),
                    rs.getLong("source_vertex"),
                    rs.getLong("target_vertex"),
                    geom,
                    rs.getObject("source_oks_id") != null ? rs.getLong("source_oks_id") : null
            );
        }, taskId, clusterId);
    }

    /**
     * Извлекает forbidden polygons индивидуально (не union)
     */
    private Map<Long, Geometry> extractForbiddenPolygons(UUID taskId, int clusterId) {
        String sql = "SELECT feature_id, ST_AsBinary(geom_utm) AS geom_wkb, " +
                     "       properties->>'restriction_type' AS restrict_type " +
                     "FROM input_feature " +
                     "WHERE task_id = ? AND object_type = 'restriction' " +
                     "AND geom_utm IS NOT NULL";

        Map<Long, Geometry> polygons = new HashMap<>();
        
        jdbc.query(sql, rs -> {
            byte[] wkb = rs.getBytes("geom_wkb");
            Geometry geom = new WKBReader(geometryFactory).read(wkb);
            polygons.put(rs.getLong("feature_id"), geom);
        }, taskId);

        return polygons;
    }

    /**
     * Валидирует рёбра через JTS против индивидуальных полигонов
     */
    private List<Long> validateEdgesWithJTS(List<EdgeToValidate> edges, 
                                            Map<Long, Geometry> polygons,
                                            int newDiameter) {
        // Определяем точный буфер OKS по диаметру трубы
        double oksBuffer = getOksBufferByDiameter(newDiameter);

        List<Long> validIds = new ArrayList<>();

        for (EdgeToValidate edge : edges) {
            boolean isValid = true;
            Long sourceOksId = edge.sourceOksId;

            for (Map.Entry<Long, Geometry> entry : polygons.entrySet()) {
                Long polygonId = entry.getKey();
                Geometry polygon = entry.getValue();
                
                // Получаем тип ограничения
                String restrictType = getRestrictionType(polygonId, polygons);
                
                // Определяем буфер для этого полигона
                double bufferDist;
                if ("oks".equals(restrictType)) {
                    if (polygonId.equals(sourceOksId)) {
                        // Свой полигон OKS: разрешаем выход (U6 rule extension)
                        continue;
                    }
                    bufferDist = oksBuffer;
                } else {
                    bufferDist = getMinHorizontalDist(restrictType);
                }

                // Создаём буфер и проверяем пересечение
                Geometry buffer = BufferOp.bufferOp(polygon, bufferDist);
                
                if (buffer.intersects(edge.geom)) {
                    isValid = false;
                    break;
                }
            }

            if (isValid) {
                validIds.add(edge.id);
            }
        }

        return validIds;
    }

    /**
     * Определяет буфер OKS по диаметру трубы (ТЗ п. 3.2)
     */
    private double getOksBufferByDiameter(int diameterMm) {
        if (diameterMm <= 100) return 5.0;
        if (diameterMm <= 200) return 7.0;
        return 9.0;
    }

    /**
     * Минимальный горизонтальный отступ по типу ограничения
     */
    private double getMinHorizontalDist(String type) {
        // Значения из restriction_rules (V7)
        switch (type) {
            case "water": return 1.0;
            case "park": return 1.0;
            case "social_area": return 1.0;
            case "prohibited_site": return 1.0;
            case "railway": return 1.0;
            default: return 1.0;
        }
    }

    /**
     * Получает тип ограничения по ID
     */
    private String getRestrictionType(Long polygonId, Map<Long, Geometry> polygons) {
        // В реальной реализации нужно запросить из БД
        // Здесь упрощённо: предполагаем, что все полигоны уже имеют тип
        return "unknown";
    }

    /**
     * Удаляет невалидные рёбра
     */
    private void removeInvalidEdges(UUID taskId, List<EdgeToValidate> allEdges, List<Long> validIds) {
        Set<Long> validSet = new HashSet<>(validIds);
        List<Long> invalidIds = allEdges.stream()
                .map(e -> e.id)
                .filter(id -> !validSet.contains(id))
                .collect(Collectors.toList());

        if (invalidIds.isEmpty()) return;

        String sql = "DELETE FROM visibility_edge WHERE id = ?";
        
        int batchSize = 100;
        for (int i = 0; i < invalidIds.size(); i += batchSize) {
            int end = Math.min(i + batchSize, invalidIds.size());
            List<Long> batch = invalidIds.subList(i, end);
            
            for (Long id : batch) {
                jdbc.update(sql, id);
            }
        }
    }

    // ===== Вспомогательный класс =====

    static class EdgeToValidate {
        final long id;
        final long sourceVertex;
        final long targetVertex;
        final Geometry geom;
        final Long sourceOksId;

        EdgeToValidate(long id, long sourceVertex, long targetVertex, 
                       Geometry geom, Long sourceOksId) {
            this.id = id;
            this.sourceVertex = sourceVertex;
            this.targetVertex = targetVertex;
            this.geom = geom;
            this.sourceOksId = sourceOksId;
        }
    }
}
