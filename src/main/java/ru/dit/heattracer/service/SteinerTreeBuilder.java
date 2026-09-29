package ru.dit.heattracer.service;

import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.io.WKTReader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import ru.dit.heattracer.model.PathResult;

import java.util.*;

/**
 * V76/V77/V78: Greedy Steiner Tree (farthest-first).
 *
 * <p>Строит дерево, шарящее общие стволы между OKS группы, вместо N
 * независимых A* (радиальная «звезда»).
 *
 * <p>Алгоритм farthest-first:
 * <ol>
 *   <li>Один pgr_dijkstra от representative до всех OKS → матрица
 *       «расстояние до сети».</li>
 *   <li>Сортировка OKS по убыванию расстояния (самый удалённый — первым).
 *       Это строит магистраль от tie-in к дальней OKS.</li>
 *   <li>Последовательное подключение в этом порядке: каждая следующая OKS
 *       втыкается в ближайшую вершину УЖЕ построенного дерева (не в tie-in).</li>
 * </ol>
 *
 * <p>V78: к total_cost каждого пути добавляется штраф за острые углы
 * (угол поворота > 90°, ТЗ разъяснение 5). Это заставляет farthest-first
 * предпочитать пути без V-образных изломов, где есть альтернатива.
 *
 * <p>Свойства: 2-approximation SPH, ацикличность by construction.
 *
 * <p>V55 (разъяснение 5): пути не проходят через чужой OKS — фильтр в SQL
 * {@code find_paths_to_set} (штраф +1000 м на OKS-инцидентные рёбра).
 */
@Service
public class SteinerTreeBuilder {

    private static final Logger log = LoggerFactory.getLogger(SteinerTreeBuilder.class);

    /** Штраф за острый угол: (angle − 90°) · PENALTY_PER_DEGREE условных метров. */
    private static final double PENALTY_PER_DEGREE = 50.0;

    private final PathFinderService pathFinderService;
    private final JdbcTemplate jdbc;

    public SteinerTreeBuilder(PathFinderService pathFinderService, JdbcTemplate jdbc) {
        this.pathFinderService = pathFinderService;
        this.jdbc = jdbc;
    }

    /**
     * Строит дерево Штейнера для одной target-группы SSP методом farthest-first.
     *
     * @param representativeTargetVertex  корень дерева (target-вершина группы SSP)
     * @param oksVertices                 все OKS, назначенные SSP на эту группу
     * @return OKS → полный путь от корня дерева до этой OKS (шарящий общие рёбра)
     */
    public Map<Long, PathResult> buildSharedTree(UUID taskId, int clusterId,
                                                 long representativeTargetVertex,
                                                 List<Long> oksVertices) {
        Map<Long, PathResult> result = new LinkedHashMap<>();
        if (oksVertices == null || oksVertices.isEmpty()) return result;

        long start = System.currentTimeMillis();

        // ===== 1. Расстояния от tie-in (representative) до каждого OKS =====
        List<PathResult> initial = pathFinderService.findPathsToSet(
                taskId, clusterId,
                Collections.singletonList(representativeTargetVertex),
                oksVertices);

        if (initial.isEmpty()) {
            log.warn("[{}] Cluster {}: farthest-first — no paths from representative {} " +
                            "to any of {} OKS",
                    taskId, clusterId, representativeTargetVertex, oksVertices.size());
            return result;
        }

        // ===== 2. Сортировка по убыванию adjusted-cost до сети =====
        // V78: adjustedCost = totalCost + sharpTurnPenalty.
        // Самый удалённый OKS строит магистраль; ближние — ответвления.
        initial.sort(Comparator
                .comparingDouble(this::adjustedCost)
                .reversed()
                .thenComparingLong(PathResult::getFromVertex));

        // ===== 3. Последовательное подключение =====
        Set<Long> treeNodes = new HashSet<>();
        treeNodes.add(representativeTargetVertex);
        Set<Long> treeEdges = new HashSet<>();
        Set<Long> unresolved = new LinkedHashSet<>(oksVertices);

        int iter = 0;
        for (PathResult p : initial) {
            long oks = p.getFromVertex();
            if (!unresolved.contains(oks)) continue;
            iter++;

            PathResult chosen;
            if (treeEdges.isEmpty()) {
                // Первый (самый удалённый) — используем готовый путь от tie-in.
                chosen = p;
            } else {
                // Остальные — кратчайший путь от ЛЮБОЙ вершины дерева до этой OKS.
                List<PathResult> toTree = pathFinderService.findPathsToSet(
                        taskId, clusterId,
                        new ArrayList<>(treeNodes),
                        Collections.singletonList(oks));

                // V78: если у OKS несколько путей к дереву — выбираем по
                // adjustedCost, а не по чистому totalCost.
                chosen = toTree.isEmpty()
                        ? p
                        : toTree.stream()
                        .min(Comparator
                                .comparingDouble(this::adjustedCost)
                                .thenComparingLong(PathResult::getToVertex))
                        .orElse(p);
            }

            treeEdges.addAll(chosen.getEdgeIds());
            treeNodes.addAll(pathFinderService.verticesOfEdges(
                    taskId, clusterId, chosen.getEdgeIds()));
            unresolved.remove(oks);
        }

        log.info("[{}] Cluster {}: farthest-first tree in {} iter: {} edges, {} nodes, unresolved={}",
                taskId, clusterId, iter, treeEdges.size(), treeNodes.size(), unresolved.size());

        // ===== 4. Экстракция полных путей representative → OKS по рёбрам дерева =====
        // find_paths_through_tree возвращает единственный путь в дереве
        // (ацикличность by construction).
        if (!treeEdges.isEmpty()) {
            List<PathResult> fullPaths = pathFinderService.findPathsThroughTree(
                    taskId, clusterId,
                    representativeTargetVertex,
                    new ArrayList<>(oksVertices),
                    treeEdges);
            for (PathResult p : fullPaths) {
                result.put(p.getFromVertex(), p);
            }
        }

        int viaTree = result.size();

        // ===== 5. Fallback для OKS, не покрытых деревом =====
        for (Long oks : oksVertices) {
            if (result.containsKey(oks)) continue;
            PathResult fb = pathFinderService.findBestPathFromOks(taskId, clusterId, oks);
            if (fb != null) result.put(oks, fb);
        }

        long elapsed = System.currentTimeMillis() - start;
        log.info("[{}] Cluster {}: farthest-first done in {} ms — {} via tree, {} fallback, " +
                        "{} unresolved (of {} OKS)",
                taskId, clusterId, elapsed, viaTree, result.size() - viaTree,
                oksVertices.size() - result.size(), oksVertices.size());

        return result;
    }

