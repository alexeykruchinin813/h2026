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
        
        // Канонические имена типов из Таблицы 2 (см. V7/V13 и InputValidator):
        // в БД используются gas_pipeline / power_cable / water, а НЕ gas / power / waterway.
        List<String> expectedTypes = List.of(
            "oks", "road", "tram_tracks", "gas_pipeline", "power_cable",
            "heat_network", "railway", "water", "park", "social_area", "prohibited_site"
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
        // gas_pipeline / power_cable — канонические имена типов (ВМЕСТО gas / power)
        List<String> specialTypes = List.of("road", "tram_tracks", "gas_pipeline", "power_cable", "heat_network");

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

        // V13: for oks min_horizontal_dist is intentionally NULL —
        // the allowed distance depends on the new pipe diameter (dynamic).
        assertNull(rule.get("min_horizontal_dist"),
            "oks: min_horizontal_dist должен быть NULL (динамический отступ по ДУ)");

        assertEquals(5.0, ((Number) rule.get("min_dist_lt_500")).doubleValue(), 0.01,
            "oks: min_dist_lt_500 = 5.0м");
        assertEquals(7.0, ((Number) rule.get("min_dist_500_800")).doubleValue(), 0.01,
            "oks: min_dist_500_800 = 7.0м");
        assertEquals(9.0, ((Number) rule.get("min_dist_ge_900")).doubleValue(), 0.01,
            "oks: min_dist_ge_900 = 9.0м");
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
        // В PostgreSQL information_schema.parameters.specific_name = "<имя>_<OID>",
        // поэтому фильтруем через JOIN с routines по routine_name.
        List<Map<String, Object>> params = jdbcTemplate.queryForList(
            "SELECT p.parameter_name, p.data_type, p.parameter_default " +
            "FROM information_schema.parameters p " +
            "JOIN information_schema.routines r " +
            "  ON p.specific_name = r.specific_name " +
            " AND p.specific_schema = r.specific_schema " +
            "WHERE r.routine_schema = 'public' " +
            "  AND r.routine_name = 'build_visibility_graph' " +
            "ORDER BY p.ordinal_position"
        );

        assertFalse(params.isEmpty(),
            "Параметры build_visibility_graph не найдены");

        java.util.Set<String> names = new java.util.HashSet<>();
        for (Map<String, Object> p : params) {
            names.add((String) p.get("parameter_name"));
        }

        assertTrue(names.contains("p_simplify_tolerance"),
            "build_visibility_graph должен иметь параметр p_simplify_tolerance");
        assertTrue(names.contains("p_max_corners"),
            "build_visibility_graph должен иметь параметр p_max_corners");
        assertTrue(names.contains("p_oks_buffer_rough"),
            "build_visibility_graph должен иметь параметр p_oks_buffer_rough");

        // Каноническая перегрузка должна быть единственной (V29):
        // иначе возможно неоднозначное разрешение при вызове.
        Long overloads = jdbcTemplate.queryForObject(
            "SELECT count(*) FROM pg_proc WHERE proname = 'build_visibility_graph'",
            Long.class);
        assertEquals(1L, overloads,
            "Должна быть ровно одна перегрузка build_visibility_graph");
    }
}
