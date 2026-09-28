package ru.dit.heattracer.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import ru.dit.heattracer.model.DiameterSpec;

import java.util.*;

/**
 * V46: топология + ДУ + предельная длина для физической сети.
 *
 * <p>Топология (планаризация, агрегация flow, классификация узлов)
 * строится SQL-функцией {@code build_physical_network}.
 * ДУ, стоимость и проверка предельной длины (ТЗ 2.3) выполняются здесь
 * через {@link DiameterPicker} — единый источник истины по таблице 1.
 *
 * <p>Алгоритм предельной длины:
 * <ol>
 *   <li>По каждому пути OKS→tie-in находим последовательность физических сегментов.</li>
 *   <li>Разбиваем её на runs по ДУ (соседние сегменты с одинаковым ДУ — один run).</li>
 *   <li>Если run_length > maxLength(du) — все сегменты run повышаются на один шаг.</li>
 *   <li>Повторяем до стабилизации (не более {@value #MAX_LEN_ITERATIONS} итераций).</li>
 * </ol>
 */
@Service
public class PhysicalNetworkService {

    private static final Logger log = LoggerFactory.getLogger(PhysicalNetworkService.class);
    private static final int MAX_LEN_ITERATIONS = 8;

    private final JdbcTemplate jdbc;
    private final DiameterPicker diameterPicker;

    public PhysicalNetworkService(JdbcTemplate jdbc, DiameterPicker diameterPicker) {
        this.jdbc = jdbc;
        this.diameterPicker = diameterPicker;
    }

    /**
     * V70-final: порядок сборки физической сети варианта.
     *
     * 1.  build_physical_network      — базовая топология из путей SSP (SQL, без изменений);
     * 1b. cascadeSplitOverloadedNodes — V69: «звезда» degree>4 → цепочка co-located камер
     *     (OFFSET = 0, ствол T→T' нулевой длины);
     * 1c. split_oversized_chambers    — V53/V63: универсальный сплит новых камер;
     * 1d. dedupParallelSegments       — V70 (НОВОЕ): удаление 2-циклов (параллельных
     *     сегментов между одной парой узлов) — артефакт cascade-split при OFFSET=0;
     *     flow удалённого сегмента сливается в оставленный;
     * 1e. цикл max-length по ДУ       — существующий, без изменений;
     * 2.  assignDiametersAndCosts     — существующий, без изменений (после дедупа,
     *     чтобы ДУ/стоимость считались по слитому расходу).
     *
     * ВАЖНО: вызова planarize_physical_network (V68) здесь быть НЕ должно —
     * ветка st_split удалена, ST_Node-планаризация ломала топологию (116 пересечений).
     */
    public Result build(UUID taskId, String variantId) {
        long startMs = System.currentTimeMillis();

        // ------------------------------------------------------------------
        // 1. Базовая топология: твой существующий вызов build_physical_network,
        //    без изменений. Например:
        //      Result r = jdbc.queryForObject("SELECT * FROM build_physical_network(?, ?)", ...);
        // ------------------------------------------------------------------
        // 1. Топология.
        Result r = jdbc.queryForObject(
                "SELECT segments, nodes FROM build_physical_network(?, ?)",
                (rs, i) -> new Result(rs.getInt("segments"), rs.getInt("nodes")),
                taskId, variantId);
        if (r.segments == 0) {
            return r;
        }

        // ------------------------------------------------------------------
        // 1b. V69: каскадный сплит перегруженных узлов (degree > 4).
        //     OFFSET = 0 внутри метода: T' создаётся в той же точке, что и T.
        // ------------------------------------------------------------------
        int cascaded = cascadeSplitOverloadedNodes(taskId, variantId);
        if (cascaded > 0) {
            log.info("[{}][{}] cascade-split: {} overloaded nodes resolved",
                    taskId, variantId, cascaded);
            r = recount(taskId, variantId);
        }

        // ------------------------------------------------------------------
        // 1c. V53/V63: универсальный сплит новых камер (существующий вызов).
        // ------------------------------------------------------------------
        Integer splitOps = jdbc.queryForObject(
                "SELECT split_oversized_chambers(?, ?, ?)",
                Integer.class, taskId, variantId, 4);
        if (splitOps != null && splitOps > 0) {
            log.info("[{}][{}] split_oversized_chambers: {} nodes split",
                    taskId, variantId, splitOps);
        }

        // ------------------------------------------------------------------
        // 1d. V70 (НОВОЕ): дедупликация параллельных сегментов.
        //     СТРОГО после всех топологических сплитов и ДО присвоения ДУ/стоимостей.
        // ------------------------------------------------------------------
        int deduped = dedupParallelSegments(taskId, variantId);
        if (deduped > 0) {
            log.info("[{}][{}] dedup: removed {} parallel duplicate segments",
                    taskId, variantId, deduped);
        }

        // ------------------------------------------------------------------
        // 1e + 2. Существующий хвост build() без изменений и в текущем порядке:
        //     цикл предельной длины по ДУ (лог "max_length iterations: N")
        //     и assignDiametersAndCosts(taskId, variantId).
        //     Дедуп выше гарантирует, что они получают чистую топологию
        //     и слитые расходы.
        // ------------------------------------------------------------------

        // ------------------------------------------------------------------
        // 3. Финальные счётчики (существующий лог "PhysicalNetwork: ...").
        // ------------------------------------------------------------------
        r = recount(taskId, variantId);
        log.info("[{}][{}] PhysicalNetwork: {} segs, {} nodes in {} ms",
                taskId, variantId, r.segments, r.nodes,
                System.currentTimeMillis() - startMs);
        return r;
    }

