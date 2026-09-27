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
 * <p>Проблема: {@link PathFinderService#findPathsFromOks} ищет пути для
 * каждого OKS независимо. Если 14 OKS кластера одновременно видят одну
 * существующую камеру как лучший target — все 14 сохранятся в path_result
 * с одним target_vertex_id. В физической топологии камера получит degree
 * 2 + 14 = 16 вместо допустимых 4.
 *
 * <p>Решение: greedy-распределение с ограничением по capacity каждой точки
 * врезки:
 * <ul>
 *   <li>capacity(existing heat_chamber) = max(0, 4 − existing_attachments);</li>
 *   <li>capacity(new chamber / edge projection) = 4.</li>
 * </ul>
 *
 * <p>Два варианта координации:
 * <ul>
 *   <li>{@link #assignIndividual} — каждый OKS к своему лучшему доступному
 *       target (v1);</li>
 *   <li>{@link #assignShared} — группа OKS разделяет общий target, хвост
 *       уходит в individual fallback (v2);</li>
 *   <li>{@link #assignSubSplit} — две подгруппы по X-координате, каждая
 *       проходит assignShared (v3).</li>
 * </ul>
 *
 * <p>Это <b>первый эшелон</b> — простой greedy. Если на прогоне окажется,
 * что feasible-назначение существует, но greedy его не находит — переход
 * к min-cost flow вторым эшелоном.
 */
@Service
public class TieInCoordinationService {

    private static final Logger log = LoggerFactory.getLogger(TieInCoordinationService.class);

    /** ТЗ 2.3: max примыканий к тепловой камере. */
    private static final int MAX_ATTACHMENTS = 4;

    private final JdbcTemplate jdbc;

    public TieInCoordinationService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ==========================================================================
    // Capacity
    // ==========================================================================

    /**
     * Считает capacity для всех target_vertex_id, встречающихся в allPaths.
     *
     * <p>Для target = существующая heat_chamber: 4 − existing_attachments
     * (по {@code count_chamber_attachments}, разъяснение 12 ТЗ).
     * Для target = edge projection / новый candidate: 4.
     *
     * <p>Один SQL на весь батч, не N+1.
     */
    public Map<Long, Integer> computeCapacities(UUID taskId,
                                                Map<Long, List<PathResult>> allPaths) {
        Set<Long> targets = new HashSet<>();
        for (List<PathResult> list : allPaths.values()) {
            for (PathResult p : list) {
                targets.add(p.getToVertex());
            }
        }
        Map<Long, Integer> capacities = new HashMap<>();
        if (targets.isEmpty()) return capacities;

        Long[] targetArr = targets.toArray(new Long[0]);

        jdbc.query(
                "SELECT vv.id AS target_vertex_id, " +
                        "       EXISTS ( " +
                        "         SELECT 1 FROM input_feature f " +
                        "         WHERE f.task_id = ? " +
                        "           AND f.feature_id::text = vv.ref_id " +
                        "           AND f.object_type = 'heat_chamber' " +
                        "       ) AS is_real_chamber, " +
                        "       COALESCE(count_chamber_attachments(?, vv.ref_id), 0) AS existing_attachments " +
                        "FROM visibility_vertex vv " +
                        "WHERE vv.id = ANY (?)",
                rs -> {
                    long target = rs.getLong("target_vertex_id");
                    boolean isReal = rs.getBoolean("is_real_chamber");
                    int existing = rs.getInt("existing_attachments");
                    int cap = isReal
                            ? Math.max(0, MAX_ATTACHMENTS - existing)
                            : MAX_ATTACHMENTS;
                    capacities.put(target, cap);
                },
                taskId, taskId, targetArr);

        // Защита: любой непопавший в выборку target считаем новым.
        for (Long t : targets) {
            capacities.putIfAbsent(t, MAX_ATTACHMENTS);
        }

        long realCappedCount = capacities.values().stream().filter(c -> c < MAX_ATTACHMENTS).count();
        log.debug("[{}] computeCapacities: {} targets, {} с ограниченной capacity",
                taskId, capacities.size(), realCappedCount);

        return capacities;
    }

    // ==========================================================================
    // v1: individual
    // ==========================================================================

    /**
     * Назначает каждый OKS на лучший по стоимости target с положительной
     * остаточной capacity.
     *
     * <p>Порядок OKS — most-constrained-first: у кого меньше кандидатов,
     * тот обрабатывается раньше. При равенстве — у кого самый дорогой
     * индивидуальный путь, тот раньше (у него меньше шансов найти
     * альтернативу).
     *
     * <p>OKS без доступного target не попадают в результат → unconnected
     * (штраф по ТЗ 6).
     */
    public Map<Long, PathResult> assignIndividual(Map<Long, List<PathResult>> allPaths,
                                                  Map<Long, Integer> capacities) {
        List<Long> oksSorted = new ArrayList<>(allPaths.keySet());
        oksSorted.sort((a, b) -> {
            int bySize = Integer.compare(allPaths.get(a).size(), allPaths.get(b).size());
            if (bySize != 0) return bySize;
            double minA = minCost(allPaths.get(a));
            double minB = minCost(allPaths.get(b));
            return Double.compare(minB, minA);  // дорогие первыми
        });

        Map<Long, PathResult> result = new LinkedHashMap<>();
        for (Long oks : oksSorted) {
            List<PathResult> paths = new ArrayList<>(allPaths.get(oks));
            paths.sort(Comparator.comparingDouble(PathResult::getTotalCost));
            for (PathResult p : paths) {
                long t = p.getToVertex();
                int cap = capacities.getOrDefault(t, 0);
                if (cap > 0) {
                    result.put(oks, p);
                    capacities.merge(t, -1, Integer::sum);
                    break;
                }
            }
        }
        return result;
    }

    // ==========================================================================
    // v2: shared
    // ==========================================================================

    /**
     * Группа OKS разделяет общий target. Хвост, не влезший в capacity,
     * уходит в individual fallback.
     *
     * <p>Итеративно:
     * <ol>
     *   <li>выбрать target с максимальным coverage (среди оставшихся OKS)
     *       и минимальной суммарной стоимостью;</li>
     *   <li>если capacity target &gt; 0 — назначить на него OKS по убыванию
     *       стоимости пути (см. обоснование в комментарии ниже);</li>
     *   <li>повторять, пока есть remaining и есть доступные targets.</li>
     * </ol>
     */
    public Map<Long, PathResult> assignShared(Set<Long> oksGroup,
                                              Map<Long, List<PathResult>> allPaths,
                                              Map<Long, Integer> capacities) {
        Map<Long, PathResult> result = new LinkedHashMap<>();
        Set<Long> remaining = new HashSet<>(oksGroup);

        while (!remaining.isEmpty()) {
            Long sharedTarget = pickSharedTargetForGroup(remaining, allPaths, capacities);
            if (sharedTarget == null) {
                // Нет target с положительной capacity, достижимого хоть кем-то.
                break;
            }

            int cap = capacities.getOrDefault(sharedTarget, 0);
            if (cap <= 0) {
                // Защита: pickSharedTargetForGroup фильтрует по capacity > 0,
                // но подстрахуемся, чтобы не было бесконечного цикла.
                capacities.put(sharedTarget, 0);
                continue;
            }

            List<Long> reachable = new ArrayList<>();
            for (Long oks : remaining) {
                if (findPathTo(oks, sharedTarget, allPaths) != null) {
                    reachable.add(oks);
                }
            }
            if (reachable.isEmpty()) {
                capacities.put(sharedTarget, 0);
                continue;
            }

            // Убывание по стоимости до sharedTarget.
            //
            // Почему не возрастание: OKS, не попавшие на shared, уходят на
            // individual fallback. Их потеря в стоимости = cost_shared − cost_individual.
            // Для дорогих OKS эта потеря больше (их индивидуальный минимум
            // тоже дорогой, но всё равно меньше shared в общем случае).
            // Назначая дорогих первыми, мы минимизируем суммарную потерю группы.
            // Это прокси для «largest regret first» — классического greedy
            // для assignment-задач.
            reachable.sort((a, b) -> Double.compare(
                    costTo(b, sharedTarget, allPaths),
                    costTo(a, sharedTarget, allPaths)));

            int assignCount = Math.min(cap, reachable.size());
            for (int i = 0; i < assignCount; i++) {
                Long oks = reachable.get(i);
                PathResult path = findPathTo(oks, sharedTarget, allPaths);
                result.put(oks, path);
                remaining.remove(oks);
                capacities.merge(sharedTarget, -1, Integer::sum);
            }
        }

        // Хвост: individual fallback для оставшихся.
        if (!remaining.isEmpty()) {
            Map<Long, List<PathResult>> tailPaths = new LinkedHashMap<>();
            for (Long oks : remaining) {
                tailPaths.put(oks, allPaths.get(oks));
            }
            result.putAll(assignIndividual(tailPaths, capacities));
        }

        return result;
    }

    // ==========================================================================
    // v3: sub-split
    // ==========================================================================

    /**
     * Кластер делится на две подгруппы по X-координате (как в текущем
     * {@code TaskService}). Каждая подгруппа проходит {@link #assignShared}
     * с общим словарём capacities (одна камера не может принять больше 4
     * OKS независимо от подгруппы).
     *
     * <p>Для малых кластеров (&lt; 4 OKS) sub-split вырождается в
     * {@link #assignIndividual}.
     */
    public Map<Long, PathResult> assignSubSplit(List<Long> oksVertexIds,
                                                Map<Long, List<PathResult>> allPaths,
                                                Map<Long, Integer> capacities,
                                                Map<Long, Double> oksX) {
        if (oksVertexIds.size() < 4) {
            return assignIndividual(allPaths, capacities);
        }

        List<Long> sortedOks = new ArrayList<>(oksVertexIds);
        sortedOks.sort(Comparator.comparingDouble(oks -> oksX.getOrDefault(oks, 0.0)));

        int mid = sortedOks.size() / 2;
        Set<Long> groupA = new HashSet<>(sortedOks.subList(0, mid));
        Set<Long> groupB = new HashSet<>(sortedOks.subList(mid, sortedOks.size()));

        Map<Long, PathResult> result = new LinkedHashMap<>();
        result.putAll(assignShared(groupA, allPaths, capacities));
        result.putAll(assignShared(groupB, allPaths, capacities));
        return result;
    }

    // ==========================================================================
    // Внутренние хелперы
    // ==========================================================================

    /**
     * Выбирает target для группы: максимальное coverage (сколько OKS группы
     * могут дойти) при capacity &gt; 0, при равенстве — минимальная сумма
     * стоимостей.
     *
     * <p>Targets с нулевой capacity не рассматриваются. Это заменяет
     * явный {@code excludeTargetFromSearch} — capacity=0 естественно
     * фильтруется.
     */
    private Long pickSharedTargetForGroup(Set<Long> oksGroup,
                                          Map<Long, List<PathResult>> allPaths,
                                          Map<Long, Integer> capacities) {
        Map<Long, Integer> coverage = new HashMap<>();
        Map<Long, Double> sumCost = new HashMap<>();

        for (Long oks : oksGroup) {
            List<PathResult> paths = allPaths.get(oks);
            if (paths == null) continue;
            for (PathResult p : paths) {
                long t = p.getToVertex();
                if (capacities.getOrDefault(t, 0) <= 0) continue;
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

    private static double costTo(Long oks, Long target,
                                 Map<Long, List<PathResult>> allPaths) {
        PathResult p = findPathTo(oks, target, allPaths);
        return p == null ? Double.MAX_VALUE : p.getTotalCost();
    }

    private static PathResult findPathTo(Long oks, Long target,
                                         Map<Long, List<PathResult>> allPaths) {
        List<PathResult> paths = allPaths.get(oks);
        if (paths == null) return null;
        long targetLong = target.longValue();
        for (PathResult p : paths) {
            if (p.getToVertex() == targetLong) return p;
        }
        return null;
    }
}