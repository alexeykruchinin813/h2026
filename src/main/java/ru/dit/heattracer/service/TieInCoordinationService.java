package ru.dit.heattracer.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.stereotype.Service;

import ru.dit.heattracer.model.PathResult;

import java.util.*;

/**
 * Координация выбора точки врезки (Проблема B).
 *
 * <p>V64.4:
 * <ul>
 *   <li>assignShared больше не greedy — вызывается SSP с cost-shift
 *       к preferred target'ам. Это устраняет suboptimality, которая
 *       давала 13/17 в v2/v3 при работающем SSP в v1.</li>
 *   <li>computeCapacities группирует edge projections по геометрии
 *       (ST_SnapToGrid 0.01 м). Раньше разные target_vertex с одной
 *       геометрией считались независимыми группами, что давало
 *       degree &gt; 4 после физической сборки.</li>
 * </ul>
 */
@Service
public class TieInCoordinationService {

    private static final Logger log = LoggerFactory.getLogger(TieInCoordinationService.class);
    private static final int MAX_ATTACHMENTS = 4;
    private static final long COST_SCALE = 1000L;
    private static final double COST_SHIFT_FACTOR = 0.1;

    private final JdbcTemplate jdbc;

    public TieInCoordinationService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ==========================================================================
    // CapacityState — без изменений
    // ==========================================================================

    public static class CapacityState {
        final Map<Long, Long> targetToGroup;
        final Map<Long, Integer> remaining;

        CapacityState(Map<Long, Long> targetToGroup, Map<Long, Integer> remaining) {
            this.targetToGroup = targetToGroup;
            this.remaining = remaining;
        }

        public long groupOf(long targetVertexId) {
            return targetToGroup.getOrDefault(targetVertexId, targetVertexId);
        }

        public int remaining(long targetVertexId) {
            return remaining.getOrDefault(groupOf(targetVertexId), 0);
        }

        public int remainingForGroup(long groupId) {
            return remaining.getOrDefault(groupId, 0);
        }

        /** V72: список всех target-вершин группы (для multi-target Dijkstra). */
        public List<Long> targetsOfGroup(long groupId) {
            List<Long> result = new ArrayList<>();
            for (Map.Entry<Long, Long> e : targetToGroup.entrySet()) {
                if (e.getValue() == groupId) result.add(e.getKey());
            }
            return result;
        }

        public void consume(long targetVertexId) {
            remaining.merge(groupOf(targetVertexId), -1, Integer::sum);
        }

        public void markExhausted(long targetVertexId) {
            remaining.put(groupOf(targetVertexId), 0);
        }

        public CapacityState copy() {
            return new CapacityState(new HashMap<>(targetToGroup), new HashMap<>(remaining));
        }
    }

    /**
     * V72: результат SSP с группировкой для multi-target Dijkstra.
     */
    public static class SspResult {
        /** OKS → выбранный путь (как раньше). */
        public final Map<Long, PathResult> assigned = new LinkedHashMap<>();

        /** group_id → список OKS, назначенных на эту группу. */
        public final Map<Long, List<Long>> byTargetGroup = new LinkedHashMap<>();

        public boolean isEmpty() { return assigned.isEmpty(); }
    }

    // ==========================================================================
    // computeCapacities — теперь с группировкой по геометрии
    // ==========================================================================