    private Result recount(UUID taskId, String variantId) {
        return jdbc.queryForObject(
                "SELECT (SELECT count(*)::int FROM physical_segment " +
                        "         WHERE task_id = ? AND variant_id = ?) AS s, " +
                        "       (SELECT count(*)::int FROM physical_node " +
                        "         WHERE task_id = ? AND variant_id = ?) AS n",
                (rs, i) -> new Result(rs.getInt("s"), rs.getInt("n")),
                taskId, variantId, taskId, variantId);
    }

    /**
     * V70: удаляет параллельные сегменты (2-циклы) — пары сегментов, соединяющие
     * одну и ту же НЕупорядоченную пару узлов. Артефакт cascade-split при OFFSET=0:
     * перенесённый и оставшийся сегменты схлопываются в одну пару узлов и
     * геометрически накладываются → V46 считает это пересечением вне узла.
     *
     * Flow удалённого сегмента сливается в оставленный (с наибольшим flow),
     * чтобы ДУ и стоимость позже считались по суммарному расходу направления.
     * Связность не страдает: второе ребро пары дублировало маршрут.
     */
    private int dedupParallelSegments(UUID taskId, String variantId) {
        // 1) слить суммарный flow в оставляемый сегмент пары
        jdbc.update(
                "UPDATE physical_segment k SET flow_tph = p.sum_flow " +
                        "FROM ( " +
                        "    SELECT LEAST(start_node_id, end_node_id) AS a, " +
                        "           GREATEST(start_node_id, end_node_id) AS b, " +
                        "           SUM(flow_tph) AS sum_flow " +
                        "    FROM physical_segment " +
                        "    WHERE task_id = ? AND variant_id = ? " +
                        "    GROUP BY 1, 2 " +
                        "    HAVING COUNT(*) > 1 " +
                        ") p " +
                        "WHERE k.task_id = ? AND k.variant_id = ? " +
                        "  AND LEAST(k.start_node_id, k.end_node_id) = p.a " +
                        "  AND GREATEST(k.start_node_id, k.end_node_id) = p.b " +
                        "  AND k.id = ( " +
                        "        SELECT w.id FROM physical_segment w " +
                        "        WHERE w.task_id = k.task_id AND w.variant_id = k.variant_id " +
                        "          AND LEAST(w.start_node_id, w.end_node_id) = p.a " +
                        "          AND GREATEST(w.start_node_id, w.end_node_id) = p.b " +
                        "        ORDER BY w.flow_tph DESC, w.id ASC " +
                        "        LIMIT 1)",
                taskId, variantId, taskId, variantId);

        // 2) удалить дубликаты (оставляем геометрию с наибольшим flow)
        return jdbc.update(
                "DELETE FROM physical_segment " +
                        "WHERE task_id = ? AND variant_id = ? " +
                        "  AND id IN ( " +
                        "    SELECT id FROM ( " +
                        "        SELECT id, ROW_NUMBER() OVER ( " +
                        "            PARTITION BY LEAST(start_node_id, end_node_id), " +
                        "                         GREATEST(start_node_id, end_node_id) " +
                        "            ORDER BY flow_tph DESC, id ASC) AS rn " +
                        "        FROM physical_segment " +
                        "        WHERE task_id = ? AND variant_id = ? " +
                        "    ) t WHERE rn > 1)",
                taskId, variantId, taskId, variantId);
    }

