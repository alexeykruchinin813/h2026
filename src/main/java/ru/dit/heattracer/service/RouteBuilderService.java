package ru.dit.heattracer.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * V46: сборка варианта новой тепловой сети.
 *
 * <p>Топология (планаризация пересечений, агрегация flow, слияние degree-2,
 * классификация узлов), ДУ и стоимость считаются вне этого класса:
 * <ul>
 *   <li>SQL-функция {@code build_physical_network} — топология;</li>
 *   <li>{@link PhysicalNetworkService} — ДУ, предельная длина, стоимость
 *       (через {@link DiameterPicker} — единый источник истины по таблице 1 ТЗ).</li>
 * </ul>
 *
 * <p>Роль {@code RouteBuilderService} — оркестрация: убедиться, что
 * {@code path_result} для варианта непуст, запустить построение
 * {@code physical_segment/physical_node}, выгрузить их в {@code variant_feature}
 * (в формате, который читает {@code ExportService}), и записать сводку в
 * {@code variant} / {@code variant_summary}.
 */
@Service
public class RouteBuilderService {

    private static final Logger log = LoggerFactory.getLogger(RouteBuilderService.class);

    // Итоговый показатель (ТЗ 6).
    private static final double SCORE_COST_SCALE   = 25_000_000.0;
    private static final double SCORE_LENGTH_SCALE = 100.0;
    private static final double SCORE_W_COST       = 0.7;
    private static final double SCORE_W_LENGTH     = 0.3;

    // Штраф за неподключённую точку (ТЗ 6, разъяснение 15).
    private static final double UNCONNECTED_BASE_PENALTY = 100_000_000.0;
    private static final double UNCONNECTED_FLOW_PENALTY =    500_000.0;

    private final JdbcTemplate jdbc;
    private final PhysicalNetworkService physicalNetworkService;
    private final ObjectMapper mapper = new ObjectMapper();

    public RouteBuilderService(JdbcTemplate jdbc,
                               PhysicalNetworkService physicalNetworkService) {
        this.jdbc = jdbc;
        this.physicalNetworkService = physicalNetworkService;
    }

    /**
     * Полный пайплайн: считает физическую топологию и пишет variant_feature /
     * variant для данного (taskId, variantId).
     *
     * @return variantId (тот же, что передан)
     */
    public String buildRoute(UUID taskId, String variantId) {
        long start = System.currentTimeMillis();

        // 1. Если для варианта нет ни одного пути — только сводка со штрафом.
        Integer pathCount = jdbc.queryForObject(
                "SELECT count(*) FROM path_result WHERE task_id = ? AND variant_id = ?",
                Integer.class, taskId, variantId);
        if (pathCount == null || pathCount == 0) {
            log.warn("[{}][{}] Нет path_result — строить маршрут нечего", taskId, variantId);
            writeEmptySummary(taskId, variantId);
            return variantId;
        }

        // 2. V46: топология + ДУ + предельная длина + стоимость.
        PhysicalNetworkService.Result pn = physicalNetworkService.build(taskId, variantId);

        // 3. Выгрузка физической сети в variant_feature.
        writePhysicalSegments(taskId, variantId);
        writePhysicalChambers(taskId, variantId);
        int technicalNodeCount = writePhysicalTechnicalNodes(taskId, variantId);

        // 4. Сводка: агрегаты берём из physical_*.
        PhysicalNetworkService.Totals t = physicalNetworkService.totals(taskId, variantId);
        double constructionCost = t.segmentCost + t.chamberCost + t.tieInCost;

        UnconnectedInfo unconn = computeUnconnected(taskId, variantId);
        double calculatedCost = constructionCost + unconn.penalty;
        double score = SCORE_W_COST * (calculatedCost / SCORE_COST_SCALE)
                + SCORE_W_LENGTH * (t.totalLength / SCORE_LENGTH_SCALE);

        if (!unconn.ids.isEmpty()) {
            log.warn("[{}][{}] Не подключено OKS: {} (штраф {} ₽)",
                    taskId, variantId, unconn.ids.size(),
                    String.format("%.0f", unconn.penalty));
        }

        writeSummary(taskId, variantId,
                constructionCost, t.chamberCost, t.existingTieInCount, t.tieInCost,
                unconn.penalty, calculatedCost, t.totalLength, score, unconn.ids);

        log.info("[{}] RouteBuilder done in {} ms: segments={}, nodes={}, chambers={}, tieIn={}, " +
                        "technicalNodes={}, len={} m, segmentCost={}, chamberCost={}, tieInCost={}, score={}",
                taskId, System.currentTimeMillis() - start,
                pn.segments, pn.nodes, t.newChamberCount, t.existingTieInCount,
                technicalNodeCount,
                String.format("%.1f", t.totalLength),
                String.format("%.0f", t.segmentCost),
                String.format("%.0f", t.chamberCost),
                String.format("%.0f", t.tieInCost),
                String.format("%.4f", score));

        return variantId;
    }

