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
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;

import java.sql.Array;

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

                for (Long oksVertex : oksVertexIds) {
                    PathResult best = pathFinderService.findBestPathFromOks(
                            id, cluster.getClusterId(), oksVertex);

                    if (best != null && best.isFound()) {
                        log.info("[{}]   OKS {} → candidate {} " +
                                        "(cost={}, length={} м, edges={})",
                                id, oksVertex, best.getToVertex(),
                                String.format("%.2f", best.getTotalCost()),
                                String.format("%.2f", best.getTotalLength()),
                                best.getEdgeCount());
                        savePath(id, cluster.getClusterId(), oksVertex, best);
                        totalPaths++;
                    } else {
                        log.warn("[{}]   OKS {} → NO PATH FOUND", id, oksVertex);
                        totalOksWithoutPath++;
                    }
                }
            }

            log.info("[{}] Total paths found: {}, OKS without path: {}",
                    id, totalPaths, totalOksWithoutPath);

            // P1-1: метрика для теста HybridConnectivityIT (assert >= 15/17)
            int oksTotal = totalPaths + totalOksWithoutPath;
            System.out.printf("[P1-1 METRIC] ОКС с найденным путём: %d из %d%n",
                    totalPaths, oksTotal);

            state.setPercent(92);
            state.setStage("PATHS_FOUND");

            // ===== 8. ПОСТРОЕНИЕ МАРШРУТА (P1.3) =====
            state.setStage("BUILDING_ROUTE");
            state.setPercent(93);

            String variantId = routeBuilderService.buildRoute(id, "v1");
            log.info("[{}] Route built: variantId={}", id, variantId);

            // ===== 9. ЭКСПОРТ РЕЗУЛЬТАТА =====
            state.setStage("EXPORTING");
            state.setPercent(95);

            Path resultPath = state.getInputPath().getParent().resolve("result.geojson");
            long written = exportService.exportVariantFeatures(id, variantId, resultPath);

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
     * V42: сохраняет путь A* в path_result для последующего экспорта GeoJSON.
     * ON CONFLICT защищает от повторного вызова (idempotent retry пайплайна).
     */
    private void savePath(UUID taskId, int clusterId, long oksVertex, PathResult path) {
        try {
            jdbcTemplate.update(con -> {
                var ps = con.prepareStatement(
                        "INSERT INTO path_result " +
                                "  (task_id, cluster_id, oks_vertex_id, target_vertex_id, " +
                                "   path_geom, total_cost, total_length_m, edge_count, edge_ids) " +
                                "VALUES (?, ?, ?, ?, ST_GeomFromText(?, 32637), ?, ?, ?, ?) " +
                                "ON CONFLICT (task_id, oks_vertex_id) DO UPDATE SET " +
                                "   cluster_id       = EXCLUDED.cluster_id, " +
                                "   target_vertex_id = EXCLUDED.target_vertex_id, " +
                                "   path_geom        = EXCLUDED.path_geom, " +
                                "   total_cost       = EXCLUDED.total_cost, " +
                                "   total_length_m   = EXCLUDED.total_length_m, " +
                                "   edge_count       = EXCLUDED.edge_count, " +
                                "   edge_ids         = EXCLUDED.edge_ids");
                ps.setObject(1, taskId);
                ps.setInt(2, clusterId);
                ps.setLong(3, oksVertex);
                ps.setLong(4, path.getToVertex());
                ps.setString(5, path.getPathWkt());
                ps.setDouble(6, path.getTotalCost());
                ps.setDouble(7, path.getTotalLength());
                ps.setInt(8, path.getEdgeCount());
                Long[] ids = path.getEdgeIds().toArray(new Long[0]);
                ps.setArray(9, con.createArrayOf("bigint", ids));
                return ps;
            });
        } catch (Exception e) {
            log.error("[{}] savePath failed for OKS vertex {} (path length {} m): {}",
                    taskId, oksVertex, path.getTotalLength(), e.getMessage());
            // Не падаем: по ТЗ п. 2.9 частичный результат допустим.
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