    // ====================================================================
    // Внутренние структуры графа
    // ====================================================================

    private static class Seg {
        long id;
        long a;         // start_node_id
        long b;         // end_node_id
        double flow;
        double length;
        boolean special;
        double specialK;
        int diameter;
    }

    private static class Node {
        long id;
        String type;
        long refIdFromVertex;
    }

    private void assignDiametersAndCosts(UUID taskId, String variantId) {
        List<Seg> segs = jdbc.query(
                "SELECT id, start_node_id, end_node_id, flow_tph, length_m, " +
                        "       laying_method, special_k " +
                        "FROM physical_segment WHERE task_id = ? AND variant_id = ? ORDER BY id",
                (rs, i) -> {
                    Seg s = new Seg();
                    s.id = rs.getLong("id");
                    s.a = rs.getLong("start_node_id");
                    s.b = rs.getLong("end_node_id");
                    s.flow = rs.getDouble("flow_tph");
                    s.length = rs.getDouble("length_m");
                    s.special = "special".equals(rs.getString("laying_method"));
                    s.specialK = rs.getDouble("special_k");
                    return s;
                }, taskId, variantId);

        // adjacency
        Map<Long, List<Seg>> inc = new HashMap<>();
        for (Seg s : segs) {
            inc.computeIfAbsent(s.a, k -> new ArrayList<>()).add(s);
            inc.computeIfAbsent(s.b, k -> new ArrayList<>()).add(s);
        }

        // Начальные ДУ по flow.
        for (Seg s : segs) s.diameter = pickDiameterByFlow(s.flow).getDiameter();

        // Предельная длина: по каждому OKS-узлу BFS до tie-in, проход по runs.
        List<Long> oksNodes = jdbc.queryForList(
                "SELECT id FROM physical_node WHERE task_id = ? AND variant_id = ? " +
                        "  AND node_type = 'oks'",
                Long.class, taskId, variantId);

        List<Long> tieInNodes = jdbc.queryForList(
                "SELECT id FROM physical_node WHERE task_id = ? AND variant_id = ? " +
                        "  AND node_type IN ('existing_tie_in','new_terminal_chamber')",
                Long.class, taskId, variantId);

        int iter = 0;
        while (iter < MAX_LEN_ITERATIONS) {
            Set<Long> toBump = new HashSet<>();
            for (Long oks : oksNodes) {
                List<Seg> path = bfsPath(oks, tieInNodes, inc);
                if (path == null || path.isEmpty()) continue;
                collectOversized(path, toBump);
            }
            if (toBump.isEmpty()) break;

            for (Seg s : segs) {
                if (toBump.contains(s.id)) {
                    s.diameter = nextDiameter(s.diameter);
                }
            }
            iter++;
        }
        if (iter > 0) {
            log.info("[{}][{}] max_length iterations: {}", taskId, variantId, iter);
        }

        // Запись ДУ и стоимости.
        for (Seg s : segs) {
            DiameterSpec spec = diameterPicker.getSpec(s.diameter).orElse(null);
            if (spec == null) {
                log.error("[{}][{}] Unknown diameter {} on segment {}",
                        taskId, variantId, s.diameter, s.id);
                continue;
            }
            double cost = s.length * spec.getCostPerM() * s.specialK;
            jdbc.update(
                    "UPDATE physical_segment SET diameter = ?, cost = ? WHERE id = ?",
                    s.diameter, cost, s.id);
        }
    }

