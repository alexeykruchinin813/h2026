package ru.dit.heattracer.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import ru.dit.heattracer.model.DiameterSpec;

import java.util.*;

/**
 * P1.3: строит выходной GeoJSON по разделу 7 Технического приложения.
 *
 * <p>Шаги:
 * <ol>
 *   <li>Читает path_result (V42) — уникальные рёбра новых участков.</li>
 *   <li>Считает flow_tph на каждом ребре = сумма flow_tph всех OKS,
 *       чьи пути его используют (ТЗ 2.3).</li>
 *   <li>Подбирает ДУ по flow (таблица 1) через {@link DiameterPicker}.</li>
 *   <li>Проверяет предельную длину непрерывного пути с одним ДУ (ТЗ 2.4).</li>
 *   <li>Считает стоимость участков, камер, сводку, score.</li>
 *   <li>Пишет в variant_feature / variant (V6) — их читает ExportService.</li>
 * </ol>
 */
@Service
public class RouteBuilderService {

    private static final Logger log = LoggerFactory.getLogger(RouteBuilderService.class);

    // Стоимость строительства (раздел 6 ТЗ)
    private static final double SCORE_COST_SCALE   = 25_000_000.0;
    private static final double SCORE_LENGTH_SCALE = 100.0;
    private static final double SCORE_W_COST       = 0.7;
    private static final double SCORE_W_LENGTH     = 0.3;
    private static final int    MAX_LENGTH_ITERATIONS = 5;

    // Стоимость новой камеры по наибольшему ДУ примыкающих участков (раздел 3.2)
    private static final int CHAMBER_DIAM_LIMIT_1 = 200;
    private static final int CHAMBER_DIAM_LIMIT_2 = 500;
    private static final int CHAMBER_DIAM_LIMIT_3 = 1000;
    private static final long CHAMBER_COST_1 = 3_000_000L;
    private static final long CHAMBER_COST_2 = 5_000_000L;
    private static final long CHAMBER_COST_3 = 8_000_000L;
    private static final long CHAMBER_COST_4 = 12_000_000L;

    // Врезка в существующую камеру (раздел 3.2)
    private static final long EXISTING_TIE_IN_COST = 5_000_000L;

    private final JdbcTemplate jdbc;
    private final DiameterPicker diameterPicker;
    private final ObjectMapper mapper = new ObjectMapper();

    public RouteBuilderService(JdbcTemplate jdbc, DiameterPicker diameterPicker) {
        this.jdbc = jdbc;
        this.diameterPicker = diameterPicker;
    }

