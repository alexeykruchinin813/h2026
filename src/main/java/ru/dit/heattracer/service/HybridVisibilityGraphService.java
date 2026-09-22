package ru.dit.heattracer.service;

import org.locationtech.jts.geom.*;
import org.locationtech.jts.geom.prep.PreparedGeometry;
import org.locationtech.jts.geom.prep.PreparedGeometryFactory;
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
 * - Применяет U6 rule extension: OKS может выйти из своего полигона
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

        // ===== 4. Извлекаем forbidden polygons с параметрами из restriction_rules =====
        long startForbidden = System.currentTimeMillis();
        Map<Long, RestrictionInfo> forbiddenPolygons = extractForbiddenPolygons(taskId, clusterId);
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
            try {
                byte[] wkb = rs.getBytes("geom_wkb");
                Geometry geom = new WKBReader(geometryFactory).read(wkb);
                
                return new EdgeToValidate(
                        rs.getLong("id"),
                        rs.getLong("source_vertex"),
                        rs.getLong("target_vertex"),
                        geom,
                        rs.getObject("source_oks_id") != null ? rs.getLong("source_oks_id") : null
                );
            } catch (org.locationtech.jts.io.ParseException e) {
                throw new RuntimeException("Failed to parse WKB geometry for edge", e);
            }
        }, taskId, clusterId);
    }

    /**
     * Извлекает forbidden polygons индивидуально с параметрами из restriction_rules
     */
    private Map<Long, RestrictionInfo> extractForbiddenPolygons(UUID taskId, int clusterId) {
        String sql = "SELECT f.feature_id, ST_AsBinary(f.geom_utm) AS geom_wkb, " +
                     "       f.properties->>'restriction_type' AS restrict_type, " +
                     "       rr.min_horizontal_dist, rr.crossing_forbidden, " +
                     "       rr.special_pass_allowed, rr.special_k " +
                     "FROM input_feature f " +
                     "LEFT JOIN restriction_rules rr ON rr.type = f.properties->>'restriction_type' " +
                     "WHERE f.task_id = ? AND f.object_type = 'restriction' " +
                     "  AND f.geom_utm IS NOT NULL " +
                     "  AND COALESCE(rr.crossing_forbidden, FALSE) = TRUE";

        Map<Long, RestrictionInfo> polygons = new HashMap<>();
        
        jdbc.query(sql, rs -> {
            try {
                byte[] wkb = rs.getBytes("geom_wkb");
                Geometry geom = new WKBReader(geometryFactory).read(wkb);
                
                RestrictionInfo info = new RestrictionInfo(
                    rs.getLong("feature_id"),
                    rs.getString("restrict_type"),
                    geom,
                    rs.getDouble("min_horizontal_dist"),
                    rs.getBoolean("crossing_forbidden"),
                    rs.getBoolean("special_pass_allowed"),
                    rs.getDouble("special_k")
                );
                polygons.put(rs.getLong("feature_id"), info);
            } catch (org.locationtech.jts.io.ParseException e) {
                log.warn("Failed to parse WKB geometry for restriction {}", rs.getLong("feature_id"), e);
            }
        }, taskId);

        return polygons;
    }

    /**
     * Класс с информацией об ограничении
     */
    static class RestrictionInfo {
        final long id;
        final String type;
        final Geometry geom;
        final double minHorizontalDist;
        final boolean crossingForbidden;
        final boolean specialPassAllowed;
        final double specialK;

        RestrictionInfo(long id, String type, Geometry geom, double minHorizontalDist,
                       boolean crossingForbidden, boolean specialPassAllowed, double specialK) {
            this.id = id;
            this.type = type;
            this.geom = geom;
            this.minHorizontalDist = minHorizontalDist;
            this.crossingForbidden = crossingForbidden;
            this.specialPassAllowed = specialPassAllowed;
            this.specialK = specialK;
        }

        /**
         * Возвращает буфер для данного ограничения
         * @param oksBufferForDu буфер OKS по диаметру трубы
         */
        double getBuffer(double oksBufferForDu) {
            if ("oks".equals(type)) {
                return oksBufferForDu;
            }
            return minHorizontalDist;
        }
    }

    /**
     * Валидирует рёбра через JTS против индивидуальных полигонов с PreparedGeometry + STRtree
     */
    private List<Long> validateEdgesWithJTS(List<EdgeToValidate> edges, 
                                            Map<Long, RestrictionInfo> polygons,
                                            int newDiameter) {
        // Определяем точный буфер OKS по диаметру трубы
        double oksBuffer = getOksBufferByDiameter(newDiameter);

        // Строим PreparedGeometry для каждого полигона (с буферизацией)
        Map<Long, PreparedGeometry> preparedGeometries = new HashMap<>();
        Map<Long, Double> bufferDistances = new HashMap<>();
        
        for (Map.Entry<Long, RestrictionInfo> entry : polygons.entrySet()) {
            Long polygonId = entry.getKey();
            RestrictionInfo info = entry.getValue();
            
            double bufferDist = info.getBuffer(oksBuffer);
            Geometry bufferedGeom = BufferOp.bufferOp(info.geom, bufferDist);
            PreparedGeometry preparedGeom = PreparedGeometryFactory.prepare(bufferedGeom);
            
            preparedGeometries.put(polygonId, preparedGeom);
            bufferDistances.put(polygonId, bufferDist);
        }

        // Строим STRtree для быстрого поиска близких полигонов
        STRtree strTree = new STRtree();
        for (Map.Entry<Long, RestrictionInfo> entry : polygons.entrySet()) {
            Long polygonId = entry.getKey();
            RestrictionInfo info = entry.getValue();
            
            // Добавляем в tree с расширенным envelope для поиска
            Geometry bufferedGeom = BufferOp.bufferOp(info.geom, bufferDistances.get(polygonId));
            strTree.insert(bufferedGeom.getEnvelopeInternal(), polygonId);
        }

        List<Long> validIds = new ArrayList<>();

        for (EdgeToValidate edge : edges) {
            boolean isValid = true;
            Long sourceOksId = edge.sourceOksId;

            // Query tree для кандидатов
            @SuppressWarnings("unchecked")
            List<Long> candidates = strTree.query(edge.geom.getEnvelopeInternal());
            
            for (Long polygonId : candidates) {
                RestrictionInfo info = polygons.get(polygonId);
                
                // Пропускаем собственный oks-полигон (U6 rule)
                if ("oks".equals(info.type) && polygonId.equals(sourceOksId)) {
                    continue;
                }
                
                PreparedGeometry preparedGeom = preparedGeometries.get(polygonId);
                
                // Проверяем пересечение
                if (preparedGeom.intersects(edge.geom)) {
                    // Для road/tram_tracks проверяем угол пересечения
                    if ("road".equals(info.type) || "tram_tracks".equals(info.type)) {
                        if (!validateCrossingAngle(edge.geom, info.geom, 45.0)) {
                            isValid = false;
                            break;
                        }
                        // Если угол >= 45°, продолжаем проверку других полигонов
                        continue;
                    }
                    
                    // Для остальных forbidden — сразу invalid
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
     * Проверяет угол пересечения line с road/tram_tracks >= minAngle градусов
     * Возвращает true если угол >= minAngle или если не удалось вычислить
     */
    private boolean validateCrossingAngle(Geometry line, Geometry restriction, double minAngleDegrees) {
        try {
            // Находим точку пересечения
            Geometry intersection = line.intersection(restriction);
            
            if (intersection.isEmpty()) {
                // Нет пересечения — угол не требуется
                return true;
            }
            
            // Получаем координату точки пересечения
            Coordinate intersectionPoint;
            if (intersection instanceof Point) {
                intersectionPoint = intersection.getCoordinate();
            } else if (intersection instanceof LineString) {
                // Пересечение по линии — берём середину
                intersectionPoint = ((LineString) intersection).getCoordinateN(0);
            } else {
                // MultiPoint, GeometryCollection — берём первую координату
                intersectionPoint = intersection.getCoordinate();
            }
            
            if (intersectionPoint == null) {
                return true; // Не удалось определить точку, пропускаем
            }
            
            // Вычисляем направление line в точке пересечения
            Coordinate[] lineCoords = ((LineString) line).getCoordinates();
            Coordinate lineDir = findDirectionAtPoint(lineCoords, intersectionPoint);
            
            if (lineDir == null) {
                return true; // Не удалось определить направление line
            }
            
            // Вычисляем направление road/tram в точке пересечения
            Coordinate[] restrictionCoords;
            if (restriction instanceof LineString) {
                restrictionCoords = ((LineString) restriction).getCoordinates();
            } else if (restriction instanceof Polygon) {
                restrictionCoords = ((Polygon) restriction).getExteriorRing().getCoordinates();
            } else {
                return true; // Неизвестный тип геометрии
            }
            
            Coordinate restrictionDir = findDirectionAtPoint(restrictionCoords, intersectionPoint);
            
            if (restrictionDir == null) {
                return true; // Не удалось определить направление restriction
            }
            
            // Считаем угол между направлениями
            double angleRad = Math.atan2(lineDir.y, lineDir.x) - Math.atan2(restrictionDir.y, restrictionDir.x);
            double angleDeg = Math.toDegrees(Math.abs(angleRad));
            
            // Нормализуем угол до [0, 180]
            if (angleDeg > 180.0) {
                angleDeg = 360.0 - angleDeg;
            }
            
            // Проверяем что угол >= minAngle
            return angleDeg >= minAngleDegrees;
            
        } catch (Exception e) {
            log.warn("Failed to validate crossing angle: {}", e.getMessage());
            return true; // fail-safe: при ошибке пропускаем
        }
    }
    
    /**
     * Находит направление геометрии в заданной точке
     */
    private Coordinate findDirectionAtPoint(Coordinate[] coords, Coordinate point) {
        if (coords.length < 2) {
            return null;
        }
        
        // Находим ближайший сегмент к точке
        double minDist = Double.MAX_VALUE;
        int closestSegmentIndex = 0;
        
        for (int i = 0; i < coords.length - 1; i++) {
            double dist = distanceToSegment(point, coords[i], coords[i+1]);
            if (dist < minDist) {
                minDist = dist;
                closestSegmentIndex = i;
            }
        }
        
        // Возвращаем направление сегмента
        Coordinate p1 = coords[closestSegmentIndex];
        Coordinate p2 = coords[closestSegmentIndex + 1];
        
        double dx = p2.x - p1.x;
        double dy = p2.y - p1.y;
        double len = Math.sqrt(dx*dx + dy*dy);
        
        if (len == 0) {
            return null;
        }
        
        return new Coordinate(dx / len, dy / len);
    }
    
    /**
     * Расстояние от точки до сегмента
     */
    private double distanceToSegment(Coordinate point, Coordinate p1, Coordinate p2) {
        double dx = p2.x - p1.x;
        double dy = p2.y - p1.y;
        double lenSq = dx*dx + dy*dy;
        
        if (lenSq == 0) {
            return point.distance(p1);
        }
        
        double t = Math.max(0, Math.min(1, 
            ((point.x - p1.x) * dx + (point.y - p1.y) * dy) / lenSq));
        
        double projX = p1.x + t * dx;
        double projY = p1.y + t * dy;
        
        return Math.sqrt(Math.pow(point.x - projX, 2) + Math.pow(point.y - projY, 2));
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
