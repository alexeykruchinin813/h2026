package ru.dit.heattracer.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.index.strtree.STRtree;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Тесты для FastVisibilityGraphService с JTS STRtree.
 *
 * Инфраструктура Testcontainers (проверка Docker, контейнер PostgreSQL+PostGIS+pgRouting,
 * проброс JDBC-свойств в Spring) унаследована из {@link BasePostgresIntegrationTest}.
 */
@DisplayName("FastVisibilityGraphService тесты")
class FastVisibilityGraphServiceTest extends BasePostgresIntegrationTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private FastVisibilityGraphService fastService;
    
    private GeometryFactory geometryFactory;

    @BeforeEach
    void setUp() {
        geometryFactory = new GeometryFactory();
    }

    @Test
    @DisplayName("STRtree строится корректно")
    void testSTRtreeConstruction() {
        List<Geometry> geometries = Arrays.asList(
            geometryFactory.createPolygon(new Coordinate[]{
                new Coordinate(0, 0), new Coordinate(10, 0),
                new Coordinate(10, 10), new Coordinate(0, 10),
                new Coordinate(0, 0)
            }),
            geometryFactory.createPolygon(new Coordinate[]{
                new Coordinate(20, 20), new Coordinate(30, 20),
                new Coordinate(30, 30), new Coordinate(20, 30),
                new Coordinate(20, 20)
            })
        );

        STRtree tree = fastService.buildSTRtreeForTest(geometries);

        assertNotNull(tree);
        assertEquals(2, tree.size());
    }

    @Test
    @DisplayName("Проверка видимости: линия без пересечений")
    void testVisibilityNoIntersection() {
        List<Geometry> forbiddenZones = Arrays.asList(
            geometryFactory.createPolygon(new Coordinate[]{
                new Coordinate(5, 5), new Coordinate(15, 5),
                new Coordinate(15, 15), new Coordinate(5, 15),
                new Coordinate(5, 5)
            })
        );

        STRtree tree = fastService.buildSTRtreeForTest(forbiddenZones);

        // Линия от (0,0) до (4,4) не пересекает запретную зону
        LineString line = geometryFactory.createLineString(
            new Coordinate[]{new Coordinate(0, 0), new Coordinate(4, 4)}
        );

        boolean isVisible = fastService.isVisibleForTest(line, tree);
        assertTrue(isVisible, "Линия должна быть видима (нет пересечений)");
    }

    @Test
    @DisplayName("Проверка видимости: линия с пересечением")
    void testVisibilityWithIntersection() {
        List<Geometry> forbiddenZones = Arrays.asList(
            geometryFactory.createPolygon(new Coordinate[]{
                new Coordinate(5, 5), new Coordinate(15, 5),
                new Coordinate(15, 15), new Coordinate(5, 15),
                new Coordinate(5, 5)
            })
        );

        STRtree tree = fastService.buildSTRtreeForTest(forbiddenZones);

        // Линия от (0,0) до (20,20) пересекает запретную зону
        LineString line = geometryFactory.createLineString(
            new Coordinate[]{new Coordinate(0, 0), new Coordinate(20, 20)}
        );

        boolean isVisible = fastService.isVisibleForTest(line, tree);
        assertFalse(isVisible, "Линия должна быть невидима (есть пересечение)");
    }

    @Test
    @DisplayName("R_MAX для corner-corner пар соблюдается")
    void testRMaxCornerCorner() {
        List<FastVisibilityGraphService.Vertex> corners = Arrays.asList(
            new FastVisibilityGraphService.Vertex(1L, 0.0, 0.0, FastVisibilityGraphService.VertexType.CORNER),
            new FastVisibilityGraphService.Vertex(2L, 50.0, 0.0, FastVisibilityGraphService.VertexType.CORNER), // 50м
            new FastVisibilityGraphService.Vertex(3L, 150.0, 0.0, FastVisibilityGraphService.VertexType.CORNER) // 150м (> R_MAX)
        );
        // Вызываем ПРОDUCTION-код генерации рёбер напрямую (без рефлексии).
        // Все вершины типа CORNER -> применяется rMaxCorner (по умолчанию 120м):
        // пара 1-2 (50м) проходит, пара 2-3 (100м) проходит, пара 1-3 (150м) — нет.
        List<FastVisibilityGraphService.Edge> edges =
            fastService.generateEdgesForTest(corners, new STRtree());

        // Рёбра неориентированные (reverse_cost), каждая пара хранится один раз
        assertEquals(2, edges.size(), "Должно быть 2 ребра: 1-2 (50м) и 2-3 (100м), оба < rMaxCorner");
        Set<String> pairs = new HashSet<>();
        for (FastVisibilityGraphService.Edge e : edges) {
            String key = Math.min(e.fromVertex, e.toVertex) + "-" + Math.max(e.fromVertex, e.toVertex);
            assertTrue(pairs.add(key), "Симметричный дубль ребра: " + e.fromVertex + "->" + e.toVertex);
        }
        assertTrue(pairs.contains("1-2"), "Должно быть ребро 1-2 (50м)");
        assertTrue(pairs.contains("2-3"), "Должно быть ребро 2-3 (100м)");
        assertFalse(pairs.contains("1-3"), "Ребра 1-3 (150м > rMaxCorner) быть не должно");
    }

    @Test
    @DisplayName("R_MAX для oks-candidate пар соблюдается")
    void testRMaxOksCandidate() {
        List<FastVisibilityGraphService.Vertex> oksList = Arrays.asList(
            new FastVisibilityGraphService.Vertex(1L, 0.0, 0.0, FastVisibilityGraphService.VertexType.OKS)
        );

        List<FastVisibilityGraphService.Vertex> candidateList = Arrays.asList(
            new FastVisibilityGraphService.Vertex(2L, 500.0, 0.0, FastVisibilityGraphService.VertexType.CANDIDATE), // 500м
            new FastVisibilityGraphService.Vertex(3L, 1500.0, 0.0, FastVisibilityGraphService.VertexType.CANDIDATE) // 1500м (> R_MAX)
        );

        // Вызываем ПРОDUCTION-код напрямую: OKS-CANDIDATE соединяются с rMaxCandidate (2500м),
        // поэтому обе пары (500м и 1500м) допустимы; дублированных обратных рёбер быть не должно.
        List<FastVisibilityGraphService.Vertex> allVertices = new ArrayList<>();
        allVertices.addAll(oksList);
        allVertices.addAll(candidateList);
        List<FastVisibilityGraphService.Edge> edges =
            fastService.generateEdgesForTest(allVertices, new STRtree());

        assertEquals(2, edges.size(), "Должно быть 2 ребра: OKS-candidate(500м) и OKS-candidate(1500м)");
        // Проверка отсутствия симметричных дублей: множества пар {a,b} уникальны
        Set<String> pairs = new HashSet<>();
        for (FastVisibilityGraphService.Edge e : edges) {
            String key = Math.min(e.fromVertex, e.toVertex) + "-" + Math.max(e.fromVertex, e.toVertex);
            assertTrue(pairs.add(key), "Симметричный дубль ребра: " + e.fromVertex + "->" + e.toVertex);
        }
    }

    @Test
    @DisplayName("Пакетная вставка рёбер работает корректно")
    void testBatchInsert() {
        // Reused Testcontainers: убираем возможные «залежи» id=1..4 от прошлых прогонов,
        // иначе INSERT вершин с жёстко зашитыми id падает с DuplicateKey.
        jdbcTemplate.update("DELETE FROM visibility_edge "
                + "WHERE source_vertex IN (1, 2, 3, 4) "
                + "   OR target_vertex IN (1, 2, 3, 4)");
        jdbcTemplate.update("DELETE FROM visibility_vertex WHERE id IN (1, 2, 3, 4)");

        UUID taskId = UUID.randomUUID();

        // Создаём тестовую запись задачи
        jdbcTemplate.update(
            "INSERT INTO task (id, status, stage, percent) VALUES (?, 'RUNNING', 'TEST', 0)",
            taskId
        );

        // Вставляем вершины для теста (схема: cluster_id NOT NULL, geom GEOMETRY(POINT, 32637))
        for (int i = 0; i < 4; i++) {
            jdbcTemplate.update(
                "INSERT INTO visibility_vertex (id, task_id, cluster_id, vertex_type, geom) " +
                "VALUES (?, ?, 0, 'polygon_corner', ST_SetSRID(ST_MakePoint(?, 0), 32637))",
                (long) (i + 1), taskId, (double) (i * 100)
            );
        }

        List<FastVisibilityGraphService.Edge> edges = Arrays.asList(
            new FastVisibilityGraphService.Edge(1L, 2L, 100.0),
            new FastVisibilityGraphService.Edge(2L, 3L, 200.0),
            new FastVisibilityGraphService.Edge(3L, 4L, 300.0)
        );

        // insertEdges — package-private, вызывается напрямую из теста того же пакета
        fastService.insertEdges(taskId, edges);

        // Проверяем, что рёбра вставлены
        int count = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM visibility_edge WHERE task_id = ?",
            Integer.class, taskId
        );

        assertEquals(3, count, "Должно быть вставлено 3 ребра");

        // Очищаем
        jdbcTemplate.update("DELETE FROM visibility_edge WHERE task_id = ?", taskId);
        jdbcTemplate.update("DELETE FROM visibility_vertex WHERE task_id = ?", taskId);
        jdbcTemplate.update("DELETE FROM task WHERE id = ?", taskId);
    }
}