    /**
     * P3.1 (ТЗ 2.9, 6): OKS, для которых в данном варианте нет пути.
     * Штраф = 100_000_000 + 500_000 × flow_tph (ТЗ 6).
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
            info.penalty += 100_000_000.0 + 500_000.0 * flow;
        }
        return info;
    }

    /**
     * Полный пайплайн: считает маршрут и пишет variant_feature для данного variantId.
     *
     * @return variantId (тот же, что передан)
     */
    public String buildRoute(UUID taskId, String variantId) {
        long start = System.currentTimeMillis();

        // 1. Читаем все пути
        List<PathRow> paths = readPaths(taskId, variantId);
        if (paths.isEmpty()) {
            log.warn("[{}] Нет path_result — строить маршрут нечего", taskId);
            writeEmptySummary(taskId, variantId);
            return variantId;
        }

        // 2. Flow OKS по oks_vertex_id
        Map<Long, Double> oksFlow = readOksFlow(taskId);

        // 3. Множество всех edge_ids из путей
        Set<Long> allEdgeIds = new LinkedHashSet<>();
        for (PathRow p : paths) allEdgeIds.addAll(p.edgeIds);

        // 4. Читаем edges
        Map<Long, EdgeRow> edges = readEdges(taskId, allEdgeIds);
        log.info("[{}] RouteBuilder: {} paths, {} unique edges",
                taskId, paths.size(), edges.size());

        // 5. Flow на каждом edge
        Map<Long, Double> edgeFlow = new HashMap<>();
        for (PathRow p : paths) {
            double f = oksFlow.getOrDefault(p.oksVertexId, 0.0);
            for (Long eid : p.edgeIds) {
                edgeFlow.merge(eid, f, Double::sum);
            }
        }

        // 6. ДУ по flow
        for (EdgeRow e : edges.values()) {
            e.flow = edgeFlow.getOrDefault(e.id, 0.0);
            e.spec = pickDiameterByFlow(e.flow);
        }

        // 7. Проверка предельной длины (несколько итераций)
        int iters = 0;
        while (enforceMaxLength(paths, edges) && iters < MAX_LENGTH_ITERATIONS) iters++;
        if (iters > 0) {
            log.info("[{}] RouteBuilder: max_length enforcement iterations: {}", taskId, iters);
        }

        // 8. Стоимость участков
        double totalSegmentCost = 0.0;
        double totalLength = 0.0;
        for (EdgeRow e : edges.values()) {
            e.cost = e.lengthM * e.spec.getCostPerM() * e.specialK;
            totalSegmentCost += e.cost;
            totalLength += e.lengthM;
        }

        // 9. Обработка tie-in: target_vertex из paths.
        //    Камера получает ДУ по НАИБОЛЬШЕМУ примыкающему участку (раздел 3.2).
        Set<Long> targetVertices = new LinkedHashSet<>();
        for (PathRow p : paths) targetVertices.add(p.targetVertexId);

        // Камера строится в target-точке ПУТИ (не ребра).
        // ДУ камеры = максимум ДУ последнего ребра каждого пути,
        // приходящего в эту точку (несколько OKS могут делить одну врезку).
        Map<Long, DiameterSpec> chamberSpec = new HashMap<>();
        for (PathRow p : paths) {
            if (p.edgeIds.isEmpty()) continue;
            Long lastEdgeId = p.edgeIds.get(p.edgeIds.size() - 1);
            EdgeRow e = edges.get(lastEdgeId);
            if (e == null) continue;
            chamberSpec.merge(p.targetVertexId, e.spec,
                    (a, b) -> a.getDiameter() >= b.getDiameter() ? a : b);
        }

        Map<Long, TargetInfo> targetInfo = readTargetInfo(taskId, targetVertices);

        double chamberCost = 0.0;
        double tieInCost = 0.0;
        int newChamberCount = 0;
        int existingTieInCount = 0;
        for (Long tv : targetVertices) {
            DiameterSpec spec = chamberSpec.get(tv);
            int diam = spec != null ? spec.getDiameter() : 100;
            TargetInfo info = targetInfo.get(tv);
            boolean existing = info != null && "heat_chamber".equals(info.existingType);
            if (existing) {
                tieInCost += EXISTING_TIE_IN_COST;
                existingTieInCount++;
            } else {
                chamberCost += chamberCostByDiameter(diam);
                newChamberCount++;
            }
        }

        // 10. Записываем edges, chambers и technical_nodes в variant_feature
        writeEdges(taskId, variantId, edges);
        writeChambers(taskId, variantId, chamberSpec, targetInfo);
        int technicalNodeCount = writeTechnicalNodes(taskId, variantId, edges);

        // 11. Сводка с учётом неподключённых OKS (P3.1, ТЗ 2.9 и 6)
        double constructionCost = totalSegmentCost + chamberCost + tieInCost;

        UnconnectedInfo unconn = computeUnconnected(taskId, variantId);
        double calculatedCost = constructionCost + unconn.penalty;
        double score = SCORE_W_COST * (calculatedCost / SCORE_COST_SCALE)
                + SCORE_W_LENGTH * (totalLength / SCORE_LENGTH_SCALE);

        if (!unconn.ids.isEmpty()) {
            log.warn("[{}][{}] Не подключено OKS: {} (штраф {} ₽)",
                    taskId, variantId, unconn.ids.size(),
                    String.format("%.0f", unconn.penalty));
        }

        writeSummary(taskId, variantId,
                constructionCost, chamberCost, existingTieInCount, tieInCost,
                unconn.penalty, calculatedCost, totalLength, score, unconn.ids);

        int specialEdges = 0;
        for (EdgeRow e : edges.values()) if (e.isSpecial) specialEdges++;

        log.info("[{}] RouteBuilder done in {} ms: edges={} ({} special), chambers={}, tieIn={}, " +
                        "technicalNodes={}, len={} m, segmentCost={}, chamberCost={}, tieInCost={}, score={}",
                taskId, System.currentTimeMillis() - start,
                edges.size(), specialEdges, newChamberCount, existingTieInCount,
                technicalNodeCount,
                String.format("%.1f", totalLength),
                String.format("%.0f", totalSegmentCost),
                String.format("%.0f", chamberCost),
                String.format("%.0f", tieInCost),
                String.format("%.4f", score));

        return variantId;
    }

