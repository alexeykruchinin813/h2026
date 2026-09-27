package ru.dit.heattracer.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.TestInfo;

/**
 * Интеграционный тест связности гибридного графа на конкурсном наборе данных (P0-4).
 * Запускает полный production-пайплайн через {@link TaskService#submit}, дожидается
 * завершения и проверяет метрику связности: количество ОКС, имеющих хотя бы одно ребро
 * к "чужим" вершинам.
 *
 * <p>V46: дополнительно проверяет инварианты физической топологии новой сети:
 * <ul>
 *   <li>0 пересечений сегментов вне общих узлов (ТЗ 2.1);</li>
 *   <li>degree ≤ 4 во всех физических узлах (ТЗ 2.3);</li>
 *   <li>агрегация flow на общем участке (ТЗ 2.3);</li>
 *   <li>ДУ проставлен по агрегированному flow (таблица 1 ТЗ).</li>
 * </ul>
 */
@DisplayName("Интеграционный тест связности гибридного графа (P0-4)")
class HybridConnectivityIT extends BasePostgresIntegrationTest {

    private static final int P0_TARGET_CONNECTED = 16;
    private static final String TEST_DATASET_RESOURCE = "first_dataset.geojson";
    private static final long TIMEOUT_MS = 180_000; // 3 минуты: escape-этап + физическая топология
    private static final long POLL_INTERVAL_MS = 1_000;