    /**
     * V78: adjustedCost = totalCost + sharpTurnPenalty.
     * Используется для выбора пути в farthest-first: при наличии альтернативы
     * с меньшим числом острых углов Dijkstra-выбор отдаст предпочтение ей.
     */
    private double adjustedCost(PathResult p) {
        return p.getTotalCost() + sharpTurnPenalty(p.getPathWkt());
    }

    /**
     * V78: штраф за острые углы вдоль пути (ТЗ разъяснение 5: ≤ 90°).
     *
     * <p>Для каждой внутренней вершины LineString считаем угол между
     * направлениями прилегающих сегментов. 0° = прямо, 90° = прямой поворот,
     * 180° = полный разворот.
     *
     * <p>Штраф: (угол − 90) · PENALTY_PER_DEGREE условных метров.
     * За угол ≤ 90° штраф 0. За угол 180° — 90 · 50 = 4500 условных метров
     * (сопоставимо с длинным обходным маршрутом, поэтому Dijkstra предпочтёт
     * объезд, если он есть).
     *
     * <p>Возвращает 0.0 при любой ошибке парсинга/геометрии — fail-safe,
     * чтобы не заблокировать пайплайн.
     */
    private double sharpTurnPenalty(String pathWkt) {
        if (pathWkt == null || pathWkt.isEmpty()) return 0.0;
        try {
            Geometry geom = new WKTReader().read(pathWkt);
            if (!(geom instanceof LineString)) {
                log.warn("sharpTurnPenalty: geometry is {} not LineString — penalty=0", geom.getGeometryType());
                // MultiLineString после ST_LineMerge — маловероятно, но не падаем.
                return 0.0;
            }
            LineString ls = (LineString) geom;
            Coordinate[] coords = ls.getCoordinates();
            if (coords.length < 3) return 0.0;

            double total = 0.0;
            for (int i = 1; i < coords.length - 1; i++) {
                double ux = coords[i].x - coords[i - 1].x;
                double uy = coords[i].y - coords[i - 1].y;
                double vx = coords[i + 1].x - coords[i].x;
                double vy = coords[i + 1].y - coords[i].y;
                double lu = Math.hypot(ux, uy);
                double lv = Math.hypot(vx, vy);
                if (lu < 1e-6 || lv < 1e-6) continue;

                double cos = (ux * vx + uy * vy) / (lu * lv);
                cos = Math.max(-1.0, Math.min(1.0, cos));
                double thetaDeg = Math.toDegrees(Math.acos(cos));   // 0 = прямо

                if (thetaDeg > 90.0) {
                    total += (thetaDeg - 90.0) * PENALTY_PER_DEGREE;
                }
            }
            if (total > 0) {
                log.warn("sharpTurnPenalty: {} со штрафом {}", pathWkt.length(), total);
            }
            return total;
        } catch (Exception e) {
            log.warn("sharpTurnPenalty failed to parse WKT: {}", e.getMessage());
            return 0.0;
        }
    }
}