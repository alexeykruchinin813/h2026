package ru.dit.heattracer.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import ru.dit.heattracer.model.PathResult;

import java.util.*;

/**
 * V76: Greedy Steiner Tree (Shortest Path Heuristic — Takahashi-Matsuyama, 1980).
 *
 * <p>Строит дерево, шарящее общие стволы между OKS группы, вместо N
 * независимых A* (радиальная «звезда»).
 *
 * <p>Алгоритм:
 * <ol>
 *   <li>tree = {representativeTarget}, uncovered = все OKS группы.</li>
 *   <li>Пока uncovered не пусто:
 *     <ul>
 *       <li>multi-source Dijkstra от tree до uncovered;</li>
 *       <li>выбрать OKS с минимальной стоимостью;</li>
 *       <li>путь добавить в дерево; вершины пути — в treeNodes;</li>
 *       <li>OKS удалить из uncovered.</li>
 *     </ul>
 *   </li>
 *   <li>Финальный pgr_dijkstra по рёбрам дерева: полные пути root → каждая OKS.</li>
 * </ol>
 *
 * <p>Свойства: 2-approximation, ацикличность by construction, общие стволы
 * появляются по построению. Согласованный компромисс: +5–20% длины части OKS
 * в обмен на резкое уменьшение числа камер и сегментов (ТЗ 2.8).
 *
 * <p>V55 (разъяснение 5): пути не проходят через чужой OKS — фильтр в SQL
 * {@code find_paths_to_set}. Для OKS, не покрытых деревом (например, все пути
 * к ним блокированы V55), Java-слой делает fallback на {@link PathFinderService#findBestPathFromOks}.
 */
@Service
public class SteinerTreeBuilder {

    private static final Logger log = LoggerFactory.getLogger(SteinerTreeBuilder.class);

    private final PathFinderService pathFinderService;
    private final JdbcTemplate jdbc;

    public SteinerTreeBuilder(PathFinderService pathFinderService, JdbcTemplate jdbc) {
        this.pathFinderService = pathFinderService;
        this.jdbc = jdbc;
    }

    /**
     * V77: farthest-first Steiner (вместо nearest-first SPH).
     *
     * <p>Стратегия:
     * <ol>
     *   <li>Один pgr_dijkstra от representative до всех OKS → матрица «расстояние
     *       до сети».</li>
     *   <li>Сортировка OKS по убыванию расстояния (самый удалённый — первым).</li>
     *   <li>Последовательное подключение в этом порядке: самый удалённый строит
     *       магистраль, остальные втыкаются в неё как ответвления.</li>
     * </ol>
     *
     * <p>Даёт топологию «магистраль + ответвления» без риска радиальной «звезды».
     * Общие стволы появляются по построению — каждая последующая OKS подключается
     * к ближайшей вершине УЖЕ построенного дерева, а не к tie-in.
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

        // ===== 2. Сортировка по убыванию расстояния до сети =====
        // Самый удалённый OKS строит магистраль; ближние — ответвления.
        initial.sort(Comparator.comparingDouble(PathResult::getTotalCost).reversed());

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
                // Он и есть магистраль.
                chosen = p;
            } else {
                // Остальные — кратчайший путь от ЛЮБОЙ вершины дерева до этой OKS.
                // Это ветвь, отходящая от магистрали (или от предыдущей ветви).
                List<PathResult> toTree = pathFinderService.findPathsToSet(
                        taskId, clusterId,
                        new ArrayList<>(treeNodes),
                        Collections.singletonList(oks));
                chosen = toTree.isEmpty() ? p : toTree.get(0);
            }

            treeEdges.addAll(chosen.getEdgeIds());
            treeNodes.addAll(pathFinderService.verticesOfEdges(
                    taskId, clusterId, chosen.getEdgeIds()));
            unresolved.remove(oks);
        }

        log.info("[{}] Cluster {}: farthest-first tree in {} iter: {} edges, {} nodes, unresolved={}",
                taskId, clusterId, iter, treeEdges.size(), treeNodes.size(), unresolved.size());

        // ===== 4. Экстракция полных путей representative → OKS по рёбрам дерева =====
        // find_paths_through_tree возвращает единственный путь в дереве (ацикличность
        // by construction: каждый путь добавляет одну новую OKS).
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
}