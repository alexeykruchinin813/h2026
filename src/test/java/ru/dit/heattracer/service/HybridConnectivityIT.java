package ru.dit.heattracer.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Интеграционный тест связности гибридного графа на конкурсном наборе данных (P0-4).
 * Запускает полный production-пайплайн через {@link TaskService#submit}, дожидается завершения
 * и проверяет метрику связности: количество ОКС, имеющих хотя бы одно ребро
 * к "чужим" вершинам (escape, candidate или чужие polygon_corner).
 * Цель P0: >= 10 из 17 ОКС.
 */
@DisplayName("Интеграционный тест связности гибридного графа (P0-4)")
class HybridConnectivityIT extends BasePostgresIntegrationTest {

    private static final int P0_TARGET_CONNECTED = 16;
    private static final String TEST_DATASET_RESOURCE = "first_dataset.geojson";
    private static final long TIMEOUT_MS = 180_000; // 5 минут: escape-этап ~75с/кластер до оптимизации V33
    private static final long POLL_INTERVAL_MS = 1_000;

    @Autowired
    private TaskService taskService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("Полный пайплайн: связность ОКС на конкурсном наборе (цель P0: >= 10)")
    void testOksConnectivityOnFirstDataset() throws Exception {
        // 1. Загрузка тестового датасета (эмулируем загрузку через REST API)
        ClassPathResource resource = new ClassPathResource(TEST_DATASET_RESOURCE);
        MockMultipartFile file = new MockMultipartFile(
                "file", "first_dataset.geojson", "application/json", resource.getInputStream());

        // 2. Запуск асинхронного пайплайна
        UUID taskId = taskService.submit(file);
        assertNotNull(taskId, "submit() должен вернуть UUID задачи");

        // 3. Ожидание завершения задачи (polling).
        // Используем var, чтобы не зависеть от модификаторов доступа класса TaskState и его Enum.
        long startTime = System.currentTimeMillis();
        boolean isDone = false;
        boolean isFailed = false;
        String errorMessage = null;

        while (System.currentTimeMillis() - startTime < TIMEOUT_MS) {
            var optState = taskService.get(taskId);
            if (optState.isPresent()) {
                var state = optState.get();
                String statusStr = state.getStatus().toString();

                if ("DONE".equals(statusStr)) {
                    isDone = true;
                    break;
                } else if ("FAILED".equals(statusStr)) {
                    isFailed = true;
                    errorMessage = state.getErrorMessage();
                    break;
                }
            }
            Thread.sleep(POLL_INTERVAL_MS);
        }

        assertTrue(isDone || isFailed,
                "Задача не завершилась за отведённое время (таймаут " + TIMEOUT_MS + " мс)");
        assertTrue(isDone,
                "Задача должна завершиться успешно. Ошибка пайплайна: " + errorMessage);

        // 4. Инвариант P0-3b: SRID графа должен быть 32637 (метры), а не 4326 (градусы)
        Integer srid = jdbcTemplate.queryForObject(
                "SELECT Find_SRID('public', 'visibility_vertex', 'geom')", Integer.class);
        assertNotNull(srid, "SRID геометрии вершин не должен быть null");
        assertEquals(32637, srid, "SRID должен быть 32637 (метры), иначе эвристика A* сломается");

        // 5. Подсчёт общего количества ОКС и рёбер
        Integer totalOksCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM visibility_vertex WHERE task_id = ? AND vertex_type = 'oks'",
                Integer.class, taskId);

        Long totalEdges = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM visibility_edge WHERE task_id = ?",
                Long.class, taskId);

        assertNotNull(totalOksCount, "Общее количество ОКС не должно быть null");
        assertNotNull(totalEdges, "Количество рёбер не должно быть null");
        assertTrue(totalEdges > 0, "Гибридный граф должен содержать рёбра после построения");

        // 6. Подсчёт ОКС, имеющих выход к чужим вершинам
        // Чужая вершина: либо не oks, либо oks с другим own_polygon_id (согласно схеме V14)
        Integer connectedOksCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(DISTINCT v1.id) " +
                        "FROM visibility_vertex v1 " +
                        "JOIN visibility_edge e ON e.source_vertex = v1.id OR e.target_vertex = v1.id " +
                        "JOIN visibility_vertex v2 ON (e.target_vertex = v2.id AND e.source_vertex = v1.id) " +
                        "                       OR (e.source_vertex = v2.id AND e.target_vertex = v1.id) " +
                        "WHERE v1.task_id = ? " +
                        "  AND v1.vertex_type = 'oks' " +
                        "  AND (v2.vertex_type != 'oks' OR v2.own_polygon_id IS DISTINCT FROM v1.own_polygon_id)",
                Integer.class, taskId);

        assertNotNull(connectedOksCount, "Запрос связности не должен возвращать null");

        System.out.printf("[P0-4 METRIC] Связность ОКС: %d из %d имеют рёбра к чужим вершинам. Всего рёбер: %d%n",
                connectedOksCount, totalOksCount, totalEdges);

        // WARN, но не FAIL: полная связность (17/17) — текущий факт, любой откат от неё фиксируем.
        if (connectedOksCount < totalOksCount) {
            System.out.printf("[P0-4 WARN] %d ОКС без рёбер к чужим вершинам — регресс от V34 (17/17). " +
                            "Проверь escape_points и валидатор мостов.%n",
                    totalOksCount - connectedOksCount);
        }

        // 7. Проверка метрики P0
        int targetConnected = Math.min(P0_TARGET_CONNECTED, totalOksCount);

        assertTrue(connectedOksCount >= targetConnected,
                String.format("Недостаточная связность графа: только %d из %d ОКС имеют рёбра к чужим вершинам. " +
                                "Цель P0: >= %d. Требуется tuning гибридного валидатора или escape points.",
                        connectedOksCount, totalOksCount, targetConnected));

        // P1-1: пути A* по вариантам (V43: три варианта × N OKS)
        List<Map<String, Object>> perVariant = jdbcTemplate.queryForList(
                "SELECT variant_id, COUNT(DISTINCT oks_vertex_id) AS cnt " +
                        "FROM path_result WHERE task_id = ? " +
                        "GROUP BY variant_id ORDER BY variant_id",
                taskId);

        System.out.println("[P1-1 METRIC] OKS с найденным путём по вариантам:");
        for (Map<String, Object> r : perVariant) {
            System.out.printf("          %s : %s из %d%n",
                    r.get("variant_id"), r.get("cnt"), totalOksCount);
        }

        // Минимум по всем вариантам — консервативный регрессионный барьер
        int minWithPath = Integer.MAX_VALUE;
        for (Map<String, Object> r : perVariant) {
            minWithPath = Math.min(minWithPath, ((Number) r.get("cnt")).intValue());
        }
        if (perVariant.isEmpty()) minWithPath = 0;

        int expectedMin = Math.min(16, totalOksCount);
        assertTrue(minWithPath >= expectedMin,
                String.format("Недостаточно OKS с путём (мин по вариантам): %d из %d (ожидали ≥ %d). " +
                                "Проверь create_escape_points (V41) и findPathsFromOks (V21).",
                        minWithPath, totalOksCount, expectedMin));

        // ===== P2.2: три содержательно отличающихся варианта =====
        List<Map<String, Object>> variants = jdbcTemplate.queryForList(
            "SELECT id, rank, score, construction_cost, new_network_length, " +
                "       unconnected_penalty, unconnected_oks_ids " +
                "FROM variant WHERE task_id = ? ORDER BY rank",
                taskId);

        System.out.printf("[P2-2 METRIC] Вариантов: %d%n", variants.size());
        for (Map<String, Object> v : variants) {
            System.out.printf("          %s rank=%s score=%s cost=%s length=%s%n",
                    v.get("id"), v.get("rank"), v.get("score"),
                    v.get("construction_cost"), v.get("new_network_length"));
        }

        // ТЗ 2.8: «основной + до двух содержательно отличающихся». Дедупликация
        // отсеивает идентичные, поэтому 1..3.
        assertTrue(variants.size() >= 1 && variants.size() <= 3,
                "ТЗ 2.8: 1–3 варианта, получено " + variants.size());

        // Все варианты должны иметь уникальный score (после дедупликации)
        Set<Double> scores = new HashSet<>();
        for (Map<String, Object> v : variants) {
            double s = Math.round(((Number) v.get("score")).doubleValue() * 10000.0) / 10000.0;
            assertTrue(scores.add(s),
                    "После дедупликации score должны быть уникальны, дубликат: " + s);
        }

        // P3.1: на конкурсном наборе все OKS подключены → штраф 0, массив пуст
        for (Map<String, Object> v : variants) {
            Number penalty = (Number) v.get("unconnected_penalty");
            Object ids = v.get("unconnected_oks_ids");
            System.out.printf("          %s unconnected_penalty=%s unconnected_oks_ids=%s%n",
                    v.get("id"), penalty, ids);
            assertEquals(0.0, penalty.doubleValue(), 0.01,
                    "На конкурсном наборе все 17 OKS подключены → штраф 0");
        }
        // rank 1 = минимальный score, score монотонно растёт по rank.
        // Проверяем без жёсткого обращения к get(2): после дедупликации
        // вариантов может быть 1, 2 или 3 (ТЗ 2.8).
        assertEquals(1, ((Number) variants.get(0).get("rank")).intValue(),
                "rank 1 должен быть у минимального score");
        for (int i = 1; i < variants.size(); i++) {
            double prev = ((Number) variants.get(i - 1).get("score")).doubleValue();
            double cur  = ((Number) variants.get(i).get("score")).doubleValue();
            assertTrue(prev <= cur,
                    "Ранжирование нарушено: rank " + i + " score=" + prev +
                            " > rank " + (i + 1) + " score=" + cur);
        }
    }
}