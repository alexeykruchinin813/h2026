package ru.dit.heattracer.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LineString;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.lang.reflect.Method;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Тесты P0-6/P0-7 для {@link HybridVisibilityGraphService}:
 * загрузка restriction_rules и валидация углов пересечения ≥45°.
 */
class HybridVisibilityGraphServiceTest extends BasePostgresIntegrationTest {

    private static final GeometryFactory GF = new GeometryFactory();
    private static final double MIN_ANGLE_DEG = 45.0;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private HybridVisibilityGraphService hybridService;

    /**
     * Вызывает приватный метод сервиса через рефлексию (метод не является API).
     */
    private static Object invokePrivate(HybridVisibilityGraphService svc, String name,
                                        Class<?>[] types, Object... args) throws Exception {
        Method m = HybridVisibilityGraphService.class.getDeclaredMethod(name, types);
        m.setAccessible(true);
        return m.invoke(svc, args);
    }

    @Test
    @DisplayName("P0-1: extractForbiddenPolygons загружает только crossing_forbidden из restriction_rules")
    void extractForbiddenPolygons_shouldLoadRulesFromDb() throws Exception {
        UUID taskId = UUID.randomUUID();
        jdbc.update("INSERT INTO task (id, status) VALUES (?, 'RUNNING')", taskId);
        // water: crossing_forbidden=TRUE → должен попасть в выборку
        insertRestriction(taskId, "9001", "water");
        // road: crossing_forbidden=FALSE → НЕ должен попасть (фильтр в SQL)
        insertRestriction(taskId, "9002", "road");

        @SuppressWarnings("unchecked")
        java.util.Map<Long, ?> map = (java.util.Map<Long, ?>) invokePrivate(
                hybridService, "extractForbiddenPolygons",
                new Class[]{UUID.class, int.class}, taskId, 1);

        assertEquals(1, map.size(), "Должен быть загружен только 1 crossing_forbidden полигон (water)");

        Object info = map.get(9001L);
        assertTrue(info != null, "Полигон water должен быть доступен по feature_id=9001");
        assertEquals("water", readField(info, "type"), "Тип ограничения должен совпадать с restriction_type");
        assertEquals(1.0, (double) readField(info, "minHorizontalDist"), 1e-9,
                "min_horizontal_dist должен прийти из restriction_rules");

        jdbc.update("DELETE FROM input_feature WHERE task_id = ?", taskId);
        jdbc.update("DELETE FROM task WHERE id = ?", taskId);
    }

    /**
     * Вставляет тестовый полигон ограничения с указанным restriction_type.
     */
    private void insertRestriction(UUID taskId, String featureId, String restrictionType) {
        jdbc.update(
                "INSERT INTO input_feature (task_id, feature_id, object_type, properties, geom_utm) "
                        + "VALUES (?, ?, 'restriction', ?::jsonb, "
                        + " ST_SetSRID(ST_GeomFromText('POLYGON((2 2, 3 2, 3 3, 2 3, 2 2))'), 32637))",
                taskId, featureId, "{\"restriction_type\": \"" + restrictionType + "\"}");
    }

    /**
     * Читает поле объекта через рефлексию (package-private поля вложенных классов сервиса).
     */
    private static Object readField(Object target, String name) throws Exception {
        var f = target.getClass().getDeclaredField(name);
        f.setAccessible(true);
        return f.get(target);
    }

    @Test
    @DisplayName("P0-3: validateCrossingAngle — угол 90° проходит валидацию")
    void validateCrossingAngle_shouldAcceptPerpendicular() throws Exception {
        LineString edge = GF.createLineString(new Coordinate[]{
                new Coordinate(0, 5), new Coordinate(10, 5)});          // направление: X
        LineString road = GF.createLineString(new Coordinate[]{
                new Coordinate(5, 0), new Coordinate(5, 10)});          // направление: Y, угол 90°

        boolean ok = (Boolean) invokePrivate(hybridService, "validateCrossingAngle",
                new Class[]{org.locationtech.jts.geom.Geometry.class,
                        org.locationtech.jts.geom.Geometry.class, double.class},
                edge, road, MIN_ANGLE_DEG);

        assertTrue(ok, "Перпендикулярное пересечение (90° >= 45°) должно быть разрешено");
    }

    @Test
    @DisplayName("P0-3: validateCrossingAngle — острый угол 30° отбраковывается")
    void validateCrossingAngle_shouldRejectAcuteAngle() throws Exception {
        LineString edge = GF.createLineString(new Coordinate[]{
                new Coordinate(0, 0), new Coordinate(10, 0)});          // направление: X
        // road под углом 30° к оси X (пересекает edge в точке 5,0)
        double a = Math.toRadians(30);
        LineString road = GF.createLineString(new Coordinate[]{
                new Coordinate(5 - 5 * Math.cos(a), -5 * Math.sin(a)),
                new Coordinate(5 + 5 * Math.cos(a), 5 * Math.sin(a))});

        boolean ok = (Boolean) invokePrivate(hybridService, "validateCrossingAngle",
                new Class[]{org.locationtech.jts.geom.Geometry.class,
                        org.locationtech.jts.geom.Geometry.class, double.class},
                edge, road, MIN_ANGLE_DEG);

        assertFalse(ok, "Пересечение под углом ~30° (< 45°) должно быть запрещено");
    }

    @Test
    @DisplayName("P0-3: validateCrossingAngle — fail-safe при отсутствии пересечения")
    void validateCrossingAngle_shouldFailSafeWhenNoIntersection() throws Exception {
        LineString edge = GF.createLineString(new Coordinate[]{
                new Coordinate(0, 0), new Coordinate(10, 0)});
        LineString road = GF.createLineString(new Coordinate[]{
                new Coordinate(0, 100), new Coordinate(10, 100)});

        boolean ok = (Boolean) invokePrivate(hybridService, "validateCrossingAngle",
                new Class[]{org.locationtech.jts.geom.Geometry.class,
                        org.locationtech.jts.geom.Geometry.class, double.class},
                edge, road, MIN_ANGLE_DEG);

        assertTrue(ok, "При ошибке/отсутствии пересечения метод обязан вернуть true (fail-safe)");
    }
}
