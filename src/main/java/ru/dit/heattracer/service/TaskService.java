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
import java.util.*;
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
 *
 * <p>V63 (Проблема B): три стратегии capacity-aware. Распределение OKS по
 * точкам врезки координируется через {@link TieInCoordinationService},
 * чтобы degree existing_tie_in не превышал 4 (ТЗ 2.3, 2.4).
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
    private final TieInCoordinationService tieInCoordinationService;
    private final SteinerTreeBuilder steinerTreeBuilder;

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
            RouteBuilderService routeBuilderService,
            TieInCoordinationService tieInCoordinationService,
            SteinerTreeBuilder steinerTreeBuilder
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
        this.tieInCoordinationService = tieInCoordinationService;
        this.steinerTreeBuilder = steinerTreeBuilder;
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
            // V77: сортируем по убыванию размера — крупные кластеры первыми.
            // Capacity-карта общая на все кластеры, а SSP назначает в порядке обработки.
            // Крупный кластер должен получить приоритет, иначе мелкие съедят
            // capacity групп, нужных ему.
            clusters.sort(Comparator.comparing(OksCluster::size).reversed());
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

            // V64: собираем пути ВСЕХ кластеров до начала координации.
            class ClusterPaths {
                final OksCluster cluster;
                final List<Long> oksVertexIds;
                final Map<Long, List<PathResult>> allPaths;
                ClusterPaths(OksCluster c, List<Long> ids, Map<Long, List<PathResult>> p) {
                    this.cluster = c; this.oksVertexIds = ids; this.allPaths = p;
                }
            }

            List<ClusterPaths> clusterPathsList = new ArrayList<>();
            Set<Long> allTargetVertexIds = new HashSet<>();

            for (OksCluster cluster : clusters) {
                List<Long> oksVertexIds = jdbcTemplate.queryForList(
                        "SELECT id FROM visibility_vertex " +
                                " WHERE task_id = ? AND cluster_id = ? AND vertex_type = 'oks' " +
                                " ORDER BY id",
                        Long.class, id, cluster.getClusterId());

                log.info("[{}] Cluster {}: {} OKS vertices", id, cluster.getClusterId(), oksVertexIds.size());

                Map<Long, List<PathResult>> allPaths = new LinkedHashMap<>();
                for (Long oksVertex : oksVertexIds) {
                    List<PathResult> list = pathFinderService.findPathsFromOks(id, cluster.getClusterId(), oksVertex);
                    allPaths.put(oksVertex, list);
                    log.info("[{}]   OKS {} → {} reachable candidates", id, oksVertex, list.size());
                    if (list.isEmpty()) totalOksWithoutPath++;
                    for (PathResult p : list) allTargetVertexIds.add(p.getToVertex());
                }
                clusterPathsList.add(new ClusterPaths(cluster, oksVertexIds, allPaths));
            }

            // V64: одна capacity-карта на вариант, общая для всех кластеров.
            TieInCoordinationService.CapacityState baseCap =
                    tieInCoordinationService.computeCapacities(id, allTargetVertexIds);

            TieInCoordinationService.CapacityState capV1 = baseCap.copy();
            TieInCoordinationService.CapacityState capV2 = baseCap.copy();
            TieInCoordinationService.CapacityState capV3 = baseCap.copy();

            // Второй проход: назначаем с общей capacity-картой на вариант.
            for (ClusterPaths cp : clusterPathsList) {
                int clusterId = cp.cluster.getClusterId();

                // ===== v1: индивидуальное назначение через SSP (capacity-aware) =====
                TieInCoordinationService.SspResult sspV1 =
                        tieInCoordinationService.assignIndividual(cp.allPaths, capV1);
                Map<Long, PathResult> v1 = rebuildSharedPaths(
                        id, clusterId, cp.oksVertexIds, sspV1, capV1);

                // ===== v2: один shared target без capacity (V79) =====
                Map<Long, PathResult> v2;
                Long sharedAll = tieInCoordinationService.pickSharedTargetIgnoringCapacity(
                        new HashSet<>(cp.oksVertexIds), cp.allPaths);
                if (sharedAll != null) {
                    Map<Long, PathResult> tree = steinerTreeBuilder.buildSharedTree(
                            id, clusterId, sharedAll, cp.oksVertexIds);
                    v2 = new LinkedHashMap<>(tree);

                    int fallbackCnt = 0;
                    for (Long oks : cp.oksVertexIds) {
                        if (v2.containsKey(oks)) continue;
                        PathResult p = pathFinderService.findBestPathFromOks(id, clusterId, oks);
                        if (p != null) {
                            v2.put(oks, p);
                            fallbackCnt++;
                        }
                    }
                    log.info("[{}] Cluster {} v2: tree covers {}/{}, {} via fallback",
                            id, clusterId, tree.size(), cp.oksVertexIds.size(), fallbackCnt);
                } else {
                    log.warn("[{}] Cluster {} v2: no shared target — falling back to v1",
                            id, clusterId);
                    v2 = new LinkedHashMap<>(v1);
                }

                // ===== v3: два shared target без capacity (V79) =====
                Map<Long, PathResult> v3;
                if (cp.oksVertexIds.size() >= 4) {
                    Map<Long, Double> oksX = new HashMap<>();
                    jdbcTemplate.query(
                            "SELECT id, ST_X(geom) AS x FROM visibility_vertex " +
                                    "WHERE task_id = ? AND cluster_id = ? AND vertex_type = 'oks'",
                            rs -> { oksX.put(rs.getLong("id"), rs.getDouble("x")); },
                            id, clusterId);

                    List<Long> sorted = new ArrayList<>(cp.oksVertexIds);
                    sorted.sort(Comparator.comparingDouble(oksX::get));
                    int mid = sorted.size() / 2;
                    Set<Long> groupA = new HashSet<>(sorted.subList(0, mid));
                    Set<Long> groupB = new HashSet<>(sorted.subList(mid, sorted.size()));

                    Long tA = tieInCoordinationService.pickSharedTargetIgnoringCapacity(
                            groupA, cp.allPaths);
                    Long tB = tieInCoordinationService.pickSharedTargetIgnoringCapacity(
                            groupB, cp.allPaths);

                    Map<Long, PathResult> treeA = tA == null
                            ? Collections.<Long, PathResult>emptyMap()
                            : steinerTreeBuilder.buildSharedTree(id, clusterId, tA,
                            new ArrayList<>(groupA));
                    Map<Long, PathResult> treeB = (tB == null || tB.equals(tA))
                            ? Collections.<Long, PathResult>emptyMap()
                            : steinerTreeBuilder.buildSharedTree(id, clusterId, tB,
                            new ArrayList<>(groupB));

                    v3 = new LinkedHashMap<>();
                    v3.putAll(treeA);
                    v3.putAll(treeB);

                    int fallbackCnt = 0;
                    for (Long oks : cp.oksVertexIds) {
                        if (v3.containsKey(oks)) continue;
                        PathResult p = pathFinderService.findBestPathFromOks(id, clusterId, oks);
                        if (p != null) {
                            v3.put(oks, p);
                            fallbackCnt++;
                        }
                    }
                    log.info("[{}] Cluster {} v3: 2-tree covers {}/{}, {} via fallback",
                            id, clusterId, v3.size() - fallbackCnt, cp.oksVertexIds.size(), fallbackCnt);
                } else {
                    v3 = new LinkedHashMap<>(v1);
                }

                for (Long oks : cp.oksVertexIds) {
                    if (v1.containsKey(oks)) savePath(id, "v1", clusterId, oks, v1.get(oks));
                    if (v2.containsKey(oks)) savePath(id, "v2", clusterId, oks, v2.get(oks));
                    if (v3.containsKey(oks)) savePath(id, "v3", clusterId, oks, v3.get(oks));
                }
                totalPaths += v1.size();
            }

            int totalOksInClusters = clusterPathsList.stream()
                    .mapToInt(cp -> cp.oksVertexIds.size()).sum();
            int totalDropped = totalOksInClusters - totalPaths;

            log.info("[{}] OKS total={}, assigned={}, dropped_by_coordination={}, no_path={}",
                    id, totalOksInClusters, totalPaths, totalDropped, totalOksWithoutPath);
            System.out.printf("[P1-1 METRIC] ОКС с путём: %d из %d (dropped=%d, no_path=%d)%n",
                    totalPaths, totalOksInClusters, totalDropped, totalOksWithoutPath);

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

            try {
                for (String vid : uniqueVariants) {
                    Path variantFile = state.getInputPath().getParent()
                            .resolve("result_" + vid + ".geojson");
                    exportService.exportVariantFeatures(id, vid, variantFile);
                }
                log.info("[{}] Split variant files written to {}", id, state.getInputPath().getParent());
            } catch (Exception ex) {
                log.warn("[{}] Split variant export failed: {}", id, ex.getMessage());
            }

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
     * V76: SPH (Greedy Steiner) вместо multi-target Dijkstra от virtual_root.
     *
     * <p>Для каждой target-группы SSP запускается {@link SteinerTreeBuilder}:
     * инкрементально строится дерево, шарящее общие стволы. Общие рёбра
     * появляются по построению (не как случайное совпадение N независимых A*).
     *
     * <p>Fallback: OKS, для которых дерево не нашло путь (V55 транзит,
     * изолированность) — берётся прямой путь из {@code ssp.assigned}.
     */
    /**
     * V77: перестраивает пути для кластера.
     *
     * <p>Три уровня:
     * <ol>
     *   <li>SPH-деревья по target-группам SSP (OKS, назначенные SSP).</li>
     *   <li>SSP-assigned, не попавшие в дерево — берём SSP-путь.</li>
     *   <li>OKS, дропнутые SSP из-за capacity — fallback через
     *       {@link PathFinderService#findBestPathFromOks}. Гарантирует 17/17,
     *       пока у OKS есть хотя бы один путь в графе видимости.</li>
     * </ol>
     *
     * <p>V77-fix: representative для дерева — target-вершина, к которой SSP
     * направил больше всего OKS из группы (не обязательно {@code groupId},
     * который может быть не связан в графе видимости).
     */
    private Map<Long, PathResult> rebuildSharedPaths(
            UUID taskId, int clusterId,
            List<Long> allOksVertices,
            TieInCoordinationService.SspResult ssp,
            TieInCoordinationService.CapacityState state) {

        Map<Long, PathResult> result = new LinkedHashMap<>();

        // ===== 1. SPH-деревья по target-группам =====
        if (ssp != null && !ssp.isEmpty()) {
            for (Map.Entry<Long, List<Long>> entry : ssp.byTargetGroup.entrySet()) {
                long groupId = entry.getKey();
                List<Long> oksList = entry.getValue();
                if (oksList == null || oksList.isEmpty()) continue;

                // V77: фактический representative — target-вершина, которую
                // SSP реально использовал (группа может содержать несколько
                // вершин с одинаковой геометрией, но разной связностью в графе).
                Map<Long, Integer> targetUsage = new HashMap<>();
                for (Long oks : oksList) {
                    PathResult p = ssp.assigned.get(oks);
                    if (p != null) targetUsage.merge(p.getToVertex(), 1, Integer::sum);
                }
                long representative = targetUsage.entrySet().stream()
                        .max(Map.Entry.comparingByValue())
                        .map(Map.Entry::getKey)
                        .orElse(groupId);

                Map<Long, PathResult> tree = steinerTreeBuilder.buildSharedTree(
                        taskId, clusterId, representative, oksList);
                result.putAll(tree);
            }

            // ===== 2. SSP-assigned, не покрытые деревом =====
            for (Map.Entry<Long, PathResult> e : ssp.assigned.entrySet()) {
                if (!result.containsKey(e.getKey())) {
                    result.put(e.getKey(), e.getValue());
                }
            }
        }

        // ===== 3. Fallback для OKS, дропнутых SSP =====
        // (capacity exhausted — не покрываются byTargetGroup и assigned)
        if (allOksVertices != null) {
            int fallbackCount = 0;
            for (Long oks : allOksVertices) {
                if (result.containsKey(oks)) continue;
                PathResult fb = pathFinderService.findBestPathFromOks(taskId, clusterId, oks);
                if (fb != null) {
                    result.put(oks, fb);
                    fallbackCount++;
                } else {
                    log.warn("[{}] Cluster {}: no fallback path for SSP-dropped OKS {}",
                            taskId, clusterId, oks);
                }
            }
            if (fallbackCount > 0) {
                log.info("[{}] Cluster {}: {} SSP-dropped OKS recovered via fallback",
                        taskId, clusterId, fallbackCount);
            }
        }

        return result;
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

    // Метод pickSharedTarget удалён в V63: логика переехала в
    // TieInCoordinationService.pickSharedTargetForGroup (capacity-aware).

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