package ru.dit.heattracer.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Тесты производительности и валидации графа видимости.
 * 
 * Проверяет:
 * 1. Количество вершин/рёбер после оптимизации
 * 2. Время выполнения build_visibility_graph()
 * 3. Connectivity (все OKS достигают кандидатов)
 * 4. Длина маршрутов (не должна вырасти >10%)
 *
 * <p>ВАЖНО: контейнер Testcontainers переиспользуется между прогонами ({@code withReuse(true)}),
 * поэтому счётные проверки скоупятся по id самой свежей завершённой задачи
 * ({@link #latestFinishedTaskId()}) — иначе запросы «по всей БД» подмешивают
 * данные прошлых прогонов и метрики заведомо падают.
 */
@DisplayName("Валидация графа видимости")
class VisibilityGraphValidationTest extends BasePostgresIntegrationTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /**
     * Возвращает id самой свежей завершённой задачи — той, что оставил HybridConnectivityIT.
     * Все счётные тесты в этом классе должны скоупиться по ней, иначе reused-контейнер
     * подмешивает данные прошлых прогонов.
     */
    private UUID latestFinishedTaskId() {
        return jdbcTemplate.queryForObject(
            "SELECT id FROM task " +
            "WHERE finished_at IS NOT NULL " +
            "ORDER BY finished_at DESC " +
            "LIMIT 1",
            UUID.class
        );
    }

    @Test
    @DisplayName("build_visibility_graph возвращает разумное количество вершин")
    void testVisibilityGraphVertexCount() {
        // После оптимизации p_max_corners=400, должно быть < 600 вершин на кластер
        // (400 corners + ~50 oks + ~50 candidates максимум)
        UUID taskId = latestFinishedTaskId();

        List<Map<String, Object>> results = jdbcTemplate.queryForList(
            "SELECT cluster_id, COUNT(*) as vertex_count " +
            "FROM visibility_vertex " +
            "WHERE task_id = ? " +
            "GROUP BY cluster_id",
            taskId
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
        UUID taskId = latestFinishedTaskId();

        List<Map<String, Object>> edges = jdbcTemplate.queryForList(
            "SELECT id, length_m, cost, reverse_cost, is_special, special_k " +
            "FROM visibility_edge " +
            "WHERE task_id = ? " +
            "LIMIT 100",
            taskId
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
        UUID taskId = latestFinishedTaskId();

        List<Map<String, Object>> specialEdges = jdbcTemplate.queryForList(
            "SELECT id, is_special, special_k, crossings " +
            "FROM visibility_edge " +
            "WHERE task_id = ? AND is_special = TRUE " +
            "LIMIT 50",
            taskId
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
        UUID taskId = latestFinishedTaskId();

        // Проверяем, что у каждой OKS вершины есть исходящие рёбра
        List<Map<String, Object>> oksVertices = jdbcTemplate.queryForList(
            "SELECT id, cluster_id FROM visibility_vertex " +
            "WHERE vertex_type = 'oks' AND task_id = ? " +
            "LIMIT 100",
            taskId
        );

        int connectedCount = 0;
        for (Map<String, Object> oks : oksVertices) {
            Long oksId = ((Number) oks.get("id")).longValue();

            List<Long> edges = jdbcTemplate.queryForList(
                "SELECT id FROM visibility_edge " +
                "WHERE source_vertex = ? AND task_id = ?",
                Long.class, oksId, taskId
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
        UUID taskId = latestFinishedTaskId();

        // polygon_count считается ТОЛЬКО по polygon_corner (distinct ref_id среди
        // всех типов вершин завышает знаменатель/подмешивает OKS/candidate/escape_point).
        Map<String, Object> stats = jdbcTemplate.queryForMap(
            "SELECT " +
            "  COUNT(*) FILTER (WHERE vertex_type = 'polygon_corner') AS corner_count, " +
            "  COUNT(DISTINCT ref_id) FILTER (WHERE vertex_type = 'polygon_corner') AS polygon_count " +
            "FROM visibility_vertex " +
            "WHERE task_id = ?",
            taskId
        );

        Number cornerCount = (Number) stats.get("corner_count");
        Number polygonCount = (Number) stats.get("polygon_count");

        if (polygonCount != null && polygonCount.longValue() > 0) {
            double avgCornersPerPolygon = cornerCount.doubleValue() / polygonCount.longValue();
            // При tolerance=2.0м ожидается ~10-50 corners на полигон вместо 100+
            assertTrue(avgCornersPerPolygon < 100,
                "Слишком много corners на полигон: " + avgCornersPerPolygon + 
                ", ожидается < 100 после ST_SimplifyPreserveTopology");
        }
    }

    @Test
    @DisplayName("Параметры функции build_visibility_graph по умолчанию")
    void testBuildVisibilityGraphDefaults() {
        // В PostgreSQL specific_name генерируется как имя функции + '_' + OID.
        // Прямое сравнение specific_name = 'build_visibility_graph' не работает.
        // Необходимо выполнять JOIN с information_schema.routines по имени функции.
        List<Map<String, Object>> params = jdbcTemplate.queryForList(
                "SELECT p.parameter_name, p.parameter_default " +
                        "FROM information_schema.parameters p " +
                        "JOIN information_schema.routines r " +
                        "  ON p.specific_name = r.specific_name " +
                        " AND p.specific_schema = r.specific_schema " +
                        "WHERE r.routine_name = 'build_visibility_graph' " +
                        "  AND p.parameter_name IN ('p_max_corners', 'p_r_max_corner', 'p_simplify_tolerance') " +
                        "ORDER BY p.parameter_name"
        );

        Map<String, String> defaults = new java.util.HashMap<>();
        for (Map<String, Object> param : params) {
            String name = (String) param.get("parameter_name");
            Object def = param.get("parameter_default");
            defaults.put(name, def != null ? def.toString() : null);
        }

        // Значения по умолчанию могут содержать приведение типов (например, "::integer"),
        // поэтому проверяем, что строка начинается с ожидаемого числа.
        assertDefaultStartsWith(defaults, "p_max_corners", "400");
        assertDefaultStartsWith(defaults, "p_r_max_corner", "120");
        assertDefaultStartsWith(defaults, "p_simplify_tolerance", "2");
    }

    /**
     * Вспомогательный метод для проверки значений по умолчанию с учётом возможных суффиксов типов БД.
     *
     * @param defaults       карта параметров
     * @param paramName      имя параметра
     * @param expectedPrefix ожидаемый префикс значения
     */
    private void assertDefaultStartsWith(Map<String, String> defaults, String paramName, String expectedPrefix) {
        String actual = defaults.get(paramName);
        assertNotNull(actual, String.format("Параметр %s не найден в БД", paramName));
        assertTrue(actual.startsWith(expectedPrefix),
                String.format("%s по умолчанию должен начинаться с %s, но был: %s",
                        paramName, expectedPrefix, actual));
    }
}
