package ru.dit.heattracer.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
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

    private final NamedParameterJdbcTemplate namedJdbc;

    public PhysicalNetworkService(JdbcTemplate jdbc, DiameterPicker diameterPicker) {
        this.jdbc = jdbc;
        this.diameterPicker = diameterPicker;
        this.namedJdbc = new NamedParameterJdbcTemplate(jdbc);
    }

    public Result build(UUID taskId, String variantId) {
        long startMs = System.currentTimeMillis();

        // 1. Базовая топология
        Result r = jdbc.queryForObject(
                "SELECT segments, nodes FROM build_physical_network(?, ?)",
                (rs, i) -> new Result(rs.getInt("segments"), rs.getInt("nodes")),
                taskId, variantId);
        if (r.segments == 0) return r;

        // 2. Каскадный сплит перегруженных узлов (degree > 4)
        int cascaded = cascadeSplitOverloadedNodes(taskId, variantId);
        if (cascaded > 0) {
            log.info("[{}][{}] cascade-split: {} overloaded nodes resolved",
                    taskId, variantId, cascaded);
            r = recount(taskId, variantId);
        }

        // 3. Сплит новых камер
        Integer splitOps = jdbc.queryForObject(
                "SELECT split_oversized_chambers(?, ?, ?)",
                Integer.class, taskId, variantId, 4);
        if (splitOps != null && splitOps > 0) {
            log.info("[{}][{}] split_oversized_chambers: {} nodes split",
                    taskId, variantId, splitOps);
        }

        // 3.5 Слияние коллинеарных путей
        int merged = mergeCoincidentNodes(taskId, variantId);
        if (merged > 0) {
            log.info("[{}][{}] mergeCoincidentNodes: {} coincident nodes merged",
                    taskId, variantId, merged);
            Integer resplit = jdbc.queryForObject(
                    "SELECT split_oversized_chambers(?, ?, ?)",
                    Integer.class, taskId, variantId, 4);
            if (resplit != null && resplit > 0) {
                log.info("[{}][{}] re-split after merge: {} nodes", taskId, variantId, resplit);
            }

        }

        // 4. Дедупликация параллельных сегментов
        int deduped = dedupParallelSegments(taskId, variantId);
        if (deduped > 0) {
            log.info("[{}][{}] dedup: removed {} parallel duplicate segments",
                    taskId, variantId, deduped);
        }

        // 5. V76: ТОЧЕЧНОЕ исправление пересечений от cascadeSplit.
        //    Не пересоздаёт топологию — только разрезает пересекающиеся пары
        //    через ST_LineLocatePoint + ST_LineSubstring.
        //    OKS-узлы, tie-in, диаметры — всё сохраняется.
        int fixed = fixCrossings(taskId, variantId);
        if (fixed > 0) {
            log.info("[{}][{}] fixCrossings: {} crossing pairs resolved",
                    taskId, variantId, fixed);
            r = recount(taskId, variantId);
        }

        // 6. Первичное назначение ДУ и стоимостей
        assignDiametersAndCosts(taskId, variantId);

        // 7. Финальный пересчёт стоимостей (страховка)
        recalcCosts(taskId, variantId);

        // 8. Финальные счётчики
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
     * Пересчёт стоимостей по финальным ДУ (после цикла предельных длин).
     * Не переназначает diameter — только cost = length_m * special_k * price.
     */
    private void recalcCosts(UUID taskId, String variantId) {
        int updated = jdbc.update(
                "UPDATE physical_segment s SET cost = s.length_m * COALESCE(s.special_k, 1.0) * " +
                        "  CASE WHEN s.diameter <= 100 THEN 3780.0  WHEN s.diameter <= 125 THEN 4050.0 " +
                        "       WHEN s.diameter <= 150 THEN 4590.0  WHEN s.diameter <= 200 THEN 5580.0 " +
                        "       WHEN s.diameter <= 250 THEN 6840.0  WHEN s.diameter <= 300 THEN 8370.0 " +
                        "       WHEN s.diameter <= 400 THEN 11070.0 WHEN s.diameter <= 500 THEN 13860.0 " +
                        "       WHEN s.diameter <= 600 THEN 16740.0 WHEN s.diameter <= 700 THEN 19530.0 " +
                        "       WHEN s.diameter <= 800 THEN 22410.0 WHEN s.diameter <= 900 THEN 25200.0 " +
                        "       WHEN s.diameter <= 1000 THEN 28080.0 WHEN s.diameter <= 1200 THEN 33660.0 " +
                        "       ELSE 39330.0 END " +
                        "WHERE s.task_id = ? AND s.variant_id = ? AND s.diameter IS NOT NULL",
                taskId, variantId);
        log.info("[{}][{}] recalcCosts: updated {} segments", taskId, variantId, updated);

        // Диагностика: проверить, что стоимости не NULL
        Integer nullCost = jdbc.queryForObject(
                "SELECT COUNT(*) FROM physical_segment WHERE task_id = ? AND variant_id = ? AND cost IS NULL",
                Integer.class, taskId, variantId);
        if (nullCost != null && nullCost > 0) {
            log.error("[{}][{}] recalcCosts: {} segments still have NULL cost!", taskId, variantId, nullCost);
        }
    }

    private int dedupParallelSegments(UUID taskId, String variantId) {
        return jdbc.queryForObject(
                "SELECT dedup_parallel_segments(?, ?)",
                Integer.class, taskId, variantId);
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

    /**
     * V76: Точечное исправление пересечений сегментов (ТЗ 2.1).
     * <p>
     * cascadeSplitOverloadedNodes может создать геометрические пересечения
     * из-за смещения узлов на TRUNK_LEN=0.4м. Этот метод находит каждую
     * пересекающуюся пару и разрезает оба сегмента в точке пересечения
     * через ST_LineLocatePoint + ST_LineSubstring, создавая новый
     * technical_node.
     * <p>
     * В отличие от replanarize (ST_Snap + ST_Node), этот метод:
     * <ul>
     *   <li>НЕ пересоздаёт топологию — OKS/tie-in узлы не теряются;</li>
     *   <li>НЕ использует ST_Snap — нет сдвига координат на 5 см;</li>
     *   <li>Работает итеративно — каждое пересечение исправляется отдельно.</li>
     * </ul>
     */
    private int fixCrossings(UUID taskId, String variantId) {
        int total = 0;
        for (int iter = 0; iter < 50; iter++) {
            // Найти ОДНУ пересекающуюся пару (LIMIT 1 для безопасности)
            List<Map<String, Object>> cross = jdbc.queryForList(
                    "SELECT a.id AS aid, b.id AS bid, " +
                            "       ST_X(ST_Intersection(a.geom, b.geom)) AS ix, " +
                            "       ST_Y(ST_Intersection(a.geom, b.geom)) AS iy " +
                            "FROM physical_segment a " +
                            "JOIN physical_segment b ON a.id < b.id " +
                            "  AND ST_Intersects(a.geom, b.geom) " +
                            "  AND NOT ST_Touches(a.geom, b.geom) " +
                            "  AND ST_GeometryType(ST_Intersection(a.geom, b.geom)) = 'ST_Point'" +
                            "WHERE a.task_id = ? AND a.variant_id = ? " +
                            "LIMIT 1",
                    taskId, variantId);

            if (cross.isEmpty()) break;

            long aid = ((Number) cross.get(0).get("aid")).longValue();
            long bid = ((Number) cross.get(0).get("bid")).longValue();
            double ix = ((Number) cross.get(0).get("ix")).doubleValue();
            double iy = ((Number) cross.get(0).get("iy")).doubleValue();

            // Создать новый technical_node в точке пересечения
            Long nodeId = jdbc.queryForObject(
                    "INSERT INTO physical_node (task_id, variant_id, geom, node_type, degree) " +
                            "VALUES (?, ?, ST_SetSRID(ST_MakePoint(?, ?), 32637), 'technical_node', 4) " +
                            "RETURNING id",
                    Long.class, taskId, variantId, ix, iy);

            // Разрезать оба сегмента в точке пересечения
            splitSegmentAtPoint(taskId, variantId, aid, nodeId, ix, iy);
            splitSegmentAtPoint(taskId, variantId, bid, nodeId, ix, iy);

            total++;
            log.debug("[{}][{}] fixCrossings iter {}: split segs {} & {} at ({}, {})",
                    taskId, variantId, iter, aid, bid, ix, iy);
        }
        return total;
    }

    /**
     * Разрезает один сегмент в точке (ix, iy), создавая два новых сегмента
     * с тем же flow/laying/special_k. Оригинальный сегмент удаляется.
     * <p>
     * Использует ST_LineLocatePoint для нахождения доли линии и
     * ST_LineSubstring для разрезания. Это надёжнее, чем ST_Split,
     * который может вернуть пустой результат при float-погрешностях.
     */
    private void splitSegmentAtPoint(UUID taskId, String variantId,
                                     long segId, long nodeId,
                                     double ix, double iy) {
        // Найти долю линии, на которой находится ближайшая точка к (ix, iy)
        Double frac = jdbc.queryForObject(
                "SELECT ST_LineLocatePoint(geom, " +
                        "  ST_SetSRID(ST_MakePoint(?, ?), 32637)) " +
                        "FROM physical_segment WHERE id = ?",
                Double.class, ix, iy, segId);

        if (frac == null || frac < 0.001 || frac > 0.999) {
            // Точка слишком близко к концу — разрез не нужен,
            // просто перепривязываем конец к новому узлу
            jdbc.update(
                    "UPDATE physical_segment SET " +
                            "  start_node_id = CASE WHEN ST_DWithin(ST_StartPoint(geom), " +
                            "    ST_SetSRID(ST_MakePoint(?, ?), 32637), 1.0) THEN ? ELSE start_node_id END, " +
                            "  end_node_id = CASE WHEN ST_DWithin(ST_EndPoint(geom), " +
                            "    ST_SetSRID(ST_MakePoint(?, ?), 32637), 1.0) THEN ? ELSE end_node_id END " +
                            "WHERE id = ?",
                    ix, iy, nodeId, ix, iy, nodeId, segId);
            return;
        }

        // Вставить две новые части с наследованием атрибутов
        jdbc.update(
                "INSERT INTO physical_segment " +
                        "  (task_id, variant_id, start_node_id, end_node_id, " +
                        "   geom, flow_tph, length_m, laying_method, special_k) " +
                        "SELECT task_id, variant_id, start_node_id, ?, " +
                        "  ST_LineSubstring(geom, 0, ?), " +
                        "  flow_tph, ST_Length(ST_LineSubstring(geom, 0, ?)), " +
                        "  laying_method, special_k " +
                        "FROM physical_segment WHERE id = ? " +
                        "UNION ALL " +
                        "SELECT task_id, variant_id, ?, end_node_id, " +
                        "  ST_LineSubstring(geom, ?, 1), " +
                        "  flow_tph, ST_Length(ST_LineSubstring(geom, ?, 1)), " +
                        "  laying_method, special_k " +
                        "FROM physical_segment WHERE id = ?",
                nodeId, frac, frac, segId,
                nodeId, frac, frac, segId);

        // Удалить оригинальный сегмент
        jdbc.update("DELETE FROM physical_segment WHERE id = ?", segId);
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
     * V74: каскадный сплит узлов degree > 4. Ствол T→T' — кусок базового колеса w0
     * длиной TRUNK_LEN (коллинеарен существующей трубе), остаток w0 уходит T'→далеко,
     * перенесённые колёса получают конец в T' (изгиб ≤ TRUNK_LEN у конца).
     * Все мутации — через NamedParameterJdbcTemplate: рассинхрон positional-аргументов
     * невозможен физически.
     */
    private int cascadeSplitOverloadedNodes(UUID taskId, String variantId) {
        final int MAX_ITER = 8;
        final int KEEP = 4;            // 3 OKS-колеса + ствол у T
        final double TRUNK_LEN = 0.4;
        int total = 0;

        for (int iter = 0; iter < MAX_ITER; iter++) {
            List<Map<String, Object>> over = jdbc.queryForList(
                    "SELECT n.id AS node_id, " +
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
                int deg = ((Number) row.get("deg")).intValue();

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

                Boolean firstIsBase = jdbc.queryForObject(
                        "SELECT EXISTS (SELECT 1 FROM graph_edge ge " +
                                "                WHERE ge.task_id = ? AND ST_DWithin(ge.geom, " +
                                "                      (SELECT geom FROM physical_segment WHERE id = ?), 0.1))",
                        Boolean.class, taskId, segs.get(0));
                if (!Boolean.TRUE.equals(firstIsBase)) {
                    log.warn("[{}][{}] cascade-split: node {} first segment NOT base — trunk on new wheel",
                            taskId, variantId, t);
                }

                long w0 = segs.get(0);
                List<Long> move = new ArrayList<>(segs.subList(KEEP, segs.size()));
                List<Long> cutSet = new ArrayList<>(move);
                cutSet.add(w0);

                Double trunkFlow = jdbc.queryForObject(
                        "SELECT COALESCE(SUM(flow_tph),0) FROM physical_segment WHERE id = ANY (?)",
                        Double.class, (Object) cutSet.toArray(new Long[0]));

                // T' на w0 в TRUNK_LEN метрах от T (ориентация от T).
                double[] tp = namedJdbc.queryForObject(
                        "SELECT ST_X(p) AS x, ST_Y(p) AS y FROM ( " +
                                "  SELECT ST_LineInterpolatePoint(g2, LEAST(:trunk / l, 0.5)) AS p FROM ( " +
                                "    SELECT CASE WHEN start_node_id = :t THEN geom ELSE ST_Reverse(geom) END AS g2, " +
                                "           ST_Length(geom) AS l " +
                                "    FROM physical_segment WHERE id = :w0) s) q",
                        new MapSqlParameterSource()
                                .addValue("trunk", TRUNK_LEN).addValue("t", t).addValue("w0", w0),
                        (rs, i) -> new double[]{rs.getDouble("x"), rs.getDouble("y")});
                double tx = tp[0], ty = tp[1];

                Long t2 = jdbc.queryForObject(
                        "SELECT id FROM physical_node " +
                                "WHERE task_id = ? AND variant_id = ? " +
                                "  AND ST_DWithin(geom, ST_SetSRID(ST_MakePoint(?, ?), 32637), 0.05) " +
                                "  AND node_type IN ('existing_tie_in','branch_chamber') " +
                                "ORDER BY CASE node_type " +
                                "    WHEN 'existing_tie_in' THEN 0 " +
                                "    WHEN 'branch_chamber'  THEN 1 " +
                                "    ELSE 2 END, " +
                                "    id " +
                                "LIMIT 1",
                        Long.class, taskId, variantId, tx, ty);
                if (t2 == null) {
                    t2 = jdbc.queryForObject(
                            "INSERT INTO physical_node (task_id, variant_id, node_type, geom) " +
                                    "VALUES (?, ?, 'branch_chamber', ST_SetSRID(ST_MakePoint(?, ?), 32637)) RETURNING id",
                            Long.class, taskId, variantId, tx, ty);
                }

                // Ствол T→T' = первый кусок w0 (коллинеарен базовой трубе).
                namedJdbc.update(
                        "INSERT INTO physical_segment " +
                                "  (task_id, variant_id, start_node_id, end_node_id, flow_tph, " +
                                "   laying_method, geom, length_m) " +
                                "SELECT :taskId, :variantId, :t, :t2, :flow, 'base', g.geom, ST_Length(g.geom) " +
                                "FROM (SELECT CASE WHEN start_node_id = :t " +
                                "             THEN ST_LineSubstring(geom, 0, LEAST(:trunk / ST_Length(geom), 0.5)) " +
                                "             ELSE ST_Reverse(ST_LineSubstring(ST_Reverse(geom), 0, LEAST(:trunk / ST_Length(geom), 0.5))) " +
                                "        END AS geom FROM physical_segment WHERE id = :w0) g",
                        new MapSqlParameterSource()
                                .addValue("taskId", taskId).addValue("variantId", variantId)
                                .addValue("t", t).addValue("t2", t2)
                                .addValue("flow", trunkFlow).addValue("trunk", TRUNK_LEN)
                                .addValue("w0", w0));

                // Остаток w0: конец у T заменяется на T'.
                // ВАЖНО: в PostgreSQL все выражения SET видят СТАРУЮ строку, поэтому
                // CASE по start_node_id корректен одновременно с заменой FK.
                namedJdbc.update(
                        "UPDATE physical_segment SET " +
                                "  geom = CASE WHEN start_node_id = :t " +
                                "         THEN ST_LineSubstring(geom, LEAST(:trunk / ST_Length(geom), 0.5), 1) " +
                                "         ELSE ST_Reverse(ST_LineSubstring(ST_Reverse(geom), LEAST(:trunk / ST_Length(geom), 0.5), 1)) END, " +
                                "  start_node_id = CASE WHEN start_node_id = :t THEN :t2 ELSE start_node_id END, " +
                                "  end_node_id   = CASE WHEN end_node_id   = :t THEN :t2 ELSE end_node_id   END " +
                                "WHERE id = :w0",
                        new MapSqlParameterSource()
                                .addValue("t", t).addValue("trunk", TRUNK_LEN)
                                .addValue("t2", t2).addValue("w0", w0));
                namedJdbc.update(
                        "UPDATE physical_segment SET length_m = ST_Length(geom) WHERE id = :w0",
                        new MapSqlParameterSource().addValue("w0", w0));

                // Переносимые колёса: конец у T переносим в T' (изгиб ≤ TRUNK_LEN у конца).
                for (Long sid : move) {
                    int moved = namedJdbc.update(
                            "UPDATE physical_segment SET " +
                                    "  start_node_id = CASE WHEN start_node_id = :t THEN :t2 ELSE start_node_id END, " +
                                    "  end_node_id   = CASE WHEN end_node_id   = :t THEN :t2 ELSE end_node_id   END, " +
                                    "  geom = CASE WHEN start_node_id = :t " +
                                    "         THEN ST_SetPoint(geom, 0, ST_SetSRID(ST_MakePoint(:x, :y), 32637)) " +
                                    "         ELSE ST_SetPoint(geom, ST_NumPoints(geom) - 1, ST_SetSRID(ST_MakePoint(:x, :y), 32637)) END " +
                                    "WHERE id = :sid",
                            new MapSqlParameterSource()
                                    .addValue("t", t).addValue("t2", t2)
                                    .addValue("x", tx).addValue("y", ty)
                                    .addValue("sid", sid));
                    if (moved != 1) {
                        log.error("[{}][{}] cascade-split: UPDATE seg {} affected {} rows — parameter misalignment!",
                                taskId, variantId, sid, moved);
                    }
                    namedJdbc.update(
                            "UPDATE physical_segment SET length_m = ST_Length(geom) WHERE id = :sid",
                            new MapSqlParameterSource().addValue("sid", sid));
                }

                total++;
                log.info("[{}][{}] cascade-split iter {}: node {} (deg={}) -> T'={} (moved {} segs, trunk {} m collinear)",
                        taskId, variantId, iter, t, deg, t2, move.size(), TRUNK_LEN);
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

    private int mergeCoincidentNodes(UUID taskId, String variantId) {
        int total = 0;
        for (int iter = 0; iter < 50; iter++) {
            List<Map<String, Object>> pair = jdbc.queryForList(
                    "SELECT a.id AS aid, b.id AS bid, " +
                            "  CASE " +
                            "    WHEN a.node_type = 'oks' THEN a.id " +
                            "    WHEN b.node_type = 'oks' THEN b.id " +
                            "    WHEN a.node_type = 'existing_tie_in' THEN a.id " +
                            "    WHEN b.node_type = 'existing_tie_in' THEN b.id " +
                            "    ELSE LEAST(a.id, b.id) " +
                            "  END AS keep_id, " +
                            "  CASE " +
                            "    WHEN a.node_type = 'oks' THEN b.id " +
                            "    WHEN b.node_type = 'oks' THEN a.id " +
                            "    WHEN a.node_type = 'existing_tie_in' THEN b.id " +
                            "    WHEN b.node_type = 'existing_tie_in' THEN a.id " +
                            "    ELSE GREATEST(a.id, b.id) " +
                            "  END AS drop_id " +
                            "FROM physical_node a " +
                            "JOIN physical_node b ON a.id < b.id " +
                            "  AND a.task_id = b.task_id AND a.variant_id = b.variant_id " +
                            "  AND ST_DWithin(a.geom, b.geom, 0.05) " +   // 5 см
                            "WHERE a.task_id = ? AND a.variant_id = ? " +
                            "  AND NOT (a.node_type = 'oks' AND b.node_type = 'oks') " + // OKS не сливаем между собой
                            "LIMIT 1",
                    taskId, variantId);

            if (pair.isEmpty()) break;

            long keepId = ((Number) pair.get(0).get("keep_id")).longValue();
            long dropId = ((Number) pair.get(0).get("drop_id")).longValue();

            // 1) перенаправить FK всех сегментов с drop на keep
            jdbc.update(
                    "UPDATE physical_segment SET " +
                            "  start_node_id = CASE WHEN start_node_id = ? THEN ? ELSE start_node_id END, " +
                            "  end_node_id   = CASE WHEN end_node_id   = ? THEN ? ELSE end_node_id   END " +
                            "WHERE task_id = ? AND variant_id = ? " +
                            "  AND (start_node_id = ? OR end_node_id = ?)",
                    dropId, keepId, dropId, keepId, taskId, variantId, dropId, dropId);

            // 2) удалить дубль-узел
            jdbc.update("DELETE FROM physical_node WHERE id = ?", dropId);
            total++;
        }
        return total;
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