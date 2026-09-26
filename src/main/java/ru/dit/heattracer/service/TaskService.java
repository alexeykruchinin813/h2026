package ru.dit.heattracer.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import ru.dit.heattracer.io.CopyService;
import ru.dit.heattracer.io.GeoJsonStreamReader;
import ru.dit.heattracer.io.InputFeatureWriter;
import ru.dit.heattracer.model.DiameterSpec;
import ru.dit.heattracer.model.GraphHealthReport;
import ru.dit.heattracer.model.GraphResult;
import ru.dit.heattracer.model.OksCluster;
import ru.dit.heattracer.model.PathResult;
import ru.dit.heattracer.model.TieInCandidate;
import ru.dit.heattracer.model.VisibilityGraphResult;
import ru.dit.heattracer.validator.InputValidator;
import ru.dit.heattracer.validator.ValidationReport;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;

/**
 * Пайплайн обработки задачи: парсинг → валидация → граф → кластеризация →
 * поиск путей A* → построение маршрутов (до 3 содержательно отличающихся
 * вариантов) → дедупликация → ранжирование → экспорт GeoJSON.
 *
 * <p>P2.2 (ТЗ 2.8): три стратегии выбора точек врезки:
 * <ul>
 *   <li>v1 — individual: каждый OKS к своему ближайшему tie-in (min path cost);</li>
 *   <li>v2 — shared: все OKS кластера используют один общий target;</li>
 *   <li>v3 — sub-split: кластер делится на 2 подгруппы, каждая со своим shared target.</li>
 * </ul>
 * После построения — дедупликация по score (4 знака); на «чистом» наборе,
 * где все стратегии дают одну конфигурацию, в GeoJSON останется 1 вариант.
 */
@Service
public class TaskService {

    private static final Logger log = LoggerFactory.getLogger(TaskService.class);

    private final ConcurrentHashMap<UUID, TaskState> tasks = new ConcurrentHashMap<>();
    private final Executor calcExecutor;
    private final Path storageRoot;
    private final GeoJsonStreamReader geoJsonReader;
    private final CopyService copyService;
    private final JdbcTemplate jdbcTemplate;
    private final InputValidator inputValidator;
    private final ExportService exportService;
    private final GraphService graphService;
    private final GraphHealthService graphHealthService;
    private final ClusterService clusterService;
    private final TieInService tieInService;
    private final VisibilityGraphService visibilityGraphService;
    private final HybridVisibilityGraphService hybridVisibilityGraphService;
    private final PathFinderService pathFinderService;
    private final DiameterPicker diameterPicker;
    private final RouteBuilderService routeBuilderService;

    public TaskService(
            @Qualifier("calcExecutor") Executor calcExecutor,
            @Value("${app.storage.dir:/tmp/heat-tracer}") String storageDir,
            GeoJsonStreamReader geoJsonReader,
            CopyService copyService,
            JdbcTemplate jdbcTemplate,
            InputValidator inputValidator,
            ExportService exportService,
            GraphService graphService,
            GraphHealthService graphHealthService,
            ClusterService clusterService,
            TieInService tieInService,
            VisibilityGraphService visibilityGraphService,
            HybridVisibilityGraphService hybridVisibilityGraphService,
            PathFinderService pathFinderService,
            DiameterPicker diameterPicker,
            RouteBuilderService routeBuilderService
    ) throws IOException {
        this.calcExecutor = calcExecutor;
        this.storageRoot = Paths.get(storageDir);
        this.geoJsonReader = geoJsonReader;
        this.copyService = copyService;
        this.jdbcTemplate = jdbcTemplate;
        this.inputValidator = inputValidator;
        this.exportService = exportService;
        this.graphService = graphService;
        this.graphHealthService = graphHealthService;
        this.clusterService = clusterService;
        this.tieInService = tieInService;
        this.visibilityGraphService = visibilityGraphService;
        this.hybridVisibilityGraphService = hybridVisibilityGraphService;
        this.pathFinderService = pathFinderService;
        this.diameterPicker = diameterPicker;
        this.routeBuilderService = routeBuilderService;
        Files.createDirectories(storageRoot);
        log.info("Storage root: {}", storageRoot.toAbsolutePath());
    }

