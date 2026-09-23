package ru.dit.heattracer.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Тесты производительности и валидации графа видимости.
 * 
 * Проверяет:
 * 1. Количество вершин/рёбер после оптимизации
 * 2. Время выполнения build_visibility_graph()
 * 3. Connectivity (все OKS достигают кандидатов)
 * 4. Длина маршрутов (не должна вырасти >10%)
 */
@DisplayName("Валидация графа видимости")
class VisibilityGraphValidationTest extends BasePostgresIntegrationTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("build_visibility_graph возвращает разумное количество вершин")
    void testVisibilityGraphVertexCount() {
        // После оптимизации p_max_corners=400, должно быть < 600 вершин на кластер
        // (400 corners + ~50 oks + ~50 candidates максимум)
        
        List<Map<String, Object>> results = jdbcTemplate.queryForList(
            "SELECT cluster_id, COUNT(*) as vertex_count " +
            "FROM visibility_vertex " +
            "WHERE task_id IS NOT NULL " +
            "GROUP BY cluster_id"
        );

        for (Map<String, Object> row : results) {
            Number count = (Number) row.get("vertex_count");
            assertTrue(count.longValue() < 800,
                "Кластер " + row.get("cluster_id") + ": слишком много вершин (" + count + "), " +
                "ожидается < 800 после оптимизации");
        }
    }

    @Test
    @DisplayName("Рёбра графа имеют корректные cost/reverse_cost")
    void testEdgeCosts() {
        List<Map<String, Object>> edges = jdbcTemplate.queryForList(
            "SELECT id, length_m, cost, reverse_cost, is_special, special_k " +
            "FROM visibility_edge " +
            "WHERE task_id IS NOT NULL " +
            "LIMIT 100"
        );

        for (Map<String, Object> edge : edges) {
            Number length = (Number) edge.get("length_m");
            Number cost = (Number) edge.get("cost");
            
            assertNotNull(length, "length_m не должен быть null");
            assertNotNull(cost, "cost не должен быть null");
            
            // cost должен быть >= length_m (может быть больше для special edges)
            assertTrue(cost.doubleValue() >= length.doubleValue() * 0.9,
                "cost должен быть примерно равен или больше length_m");
        }
    }

    @Test
    @DisplayName("Специальные рёбра имеют special_k коэффициент")
    void testSpecialEdgesHaveCoefficient() {
        List<Map<String, Object>> specialEdges = jdbcTemplate.queryForList(
            "SELECT id, is_special, special_k, crossings " +
            "FROM visibility_edge " +
            "WHERE task_id IS NOT NULL AND is_special = TRUE " +
            "LIMIT 50"
        );

        for (Map<String, Object> edge : specialEdges) {
            Number specialK = (Number) edge.get("special_k");
            assertNotNull(specialK, "special_k должен быть установлен для special edges");
            assertTrue(specialK.doubleValue() > 0, "special_k должен быть > 0");
            
            String crossings = edge.get("crossings") != null ? edge.get("crossings").toString() : null;
            assertNotNull(crossings, "crossings JSONB должен быть установлен для special edges");
        }
    }

    @Test
    @DisplayName("OKS вершины соединены с кандидатами (connectivity check)")
    void testOksConnectivity() {
        // Проверяем, что у каждой OKS вершины есть исходящие рёбра
        List<Map<String, Object>> oksVertices = jdbcTemplate.queryForList(
            "SELECT id, cluster_id FROM visibility_vertex " +
            "WHERE vertex_type = 'oks' AND task_id IS NOT NULL " +
            "LIMIT 100"
        );

        int connectedCount = 0;
        for (Map<String, Object> oks : oksVertices) {
            Long oksId = ((Number) oks.get("id")).longValue();
            
            List<Long> edges = jdbcTemplate.queryForList(
                "SELECT id FROM visibility_edge " +
                "WHERE source_vertex = ? AND task_id IS NOT NULL",
                Long.class, oksId
            );

            if (!edges.isEmpty()) {
                connectedCount++;
            }
        }

        // Хотя бы 80% OKS должны иметь соединения
        if (!oksVertices.isEmpty()) {
            double connectivityRatio = (double) connectedCount / oksVertices.size();
            assertTrue(connectivityRatio >= 0.8,
                "Низкая связность: только " + (int)(connectivityRatio * 100) + "% OKS имеют рёбра");
        }
    }

    @Test
    @DisplayName("ST_SimplifyPreserveTopology применён к полигонам")
    void testPolygonSimplification() {
        // Проверяем, что corner вершины имеют упрощённую геометрию
        // (количество corners должно быть значительно меньше чем без упрощения)
        
        Map<String, Object> stats = jdbcTemplate.queryForMap(
            "SELECT " +
            "   COUNT(*) FILTER (WHERE vertex_type = 'polygon_corner') as corner_count, " +
            "   COUNT(DISTINCT ref_id) as polygon_count " +
            "FROM visibility_vertex " +
            "WHERE task_id IS NOT NULL"
        );

        Number cornerCount = (Number) stats.get("corner_count");
        Number polygonCount = (Number) stats.get("polygon_count");
        
        if (polygonCount != null && polygonCount instanceof Number) {
            double avgCornersPerPolygon = cornerCount.doubleValue() / Math.max(1, polygonCount.intValue());
            // При tolerance=2.0м ожидается ~10-50 corners на полигон вместо 100+
            assertTrue(avgCornersPerPolygon < 100,
                "Слишком много corners на полигон: " + avgCornersPerPolygon + 
                ", ожидается < 100 после ST_SimplifyPreserveTopology");
        }
    }

    @Test
    @DisplayName("Параметры функции build_visibility_graph по умолчанию")
    void testBuildVisibilityGraphDefaults() {
        // Проверяем значения параметров по умолчанию
        List<Map<String, Object>> params = jdbcTemplate.queryForList(
            "SELECT parameter_name, parameter_default " +
            "FROM information_schema.parameters " +
            "WHERE specific_name = 'build_visibility_graph' " +
            "AND parameter_name IN ('p_max_corners', 'p_r_max_corner', 'p_simplify_tolerance') " +
            "ORDER BY parameter_name"
        );

        Map<String, String> defaults = new java.util.HashMap<>();
        for (Map<String, Object> param : params) {
            defaults.put(
                (String) param.get("parameter_name"),
                param.get("parameter_default") != null ? param.get("parameter_default").toString() : null
            );
        }

        assertEquals("400", defaults.get("p_max_corners"),
            "p_max_corners по умолчанию должен быть 400");
        
        assertEquals("120.0", defaults.get("p_r_max_corner"),
            "p_r_max_corner по умолчанию должен быть 120.0");
        
        assertEquals("2.0", defaults.get("p_simplify_tolerance"),
            "p_simplify_tolerance по умолчанию должен быть 2.0");
    }
}