    public CapacityState computeCapacities(UUID taskId, Set<Long> allTargetVertexIds) {
        Map<Long, Long> targetToGroup = new HashMap<>();
        Map<Long, Integer> remaining = new HashMap<>();

        if (allTargetVertexIds.isEmpty()) {
            return new CapacityState(targetToGroup, remaining);
        }

        Long[] targetArr = allTargetVertexIds.toArray(new Long[0]);

        // Собираем: id, ref_id, is_real_chamber, geom_hash (снап 0.01 м).
        //
        // V71: is_real_chamber теперь требует геометрического совпадения с камерой.
        // Без ST_DWithin(f.geom_utm, vv.geom, 0.01) edge projections с ref_id,
        // совпавшим с feature_id nearby-камеры (106, 107, ...), ошибочно попадали
        // в одну группу с этой камерой. Capacity группы = 4 − attachments,
        // а не 4 per projection → кластер не мог разместить все OKS.
        Map<Long, String> targetRefId = new HashMap<>();
        Map<Long, Boolean> targetIsRealChamber = new HashMap<>();
        Map<Long, String> targetGeomHash = new HashMap<>();

        jdbc.query(
                "SELECT vv.id AS target_vertex_id, vv.ref_id, " +
                        "       ST_AsText(ST_SnapToGrid(vv.geom, 0.01)) AS geom_hash, " +
                        "       EXISTS ( " +
                        "         SELECT 1 FROM input_feature f " +
                        "         WHERE f.task_id = ? " +
                        "           AND f.feature_id::text = vv.ref_id " +
                        "           AND f.object_type = 'heat_chamber' " +
                        "           AND ST_DWithin(f.geom_utm, vv.geom, 0.01) " +   // ← V71
                        "       ) AS is_real_chamber " +
                        "FROM visibility_vertex vv " +
                        "WHERE vv.id = ANY (?)",
                rs -> {
                    long target = rs.getLong("target_vertex_id");
                    targetRefId.put(target, rs.getString("ref_id"));
                    targetIsRealChamber.put(target, rs.getBoolean("is_real_chamber"));
                    targetGeomHash.put(target, rs.getString("geom_hash"));
                },
                taskId, targetArr);

        // Группировка: real-chamber → по ref_id; edge projection → по geom_hash.
        Map<String, Long> refIdToGroup = new HashMap<>();
        Map<String, Long> geomHashToGroup = new HashMap<>();

        for (Long t : allTargetVertexIds) {
            boolean isReal = targetIsRealChamber.getOrDefault(t, false);
            String refId = targetRefId.get(t);
            String geomHash = targetGeomHash.get(t);

            if (isReal && refId != null) {
                long group = refIdToGroup.computeIfAbsent(refId, k -> t);
                targetToGroup.put(t, group);
            } else if (geomHash != null) {
                long group = geomHashToGroup.computeIfAbsent(geomHash, k -> t);
                targetToGroup.put(t, group);
            } else {
                targetToGroup.put(t, t);
            }
        }

        // Capacities для real-chamber (единственное место, где ограничение честное).
        Set<String> distinctRefIds = new HashSet<>();
        for (Long t : allTargetVertexIds) {
            if (targetIsRealChamber.getOrDefault(t, false)) {
                String refId = targetRefId.get(t);
                if (refId != null) distinctRefIds.add(refId);
            }
        }
        for (String refId : distinctRefIds) {
            Integer existing = jdbc.queryForObject(
                    "SELECT COALESCE(count_chamber_attachments(?, ?), 0)",
                    Integer.class, taskId, refId);
            int cap = Math.max(0, MAX_ATTACHMENTS - (existing == null ? 0 : existing));
            remaining.put(refIdToGroup.get(refId), cap);
        }

        // Capacities для edge projections / graph_node — всегда MAX_ATTACHMENTS.
        // Перегруз разруливает физический слой:
        //   split_oversized_chambers (V53) — для новых камер,
        //   cascadeSplitOverloadedNodes (V69) — для существующих врезок.
        Set<Long> newGroups = new HashSet<>();
        for (Long t : allTargetVertexIds) {
            if (!targetIsRealChamber.getOrDefault(t, false)) {
                newGroups.add(targetToGroup.get(t));
            }
        }
        for (Long g : newGroups) {
            remaining.put(g, MAX_ATTACHMENTS);
        }

        long constrained = remaining.values().stream().filter(v -> v < MAX_ATTACHMENTS).count();
        log.debug("[{}] computeCapacities: {} targets, {} групп ({} real-chamber, {} edge/projection), {} ограниченных",
                taskId, allTargetVertexIds.size(), remaining.size(),
                distinctRefIds.size(), newGroups.size(), constrained);

        return new CapacityState(targetToGroup, remaining);
    }

    // ==========================================================================
    // v1: individual = SSP без cost-shift
    // ==========================================================================

    public SspResult assignIndividual(Map<Long, List<PathResult>> allPaths,
                                      CapacityState state) {
        return runSsp(allPaths, state, Collections.emptyMap());
    }

    // ==========================================================================
    // v2: shared через SSP с cost-shift к shared target
    // ==========================================================================

    /**
     * V64.4: вместо greedy — единый SSP для всей группы, где рёбра к
     * preferred targets дешевле в COST_SHIFT_FACTOR раз. SSP максимизирует
     * flow (все OKS будут назначены, если feasible), среди оптимумов
     * предпочитает shared target.
     **/
    public SspResult assignShared(Set<Long> oksGroup,
                                  Map<Long, List<PathResult>> allPaths,
                                  CapacityState state,
                                  Set<Long> preferredTargets) {
        if (preferredTargets == null || preferredTargets.isEmpty()) {
            return runSsp(allPaths, state, Collections.emptyMap());
        }
        Map<Long, Double> shift = new HashMap<>();
        for (Long t : preferredTargets) {
            shift.put(t, COST_SHIFT_FACTOR);
        }
        return runSsp(allPaths, state, shift);
    }

