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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;


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

            // Параллельная обработка кластеров (OPTIMIZATION: parallel stream)
            // Используем гибридный подход (SQL + JTS) для решения проблемы связности в плотной застройке
            int clusterCount = clusters.size();
            log.info("[{}] Building hybrid visibility graphs for {} clusters in parallel...", id, clusterCount);

            clusters.parallelStream().forEach(cluster -> {
                try {
                    // E1: подбор предварительного ДУ по суммарному расходу кластера
                    DiameterSpec provisionalSpec = diameterPicker.pickForFlow(cluster.getTotalFlow());
                    int provisionalDiameter = provisionalSpec.getDiameter();

                    log.info("[{}] Cluster {}: provisional {} for flow {} т/ч",
                            id, cluster.getClusterId(),
                            provisionalSpec, cluster.getTotalFlow());

                    // HYBRID APPROACH: SQL coarse graph + JTS validation against individual polygons
                    // Решает проблему разорванного графа при слиянии буферов OKS
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
                // Получаем ID вершин-ОКС кластера
                List<Long> oksVertexIds = jdbcTemplate.queryForList(
                        "SELECT id FROM visibility_vertex " +
                                " WHERE task_id = ? AND cluster_id = ? AND vertex_type = 'oks' " +
                                " ORDER BY id",
                        Long.class, id, cluster.getClusterId());

                log.info("[{}] Cluster {}: {} OKS vertices",
                        id, cluster.getClusterId(), oksVertexIds.size());

                // P2.2: собираем все пути от каждого OKS, чтобы выбрать 3 стратегии
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

                // v1: минимальная стоимость для каждого OKS
                // v2: минимальная стоимость среди target != target(v1)
                // v3: один общий target на кластер (min сумма)
                Map<Long, PathResult> v1 = new LinkedHashMap<>();
                Map<Long, PathResult> v2 = new LinkedHashMap<>();
                for (Map.Entry<Long, List<PathResult>> e : allPaths.entrySet()) {
                    Long oks = e.getKey();
                    List<PathResult> sorted = new ArrayList<>(e.getValue());
                    sorted.sort(Comparator.comparingDouble(PathResult::getTotalCost));
                    if (sorted.isEmpty()) continue;

                    PathResult best1 = sorted.get(0);
                    v1.put(oks, best1);

                    PathResult best2 = null;
                    for (PathResult p : sorted) {
                        if (p.getToVertex() != best1.getToVertex()) { best2 = p; break; }
                    }
                    v2.put(oks, best2 != null ? best2 : best1);   // нет альтернативы — тот же путь
                }

                // v3: target с минимальной суммой стоимостей путей ВСЕХ OKS кластера
                Map<Long, Double> sumByTarget = new HashMap<>();
                Map<Long, Integer> coverByTarget = new HashMap<>();
                for (List<PathResult> list : allPaths.values()) {
                    for (PathResult p : list) {
                        sumByTarget.merge(p.getToVertex(), p.getTotalCost(), Double::sum);
                        coverByTarget.merge(p.getToVertex(), 1, Integer::sum);
                    }
                }
                long bestTarget = -1;
                double bestSum = Double.MAX_VALUE;
                int oksInCluster = allPaths.size();
                for (Map.Entry<Long, Double> e : sumByTarget.entrySet()) {
                    // Приоритет: покрытие всех OKS кластера; среди таких — минимальная сумма
                    int cover = coverByTarget.getOrDefault(e.getKey(), 0);
                    boolean fullCover = cover >= oksInCluster;
                    boolean betterCover = bestTarget < 0
                            || fullCover && coverByTarget.getOrDefault(bestTarget, 0) < oksInCluster;
                    if (betterCover || (cover >= coverByTarget.getOrDefault(bestTarget, 0) && e.getValue() < bestSum)) {
                        bestTarget = e.getKey();
                        bestSum = e.getValue();
                    }
                }

                Map<Long, PathResult> v3 = new LinkedHashMap<>();
                for (Map.Entry<Long, List<PathResult>> e : allPaths.entrySet()) {
                    PathResult chosen = null;
                    for (PathResult p : e.getValue()) {
                        if (p.getToVertex() == bestTarget) { chosen = p; break; }
                    }
                    if (chosen == null && !e.getValue().isEmpty()) {
                        // OKS не достигает общего target — берём v1 (частичный результат)
                        chosen = v1.get(e.getKey());
                    }
                    if (chosen != null) v3.put(e.getKey(), chosen);
                }

                // Сохраняем все три варианта в path_result
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

            // P1-1: метрика для теста HybridConnectivityIT (assert >= 15/17)
            int oksTotal = totalPaths + totalOksWithoutPath;
            System.out.printf("[P1-1 METRIC] ОКС с найденным путём: %d из %d%n",
                    totalPaths, oksTotal);

            state.setPercent(92);
            state.setStage("PATHS_FOUND");

            // ===== 8. ПОСТРОЕНИЕ МАРШРУТОВ (P2.2: три варианта) =====
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

            // Ранжирование (ТЗ 2.8): по возрастанию score
            jdbcTemplate.update(
                    "UPDATE variant v SET rank = sub.rn FROM ( " +
                            "  SELECT id, ROW_NUMBER() OVER (ORDER BY score ASC) AS rn " +
                            "  FROM variant WHERE task_id = ? " +
                            ") sub WHERE v.task_id = ? AND v.id = sub.id",
                    id, id);

            log.info("[{}] Variants built and ranked: {}", id, variantIds);

            // ===== 9. ЭКСПОРТ РЕЗУЛЬТАТА (только v1) =====
            state.setStage("EXPORTING");
            state.setPercent(95);

            Path resultPath = state.getInputPath().getParent().resolve("result.geojson");
            long written = exportService.exportVariantFeatures(id, "v1", resultPath);

            log.info("[{}] Result written: {} features to {}", id, written, resultPath);
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