    @Autowired
    private TaskService taskService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("Полный пайплайн: связность ОКС и физическая топология (V46)")
    void testOksConnectivityOnFirstDataset() throws Exception {
        // 1. Загрузка тестового датасета (эмулируем загрузку через REST API)
        ClassPathResource resource = new ClassPathResource(TEST_DATASET_RESOURCE);
        MockMultipartFile file = new MockMultipartFile(
                "file", "first_dataset.geojson", "application/json", resource.getInputStream());

        // 2. Запуск асинхронного пайплайна
        UUID taskId = taskService.submit(file);
        this.currentTaskId = taskId;
        assertNotNull(taskId, "submit() должен вернуть UUID задачи");

        // 3. Ожидание завершения задачи (polling).
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

        // 4. Инвариант P0-3b: SRID графа = 32637 (метры), а не 4326 (градусы)
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

        // 6. Подсчёт ОКС, имеющих выход к чужим вершинам.
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

        if (connectedOksCount < totalOksCount) {
            System.out.printf("[P0-4 WARN] %d ОКС без рёбер к чужим вершинам.%n",
                    totalOksCount - connectedOksCount);
        }

        int targetConnected = Math.min(P0_TARGET_CONNECTED, totalOksCount);
        assertTrue(connectedOksCount >= targetConnected,
                String.format("Недостаточная связность графа: только %d из %d ОКС имеют рёбра " +
                                "к чужим вершинам. Цель P0: >= %d.",
                        connectedOksCount, totalOksCount, targetConnected));

        // 7. P1-1: пути A* по вариантам (V43: три варианта × N OKS)
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

        int minWithPath = Integer.MAX_VALUE;
        for (Map<String, Object> r : perVariant) {
            minWithPath = Math.min(minWithPath, ((Number) r.get("cnt")).intValue());
        }
        if (perVariant.isEmpty()) minWithPath = 0;

        int expectedMin = Math.min(16, totalOksCount);
        assertTrue(minWithPath >= expectedMin,
                String.format("Недостаточно OKS с путём (мин по вариантам): %d из %d (ожидали ≥ %d).",
                        minWithPath, totalOksCount, expectedMin));

        // V55: среди сохранённых путей нет транзита через чужой OKS.
        Long foreignOksTransits = jdbcTemplate.queryForObject(
                "SELECT count(*) " +
                        "  FROM path_result pr " +
                        " CROSS JOIN LATERAL unnest(pr.edge_ids) AS eid " +
                        "  JOIN visibility_edge ve ON ve.id = eid " +
                        "  JOIN visibility_vertex vv2 " +
                        "       ON vv2.id IN (ve.source_vertex, ve.target_vertex) " +
                        " WHERE pr.task_id = ? " +
                        "   AND vv2.vertex_type = 'oks' " +
                        "   AND vv2.id <> pr.oks_vertex_id",
                Long.class, taskId);
        assertEquals(0L, foreignOksTransits.longValue(),
                "V55: ни один path_result не должен проходить через чужой OKS. " +
                        "PathFinderService.findPathsFromOks фильтрует такие пути; " +
                        "если assertion падает — фильтр не применяется.");

        // ===== P2.2: до трёх содержательно отличающихся вариантов =====
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

        assertTrue(variants.size() >= 1 && variants.size() <= 3,
                "ТЗ 2.8: 1–3 варианта, получено " + variants.size());

        Set<Double> scores = new HashSet<>();
        for (Map<String, Object> v : variants) {
            double s = Math.round(((Number) v.get("score")).doubleValue() * 10000.0) / 10000.0;
            assertTrue(scores.add(s),
                    "После дедупликации score должны быть уникальны, дубликат: " + s);
        }

        // P3.1: на конкурсном наборе все OKS подключены → штраф 0.
        for (Map<String, Object> v : variants) {
            Number penalty = (Number) v.get("unconnected_penalty");
            Object ids = v.get("unconnected_oks_ids");
            System.out.printf("          %s unconnected_penalty=%s unconnected_oks_ids=%s%n",
                    v.get("id"), penalty, ids);
            assertEquals(0.0, penalty.doubleValue(), 0.01,
                    "На конкурсном наборе все OKS подключены → штраф 0");
        }

        assertEquals(1, ((Number) variants.get(0).get("rank")).intValue(),
                "rank 1 должен быть у минимального score");
        for (int i = 1; i < variants.size(); i++) {
            double prev = ((Number) variants.get(i - 1).get("score")).doubleValue();
            double cur  = ((Number) variants.get(i).get("score")).doubleValue();
            assertTrue(prev <= cur,
                    "Ранжирование нарушено: rank " + i + " score=" + prev +
                            " > rank " + (i + 1) + " score=" + cur);
        }

        // ==================================================================
        // V46: инварианты физической топологии новой сети
        // ==================================================================

        // V46-a: 0 пересечений сегментов вне общих узлов (ТЗ 2.1).
        Long badCrossings = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM physical_segment a " +
                        "JOIN physical_segment b " +
                        "  ON a.task_id = b.task_id " +
                        " AND a.variant_id = b.variant_id " +
                        " AND a.id < b.id " +
                        "WHERE a.task_id = ? " +
                        "  AND ST_Intersects(a.geom, b.geom) " +
                        "  AND NOT ST_Touches(a.geom, b.geom)",
                Long.class, taskId);
        assertNotNull(badCrossings);
        assertEquals(0L, badCrossings.longValue(),
                "V46 (ТЗ 2.1): пересечений сегментов вне общих узлов быть не должно. " +
                        "ST_Node не справился с планаризацией.");

        // V46-b: degree ≤ 4 во всех физических узлах (ТЗ 2.3).
        List<Map<String, Object>> overDeg = jdbcTemplate.queryForList(
                "SELECT id, node_type, degree FROM physical_node " +
                        "WHERE task_id = ? AND degree > 4",
                taskId);
        assertTrue(overDeg.isEmpty(),
                "V46 (ТЗ 2.3): узлов с degree > 4 быть не должно: " + overDeg);

