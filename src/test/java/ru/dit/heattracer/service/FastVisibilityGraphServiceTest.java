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
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Тесты для FastVisibilityGraphService с JTS STRtree.
 * 
 * Используют Testcontainers для поднятия изолированного PostgreSQL с PostGIS и pgRouting.
 * Каждый тест запускается в чистой базе данных.
 */
@Testcontainers
@SpringBootTest
@ActiveProfiles("test")
@DisplayName("FastVisibilityGraphService тесты")
class FastVisibilityGraphServiceTest {

    // Образ PostgreSQL с PostGIS и pgRouting
    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("nickblah/pgrouting:16-postgis-3.6-pgrouting-4.0.1")
    ).withDatabaseName("testdb")
      .withUsername("test")
      .withPassword("test");

    @DynamicPropertySource
    static void overrideProps(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("spring.flyway.locations", () -> "classpath:db/migration");
    }

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private FastVisibilityGraphService fastService;
    private GeometryFactory geometryFactory;

    @BeforeEach
    void setUp() {
        geometryFactory = new GeometryFactory();
        // Создаём сервис с параметрами по умолчанию
        fastService = new FastVisibilityGraphService(jdbcTemplate);
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
        // Устанавливаем rMaxCorner = 100м
        FastVisibilityGraphService service = new FastVisibilityGraphService(
            jdbcTemplate, 100.0, 2500.0, 500.0, 400, 2.0
        );

        List<FastVisibilityGraphService.Vertex> corners = Arrays.asList(
            new FastVisibilityGraphService.Vertex(1L, 0.0, 0.0, FastVisibilityGraphService.VertexType.CORNER),
            new FastVisibilityGraphService.Vertex(2L, 50.0, 0.0, FastVisibilityGraphService.VertexType.CORNER), // 50м
            new FastVisibilityGraphService.Vertex(3L, 150.0, 0.0, FastVisibilityGraphService.VertexType.CORNER) // 150м (> R_MAX)
        );

        List<FastVisibilityGraphService.Edge> edges = new ArrayList<>();
        STRtree emptyTree = new STRtree();

        // Используем рефлексию для вызова приватного метода
        try {
            java.lang.reflect.Method method = FastVisibilityGraphService.class.getDeclaredMethod(
                "addEdgesForPair", List.class, List.class, List.class, STRtree.class, double.class
            );
            method.setAccessible(true);
            method.invoke(service, corners, corners, edges, emptyTree, 100.0);
        } catch (Exception e) {
            fail("Не удалось вызвать метод: " + e.getMessage());
        }

        // Должно быть 1 ребро (между вершинами 1 и 2, расстояние 50м)
        // Ребро между 1 и 3 (150м) не должно быть создано
        assertEquals(1, edges.size(), "Должно быть 1 ребро (50м < 100м R_MAX)");
        assertEquals(1L, edges.get(0).fromVertex);
        assertEquals(2L, edges.get(0).toVertex);
    }

    @Test
    @DisplayName("R_MAX для oks-candidate пар соблюдается")
    void testRMaxOksCandidate() {
        // Устанавливаем rMaxCandidate = 1000м
        FastVisibilityGraphService service = new FastVisibilityGraphService(
            jdbcTemplate, 120.0, 1000.0, 500.0, 400, 2.0
        );

        List<FastVisibilityGraphService.Vertex> oksList = Arrays.asList(
            new FastVisibilityGraphService.Vertex(1L, 0.0, 0.0, FastVisibilityGraphService.VertexType.OKS)
        );

        List<FastVisibilityGraphService.Vertex> candidateList = Arrays.asList(
            new FastVisibilityGraphService.Vertex(2L, 500.0, 0.0, FastVisibilityGraphService.VertexType.CANDIDATE), // 500м
            new FastVisibilityGraphService.Vertex(3L, 1500.0, 0.0, FastVisibilityGraphService.VertexType.CANDIDATE) // 1500м (> R_MAX)
        );

        List<FastVisibilityGraphService.Edge> edges = new ArrayList<>();
        STRtree emptyTree = new STRtree();

        try {
            java.lang.reflect.Method method = FastVisibilityGraphService.class.getDeclaredMethod(
                "addEdgesForPair", List.class, List.class, List.class, STRtree.class, double.class
            );
            method.setAccessible(true);
            method.invoke(service, oksList, candidateList, edges, emptyTree, 1000.0);
        } catch (Exception e) {
            fail("Не удалось вызвать метод: " + e.getMessage());
        }

        // Должно быть 1 ребро (между OKS и кандидатом на расстоянии 500м)
        assertEquals(1, edges.size(), "Должно быть 1 ребро (500м < 1000м R_MAX)");
    }

    @Test
    @DisplayName("Пакетная вставка рёбер работает корректно")
    void testBatchInsert() {
        UUID taskId = UUID.randomUUID();
        
        // Создаём сервис для теста
        FastVisibilityGraphService service = new FastVisibilityGraphService(jdbcTemplate);
        
        List<FastVisibilityGraphService.Edge> edges = Arrays.asList(
            new FastVisibilityGraphService.Edge(1L, 2L, 100.0),
            new FastVisibilityGraphService.Edge(2L, 3L, 200.0),
            new FastVisibilityGraphService.Edge(3L, 4L, 300.0)
        );

        // Создаём тестовую запись задачи
        jdbcTemplate.update(
            "INSERT INTO task (id, status, stage, percent) VALUES (?, 'RUNNING', 'TEST', 0)",
            taskId
        );

        // Вставляем вершины для теста
        jdbcTemplate.update(
            "INSERT INTO visibility_vertex (task_id, id, vertex_type, geom_utm) VALUES (?, ?, 'corner', ST_MakePoint(0, 0))",
            taskId, 1L
        );
        jdbcTemplate.update(
            "INSERT INTO visibility_vertex (task_id, id, vertex_type, geom_utm) VALUES (?, ?, 'corner', ST_MakePoint(100, 0))",
            taskId, 2L
        );
        jdbcTemplate.update(
            "INSERT INTO visibility_vertex (task_id, id, vertex_type, geom_utm) VALUES (?, ?, 'corner', ST_MakePoint(200, 0))",
            taskId, 3L
        );
        jdbcTemplate.update(
            "INSERT INTO visibility_vertex (task_id, id, vertex_type, geom_utm) VALUES (?, ?, 'corner', ST_MakePoint(300, 0))",
            taskId, 4L
        );

        try {
            java.lang.reflect.Method method = FastVisibilityGraphService.class.getDeclaredMethod(
                "insertEdges", UUID.class, List.class
            );
            method.setAccessible(true);
            method.invoke(fastService, taskId, edges);
        } catch (Exception e) {
            fail("Не удалось вызвать метод: " + e.getMessage());
        }

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