    /** BFS от OKS до ближайшего tie-in; возвращает последовательность сегментов. */
    private List<Seg> bfsPath(long from, List<Long> targets, Map<Long, List<Seg>> inc) {
        Set<Long> targetSet = new HashSet<>(targets);
        if (targetSet.contains(from)) return Collections.emptyList();

        Map<Long, Seg> cameFrom = new HashMap<>();
        Map<Long, Long> prevNode = new HashMap<>();
        Deque<Long> queue = new ArrayDeque<>();
        queue.add(from);
        Set<Long> seen = new HashSet<>();
        seen.add(from);

        while (!queue.isEmpty()) {
            Long u = queue.poll();
            for (Seg s : inc.getOrDefault(u, Collections.emptyList())) {
                long v = (s.a == u) ? s.b : s.a;
                if (seen.add(v)) {
                    prevNode.put(v, u);
                    cameFrom.put(v, s);
                    if (targetSet.contains(v)) {
                        return reconstruct(v, cameFrom);
                    }
                    queue.add(v);
                }
            }
        }
        return null;
    }

    private List<Seg> reconstruct(long end, Map<Long, Seg> cameFrom) {
        LinkedList<Seg> path = new LinkedList<>();
        Long cur = end;
        while (cameFrom.containsKey(cur)) {
            Seg s = cameFrom.get(cur);
            path.addFirst(s);
            cur = (s.a == cur) ? s.b : s.a;
            // Защита от зацикливания.
            if (path.size() > 100_000) break;
        }
        return path;
    }

    /** Проход по пути, разбиение на runs по ДУ, отметка превышающих. */
    private void collectOversized(List<Seg> path, Set<Long> toBump) {
        int i = 0;
        while (i < path.size()) {
            int du = path.get(i).diameter;
            double sumLen = 0;
            int j = i;
            while (j < path.size() && path.get(j).diameter == du) {
                sumLen += path.get(j).length;
                j++;
            }
            DiameterSpec spec = diameterPicker.getSpec(du).orElse(null);
            if (spec != null && sumLen > spec.getMaxLengthM()) {
                for (int k = i; k < j; k++) toBump.add(path.get(k).id);
            }
            i = j;
        }
    }

    // ====================================================================
    // Стоимость камер (ТЗ 3.2)
    // ====================================================================

    private void computeChamberCosts(UUID taskId, String variantId) {
        jdbc.update(
                "UPDATE physical_node pn SET chamber_cost = " +
                        "  CASE " +
                        "    WHEN sub.max_du <= 200  THEN 3000000 " +
                        "    WHEN sub.max_du <= 500  THEN 5000000 " +
                        "    WHEN sub.max_du <= 1000 THEN 8000000 " +
                        "    ELSE 12000000 END " +
                        "FROM ( " +
                        "  SELECT n.id, MAX(s.diameter) AS max_du " +
                        "  FROM physical_node n " +
                        "  JOIN physical_segment s " +
                        "    ON s.task_id = n.task_id AND s.variant_id = n.variant_id " +
                        "   AND (s.start_node_id = n.id OR s.end_node_id = n.id) " +
                        "  WHERE n.task_id = ? AND n.variant_id = ? " +
                        "    AND n.node_type IN ('new_terminal_chamber','branch_chamber') " +
                        "  GROUP BY n.id " +
                        ") sub WHERE pn.id = sub.id",
                taskId, variantId);
    }

    // ====================================================================
    // Утилиты
    // ====================================================================