    /**
     * Сохраняет файл, создаёт задачу в БД и в памяти, ставит в очередь на расчёт.
     */
    public UUID submit(MultipartFile file) throws IOException {
        UUID taskId = UUID.randomUUID();
        Path taskDir = storageRoot.resolve(taskId.toString());
        Files.createDirectories(taskDir);

        Path inputPath = taskDir.resolve("input.geojson");
        try (InputStream in = file.getInputStream()) {
            Files.copy(in, inputPath, StandardCopyOption.REPLACE_EXISTING);
        }
        log.info("[{}] Input saved: {} ({} bytes)", taskId, inputPath, Files.size(inputPath));

        // Запись задачи в БД — нужна для SQL-запросов, которые ссылаются на task.id
        jdbcTemplate.update(
                "INSERT INTO task (id, status, stage, percent, input_path) " +
                        "VALUES (?, 'RUNNING', 'INIT', 0, ?)",
                taskId, inputPath.toString());

        TaskState state = new TaskState(taskId, inputPath);
        tasks.put(taskId, state);

        calcExecutor.execute(() -> runCalculation(state));
        return taskId;
    }

    private void runCalculation(TaskState state) {
        UUID id = state.getId();
        try {
            log.info("[{}] Calculation started", id);

            // ===== 1. ПАРСИНГ + ЗАГРУЗКА В БД (B1 + B2) =====
            state.setStage("PARSING");
            state.setPercent(10);

            InputFeatureWriter writer = new InputFeatureWriter(id, copyService, jdbcTemplate);
            geoJsonReader.read(state.getInputPath(), writer::append);
            long loaded = writer.finish();

            log.info("[{}] Loaded {} features into DB", id, loaded);
            state.setPercent(35);
            state.setStage("LOADED");

            // ===== 2. ВАЛИДАЦИЯ (B3 + U1) =====
            state.setStage("VALIDATING");
            state.setPercent(45);

            ValidationReport report = inputValidator.validate(id);

            log.info("[{}] Validation: total={}, errors={}, warnings={}",
                    id, report.getTotalFeatures(),
                    report.getErrors().size(), report.getWarnings().size());

            if (report.hasErrors()) {
                for (String err : report.getErrors()) {
                    log.error("[{}] VALIDATION ERROR: {}", id, err);
                }
            }
            if (report.hasWarnings()) {
                for (String warn : report.getWarnings()) {
                    log.warn("[{}] VALIDATION WARNING: {}", id, warn);
                }
            }

            state.setPercent(55);
            state.setStage(report.hasErrors() ? "VALIDATED_WITH_ERRORS" : "VALIDATED");

            // ===== 3. ПОСТРОЕНИЕ ГРАФА СУЩЕСТВУЮЩЕЙ СЕТИ (C1) =====
            state.setStage("BUILDING_GRAPH");
            state.setPercent(65);

            GraphResult graph = graphService.build(id);

            log.info("[{}] Graph: {} nodes, {} edges, sourceNodeId={}",
                    id, graph.getNodes(), graph.getEdges(), graph.getSourceNodeId());

            // ===== 3.1. HEALTH CHECK ГРАФА (C2) =====
            GraphHealthReport health = graphHealthService.check(id);

            if (!health.isHealthy()) {
                log.warn("[{}] Graph health issues detected: {}",
                        id, health.getWarnings());
            }

            state.setPercent(75);
            state.setStage("GRAPH_BUILT");

            // ===== 4. КЛАСТЕРИЗАЦИЯ ОКС (C4) =====
            state.setStage("CLUSTERING_OKS");
            state.setPercent(80);

            List<OksCluster> clusters = clusterService.cluster(id);
            log.info("[{}] OKS clusters: {}", id, clusters.size());

            state.setPercent(83);
            state.setStage("CLUSTERED");

            // ===== 5. ПОИСК КАНДИДАТОВ ТОЧЕК ВРЕЗКИ (C5) =====
            state.setStage("FINDING_TIE_IN");
            state.setPercent(85);

            for (OksCluster cluster : clusters) {
                List<TieInCandidate> candidates = tieInService.findCandidates(id, cluster);
                log.info("[{}] Cluster {}: {} candidates",
                        id, cluster.getClusterId(), candidates.size());
            }

            state.setPercent(87);
            state.setStage("TIE_IN_FOUND");

            // ===== 6. ПОСТРОЕНИЕ ГРАФА ВИДИМОСТИ (D1) — ПАРАЛЛЕЛЬНО ПО КЛАСТЕРАМ =====
            state.setStage("BUILDING_VISIBILITY");
            state.setPercent(88);

            int clusterCount = clusters.size();
            log.info("[{}] Building hybrid visibility graphs for {} clusters in parallel...",
                    id, clusterCount);

            clusters.parallelStream().forEach(cluster -> {
                try {
                    // E1: подбор предварительного ДУ по суммарному расходу кластера
                    DiameterSpec provisionalSpec = diameterPicker.pickForFlow(cluster.getTotalFlow());
                    int provisionalDiameter = provisionalSpec.getDiameter();

                    log.info("[{}] Cluster {}: provisional {} for flow {} т/ч",
                            id, cluster.getClusterId(),
                            provisionalSpec, cluster.getTotalFlow());

                    // HYBRID APPROACH: SQL coarse graph + JTS validation against individual polygons
                    VisibilityGraphResult vg = hybridVisibilityGraphService.buildHybrid(
                            id, cluster.getClusterId(), provisionalDiameter);

                    log.info("[{}] Cluster {}: {}",
                            id, cluster.getClusterId(), vg);
                } catch (Exception e) {
                    log.error("[{}] Cluster {}: visibility graph build failed",
                            id, cluster.getClusterId(), e);
                    throw e; // Re-throw to fail the task
                }
            });

            state.setPercent(90);
            state.setStage("VISIBILITY_BUILT");

            // ===== 7. ПОИСК ПУТЕЙ ОТ ОКС ДО КАНДИДАТОВ (D2) =====
            state.setStage("FINDING_PATHS");
            state.setPercent(91);

            int totalPaths = 0;
            int totalOksWithoutPath = 0;

            for (OksCluster cluster : clusters) {
                List<Long> oksVertexIds = jdbcTemplate.queryForList(
                        "SELECT id FROM visibility_vertex " +
                                " WHERE task_id = ? AND cluster_id = ? AND vertex_type = 'oks' " +
                                " ORDER BY id",
                        Long.class, id, cluster.getClusterId());

                log.info("[{}] Cluster {}: {} OKS vertices",
                        id, cluster.getClusterId(), oksVertexIds.size());

                // P2.2: собираем все пути от каждого OKS
                Map<Long, List<PathResult>> allPaths = new LinkedHashMap<>();
                for (Long oksVertex : oksVertexIds) {
                    List<PathResult> list = pathFinderService.findPathsFromOks(
                            id, cluster.getClusterId(), oksVertex);
                    allPaths.put(oksVertex, list);
                    log.info("[{}]   OKS {} → {} reachable candidates",
                            id, oksVertex, list.size());
                    if (list.isEmpty()) {
                        totalOksWithoutPath++;
                    }
                }

                // ===== Стратегия v1: individual =====
                // Каждый OKS идёт в свой ближайший (min path cost) tie-in.
                Map<Long, PathResult> v1 = new LinkedHashMap<>();
                for (Map.Entry<Long, List<PathResult>> e : allPaths.entrySet()) {
                    Long oks = e.getKey();
                    List<PathResult> list = e.getValue();
                    if (list.isEmpty()) continue;
                    PathResult best = list.get(0);
                    for (PathResult p : list) {
                        if (p.getTotalCost() < best.getTotalCost()) best = p;
                    }
                    v1.put(oks, best);
                }

                // ===== Стратегия v2: shared =====
                // Один общий target на весь кластер (min суммарная стоимость).
                Long sharedTargetAll = pickSharedTarget(new HashSet<>(oksVertexIds), allPaths);
                Map<Long, PathResult> v2 = new LinkedHashMap<>();
                for (Map.Entry<Long, List<PathResult>> e : allPaths.entrySet()) {
                    Long oks = e.getKey();
                    PathResult chosen = null;
                    if (sharedTargetAll != null) {
                        for (PathResult p : e.getValue()) {
                            if (p.getToVertex() == sharedTargetAll.longValue()) {
                                chosen = p;
                                break;
                            }
                        }
                    }
                    if (chosen == null) chosen = v1.get(oks);
                    if (chosen != null) v2.put(oks, chosen);
                }

                // ===== Стратегия v3: sub-split =====
                // Кластер делится на 2 подгруппы по X-координате, каждая со своим
                // shared target. Содержательно отличается от v1/v2 (ТЗ 2.8:
                // «разделение на несколько отдельных частей новой сети»).
                Map<Long, PathResult> v3 = new LinkedHashMap<>();
                if (oksVertexIds.size() >= 4) {
                    Map<Long, Double> oksX = new HashMap<>();
                    jdbcTemplate.query(
                            "SELECT id, ST_X(geom) AS x FROM visibility_vertex " +
                                    "WHERE task_id = ? AND cluster_id = ? AND vertex_type = 'oks'",
                            rs -> { oksX.put(rs.getLong("id"), rs.getDouble("x")); },
                            id, cluster.getClusterId());

                    List<Long> sortedOks = new ArrayList<>(oksVertexIds);
                    sortedOks.sort(Comparator.comparingDouble(oksX::get));

                    int mid = sortedOks.size() / 2;
                    Set<Long> groupA = new HashSet<>(sortedOks.subList(0, mid));
                    Set<Long> groupB = new HashSet<>(sortedOks.subList(mid, sortedOks.size()));

                    Long sharedA = pickSharedTarget(groupA, allPaths);
                    Long sharedB = pickSharedTarget(groupB, allPaths);

                    for (Long oks : oksVertexIds) {
                        Long target = groupA.contains(oks) ? sharedA : sharedB;
                        PathResult chosen = null;
                        if (target != null) {
                            for (PathResult p : allPaths.get(oks)) {
                                if (p.getToVertex() == target.longValue()) {
                                    chosen = p;
                                    break;
                                }
                            }
                        }
                        if (chosen == null) chosen = v1.get(oks);
                        if (chosen != null) v3.put(oks, chosen);
                    }
                } else {
                    // Малый кластер (< 4 OKS) — sub-split не имеет смысла, копия v1
                    v3.putAll(v1);
                }

                // Сохраняем все три стратегии (дедупликация — позже)
                int clusterId = cluster.getClusterId();
                for (Long oks : oksVertexIds) {
                    if (v1.containsKey(oks)) savePath(id, "v1", clusterId, oks, v1.get(oks));
                    if (v2.containsKey(oks)) savePath(id, "v2", clusterId, oks, v2.get(oks));
                    if (v3.containsKey(oks)) savePath(id, "v3", clusterId, oks, v3.get(oks));
                }
                totalPaths += v1.size();
            }

            log.info("[{}] Total paths found: {}, OKS without path: {}",
                    id, totalPaths, totalOksWithoutPath);

            int oksTotal = totalPaths + totalOksWithoutPath;
            System.out.printf("[P1-1 METRIC] ОКС с найденным путём: %d из %d%n",
                    totalPaths, oksTotal);

            state.setPercent(92);
            state.setStage("PATHS_FOUND");

            // ===== 8. ПОСТРОЕНИЕ МАРШРУТОВ (три варианта) =====
            state.setStage("BUILDING_ROUTE");
            state.setPercent(93);

            List<String> variantIds = new ArrayList<>();
            for (String vid : List.of("v1", "v2", "v3")) {
                try {
                    routeBuilderService.buildRoute(id, vid);
                    variantIds.add(vid);
                } catch (Exception ex) {
                    log.error("[{}] buildRoute {} failed: {}", id, vid, ex.getMessage());
                }
            }

            // ===== Дедупликация вариантов по score (ТЗ 2.8) =====
            // Если две стратегии дали идентичный score — оставляем только первую.
            // Это гарантирует, что в GeoJSON все варианты содержательно отличаются.
            List<String> uniqueVariants = new ArrayList<>();
            Set<Double> seenScores = new HashSet<>();
            for (String vid : variantIds) {
                Double score = jdbcTemplate.queryForObject(
                        "SELECT score FROM variant WHERE task_id = ? AND id = ?",
                        Double.class, id, vid);
                if (score == null) continue;
                double rounded = Math.round(score * 10000.0) / 10000.0;
                if (seenScores.contains(rounded)) {
                    log.warn("[{}] Variant {} dropped: score={} identical to already kept",
                            id, vid, rounded);
                    jdbcTemplate.update("DELETE FROM path_result WHERE task_id = ? AND variant_id = ?",
                            id, vid);
                    jdbcTemplate.update("DELETE FROM variant_feature WHERE task_id = ? AND variant_id = ?",
                            id, vid);
                    jdbcTemplate.update("DELETE FROM variant WHERE task_id = ? AND id = ?",
                            id, vid);
                } else {
                    seenScores.add(rounded);
                    uniqueVariants.add(vid);
                }
            }

            // Переранжирование после дедупликации (ТЗ 2.8: rank 1 = min score)
            jdbcTemplate.update(
                    "UPDATE variant v SET rank = sub.rn FROM ( " +
                            "  SELECT id, ROW_NUMBER() OVER (ORDER BY score ASC) AS rn " +
                            "  FROM variant WHERE task_id = ? " +
                            ") sub WHERE v.task_id = ? AND v.id = sub.id",
                    id, id);

            // Синхронизируем rank в variant_summary.properties (иначе экспорт
            // покажет хардкод 1 у всех вариантов)
            jdbcTemplate.update(
                    "UPDATE variant_feature vf SET properties = " +
                            "  jsonb_set(vf.properties, '{rank}', to_jsonb(v.rank)) " +
                            "FROM variant v " +
                            "WHERE vf.task_id = ? AND vf.task_id = v.task_id " +
                            "  AND vf.variant_id = v.id AND vf.object_type = 'variant_summary'",
                    id);

            // Прозрачность: фиксируем причину удаления каждого отброшенного варианта
            Set<String> dropped = new HashSet<>(variantIds);
            uniqueVariants.forEach(dropped::remove);
            if (!dropped.isEmpty()) {
                log.warn("[{}] Variants dropped by dedup (same score): {} " +
                                "— на плотном наборе разные стратегии дают идентичную конфигурацию",
                        id, dropped);
            }
            log.info("[{}] Unique variants after dedup: {}", id, uniqueVariants);

            // ===== 9. ЭКСПОРТ РЕЗУЛЬТАТА (все уникальные варианты) =====
            state.setStage("EXPORTING");
            state.setPercent(95);

            Path resultPath = state.getInputPath().getParent().resolve("result.geojson");
            long written = exportService.exportAllVariants(id, resultPath);

            log.info("[{}] Result written: {} features (all variants) to {}",
                    id, written, resultPath);
            state.setResultPath(resultPath);

            // ===== 10. ЗАВЕРШЕНИЕ =====
            state.setPercent(100);
            state.setStage("DONE");
            state.setStatus(TaskState.Status.DONE);
            log.info("[{}] Calculation done", id);

            jdbcTemplate.update(
                    "UPDATE task SET status='DONE', stage='DONE', percent=100, " +
                            "       result_path=?, finished_at=now() WHERE id=?",
                    state.getResultPath() != null ? state.getResultPath().toString() : null,
                    id);

        } catch (Exception e) {
            state.setStatus(TaskState.Status.FAILED);
            state.setErrorMessage(e.getMessage());
            log.error("[{}] Calculation failed", id, e);

            jdbcTemplate.update(
                    "UPDATE task SET status='FAILED', stage='FAILED', " +
                            "       error_message=?, finished_at=now() WHERE id=?",
                    e.getMessage(), id);
        } finally {
            state.markFinished();
        }
    }

