package ru.dit.heattracer.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Тесты поиска путей по графу видимости через pgRouting ({@link PathFinderService}).
 *
 * <p>Покрывают требования ТЗ:
 * <ul>
 *   <li>существующий путь возвращается с корректной длиной и геометрией;</li>
 *   <li>несвязная вершина даёт notFound (без исключения);</li>
 *   <li>findPathsFromOks возвращает пути до всех достижимых кандидатов.</li>
 * </ul>
 */
class PathFinderServiceTest extends BasePostgresIntegrationTest {

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PathFinderService pathFinder;

    /**
     * Создает задачу, три вершины (oks + два candidate) и одно ребро 101→102.
     *
     * <p>cluster_id = 0 намеренно: production-код {@code FastVisibilityGraphService.insertEdges}
     * пишет рёбра с cluster_id = 0, поэтому тестовые данные должны соответствовать прод-поведению,
     * иначе pgrouting-функция не увидит рёбра в подграфе кластера.
     *
     * @return идентификатор созданной задачи
     */
    private UUID seedSimpleGraph() {
        // Reused Testcontainers: если прошлый прогон упал до cleanup(),
        // строки с id 101..103 остались в БД. Удаляем их до вставки,
        // иначе INSERT падает с DuplicateKey на жёстко зашитых id.
        jdbc.update("DELETE FROM visibility_edge "
                + "WHERE source_vertex IN (101, 102, 103) "
                + "   OR target_vertex IN (101, 102, 103)");
        jdbc.update("DELETE FROM visibility_vertex WHERE id IN (101, 102, 103)");

        UUID taskId = UUID.randomUUID();
        jdbc.update("INSERT INTO task (id, status) VALUES (?, 'RUNNING')", taskId);
        jdbc.update("INSERT INTO visibility_vertex (id, task_id, cluster_id, vertex_type, geom) "
                        + "VALUES (101, ?, 0, 'oks', ST_SetSRID(ST_MakePoint(0, 0), 32637))", taskId);
        jdbc.update("INSERT INTO visibility_vertex (id, task_id, cluster_id, vertex_type, geom) "
                        + "VALUES (102, ?, 0, 'candidate', ST_SetSRID(ST_MakePoint(100, 0), 32637))", taskId);
        jdbc.update("INSERT INTO visibility_vertex (id, task_id, cluster_id, vertex_type, geom) "
                        + "VALUES (103, ?, 0, 'candidate', ST_SetSRID(ST_MakePoint(500, 0), 32637))", taskId);
        jdbc.update("INSERT INTO visibility_edge (task_id, cluster_id, source_vertex, target_vertex, "
                        + "geom, length_m, cost, reverse_cost) VALUES (?, 0, 101, 102, "
                        + "ST_SetSRID(ST_MakeLine(ST_MakePoint(0, 0), ST_MakePoint(100, 0)), 32637), "
                        + "100, 100, 100)", taskId);
        return taskId;
    }

    private void cleanup(UUID taskId) {
        jdbc.update("DELETE FROM visibility_edge WHERE task_id = ?", taskId);
        jdbc.update("DELETE FROM visibility_vertex WHERE task_id = ?", taskId);
        jdbc.update("DELETE FROM task WHERE id = ?", taskId);
    }

    @Test
    @DisplayName("findPath находит путь oks→candidate длиной 100 м")
    void findPath_shouldReturnReachablePath() {
        UUID taskId = seedSimpleGraph();
        try {
            var result = pathFinder.findPath(taskId, 0, 101L, 102L);

            assertTrue(result.isFound(), "Путь 101→102 должен существовать");
            assertEquals(1, result.getEdgeCount(), "Путь состоит из одного ребра");
            assertEquals(100.0, result.getTotalLength(), 1e-6, "Длина пути — 100 м");
            assertFalse(result.getPathWkt() == null || result.getPathWkt().isEmpty(),
                    "Геометрия пути должна быть заполнена");
        } finally {
            cleanup(taskId);
        }
    }

    @Test
    @DisplayName("findPath возвращает notFound для изолированной вершины (без исключения)")
    void findPath_shouldReturnNotFoundForDisconnectedVertex() {
        UUID taskId = seedSimpleGraph();
        try {
            var result = pathFinder.findPath(taskId, 0, 101L, 103L); // 103 не соединена

            assertFalse(result.isFound(), "Пути до изолированной вершины 103 быть не должно");
        } finally {
            cleanup(taskId);
        }
    }

    @Test
    @DisplayName("findPathsFromOks возвращает только достижимые кандидаты")
    void findPathsFromOks_shouldReturnOnlyReachableCandidates() {
        UUID taskId = seedSimpleGraph();
        try {
            List<?> paths = pathFinder.findPathsFromOks(taskId, 0, 101L);

            assertEquals(1, paths.size(), "Из двух кандидатов достижим ровно один");
        } finally {
            cleanup(taskId);
        }
    }
}