    // ===== Чтение из БД =====

    private List<PathRow> readPaths(UUID taskId, String variantId) {
        return jdbc.query(
                "SELECT cluster_id, oks_vertex_id, target_vertex_id, edge_ids, total_length_m " +
                    "FROM path_result WHERE task_id = ? AND variant_id = ? ORDER BY oks_vertex_id",
                (rs, i) -> {
                    PathRow p = new PathRow();
                    p.clusterId = rs.getInt("cluster_id");
                    p.oksVertexId = rs.getLong("oks_vertex_id");
                    p.targetVertexId = rs.getLong("target_vertex_id");
                    p.totalLength = rs.getDouble("total_length_m");
                    java.sql.Array arr = rs.getArray("edge_ids");
                    Object raw = arr != null ? arr.getArray() : null;
                    List<Long> ids = new ArrayList<>();
                    if (raw instanceof Long[]) {
                        ids = Arrays.asList((Long[]) raw);
                    } else if (raw instanceof Object[]) {
                        for (Object o : (Object[]) raw) ids.add(((Number) o).longValue());
                    }
                    p.edgeIds = ids;
                    return p;
                }, taskId, variantId);
    }

    private Map<Long, Double> readOksFlow(UUID taskId) {
        Map<Long, Double> result = new HashMap<>();
        jdbc.query(
                "SELECT vv.id AS oks_vid, (f.properties->>'flow_tph')::double precision AS flow " +
                        "FROM visibility_vertex vv " +
                        "JOIN input_feature f " +
                        "  ON f.task_id = vv.task_id AND f.feature_id::text = vv.ref_id " +
                        "WHERE vv.task_id = ? AND vv.vertex_type = 'oks' " +
                        "  AND f.object_type = 'oks_connection_point'",
                rs -> {
                    result.put(rs.getLong("oks_vid"), rs.getDouble("flow"));
                }, taskId);
        return result;
    }

    /**
     * Читает edges по списку id. Массив id передаётся через PreparedStatementSetter —
     * jdbc.query(sql, setter, handler) — это обходит ловушку varargs с Long[].
     */
    private Map<Long, EdgeRow> readEdges(UUID taskId, Set<Long> edgeIds) {
        if (edgeIds.isEmpty()) return Collections.emptyMap();
        Map<Long, EdgeRow> result = new HashMap<>();
        Long[] arr = edgeIds.toArray(new Long[0]);
        jdbc.query(
                "SELECT ve.id, ve.source_vertex, ve.target_vertex, ve.length_m, " +
                        "       ve.is_special, ve.special_k, " +
                        "       ST_AsText(ve.geom) AS wkt " +
                        "FROM visibility_edge ve " +
                        "WHERE ve.task_id = ? AND ve.id = ANY (?)",
                ps -> {
                    ps.setObject(1, taskId);
                    ps.setArray(2, ps.getConnection().createArrayOf("bigint", arr));
                },
                rs -> {
                    EdgeRow e = new EdgeRow();
                    e.id = rs.getLong("id");
                    e.sourceVertex = rs.getLong("source_vertex");
                    e.targetVertex = rs.getLong("target_vertex");
                    e.lengthM = rs.getDouble("length_m");
                    e.wkt = rs.getString("wkt");
                    e.isSpecial = rs.getBoolean("is_special");
                    double k = rs.getDouble("special_k");
                    e.specialK = rs.wasNull() ? 1.0 : k;
                    result.put(e.id, e);
                });
        return result;
    }