    /**
     * V42/V43: сохраняет путь A* в path_result для конкретного варианта.
     */
    private void savePath(UUID taskId, String variantId, int clusterId,
                          long oksVertex, PathResult path) {
        try {
            jdbcTemplate.update(con -> {
                var ps = con.prepareStatement(
                        "INSERT INTO path_result " +
                                "  (task_id, variant_id, cluster_id, oks_vertex_id, target_vertex_id, " +
                                "   path_geom, total_cost, total_length_m, edge_count, edge_ids) " +
                                "VALUES (?, ?, ?, ?, ?, ST_GeomFromText(?, 32637), ?, ?, ?, ?) " +
                                "ON CONFLICT (task_id, variant_id, oks_vertex_id) DO UPDATE SET " +
                                "   cluster_id       = EXCLUDED.cluster_id, " +
                                "   target_vertex_id = EXCLUDED.target_vertex_id, " +
                                "   path_geom        = EXCLUDED.path_geom, " +
                                "   total_cost       = EXCLUDED.total_cost, " +
                                "   total_length_m   = EXCLUDED.total_length_m, " +
                                "   edge_count       = EXCLUDED.edge_count, " +
                                "   edge_ids         = EXCLUDED.edge_ids");
                ps.setObject(1, taskId);
                ps.setString(2, variantId);
                ps.setInt(3, clusterId);
                ps.setLong(4, oksVertex);
                ps.setLong(5, path.getToVertex());
                ps.setString(6, path.getPathWkt());
                ps.setDouble(7, path.getTotalCost());
                ps.setDouble(8, path.getTotalLength());
                ps.setInt(9, path.getEdgeCount());
                Long[] ids = path.getEdgeIds().toArray(new Long[0]);
                ps.setArray(10, con.createArrayOf("bigint", ids));
                return ps;
            });
        } catch (Exception e) {
            log.error("[{}][{}] savePath failed for OKS {}: {}",
                    taskId, variantId, oksVertex, e.getMessage());
        }
    }

