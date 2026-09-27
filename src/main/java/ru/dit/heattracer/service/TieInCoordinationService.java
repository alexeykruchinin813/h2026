package ru.dit.heattracer.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import ru.dit.heattracer.model.PathResult;

import java.util.*;

/**
 * Координация выбора точки врезки (Проблема B).
 *
 * <p>V64: фикс утечек capacity, обнаруженных в V63:
 * <ul>
 *   <li>cross-cluster: {@code computeCapacities} теперь вызывается ОДИН раз на вариант,
 *       а не на каждый кластер. {@link CapacityState} живёт в масштабе всей задачи+варианта.</li>
 *   <li>cross-candidate: capacity группируется по {@code ref_id} для существующих камер.
 *       Все кандидаты, указывающие на одну камеру, делят одну общую capacity.</li>
 * </ul>
 *
 * <p>Гарантии:
 * <ul>
 *   <li>∀ существующая камера c: {@code existing_attachments(c) + #OKS(c) ≤ 4};</li>
 *   <li>∀ новый target: {@code #OKS ≤ 4};</li>
 *   <li>OKS без допустимого target → unconnected (штраф по ТЗ 6).</li>
 * </ul>
 */
@Service
public class TieInCoordinationService {

    private static final Logger log = LoggerFactory.getLogger(TieInCoordinationService.class);
    private static final int MAX_ATTACHMENTS = 4;

    private final JdbcTemplate jdbc;

    public TieInCoordinationService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ==========================================================================
    // CapacityState
    // ==========================================================================

    public static class CapacityState {
        final Map<Long, Long> targetToGroup;
        final Map<Long, Integer> remaining;

        CapacityState(Map<Long, Long> targetToGroup, Map<Long, Integer> remaining) {
            this.targetToGroup = targetToGroup;
            this.remaining = remaining;
        }

        private long groupOf(long targetVertexId) {
            return targetToGroup.getOrDefault(targetVertexId, targetVertexId);
        }

        public int remaining(long targetVertexId) {
            return remaining.getOrDefault(groupOf(targetVertexId), 0);
        }

        public void consume(long targetVertexId) {
            remaining.merge(groupOf(targetVertexId), -1, Integer::sum);
        }

        public void markExhausted(long targetVertexId) {
            remaining.put(groupOf(targetVertexId), 0);
        }

        public CapacityState copy() {
            return new CapacityState(
                    new HashMap<>(targetToGroup),
                    new HashMap<>(remaining));
        }
    }

    // ==========================================================================
    // computeCapacities
    // ==========================================================================