    /**
     * Определяет лучший shared target для группы OKS с учётом capacity.
     * Возвращает null, если нет ни одного доступного.
     */
    public Long pickSharedTarget(Set<Long> oksGroup,
                                 Map<Long, List<PathResult>> allPaths,
                                 CapacityState state) {
        Map<Long, Integer> coverage = new HashMap<>();
        Map<Long, Double> sumCost = new HashMap<>();

        for (Long oks : oksGroup) {
            List<PathResult> paths = allPaths.get(oks);
            if (paths == null) continue;
            for (PathResult p : paths) {
                long t = p.getToVertex();
                if (state.remaining(t) <= 0) continue;
                coverage.merge(t, 1, Integer::sum);
                sumCost.merge(t, p.getTotalCost(), Double::sum);
            }
        }

        if (coverage.isEmpty()) return null;

        Long best = null;
        int bestCover = -1;
        double bestSum = Double.MAX_VALUE;
        for (Map.Entry<Long, Integer> e : coverage.entrySet()) {
            long t = e.getKey();
            int cov = e.getValue();
            double sum = sumCost.getOrDefault(t, Double.MAX_VALUE);
            if (cov > bestCover || (cov == bestCover && sum < bestSum)) {
                best = t;
                bestCover = cov;
                bestSum = sum;
            }
        }
        return best;
    }

    // ==========================================================================
    // v3: sub-split через SSP с двумя preferred targets
    // ==========================================================================

    public SspResult assignSubSplit(List<Long> oksVertexIds,
                                    Map<Long, List<PathResult>> allPaths,
                                    CapacityState state,
                                    Map<Long, Double> oksX,
                                    Set<Long> preferredTargets) {
        if (oksVertexIds.size() < 4) {
            return runSsp(allPaths, state,
                    preferredTargets == null ? Collections.emptyMap() : shiftMap(preferredTargets));
        }
        return runSsp(allPaths, state,
                preferredTargets == null ? Collections.emptyMap() : shiftMap(preferredTargets));
    }

    private static Map<Long, Double> shiftMap(Set<Long> preferred) {
        Map<Long, Double> m = new HashMap<>();
        for (Long t : preferred) m.put(t, COST_SHIFT_FACTOR);
        return m;
    }

    // ==========================================================================
    // SSP — ядро. Общая для assignIndividual и assignShared.
    // ==========================================================================