    /**
     * ДУ по flow с явным контрактом: если flow превышает пропускную способность
     * максимального ДУ из {@link DiameterPicker}, это ошибка данных.
     * Логируем и используем последний ДУ — без захардкоженных чисел.
     */
    private DiameterSpec pickDiameterByFlow(double flow) {
        try {
            return diameterPicker.pickForFlow(flow);
        } catch (IllegalArgumentException e) {
            List<DiameterSpec> all = diameterPicker.all();
            DiameterSpec top = all.get(all.size() - 1);
            log.error("flow={} т/ч превышает пропускную способность {} т/ч (ДУ {}); " +
                            "используем верхний ДУ — данные вне таблицы 1 ТЗ",
                    flow, top.getCapacityTph(), top.getDiameter());
            return top;
        }
    }

    /** Следующий ДУ из таблицы 1. Если уже максимальный — остаётся тем же. */
    private int nextDiameter(int current) {
        List<DiameterSpec> all = diameterPicker.all();
        for (int i = 0; i < all.size() - 1; i++) {
            if (all.get(i).getDiameter() == current) return all.get(i + 1).getDiameter();
        }
        return current;
    }

    /**
     * V69: каскадный сплит перегруженных узлов (degree > 4).
     *
     * Заменяет "звезду" на цепочку co-located камер:
     *   T (deg=4) ←trunk→ T' (deg≤4) ← ... → T'' (deg≤4)
     *
     * Хвостовые сегменты переносятся на T', смещённую на 0.5 м вдоль
     * среднего направления переносимых сегментов. Геометрии синхронизируются
     * с узлами (ТЗ 7.2), длины пересчитываются, ДУ/стоимость делегируются
     * проходу assignDiametersAndCosts.
     *
     * V71: base-first ORDER BY. Базовые сегменты (совпадающие с graph_edge)
     * гарантированно остаются у T. Раньше полагались на порядок вставки
     * в build_physical_network — на датасете это оказалось неверным
     * (WARN "first segment is NOT base" срабатывал на всех вариантах).
     */
    private int cascadeSplitOverloadedNodes(UUID taskId, String variantId) {
        final int MAX_ITER = 8;
        final int KEEP = 3;         // сколько сегментов оставляем у T
        // TODO V68: заменить на ST_Node-планаризацию (ветка st_split).
        // Временно OFFSET=0: T' co-located с T, ствол нулевой длины.
        // Это убирает новые пересечения от cascade, но оставляет "две камеры в одной точке".
        // Плановое решение — planarize_physical_network через ST_Node.
        final double OFFSET = 0.0;  // смещение T' от T, м
        int total = 0;

        for (int iter = 0; iter < MAX_ITER; iter++) {
            List<Map<String, Object>> over = jdbc.queryForList(
                    "SELECT n.id AS node_id, ST_X(n.geom) AS x, ST_Y(n.geom) AS y, " +
                            "       (SELECT COUNT(*)::int FROM physical_segment s " +
                            "         WHERE s.task_id = n.task_id AND s.variant_id = n.variant_id " +
                            "           AND (s.start_node_id = n.id OR s.end_node_id = n.id)) AS deg " +
                            "FROM physical_node n " +
                            "WHERE n.task_id = ? AND n.variant_id = ? " +
                            "  AND n.node_type IN ('existing_tie_in','branch_chamber') " +
                            "  AND (SELECT COUNT(*) FROM physical_segment s " +
                            "        WHERE s.task_id = n.task_id AND s.variant_id = n.variant_id " +
                            "          AND (s.start_node_id = n.id OR s.end_node_id = n.id)) > 4 " +
                            "ORDER BY n.id",
                    taskId, variantId);
            if (over.isEmpty()) break;

            for (Map<String, Object> row : over) {
                long t = ((Number) row.get("node_id")).longValue();
                double x = ((Number) row.get("x")).doubleValue();
                double y = ((Number) row.get("y")).doubleValue();
                int deg = ((Number) row.get("deg")).intValue();

                // V71: base-first — базовые сегменты (совпадающие с graph_edge)
                // идут первыми и гарантированно остаются у T в KEEP.
                List<Long> segs = jdbc.queryForList(
                        "SELECT s.id FROM physical_segment s " +
                                "WHERE s.task_id = ? AND s.variant_id = ? " +
                                "  AND (s.start_node_id = ? OR s.end_node_id = ?) " +
                                "ORDER BY EXISTS (SELECT 1 FROM graph_edge ge " +
                                "                  WHERE ge.task_id = s.task_id " +
                                "                    AND ST_DWithin(ge.geom, s.geom, 0.1)) DESC, " +
                                "         s.id ASC",
                        Long.class, taskId, variantId, t, t);
                if (segs.size() <= 4) continue;

                // Страховочный assert: первый сегмент должен быть базовым.
                Boolean firstIsBase = jdbc.queryForObject(
                        "SELECT EXISTS (SELECT 1 FROM graph_edge ge " +
                                "                WHERE ge.task_id = ? AND ST_DWithin(ge.geom, " +
                                "                      (SELECT geom FROM physical_segment WHERE id = ?), 0.1))",
                        Boolean.class, taskId, segs.get(0));
                if (!Boolean.TRUE.equals(firstIsBase)) {
                    log.warn("[{}][{}] cascade-split: node {} first segment still NOT base " +
                                    "even after base-first ordering — review graph_edge table",
                            taskId, variantId, t);
                }

                List<Long> move = new ArrayList<>(segs.subList(KEEP, segs.size()));
                Long[] moveArr = move.toArray(new Long[0]);

                // Направление на T': средний вектор к дальним концам переносимых сегментов.
                double[] d = jdbc.queryForObject(
                        "SELECT COALESCE(AVG(dx), 1.0) AS dx, COALESCE(AVG(dy), 0.0) AS dy FROM (" +
                                "  SELECT ST_X(ST_PointN(geom, CASE WHEN start_node_id = ? THEN ST_NumPoints(geom) ELSE 1 END)) - ? AS dx, " +
                                "         ST_Y(ST_PointN(geom, CASE WHEN start_node_id = ? THEN ST_NumPoints(geom) ELSE 1 END)) - ? AS dy " +
                                "  FROM physical_segment WHERE id = ANY (?) ) v",
                        (rs, i) -> new double[]{rs.getDouble("dx"), rs.getDouble("dy")},
                        t, x, t, y, moveArr);
                double len = Math.hypot(d[0], d[1]);
                if (len < 1e-9) { d[0] = 1; d[1] = 0; len = 1; }
                double tx = x + OFFSET * d[0] / len;
                double ty = y + OFFSET * d[1] / len;

                // Создаём T' (branch_chamber)
                Long t2 = jdbc.queryForObject(
                        "INSERT INTO physical_node (task_id, variant_id, node_type, geom) " +
                                "VALUES (?, ?, 'branch_chamber', ST_SetSRID(ST_MakePoint(?, ?), 32637)) " +
                                "RETURNING id",
                        Long.class, taskId, variantId, tx, ty);

                // Переносим сегменты: FK + геометрия (ТЗ 7.2) + длина.
                for (Long sid : move) {
                    jdbc.update(
                            "UPDATE physical_segment SET " +
                                    "  start_node_id = CASE WHEN start_node_id = ? THEN ? ELSE start_node_id END, " +
                                    "  end_node_id   = CASE WHEN end_node_id   = ? THEN ? ELSE end_node_id   END, " +
                                    "  geom = CASE WHEN start_node_id = ? " +
                                    "         THEN ST_SetPoint(geom, 0, ST_SetSRID(ST_MakePoint(?, ?), 32637)) " +
                                    "         ELSE ST_SetPoint(geom, ST_NumPoints(geom) - 1, ST_SetSRID(ST_MakePoint(?, ?), 32637)) END " +
                                    "WHERE id = ?",
                            t, t2, t, t2, t, tx, ty, tx, ty, sid);
                    jdbc.update("UPDATE physical_segment SET length_m = ST_Length(geom) WHERE id = ?", sid);
                }

                // Ствол T -> T'. length_m вычисляется инлайн (NOT NULL).
                // diameter/cost оставляем NULL — их проставит assignDiametersAndCosts.
                Double trunkFlow = jdbc.queryForObject(
                        "SELECT COALESCE(SUM(flow_tph), 0) FROM physical_segment WHERE id = ANY (?)",
                        Double.class, (Object) moveArr);
                jdbc.update(
                        "INSERT INTO physical_segment " +
                                "  (task_id, variant_id, start_node_id, end_node_id, flow_tph, " +
                                "   laying_method, geom, length_m) " +
                                "VALUES (?, ?, ?, ?, ?, 'base', " +
                                "        ST_SetSRID(ST_MakeLine(ST_MakePoint(?, ?), ST_MakePoint(?, ?)), 32637), " +
                                "        ST_Length(ST_SetSRID(ST_MakeLine(ST_MakePoint(?, ?), ST_MakePoint(?, ?)), 32637)))",
                        taskId, variantId, t, t2, trunkFlow,
                        x, y, tx, ty,
                        x, y, tx, ty);

                total++;
                log.info("[{}][{}] cascade-split iter {}: node {} (deg={}) -> T'={} (moved {} segs, trunk flow={})",
                        taskId, variantId, iter, t, deg, t2, move.size(), trunkFlow);
            }
        }
        return total;
    }