    private Map<Long, TargetInfo> readTargetInfo(UUID taskId, Set<Long> targetVertices) {
        if (targetVertices.isEmpty()) return Collections.emptyMap();
        Map<Long, TargetInfo> result = new HashMap<>();
        jdbc.query(
                "SELECT vv.id, vv.ref_id, tic.existing_object_type " +
                        "FROM visibility_vertex vv " +
                        "LEFT JOIN tie_in_candidate tic " +
                        "  ON tic.task_id = vv.task_id AND tic.existing_object_id = vv.ref_id " +
                        "WHERE vv.task_id = ? AND vv.vertex_type = 'candidate'",
                rs -> {
                    TargetInfo t = new TargetInfo();
                    t.vertexId = rs.getLong("id");
                    t.refId = rs.getString("ref_id");
                    t.existingType = rs.getString("existing_object_type");  // ← было "object_type"
                    result.put(t.vertexId, t);
                }, taskId);
        return result;
    }

    // ===== Подбор ДУ и предельная длина =====

    private DiameterSpec pickDiameterByFlow(double flow) {
        try {
            return diameterPicker.pickForFlow(flow);
        } catch (IllegalArgumentException e) {
            // Не должны сюда попасть при нормальных данных (max flow 22501.9 т/ч).
            // При экстремальном расходе берём максимальный ДУ и логируем.
            List<DiameterSpec> all = diameterPicker.all();
            DiameterSpec max = all.get(all.size() - 1);
            log.warn("RouteBuilder: flow {} exceeds max capacity, using top diameter {}",
                    flow, max.getDiameter());
            return max;
        }
    }

    /**
     * Следующий (больший) ДУ. Если текущий — максимальный, возвращает его же.
     */
    private DiameterSpec nextDiameter(DiameterSpec current) {
        List<DiameterSpec> all = diameterPicker.all();
        for (int i = 0; i < all.size() - 1; i++) {
            if (all.get(i).getDiameter() == current.getDiameter()) {
                return all.get(i + 1);
            }
        }
        return current;
    }

    /**
     * Проверка предельной длины (ТЗ 2.4): непрерывные рёбра с одинаковым ДУ
     * вдоль одного OKS-пути не должны превышать maxLengthM этого ДУ.
     * При превышении увеличиваем ДУ всех рёбер сегмента на один шаг.
     *
     * @return true если что-то было увеличено (нужна ещё итерация)
     */
    private boolean enforceMaxLength(List<PathRow> paths, Map<Long, EdgeRow> edges) {
        Set<Long> toIncrease = new HashSet<>();
        for (PathRow p : paths) {
            DiameterSpec segSpec = null;
            double segLen = 0.0;
            List<Long> segEdges = new ArrayList<>();
            for (Long eid : p.edgeIds) {
                EdgeRow e = edges.get(eid);
                if (e == null) continue;
                if (segSpec == null || e.spec.getDiameter() != segSpec.getDiameter()) {
                    checkSegment(segSpec, segLen, segEdges, toIncrease);
                    segSpec = e.spec;
                    segLen = 0.0;
                    segEdges.clear();
                }
                segLen += e.lengthM;
                segEdges.add(eid);
            }
            checkSegment(segSpec, segLen, segEdges, toIncrease);
        }

        if (toIncrease.isEmpty()) return false;
        for (Long eid : toIncrease) {
            EdgeRow e = edges.get(eid);
            if (e != null) {
                e.spec = nextDiameter(e.spec);
            }
        }
        return true;
    }

    private void checkSegment(DiameterSpec spec, double len,
                              List<Long> segEdges, Set<Long> toIncrease) {
        if (spec == null || segEdges.isEmpty()) return;
        if (len > spec.getMaxLengthM()) {
            toIncrease.addAll(segEdges);
        }
    }