    /**
     * P3.1 (ТЗ 2.9, 6): OKS, для которых в данном варианте нет пути.
     * Штраф = 100_000_000 + 500_000 × flow_tph.
     */
    private UnconnectedInfo computeUnconnected(UUID taskId, String variantId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT vv.ref_id AS oks_id, " +
                        "       (f.properties->>'flow_tph')::double precision AS flow " +
                        "FROM visibility_vertex vv " +
                        "JOIN input_feature f " +
                        "  ON f.task_id = vv.task_id AND f.feature_id::text = vv.ref_id " +
                        "WHERE vv.task_id = ? AND vv.vertex_type = 'oks' " +
                        "  AND f.object_type = 'oks_connection_point' " +
                        "  AND NOT EXISTS ( " +
                        "    SELECT 1 FROM path_result pr " +
                        "    WHERE pr.task_id = vv.task_id " +
                        "      AND pr.variant_id = ? " +
                        "      AND pr.oks_vertex_id = vv.id )",
                taskId, variantId);

        UnconnectedInfo info = new UnconnectedInfo();
        for (Map<String, Object> r : rows) {
            info.ids.add(String.valueOf(r.get("oks_id")));
            double flow = ((Number) r.get("flow")).doubleValue();
            info.penalty += UNCONNECTED_BASE_PENALTY + UNCONNECTED_FLOW_PENALTY * flow;
        }
        return info;
    }

    // ==========================================================================
    // V46: выгрузка физической топологии в variant_feature
    // ==========================================================================

    private void writePhysicalSegments(UUID taskId, String variantId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT id, start_node_id, end_node_id, flow_tph, length_m, diameter, " +
                        "       laying_method, cost, ST_AsText(geom) AS wkt " +
                        "FROM physical_segment WHERE task_id = ? AND variant_id = ? ORDER BY id",
                taskId, variantId);

        for (Map<String, Object> r : rows) {
            try {
                Map<String, Object> props = new LinkedHashMap<>();
                props.put("variant_id",    variantId);
                props.put("start_node_id", "pn_" + r.get("start_node_id"));
                props.put("end_node_id",   "pn_" + r.get("end_node_id"));
                props.put("flow_tph",      round2(((Number) r.get("flow_tph")).doubleValue()));
                props.put("diameter",      ((Number) r.get("diameter")).intValue());
                props.put("length",        round2(((Number) r.get("length_m")).doubleValue()));
                props.put("laying_method", r.get("laying_method"));
                props.put("depth_start",   null);
                props.put("depth_end",     null);
                props.put("cost",          round2(((Number) r.get("cost")).doubleValue()));

                String json = mapper.writeValueAsString(props);
                String wkt  = (String) r.get("wkt");
                jdbc.update(
                        "INSERT INTO variant_feature " +
                                "  (task_id, variant_id, feature_id, object_type, properties, geom_utm, geom_4326) " +
                                "VALUES (?, ?, ?, 'heat_network', ?::jsonb, ST_GeomFromText(?, 32637), " +
                                "        ST_Transform(ST_GeomFromText(?, 32637), 4326))",
                        taskId, variantId, "ps_" + variantId + "_" + r.get("id"), json, wkt, wkt);
            } catch (Exception ex) {
                log.error("[{}][{}] writePhysicalSegment {} failed: {}",
                        taskId, variantId, r.get("id"), ex.getMessage());
            }
        }
    }

    /**
     * Новые камеры: терминалы (new_terminal_chamber) и ветвления (branch_chamber).
     * Существующие врезки (existing_tie_in) отдельной фичей не рисуются —
     * они уже присутствуют во входных данных как source-feature.
     */
    private void writePhysicalChambers(UUID taskId, String variantId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT id, node_type, chamber_cost, ST_AsText(geom) AS wkt " +
                        "FROM physical_node " +
                        "WHERE task_id = ? AND variant_id = ? " +
                        "  AND node_type IN ('new_terminal_chamber','branch_chamber')",
                taskId, variantId);

        for (Map<String, Object> r : rows) {
            try {
                Map<String, Object> props = new LinkedHashMap<>();
                props.put("variant_id", variantId);
                props.put("node_type",  r.get("node_type"));
                props.put("cost",       round2(((Number) r.get("chamber_cost")).doubleValue()));

                String json = mapper.writeValueAsString(props);
                String wkt  = (String) r.get("wkt");
                jdbc.update(
                        "INSERT INTO variant_feature " +
                                "  (task_id, variant_id, feature_id, object_type, properties, geom_utm, geom_4326) " +
                                "VALUES (?, ?, ?, 'heat_chamber', ?::jsonb, ST_GeomFromText(?, 32637), " +
                                "        ST_Transform(ST_GeomFromText(?, 32637), 4326))",
                        taskId, variantId, "ch_" + variantId + "_" + r.get("id"), json, wkt, wkt);
            } catch (Exception ex) {
                log.error("[{}][{}] writePhysicalChamber {} failed: {}",
                        taskId, variantId, r.get("id"), ex.getMessage());
            }
        }
    }

    /**
     * technical_node — degree-2 узлы смены ДУ или laying_method (ТЗ 2.1).
     * Разветвления идут как heat_chamber.
     *
     * @return количество созданных technical_node
     */
    private int writePhysicalTechnicalNodes(UUID taskId, String variantId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT id, ST_AsText(geom) AS wkt FROM physical_node " +
                        "WHERE task_id = ? AND variant_id = ? AND node_type = 'technical_node'",
                taskId, variantId);

        int written = 0;
        for (Map<String, Object> r : rows) {
            try {
                Map<String, Object> props = new LinkedHashMap<>();
                props.put("variant_id", variantId);

                String json = mapper.writeValueAsString(props);
                String wkt  = (String) r.get("wkt");
                jdbc.update(
                        "INSERT INTO variant_feature " +
                                "  (task_id, variant_id, feature_id, object_type, properties, geom_utm, geom_4326) " +
                                "VALUES (?, ?, ?, 'technical_node', ?::jsonb, ST_GeomFromText(?, 32637), " +
                                "        ST_Transform(ST_GeomFromText(?, 32637), 4326))",
                        taskId, variantId, "tn_" + variantId + "_" + r.get("id"), json, wkt, wkt);
                written++;
            } catch (Exception ex) {
                log.error("[{}][{}] writePhysicalTechnicalNode {} failed: {}",
                        taskId, variantId, r.get("id"), ex.getMessage());
            }
        }
        return written;
    }

    // ==========================================================================
    // Сводка
    // ==========================================================================

    private void writeSummary(UUID taskId, String variantId,
                              double constructionCost, double chamberCost,
                              int existingTieInCount, double tieInCost,
                              double unconnectedPenalty, double calculatedCost,
                              double newNetworkLength, double score,
                              List<String> unconnectedOksIds) {
        try {
            Map<String, Object> props = new LinkedHashMap<>();
            props.put("variant_id",                    variantId);
            props.put("rank",                          1);
            props.put("construction_cost",             round2(constructionCost));
            props.put("chamber_construction_cost",     round2(chamberCost));
            props.put("existing_chamber_tie_in_count", existingTieInCount);
            props.put("existing_chamber_tie_in_cost",  round2(tieInCost));
            props.put("unconnected_penalty",           round2(unconnectedPenalty));
            props.put("calculated_cost",               round2(calculatedCost));
            props.put("new_network_length",            round2(newNetworkLength));
            props.put("score",                         round4(score));
            props.put("unconnected_oks_ids",           unconnectedOksIds);

            String json = mapper.writeValueAsString(props);
            jdbc.update(
                    "INSERT INTO variant_feature " +
                            "  (task_id, variant_id, feature_id, object_type, properties) " +
                            "VALUES (?, ?, ?, 'variant_summary', ?::jsonb)",
                    taskId, variantId, "summary_" + variantId, json);

            jdbc.update(
                    "INSERT INTO variant " +
                            "  (id, task_id, rank, construction_cost, chamber_construction_cost, tie_in_cost, " +
                            "   reconstruction_cost, chamber_reconstruction_cost, unconnected_penalty, " +
                            "   calculated_cost, new_network_length, reconstruction_length, length, score, unconnected_oks_ids) " +
                            "VALUES (?, ?, 1, ?, ?, ?, 0, 0, ?, ?, ?, 0, ?, ?, ?) " +
                            "ON CONFLICT (task_id, id) DO UPDATE SET " +
                            "  construction_cost = EXCLUDED.construction_cost, " +
                            "  chamber_construction_cost = EXCLUDED.chamber_construction_cost, " +
                            "  tie_in_cost = EXCLUDED.tie_in_cost, " +
                            "  calculated_cost = EXCLUDED.calculated_cost, " +
                            "  new_network_length = EXCLUDED.new_network_length, " +
                            "  score = EXCLUDED.score",
                    variantId, taskId, constructionCost, chamberCost, tieInCost,
                    unconnectedPenalty, calculatedCost, newNetworkLength, newNetworkLength,
                    score, unconnectedOksIds.toArray(new String[0]));
        } catch (Exception ex) {
            log.error("[{}] writeSummary failed: {}", taskId, ex.getMessage());
        }
    }

    private void writeEmptySummary(UUID taskId, String variantId) {
        UnconnectedInfo unconn = computeUnconnected(taskId, variantId);
        double calculatedCost = unconn.penalty;
        double score = SCORE_W_COST * (calculatedCost / SCORE_COST_SCALE);
        writeSummary(taskId, variantId,
                0, 0, 0, 0, unconn.penalty, calculatedCost, 0, score, unconn.ids);
    }

    private static double round2(double v) { return Math.round(v * 100.0) / 100.0; }
    private static double round4(double v) { return Math.round(v * 10000.0) / 10000.0; }

    // ==========================================================================
    // Внутренние структуры
    // ==========================================================================

    static class UnconnectedInfo {
        final List<String> ids = new ArrayList<>();
        double penalty = 0.0;
    }
}