    /**
     * Выбирает shared target для группы OKS: target с максимальным покрытием,
     * среди равных — с минимальной суммой path.cost.
     * Возвращает null, если ни один target не достижим ни от одного OKS группы.
     */
    private Long pickSharedTarget(Set<Long> oksGroup, Map<Long, List<PathResult>> allPaths) {
        Map<Long, Double> sumByTarget = new HashMap<>();
        Map<Long, Integer> coverByTarget = new HashMap<>();
        for (Long oks : oksGroup) {
            List<PathResult> paths = allPaths.get(oks);
            if (paths == null) continue;
            for (PathResult p : paths) {
                sumByTarget.merge(p.getToVertex(), p.getTotalCost(), Double::sum);
                coverByTarget.merge(p.getToVertex(), 1, Integer::sum);
            }
        }
        Long best = null;
        double bestSum = Double.MAX_VALUE;
        int bestCover = -1;
        for (Map.Entry<Long, Double> e : sumByTarget.entrySet()) {
            int cover = coverByTarget.getOrDefault(e.getKey(), 0);
            if (cover > bestCover || (cover == bestCover && e.getValue() < bestSum)) {
                best = e.getKey();
                bestSum = e.getValue();
                bestCover = cover;
            }
        }
        return best;
    }

    public Optional<TaskState> get(UUID id) {
        return Optional.ofNullable(tasks.get(id));
    }

    public Collection<TaskState> all() {
        return tasks.values();
    }

    public void remove(UUID id) {
        tasks.remove(id);
    }
}