    private long chamberCostByDiameter(int maxDiameter) {
        if (maxDiameter <= CHAMBER_DIAM_LIMIT_1) return CHAMBER_COST_1;
        if (maxDiameter <= CHAMBER_DIAM_LIMIT_2) return CHAMBER_COST_2;
        if (maxDiameter <= CHAMBER_DIAM_LIMIT_3) return CHAMBER_COST_3;
        return CHAMBER_COST_4;
    }

    // ===== Запись в variant_feature / variant =====

    private void writeEdges(UUID taskId, String variantId, Map<Long, EdgeRow> edges) {
        for (EdgeRow e : edges.values()) {
            try {
                Map<String, Object> props = new LinkedHashMap<>();
                props.put("variant_id",    variantId);
                props.put("start_node_id", String.valueOf(e.sourceVertex));
                props.put("end_node_id",   String.valueOf(e.targetVertex));
                props.put("flow_tph",      round2(e.flow));
                props.put("diameter",      e.spec.getDiameter());
                props.put("length",        round2(e.lengthM));
                props.put("laying_method", e.isSpecial ? "special" : "base");
                props.put("depth_start",   null);
                props.put("depth_end",     null);
                props.put("cost",          round2(e.cost));

                if (e.isSpecial) {
                    props.put("special_k", e.specialK);
                }

                String json = mapper.writeValueAsString(props);
                jdbc.update(
                        "INSERT INTO variant_feature " +
                                "  (task_id, variant_id, feature_id, object_type, properties, geom_utm, geom_4326) " +
                                "VALUES (?, ?, ?, 'heat_network', ?::jsonb, ST_GeomFromText(?, 32637), " +
                                "        ST_Transform(ST_GeomFromText(?, 32637), 4326))",
                        taskId, variantId, "v_" + e.id, json, e.wkt, e.wkt);
            } catch (Exception ex) {
                log.error("[{}] writeEdge {} failed: {}", taskId, e.id, ex.getMessage());
            }
        }
    }

    private void writeChambers(UUID taskId, String variantId,
                               Map<Long, DiameterSpec> chamberSpec,
                               Map<Long, TargetInfo> targetInfo) {
        for (Map.Entry<Long, DiameterSpec> entry : chamberSpec.entrySet()) {
            Long tv = entry.getKey();
            TargetInfo info = targetInfo.get(tv);

            // Существующая врезка: heat_chamber не создаём, учитываем только в summary
            boolean existing = info != null && "heat_chamber".equals(info.existingType);
            if (existing) {
                continue;
            }

            int diam = entry.getValue().getDiameter();
            long cost = chamberCostByDiameter(diam);

            try {
                Map<String, Object> props = new LinkedHashMap<>();
                props.put("variant_id", variantId);
                props.put("diameter",   diam);
                props.put("cost",       round2(cost));

                String json = mapper.writeValueAsString(props);
                jdbc.update(
                        "INSERT INTO variant_feature " +
                                "  (task_id, variant_id, feature_id, object_type, properties, geom_utm, geom_4326) " +
                                "SELECT ?, ?, ?, 'heat_chamber', ?::jsonb, vv.geom, " +
                                "       ST_Transform(vv.geom, 4326) " +
                                "FROM visibility_vertex vv WHERE vv.id = ?",
                        taskId, variantId, "ch_" + tv, json, tv);
            } catch (Exception ex) {
                log.error("[{}] writeChamber for target {} failed: {}", taskId, tv, ex.getMessage());
            }
        }
    }