    /**
     * SSP-ядро: source → OKS → target → sink.
     *
     * V72: возвращает {@link SspResult} — назначения + группировку OKS по
     * target-group. Группировка нужна, чтобы после SSP перестроить пути через
     * multi-target Dijkstra (find_shared_paths_from_group).
     */
    private SspResult runSsp(Map<Long, List<PathResult>> allPaths,
                             CapacityState state,
                             Map<Long, Double> costShift) {
        if (allPaths.isEmpty()) return new SspResult();

        List<Long> oksList = new ArrayList<>(allPaths.keySet());
        Set<Long> targetsSet = new HashSet<>();
        for (List<PathResult> paths : allPaths.values()) {
            for (PathResult p : paths) targetsSet.add(p.getToVertex());
        }
        if (targetsSet.isEmpty()) return new SspResult();
        List<Long> targetList = new ArrayList<>(targetsSet);

        Map<Long, Integer> oksIndex = new HashMap<>();
        for (int i = 0; i < oksList.size(); i++) oksIndex.put(oksList.get(i), i);
        Map<Long, Integer> targetIndex = new HashMap<>();
        for (int j = 0; j < targetList.size(); j++) targetIndex.put(targetList.get(j), j);

        int n = oksList.size();
        int m = targetList.size();
        int sourceNode = 0;
        int oksBase = 1;
        int targetBase = n + 1;
        int sinkNode = n + m + 1;
        int numNodes = n + m + 2;

        List<List<Edge>> graph = new ArrayList<>(numNodes);
        for (int i = 0; i < numNodes; i++) graph.add(new ArrayList<>());

        // source → OKS (cap=1)
        for (int i = 0; i < n; i++) addEdge(graph, sourceNode, oksBase + i, 1, 0L);

        // target → sink (cap = remaining(target))
        for (int j = 0; j < m; j++) {
            long targetId = targetList.get(j);
            int cap = state.remaining(targetId);
            if (cap > 0) addEdge(graph, targetBase + j, sinkNode, cap, 0L);
        }

        // OKS → target (cap=1, cost = total_cost * shift)
        for (Long oks : oksList) {
            int oksIdx = oksIndex.get(oks);
            for (PathResult p : allPaths.get(oks)) {
                Integer tj = targetIndex.get(p.getToVertex());
                if (tj == null) continue;
                double w = p.getTotalCost();
                Double shift = costShift.get(p.getToVertex());
                if (shift != null) w *= shift;
                long cost = Math.round(w * COST_SCALE);
                if (cost < 0) cost = 0;
                addEdge(graph, oksBase + oksIdx, targetBase + tj, 1, cost);
            }
        }

        // SSP (Successive Shortest Path с потенциалами)
        long[] potential = new long[numNodes];
        long totalFlow = 0;

        while (true) {
            long[] dist = new long[numNodes];
            int[] prevNode = new int[numNodes];
            int[] prevEdgeIdx = new int[numNodes];
            Arrays.fill(dist, Long.MAX_VALUE);
            Arrays.fill(prevNode, -1);
            dist[sourceNode] = 0;

            PriorityQueue<long[]> pq = new PriorityQueue<>(Comparator.comparingLong(a -> a[0]));
            pq.add(new long[]{0, sourceNode});

            while (!pq.isEmpty()) {
                long[] cur = pq.poll();
                long d = cur[0];
                int u = (int) cur[1];
                if (d > dist[u]) continue;
                List<Edge> edges = graph.get(u);
                for (int i = 0; i < edges.size(); i++) {
                    Edge e = edges.get(i);
                    if (e.cap <= 0) continue;
                    long reweighted = e.cost + potential[u] - potential[e.to];
                    if (reweighted < 0) reweighted = 0;
                    long nd = d + reweighted;
                    if (nd < dist[e.to]) {
                        dist[e.to] = nd;
                        prevNode[e.to] = u;
                        prevEdgeIdx[e.to] = i;
                        pq.add(new long[]{nd, e.to});
                    }
                }
            }

            if (dist[sinkNode] == Long.MAX_VALUE) break;

            for (int v = 0; v < numNodes; v++) {
                if (dist[v] < Long.MAX_VALUE) potential[v] += dist[v];
            }

            int v = sinkNode;
            while (v != sourceNode) {
                int u = prevNode[v];
                int eIdx = prevEdgeIdx[v];
                Edge e = graph.get(u).get(eIdx);
                e.cap -= 1;
                graph.get(v).get(e.rev).cap += 1;
                v = u;
            }
            totalFlow++;
        }

        // Извлечение результатов + группировка по target-group
        SspResult sspResult = new SspResult();
        Map<Long, PathResult> result = sspResult.assigned;

        for (int i = 0; i < n; i++) {
            Long oks = oksList.get(i);
            int oksNode = oksBase + i;
            for (Edge e : graph.get(oksNode)) {
                if (e.isReverse) continue;
                if (e.to < targetBase || e.to >= targetBase + m) continue;
                if (e.cap != 0) continue;
                int tj = e.to - targetBase;
                Long target = targetList.get(tj);
                PathResult path = findPathTo(oks, target, allPaths);
                if (path != null && !result.containsKey(oks)) {
                    result.put(oks, path);
                    state.consume(target);
                    long group = state.groupOf(target);
                    sspResult.byTargetGroup
                            .computeIfAbsent(group, k -> new ArrayList<>())
                            .add(oks);
                }
            }
        }

        List<Long> unconnected = new ArrayList<>();
        for (Long oks : oksList) {
            if (!result.containsKey(oks)) unconnected.add(oks);
        }
        if (!unconnected.isEmpty()) {
            log.warn("SSP: {} OKS unconnected due to exhausted capacity: {}",
                    unconnected.size(), unconnected);
        } else {
            log.debug("SSP: назначено {} из {} OKS (flow={}), {} target-групп",
                    result.size(), n, totalFlow, sspResult.byTargetGroup.size());
        }

        return sspResult;
    }

    // ==========================================================================
    // Внутренние хелперы
    // ==========================================================================

    private static class Edge {
        int to;
        int rev;
        int cap;
        long cost;
        boolean isReverse;

        Edge(int to, int rev, int cap, long cost, boolean isReverse) {
            this.to = to;
            this.rev = rev;
            this.cap = cap;
            this.cost = cost;
            this.isReverse = isReverse;
        }
    }

    private static void addEdge(List<List<Edge>> graph, int from, int to, int cap, long cost) {
        Edge forward = new Edge(to, graph.get(to).size(), cap, cost, false);
        Edge backward = new Edge(from, graph.get(from).size(), 0, -cost, true);
        graph.get(from).add(forward);
        graph.get(to).add(backward);
    }

    private static PathResult findPathTo(Long oks, Long target, Map<Long, List<PathResult>> allPaths) {
        List<PathResult> paths = allPaths.get(oks);
        if (paths == null) return null;
        long targetLong = target.longValue();
        for (PathResult p : paths) {
            if (p.getToVertex() == targetLong) return p;
        }
        return null;
    }
}