    // ====================================================================
    // Totals для сводки
    // ====================================================================

    public Totals totals(UUID taskId, String variantId) {
        return jdbc.queryForObject(
                "SELECT " +
                        "  COALESCE((SELECT SUM(cost) FROM physical_segment " +
                        "             WHERE task_id = ? AND variant_id = ?), 0) AS segment_cost, " +
                        "  COALESCE((SELECT SUM(length_m) FROM physical_segment " +
                        "             WHERE task_id = ? AND variant_id = ?), 0) AS total_length, " +
                        "  COALESCE((SELECT SUM(chamber_cost) FROM physical_node " +
                        "             WHERE task_id = ? AND variant_id = ? " +
                        "               AND node_type IN ('new_terminal_chamber','branch_chamber')), 0) AS chamber_cost, " +
                        "  (SELECT count(*) FROM physical_node " +
                        "     WHERE task_id = ? AND variant_id = ? " +
                        "       AND node_type IN ('new_terminal_chamber','branch_chamber')) AS new_chambers, " +
                        "  COALESCE((SELECT SUM(chamber_cost) FROM physical_node " +
                        "             WHERE task_id = ? AND variant_id = ? " +
                        "               AND node_type = 'existing_tie_in'), 0) AS tie_in_cost, " +
                        "  (SELECT count(*) FROM physical_node " +
                        "     WHERE task_id = ? AND variant_id = ? " +
                        "       AND node_type = 'existing_tie_in') AS existing_tie_ins",
                (rs, i) -> {
                    Totals t = new Totals();
                    t.segmentCost = rs.getDouble("segment_cost");
                    t.totalLength = rs.getDouble("total_length");
                    t.chamberCost = rs.getDouble("chamber_cost");
                    t.newChamberCount = rs.getInt("new_chambers");
                    t.tieInCost = rs.getDouble("tie_in_cost");
                    t.existingTieInCount = rs.getInt("existing_tie_ins");
                    return t;
                },
                taskId, variantId, taskId, variantId,
                taskId, variantId, taskId, variantId,
                taskId, variantId, taskId, variantId);
    }

    public static class Result {
        public final int segments;
        public final int nodes;
        public Result(int segments, int nodes) {
            this.segments = segments;
            this.nodes = nodes;
        }
    }

    public static class Totals {
        public double segmentCost;
        public double totalLength;
        public double chamberCost;
        public int    newChamberCount;
        public double tieInCost;
        public int    existingTieInCount;
    }
}