    /**
     * P3.2 (ТЗ 7.1): создаёт technical_node в узлах смены ДУ или laying_method
     * между двумя последовательными рёбрами одного варианта.
     *
     * Узел становится technical_node, если:
     *   - через него проходит РОВНО 2 ребра;
     *   - эти рёбра отличаются diameter ИЛИ is_special;
     *   - узел не является OKS и не является target (там heat_chamber).
     *
     * Узлы с 3+ рёбрами — разветвления, по ТЗ 7.1 выполняются только в камерах.
     *
     * @return количество созданных technical_node
     */
    private int writeTechnicalNodes(UUID taskId, String variantId, Map<Long, EdgeRow> edges) {
        // 1. Degree-карта: node_id → список edge_id, примыкающих к узлу
        Map<Long, List<Long>> nodeEdges = new HashMap<>();
        for (EdgeRow e : edges.values()) {
            nodeEdges.computeIfAbsent(e.sourceVertex, k -> new ArrayList<>()).add(e.id);
            nodeEdges.computeIfAbsent(e.targetVertex, k -> new ArrayList<>()).add(e.id);
        }

        // 2. Запрещённые узлы: OKS, target (heat_chamber)
        Set<Long> forbidden = new HashSet<>();
        jdbc.query(
                "SELECT id FROM visibility_vertex " +
                        "WHERE task_id = ? AND vertex_type IN ('oks', 'candidate')",
                rs -> { forbidden.add(rs.getLong("id")); },
                taskId);
        jdbc.query(
                "SELECT DISTINCT target_vertex_id FROM path_result " +
                        "WHERE task_id = ? AND variant_id = ?",
                rs -> { forbidden.add(rs.getLong("target_vertex_id")); },
                taskId, variantId);

        // 3. Ищем узлы с degree==2 и сменой параметров
        int written = 0;
        for (Map.Entry<Long, List<Long>> entry : nodeEdges.entrySet()) {
            Long nodeId = entry.getKey();
            List<Long> eids = entry.getValue();
            if (eids.size() != 2) continue;
            if (forbidden.contains(nodeId)) continue;

            EdgeRow e1 = edges.get(eids.get(0));
            EdgeRow e2 = edges.get(eids.get(1));
            if (e1 == null || e2 == null) continue;

            boolean diamChanged = e1.spec.getDiameter() != e2.spec.getDiameter();
            boolean layChanged  = e1.isSpecial != e2.isSpecial;
            if (!diamChanged && !layChanged) continue;

            try {
                Map<String, Object> props = new LinkedHashMap<>();
                props.put("variant_id", variantId);
                if (diamChanged) {
                    props.put("diameter_from", e1.spec.getDiameter());
                    props.put("diameter_to",   e2.spec.getDiameter());
                }
                if (layChanged) {
                    props.put("laying_from", e1.isSpecial ? "special" : "base");
                    props.put("laying_to",   e2.isSpecial ? "special" : "base");
                }
                String json = mapper.writeValueAsString(props);

                jdbc.update(
                        "INSERT INTO technical_node (task_id, variant_id, geom) " +
                                "SELECT ?, ?, vv.geom FROM visibility_vertex vv WHERE vv.id = ?",
                        taskId, variantId, nodeId);

                jdbc.update(
                        "INSERT INTO variant_feature " +
                                "  (task_id, variant_id, feature_id, object_type, properties, geom_utm, geom_4326) " +
                                "SELECT ?, ?, ?, 'technical_node', ?::jsonb, vv.geom, " +
                                "       ST_Transform(vv.geom, 4326) " +
                                "FROM visibility_vertex vv WHERE vv.id = ?",
                        taskId, variantId, "tn_" + variantId + "_" + nodeId, json, nodeId);

                written++;
            } catch (Exception ex) {
                log.error("[{}][{}] writeTechnicalNode node={} failed: {}",
                        taskId, variantId, nodeId, ex.getMessage());
            }
        }
        return written;
    }

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

    // ===== Внутренние структуры =====

    static class UnconnectedInfo {
        final List<String> ids = new ArrayList<>();
        double penalty = 0.0;
    }

    static class PathRow {
        int clusterId;
        long oksVertexId;
        long targetVertexId;
        List<Long> edgeIds;
        double totalLength;
    }

    static class EdgeRow {
        long id;
        long sourceVertex;
        long targetVertex;
        double lengthM;
        String wkt;
        double flow;
        DiameterSpec spec;
        double cost;
        boolean isSpecial;
        double specialK;
    }

    static class TargetInfo {
        long vertexId;
        String refId;
        String existingType;
    }
}