    public CapacityState computeCapacities(UUID taskId, Set<Long> allTargetVertexIds) {
        Map<Long, Long> targetToGroup = new HashMap<>();
        Map<Long, Integer> remaining = new HashMap<>();

        if (allTargetVertexIds.isEmpty()) {
            return new CapacityState(targetToGroup, remaining);
        }

        Long[] targetArr = allTargetVertexIds.toArray(new Long[0]);

        Map<Long, String> targetRefId = new HashMap<>();
        Map<Long, Boolean> targetIsRealChamber = new HashMap<>();

        jdbc.query(
                "SELECT vv.id AS target_vertex_id, vv.ref_id, " +
                        "       EXISTS ( " +
                        "         SELECT 1 FROM input_feature f " +
                        "         WHERE f.task_id = ? " +
                        "           AND f.feature_id::text = vv.ref_id " +
                        "           AND f.object_type = 'heat_chamber' " +
                        "       ) AS is_real_chamber " +
                        "FROM visibility_vertex vv " +
                        "WHERE vv.id = ANY (?)",
                rs -> {
                    long target = rs.getLong("target_vertex_id");
                    String refId = rs.getString("ref_id");
                    boolean isReal = rs.getBoolean("is_real_chamber");
                    targetRefId.put(target, refId);
                    targetIsRealChamber.put(target, isReal);
                },
                taskId, targetArr);

        Map<String, Long> refIdToGroup = new HashMap<>();
        for (Long t : allTargetVertexIds) {
            boolean isReal = targetIsRealChamber.getOrDefault(t, false);
            String refId = targetRefId.get(t);
            if (isReal && refId != null) {
                long group = refIdToGroup.computeIfAbsent(refId, k -> t);
                targetToGroup.put(t, group);
            } else {
                targetToGroup.put(t, t);
            }
        }

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
                    Integer.class,
                    taskId, refId);
            int cap = Math.max(0, MAX_ATTACHMENTS - (existing == null ? 0 : existing));
            long group = refIdToGroup.get(refId);
            remaining.put(group, cap);
        }

        for (Long t : allTargetVertexIds) {
            if (!targetIsRealChamber.getOrDefault(t, false)) {
                remaining.put(t, MAX_ATTACHMENTS);
            }
        }

        long constrainedGroups = remaining.values().stream().filter(v -> v < MAX_ATTACHMENTS).count();
        log.debug("[{}] computeCapacities: {} targets, {} групп, {} с ограниченной capacity",
                taskId, allTargetVertexIds.size(), remaining.size(), constrainedGroups);

        return new CapacityState(targetToGroup, remaining);
    }

    // ==========================================================================
    // v1: individual
    // ==========================================================================

    public Map<Long, PathResult> assignIndividual(Map<Long, List<PathResult>> allPaths,
                                                  CapacityState state) {
        List<Long> oksSorted = new ArrayList<>(allPaths.keySet());
        oksSorted.sort((a, b) -> {
            int bySize = Integer.compare(allPaths.get(a).size(), allPaths.get(b).size());
            if (bySize != 0) return bySize;
            double minA = minCost(allPaths.get(a));
            double minB = minCost(allPaths.get(b));
            return Double.compare(minB, minA);
        });

        Map<Long, PathResult> result = new LinkedHashMap<>();
        for (Long oks : oksSorted) {
            List<PathResult> paths = new ArrayList<>(allPaths.get(oks));
            paths.sort(Comparator.comparingDouble(PathResult::getTotalCost));
            for (PathResult p : paths) {
                long t = p.getToVertex();
                if (state.remaining(t) > 0) {
                    result.put(oks, p);
                    state.consume(t);
                    break;
                }
            }
        }
        return result;
    }

    // ==========================================================================
    // v2: shared
    // ==========================================================================

    public Map<Long, PathResult> assignShared(Set<Long> oksGroup,
                                              Map<Long, List<PathResult>> allPaths,
                                              CapacityState state) {
        Map<Long, PathResult> result = new LinkedHashMap<>();
        Set<Long> remainingOks = new HashSet<>(oksGroup);

        while (!remainingOks.isEmpty()) {
            Long sharedTarget = pickSharedTargetForGroup(remainingOks, allPaths, state);
            if (sharedTarget == null) break;

            int cap = state.remaining(sharedTarget);
            if (cap <= 0) {
                state.markExhausted(sharedTarget);
                continue;
            }

            List<Long> reachable = new ArrayList<>();
            for (Long oks : remainingOks) {
                if (findPathTo(oks, sharedTarget, allPaths) != null) {
                    reachable.add(oks);
                }
            }
            if (reachable.isEmpty()) {
                state.markExhausted(sharedTarget);
                continue;
            }

            reachable.sort((a, b) -> Double.compare(
                    costTo(b, sharedTarget, allPaths),
                    costTo(a, sharedTarget, allPaths)));

            int assignCount = Math.min(cap, reachable.size());
            for (int i = 0; i < assignCount; i++) {
                Long oks = reachable.get(i);
                PathResult path = findPathTo(oks, sharedTarget, allPaths);
                result.put(oks, path);
                remainingOks.remove(oks);
                state.consume(sharedTarget);
            }
        }

        if (!remainingOks.isEmpty()) {
            Map<Long, List<PathResult>> tailPaths = new LinkedHashMap<>();
            for (Long oks : remainingOks) {
                tailPaths.put(oks, allPaths.get(oks));
            }
            result.putAll(assignIndividual(tailPaths, state));
        }
        return result;
    }

    // ==========================================================================
    // v3: sub-split
    // ==========================================================================

    public Map<Long, PathResult> assignSubSplit(List<Long> oksVertexIds,
                                                Map<Long, List<PathResult>> allPaths,
                                                CapacityState state,
                                                Map<Long, Double> oksX) {
        if (oksVertexIds.size() < 4) {
            return assignIndividual(allPaths, state);
        }

        List<Long> sortedOks = new ArrayList<>(oksVertexIds);
        sortedOks.sort(Comparator.comparingDouble(oks -> oksX.getOrDefault(oks, 0.0)));

        int mid = sortedOks.size() / 2;
        Set<Long> groupA = new HashSet<>(sortedOks.subList(0, mid));
        Set<Long> groupB = new HashSet<>(sortedOks.subList(mid, sortedOks.size()));

        Map<Long, PathResult> result = new LinkedHashMap<>();
        result.putAll(assignShared(groupA, allPaths, state));
        result.putAll(assignShared(groupB, allPaths, state));
        return result;
    }

    // ==========================================================================
    // Внутренние хелперы
    // ==========================================================================

    private Long pickSharedTargetForGroup(Set<Long> oksGroup,
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

    private static double minCost(List<PathResult> paths) {
        double min = Double.MAX_VALUE;
        for (PathResult p : paths) {
            if (p.getTotalCost() < min) min = p.getTotalCost();
        }
        return min == Double.MAX_VALUE ? 0.0 : min;
    }

    private static double costTo(Long oks, Long target, Map<Long, List<PathResult>> allPaths) {
        PathResult p = findPathTo(oks, target, allPaths);
        return p == null ? Double.MAX_VALUE : p.getTotalCost();
    }

    private static PathResult findPathTo(Long oks, Long target, Map<Long, List<PathResult>> allPaths) {
        List<PathResult> paths = allPaths.get(oks);
        if (paths == null) return null;
        for (PathResult p : paths) {
            if (p.getToVertex() == target) return p;
        }
        return null;
    }
}