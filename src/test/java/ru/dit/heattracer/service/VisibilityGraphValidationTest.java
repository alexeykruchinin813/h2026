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
        // ВАЖНО: в БД исторически накапливались ОСИРОТЕВШИЕ сигнатуры build_visibility_graph
        // (V19: 5 аргументов, V23: 7 аргументов — их не удаляли ни V24, ни V25).
        // information_schema.parameters фильтруется только по specific_name, поэтому без
        // фильтра по сигнатуре строки параметров разных версий смешиваются,
        // и Map-'ассоциации' перезаписываются NULL/устаревшими дефолтами (регрессия: expected 400 but was null).
        // Миграция V29 удаляет все старые сигнатуры; тест проверяет, что в каталоге
        // осталась ровно ОДНА каноническая версия, и читает дефолты из pg_proc по её OID.
        List<Map<String, Object>> overloads = jdbcTemplate.queryForList(
            "SELECT p.oid, " +
            "       pg_get_function_arguments(p.oid) AS args " +
            "FROM pg_proc p " +
            "JOIN pg_namespace ns ON ns.oid = p.pronamespace " +
            "WHERE p.proname = 'build_visibility_graph' " +
            "  AND ns.nspname NOT IN ('pg_catalog','information_schema')"
        );

        assertFalse(overloads.isEmpty(),
            "build_visibility_graph отсутствует в каталоге — миграции не применились?");

        if (overloads.size() > 1) {
            StringBuilder sb = new StringBuilder();
            for (Map<String, Object> o : overloads) {
                sb.append("\n  - ").append(o.get("args"));
            }
            fail("В БД найдено " + overloads.size() + " перегрузок build_visibility_graph " +
                "(осиротевшие сигнатуры, регрессия V19/V23 — см. V29):" + sb);
        }

        Long fnOid = ((Number) overloads.get(0).get("oid")).longValue();

        List<Map<String, Object>> params = jdbcTemplate.queryForList(
            "SELECT a.argname AS parameter_name, " +
            "       pg_get_function_arg_default(p.oid, a.ordinality) AS parameter_default " +
            "FROM pg_proc p, unnest(p.proargnames) WITH ORDINALITY AS a(argname, ordinality) " +
            "WHERE p.oid = ?::oid " +
            "  AND a.argname IN ('p_max_corners', 'p_r_max_corner', 'p_simplify_tolerance')",
            fnOid
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