        // V46-c: агрегация flow на общем участке (ТЗ 2.3).
        // На общем сегменте flow_tph > flow любой отдельной точки OKS.
        Long aggregatedSegments = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM physical_segment s " +
                        "WHERE s.task_id = ? AND s.variant_id = 'v1' " +
                        "  AND s.flow_tph > (SELECT MAX((f.properties->>'flow_tph')::double precision) " +
                        "                    FROM input_feature f " +
                        "                    WHERE f.task_id = ? " +
                        "                      AND f.object_type = 'oks_connection_point')",
                Long.class, taskId, taskId);
        assertNotNull(aggregatedSegments);
        assertTrue(aggregatedSegments > 0,
                "V46 (ТЗ 2.3): на общих участках flow должен быть агрегирован — " +
                        "хотя бы один сегмент с flow_tph > flow отдельной OKS.");

        // V46-d: ДУ сегментов присутствует и осмыслен (подобран DiameterPicker).
        //   - не NULL на всех сегментах варианта v1;
        //   - входит в таблицу 1 ТЗ.
        Long nullDiam = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM physical_segment " +
                        "WHERE task_id = ? AND variant_id = 'v1' AND diameter IS NULL",
                Long.class, taskId);
        assertEquals(0L, nullDiam.longValue(),
                "V46: diameter должен быть заполнен на всех сегментах (DiameterPicker).");

        Long badDiam = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM physical_segment " +
                        "WHERE task_id = ? AND variant_id = 'v1' " +
                        "  AND diameter NOT IN (50,65,80,100,125,150,200,250,300,400,500,600,700,800,900,1000,1200,1400)",
                Long.class, taskId);
        assertEquals(0L, badDiam.longValue(),
                "V46: диаметр вне таблицы 1 ТЗ.");

        // Метрика по физической топологии — для отладки.
        Long segCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM physical_segment WHERE task_id = ? AND variant_id = 'v1'",
                Long.class, taskId);
        Long nodeCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM physical_node WHERE task_id = ? AND variant_id = 'v1'",
                Long.class, taskId);

        System.out.printf("[V46 METRIC] v1: segments=%d, nodes=%d, " +
                        "bad_crossings=%d, degree_gt_4=%d, aggregated_segments=%d%n",
                segCount, nodeCount, badCrossings, overDeg.size(), aggregatedSegments);
    }

    // V58: диагностический вывод — всегда, для отладки упавших прогонов.
    private UUID currentTaskId;

    @AfterEach
    void printDiagnostics(TestInfo testInfo) {
        StringBuilder sb = new StringBuilder();
        sb.append("\n=== DIAGNOSTICS BEGIN taskId=")
                .append(currentTaskId).append(" status=")
                .append(testInfo.getTags()).append(" ===\n");

        try {
            // 1. Flyway.
            jdbcTemplate.queryForList(
                            "SELECT version, description FROM flyway_schema_history ORDER BY version DESC LIMIT 5")
                    .forEach(r -> sb.append("[flyway] ")
                            .append(r.get("version")).append(" | ")
                            .append(r.get("description")).append("\n"));

            if (currentTaskId == null) {
                sb.append("[task] taskId not set — тест упал до submit\n");
                sb.append("=== DIAGNOSTICS END ===\n");
                System.out.println(sb);
                return;
            }

            // 2. Task status.
            jdbcTemplate.queryForList(
                            "SELECT status, stage, error_message FROM task WHERE id = ?", currentTaskId)
                    .forEach(r -> sb.append("[task] status=").append(r.get("status"))
                            .append(", stage=").append(r.get("stage"))
                            .append(", error=").append(r.get("error_message")).append("\n"));

            // 3. path_result.
            jdbcTemplate.queryForList(
                    "SELECT variant_id, COUNT(DISTINCT oks_vertex_id) AS cnt " +
                            "FROM path_result WHERE task_id = ? GROUP BY variant_id ORDER BY variant_id",
                    currentTaskId).forEach(r -> sb.append("[paths] ")
                    .append(r.get("variant_id")).append("=")
                    .append(r.get("cnt")).append("\n"));

            // 4. physical_segment (только v1).
            jdbcTemplate.queryForList(
                            "SELECT COUNT(*) AS n, " +
                                    "       COUNT(*) FILTER (WHERE diameter IS NULL) AS null_diam, " +
                                    "       ROUND(MIN(ST_Length(geom))::numeric, 2) AS min_len, " +
                                    "       ROUND(MAX(ST_Length(geom))::numeric, 2) AS max_len " +
                                    "FROM physical_segment WHERE task_id = ? AND variant_id = 'v1'",
                            currentTaskId)
                    .forEach(r -> sb.append("[physical_segment v1] n=").append(r.get("n"))
                            .append(", null_diam=").append(r.get("null_diam"))
                            .append(", len=[").append(r.get("min_len"))
                            .append("..").append(r.get("max_len")).append("]\n"));

            // 5. physical_node по типам (только v1).
            jdbcTemplate.queryForList(
                            "SELECT node_type, COUNT(*) AS n, MAX(degree) AS max_deg " +
                                    "FROM physical_node WHERE task_id = ? AND variant_id = 'v1' " +
                                    "GROUP BY node_type ORDER BY node_type",
                            currentTaskId)
                    .forEach(r -> sb.append("[physical_node v1] ")
                            .append(r.get("node_type")).append(": n=")
                            .append(r.get("n")).append(", max_degree=")
                            .append(r.get("max_deg")).append("\n"));

            // 6. bad_crossings (только v1).
            Long badCrossings = jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM physical_segment a " +
                            "JOIN physical_segment b ON a.task_id = b.task_id " +
                            "  AND a.variant_id = b.variant_id AND a.id < b.id " +
                            "WHERE a.task_id = ? AND a.variant_id = 'v1' AND ST_Intersects(a.geom, b.geom) " +
                            "  AND NOT ST_Touches(a.geom, b.geom)",
                    Long.class, currentTaskId);
            sb.append("[metric v1] bad_crossings=").append(badCrossings).append("\n");

            // 7. degree > 4 (только v1).
            List<Map<String,Object>> overDeg = jdbcTemplate.queryForList(
                    "SELECT id, node_type, degree FROM physical_node " +
                            "WHERE task_id = ? AND variant_id = 'v1' AND degree > 4", currentTaskId);
            sb.append("[metric v1] degree_gt_4=").append(overDeg.size()).append("\n");
            overDeg.forEach(r -> sb.append("    node id=")
                    .append(r.get("id")).append(", type=")
                    .append(r.get("node_type")).append(", degree=")
                    .append(r.get("degree")).append("\n"));

// 7b. SQL A: конкретная пересекающаяся пара при bad_crossings > 0.
            try {
                List<Map<String,Object>> crossPairs = jdbcTemplate.queryForList(
                        "SELECT a.id AS aid, ROUND(a.length_m::numeric,2) AS a_len, a.diameter AS a_diam, " +
                                "       a.start_node_id AS a_start, a.end_node_id AS a_end, " +
                                "       asn.node_type AS a_start_type, aen.node_type AS a_end_type, " +
                                "       asn.ref_id    AS a_start_ref,  aen.ref_id    AS a_end_ref, " +
                                "       b.id AS bid, ROUND(b.length_m::numeric,2) AS b_len, b.diameter AS b_diam, " +
                                "       b.start_node_id AS b_start, b.end_node_id AS b_end, " +
                                "       bsn.node_type AS b_start_type, ben.node_type AS b_end_type, " +
                                "       bsn.ref_id    AS b_start_ref,  ben.ref_id    AS b_end_ref, " +
                                "       ST_GeometryType(ST_Intersection(a.geom, b.geom)) AS cross_type, " +
                                "       ST_AsText(ST_Intersection(a.geom, b.geom)) AS cross_geom " +
                                "FROM physical_segment a " +
                                "JOIN physical_segment b ON a.task_id = b.task_id " +
                                "  AND a.variant_id = b.variant_id AND a.id < b.id " +
                                "LEFT JOIN physical_node asn ON asn.id = a.start_node_id " +
                                "LEFT JOIN physical_node aen ON aen.id = a.end_node_id " +
                                "LEFT JOIN physical_node bsn ON bsn.id = b.start_node_id " +
                                "LEFT JOIN physical_node ben ON ben.id = b.end_node_id " +
                                "WHERE a.task_id = ? AND a.variant_id = 'v1' " +
                                "  AND ST_Intersects(a.geom, b.geom) AND NOT ST_Touches(a.geom, b.geom)",
                        currentTaskId);
                sb.append("[sqlA v1] crossing_pairs=").append(crossPairs.size()).append("\n");
                for (Map<String,Object> r : crossPairs) {
                    sb.append("    A=").append(r.get("aid"))
                            .append(" len=").append(r.get("a_len")).append(" diam=").append(r.get("a_diam"))
                            .append(" start=").append(r.get("a_start")).append("/").append(r.get("a_start_type")).append("/").append(r.get("a_start_ref"))
                            .append(" end=").append(r.get("a_end")).append("/").append(r.get("a_end_type")).append("/").append(r.get("a_end_ref"))
                            .append("\n");
                    sb.append("    B=").append(r.get("bid"))
                            .append(" len=").append(r.get("b_len")).append(" diam=").append(r.get("b_diam"))
                            .append(" start=").append(r.get("b_start")).append("/").append(r.get("b_start_type")).append("/").append(r.get("b_start_ref"))
                            .append(" end=").append(r.get("b_end")).append("/").append(r.get("b_end_type")).append("/").append(r.get("b_end_ref"))
                            .append("\n");
                    sb.append("    cross_type=").append(r.get("cross_type"))
                            .append(" cross_geom=").append(r.get("cross_geom")).append("\n");
                }
            } catch (Exception e) {
                sb.append("[sqlA_error] ").append(e.getMessage()).append("\n");
            }

            // 7c. SQL B: происхождение existing_tie_in с degree > 4.
            try {
                List<Map<String,Object>> tieInHi = jdbcTemplate.queryForList(
                        "SELECT pn.id, pn.ref_id, pn.degree, pn.node_type, " +
                                "       (SELECT COUNT(*) FROM physical_segment ps " +
                                "         WHERE ps.task_id = pn.task_id AND ps.variant_id = pn.variant_id " +
                                "           AND (ps.start_node_id = pn.id OR ps.end_node_id = pn.id)) AS actual_degree, " +
                                "       (SELECT COUNT(*) FROM input_feature f " +
                                "         WHERE f.task_id = pn.task_id AND f.feature_id::text = pn.ref_id " +
                                "           AND f.object_type = 'heat_chamber') AS is_input_chamber, " +
                                "       (SELECT COUNT(DISTINCT pr.oks_vertex_id) FROM path_result pr " +
                                "         JOIN visibility_vertex vv ON vv.id = pr.target_vertex_id " +
                                "         WHERE pr.task_id = pn.task_id AND pr.variant_id = pn.variant_id " +
                                "           AND vv.ref_id::text = pn.ref_id) AS oks_paths_ending_here, " +
                                "       (SELECT array_agg(DISTINCT pn2.node_type) FROM physical_segment ps " +
                                "         JOIN physical_node pn2 " +
                                "           ON pn2.id = CASE WHEN ps.start_node_id = pn.id THEN ps.end_node_id ELSE ps.start_node_id END " +
                                "         WHERE ps.task_id = pn.task_id AND ps.variant_id = pn.variant_id " +
                                "           AND (ps.start_node_id = pn.id OR ps.end_node_id = pn.id)) AS neighbor_types, " +
                                "       (SELECT COUNT(*) FROM physical_node pn3 " +
                                "         WHERE pn3.task_id = pn.task_id AND pn3.variant_id = pn.variant_id " +
                                "           AND pn3.ref_id LIKE 'split_%') AS split_created_nodes " +
                                "FROM physical_node pn " +
                                "WHERE pn.task_id = ? AND pn.variant_id = 'v1' " +
                                "  AND pn.degree > 4 " +
                                "  AND pn.node_type IN ('branch_chamber','new_terminal_chamber','existing_tie_in')",
                        currentTaskId);
                sb.append("[sqlB v1] high_degree_nodes=").append(tieInHi.size()).append("\n");
                for (Map<String,Object> r : tieInHi) {
                    sb.append("    id=").append(r.get("id"))
                            .append(" ref=").append(r.get("ref_id"))
                            .append(" type=").append(r.get("node_type"))
                            .append(" degree=").append(r.get("degree"))
                            .append(" actual=").append(r.get("actual_degree"))
                            .append(" is_input_chamber=").append(r.get("is_input_chamber"))
                            .append(" oks_paths_ending_here=").append(r.get("oks_paths_ending_here"))
                            .append(" split_created_nodes=").append(r.get("split_created_nodes"))
                            .append(" neighbor_types=").append(r.get("neighbors"))
                            .append("\n");
                }
            } catch (Exception e) {
                sb.append("[sqlB_error] ").append(e.getMessage()).append("\n");
            }

            // 8. aggregated_segments.
            Long agg = jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM physical_segment s " +
                            "WHERE s.task_id = ? AND s.variant_id = 'v1' AND s.flow_tph > " +
                            "  (SELECT MAX((f.properties->>'flow_tph')::double precision) " +
                            "   FROM input_feature f WHERE f.task_id = ? AND f.object_type = 'oks_connection_point')",
                    Long.class, currentTaskId, currentTaskId);
            sb.append("[metric] aggregated_segments=").append(agg).append("\n");

            // 9. variant summary.
            jdbcTemplate.queryForList(
                            "SELECT id, rank, ROUND(score::numeric, 4) AS score, " +
                                    "       ROUND(construction_cost::numeric, 0) AS cost, " +
                                    "       ROUND(new_network_length::numeric, 1) AS len, " +
                                    "       unconnected_penalty " +
                                    "FROM variant WHERE task_id = ? ORDER BY rank", currentTaskId)
                    .forEach(r -> sb.append("[variant] ").append(r.get("id"))
                            .append(" rank=").append(r.get("rank"))
                            .append(" score=").append(r.get("score"))
                            .append(" cost=").append(r.get("cost"))
                            .append(" len=").append(r.get("len"))
                            .append(" unconn_penalty=").append(r.get("unconnected_penalty"))
                            .append("\n"));

            // 10. Top-5 сегментов по flow.
            jdbcTemplate.queryForList(
                            "SELECT id, ROUND(flow_tph::numeric, 2) AS flow, diameter, " +
                                    "       ROUND(ST_Length(geom)::numeric, 2) AS len " +
                                    "FROM physical_segment WHERE task_id = ? AND variant_id = 'v1' " +
                                    "ORDER BY flow_tph DESC LIMIT 5", currentTaskId)
                    .forEach(r -> sb.append("[top_flow] seg=").append(r.get("id"))
                            .append(" flow=").append(r.get("flow"))
                            .append(" diam=").append(r.get("diameter"))
                            .append(" len=").append(r.get("len")).append("\n"));

            // 11.  cross-candidate
            try {
                List<Map<String,Object>> candidatesByRef = jdbcTemplate.queryForList(
                        "SELECT vv.ref_id, COUNT(*) AS candidate_count " +
                                "FROM visibility_vertex vv " +
                                "JOIN input_feature f ON f.task_id = vv.task_id " +
                                "  AND f.feature_id::text = vv.ref_id AND f.object_type = 'heat_chamber' " +
                                "WHERE vv.task_id = ? AND vv.vertex_type = 'candidate' " +
                                "GROUP BY vv.ref_id HAVING COUNT(*) > 1",
                        currentTaskId);
                sb.append("[candidates_by_ref] multi_candidate_chambers=")
                        .append(candidatesByRef.size()).append("\n");
                candidatesByRef.forEach(r -> sb.append("    ref=")
                        .append(r.get("ref_id")).append(" candidates=")
                        .append(r.get("candidate_count")).append("\n"));
            } catch (Exception e) {
                sb.append("[candidates_by_ref_error] ").append(e.getMessage()).append("\n");
            }

        } catch (Exception e) {
            sb.append("[diag_error] ").append(e.getMessage()).append("\n");
        }

        sb.append("=== DIAGNOSTICS END ===\n");
        System.out.println(sb);
    }

}