package ru.dit.heattracer.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Интеграционный тест связности гибридного графа на конкурсном наборе данных (P0-4).
 *
 * Запускает полный production-пайплайн через {@link TaskService#submit}, дожидается завершения
 * и проверяет метрику связности: количество ОКС, имеющих хотя бы одно ребро
 * к "чужим" вершинам (escape, candidate или чужие polygon_corner).
 *
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

        // ===== P1-1: пути A* (V42) =====
        Integer oksWithPath = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM path_result WHERE task_id = ?",
                Integer.class, taskId);

        assertNotNull(oksWithPath, "Запрос path_result не должен возвращать null");

        System.out.printf("[P1-1 METRIC] ОКС с найденным путём в path_result: %d из %d%n",
                oksWithPath, totalOksCount);

        // Регрессионный барьер: после V41 факт = 17/17; допускаем 1 не найденный на всякий случай.
        int minWithPath = Math.min(16, totalOksCount);
        assertTrue(oksWithPath >= minWithPath,
                String.format("Недостаточно OKS с путём: только %d из %d (минимум %d). " +
                                "Проверь create_escape_points (V41) и find_best_path_from_oks (V21).",
                        oksWithPath, totalOksCount, minWithPath));
    }
}