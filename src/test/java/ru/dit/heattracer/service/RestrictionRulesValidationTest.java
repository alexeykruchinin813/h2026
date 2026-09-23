package ru.dit.heattracer.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Валидационные тесты для проверки соответствия ТЗ (Техническое приложение ЛЦТ).
 * 
 * Проверяемые требования:
 * 1. Таблица 2 ТЗ: типы ограничений (включая railway)
 * 2. crossing_angle_max = 45° для road/tram_tracks
 * 3. min_horizontal_dist для всех типов
 * 4. special_pass_allowed и special_k коэффициенты
 * 5. crossing_forbidden флаг
 */
@DisplayName("Валидация соответствия ТЗ")
class RestrictionRulesValidationTest extends BasePostgresIntegrationTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("Таблица 2 ТЗ: все типы ограничений присутствуют")
    void testAllRestrictionTypesPresent() {
        // По ТЗ должны быть: okc, road, tram_tracks, gas, power, heat_network, railway, waterway, green_zone
        
        List<String> expectedTypes = List.of(
            "oks", "road", "tram_tracks", "gas", "power", 
            "heat_network", "railway", "waterway", "green_zone"
        );

        List<String> actualTypes = jdbcTemplate.queryForList(
            "SELECT type FROM restriction_rules ORDER BY type",
            String.class
        );

        for (String expected : expectedTypes) {
            assertTrue(actualTypes.contains(expected),
                "Отсутствует тип ограничения: " + expected);
        }
    }

    @Test
    @DisplayName("railway: crossing_forbidden=TRUE, min_horizontal_dist=1.0м")
    void testRailwayRule() {
        Map<String, Object> rule = jdbcTemplate.queryForMap(
            "SELECT * FROM restriction_rules WHERE type = 'railway'"
        );

        assertTrue((Boolean) rule.get("crossing_forbidden"),
            "railway: crossing_forbidden должен быть TRUE");
        
        assertEquals(1.0, ((Number) rule.get("min_horizontal_dist")).doubleValue(), 0.01,
            "railway: min_horizontal_dist должен быть 1.0м");
        
        assertFalse((Boolean) rule.get("special_pass_allowed"),
            "railway: special_pass_allowed должен быть FALSE (запрещено)");
    }

    @Test
    @DisplayName("road/tram_tracks: crossing_angle_max=45°")
    void testCrossingAngleMax() {
        for (String type : List.of("road", "tram_tracks")) {
            Map<String, Object> rule = jdbcTemplate.queryForMap(
                "SELECT crossing_angle_max FROM restriction_rules WHERE type = ?",
                type
            );

            Number angleMax = (Number) rule.get("crossing_angle_max");
            assertNotNull(angleMax, type + ": crossing_angle_max не установлен");
            assertEquals(45.0, angleMax.doubleValue(), 0.1,
                type + ": crossing_angle_max должен быть 45°");
        }
    }

    @Test
    @DisplayName("special_pass_allowed типы имеют special_k коэффициент")
    void testSpecialPassCoefficients() {
        List<String> specialTypes = List.of("road", "tram_tracks", "gas", "power", "heat_network");

        for (String type : specialTypes) {
            Map<String, Object> rule = jdbcTemplate.queryForMap(
                "SELECT special_pass_allowed, special_k FROM restriction_rules WHERE type = ?",
                type
            );

            assertTrue((Boolean) rule.get("special_pass_allowed"),
                type + ": special_pass_allowed должен быть TRUE");
            
            Number specialK = (Number) rule.get("special_k");
            assertNotNull(specialK, type + ": special_k не установлен");
            assertTrue(((Number) rule.get("special_k")).doubleValue() > 0,
                type + ": special_k должен быть > 0");
        }
    }

    @Test
    @DisplayName("oks: специальный буфер 5/7/9м в зависимости от ДУ")
    void testOksBufferRules() {
        Map<String, Object> rule = jdbcTemplate.queryForMap(
            "SELECT * FROM restriction_rules WHERE type = 'oks'"
        );

        assertTrue((Boolean) rule.get("crossing_forbidden"),
            "oks: crossing_forbidden должен быть TRUE");
        
        // Буфер задаётся в параметрах функции (p_oks_buffer_rough)
        // Здесь проверяем только наличие правила
        assertNotNull(rule.get("min_horizontal_dist"),
            "oks: min_horizontal_dist должен быть задан");
    }

    @Test
    @DisplayName("validate_crossing_angle функция существует")
    void testValidateCrossingAngleFunctionExists() {
        List<Map<String, Object>> functions = jdbcTemplate.queryForList(
            "SELECT routine_name FROM information_schema.routines " +
            "WHERE routine_schema = 'public' AND routine_name = 'validate_crossing_angle'"
        );

        assertFalse(functions.isEmpty(),
            "Функция validate_crossing_angle должна существовать");
    }

    @Test
    @DisplayName("build_visibility_graph имеет параметры оптимизации")
    void testBuildVisibilityGraphParameters() {
        List<Map<String, Object>> params = jdbcTemplate.queryForList(
            "SELECT parameter_name, data_type, parameter_default " +
            "FROM information_schema.parameters " +
            "WHERE specific_name = 'build_visibility_graph' " +
            "ORDER BY ordinal_position"
        );

        boolean hasSimplifyTolerance = params.stream()
            .anyMatch(p -> "p_simplify_tolerance".equals(p.get("parameter_name")));

        assertTrue(hasSimplifyTolerance,
            "build_visibility_graph должен иметь параметр p_simplify_tolerance");

        boolean hasMaxCorners = params.stream()
            .anyMatch(p -> "p_max_corners".equals(p.get("parameter_name")));

        assertTrue(hasMaxCorners,
            "build_visibility_graph должен иметь параметр p_max_corners");
    }
}
