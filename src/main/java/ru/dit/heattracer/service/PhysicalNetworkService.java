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

        // 1.5 V77: разрыв циклов (артефакт ST_Node planarization).
        // Удаляем по одному ребру на цикл, предпочитая рёбра, удаление
        // которых понижает degree branch_chamber (экономия на камере).
        Integer pruned = jdbc.queryForObject(
                "SELECT prune_physical_cycles(?, ?)",
                Integer.class, taskId, variantId);
        if (pruned != null && pruned > 0) {
            log.info("[{}][{}] prune_physical_cycles: {} edges removed",
                    taskId, variantId, pruned);
            r = recount(taskId, variantId);
        }

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

        // 5.0 SnapSegmentEndpoints
        snapSegmentEndpoints(taskId, variantId);

        // 5. Точечное исправление пересечений (ST_Point и ST_LineString)
        int fixed = fixCrossings(taskId, variantId);
        if (fixed > 0) {
            log.info("[{}][{}] fixCrossings: {} crossing pairs resolved",
                    taskId, variantId, fixed);
            r = recount(taskId, variantId);
        }

        // 5.5 Удаление вырожденных сегментов (создаются split-ом на почти-концах)
        int dropped = dropZeroLengthSegments(taskId, variantId);
        if (dropped > 0) {
            log.info("[{}][{}] dropZeroLengthSegments: {} degenerate segments removed",
                    taskId, variantId, dropped);
            r = recount(taskId, variantId);
        }

        // 5.6 Удаление осиротевших узлов (degree=0), оставшихся от удалённых сегментов
        int orphans = jdbc.update(
                "DELETE FROM physical_node pn " +
                        "WHERE pn.task_id = ? AND pn.variant_id = ? " +
                        "  AND NOT EXISTS (SELECT 1 FROM physical_segment s " +
                        "                  WHERE s.task_id = pn.task_id AND s.variant_id = pn.variant_id " +
                        "                    AND (s.start_node_id = pn.id OR s.end_node_id = pn.id))",
                taskId, variantId);
        if (orphans > 0) {
            log.info("[{}][{}] removed {} orphaned nodes", taskId, variantId, orphans);
            r = recount(taskId, variantId);
        }

        // 6. Первичное назначение ДУ и стоимостей сегментов
        assignDiametersAndCosts(taskId, variantId);

        // 6.5 Стоимость камер (ТЗ 3.2) — ДОЛЖНА идти после assignDiametersAndCosts:
        //     использует s.diameter для определения max_du
        computeChamberCosts(taskId, variantId);

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
        jdbc.update(
                "UPDATE physical_segment SET length_m = ST_Length(geom) " +
                        "WHERE task_id = ? AND variant_id = ?",
                taskId, variantId);

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

    // 5.0 Snap segment endpoints to their node geometry (V77).
    // Устраняет микрозазоры между physical_segment.geom (LINESTRING endpoint)
    // и physical_node.geom. Без этого ST_Touches для пар сегментов, делящих
    // узел, возвращает false, и fixCrossings уходит в 200-итерационный цикл.
    private int snapSegmentEndpoints(UUID taskId, String variantId) {
        int n = jdbc.update(
                "UPDATE physical_segment s SET geom = " +
                        "  ST_SetPoint(s.geom, 0, n.geom) " +
                        "FROM physical_node n " +
                        "WHERE s.task_id = ? AND s.variant_id = ? " +
                        "  AND n.task_id = s.task_id AND n.variant_id = s.variant_id " +
                        "  AND s.start_node_id = n.id " +
                        "  AND NOT ST_Equals(ST_StartPoint(s.geom), n.geom)",
                taskId, variantId);
        n += jdbc.update(
                "UPDATE physical_segment s SET geom = " +
                        "  ST_SetPoint(s.geom, ST_NumPoints(s.geom) - 1, n.geom) " +
                        "FROM physical_node n " +
                        "WHERE s.task_id = ? AND s.variant_id = ? " +
                        "  AND n.task_id = s.task_id AND n.variant_id = s.variant_id " +
                        "  AND s.end_node_id = n.id " +
                        "  AND NOT ST_Equals(ST_EndPoint(s.geom), n.geom)",
                taskId, variantId);
        if (n > 0) {
            log.info("[{}][{}] snapSegmentEndpoints: {} endpoint fixes",
                    taskId, variantId, n);
        }
        return n;
    }

    /**
     * V76/V77.4: Точечное исправление пересечений сегментов (ТЗ 2.1).
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
     * <p>
     * V77.4: добавлен ранний выход, если за итерацию число unresolved пар
     * не уменьшилось. Без этого цикл при микрозазорах ST_LineSubstring
     * отрабатывает все 200 итераций впустую, создавая orphan-узлы.
     * Дополнительно splitSegmentAtPoint теперь стягивает концы новых
     * сегментов в геометрию узла — основной источник микрозазоров закрыт.
     */
    private int fixCrossings(UUID taskId, String variantId) {
        int total = 0;
        long prevUnresolved = Long.MAX_VALUE;

        for (int iter = 0; iter < 200; iter++) {
            List<Map<String, Object>> cross = jdbc.queryForList(
                    "SELECT a.id AS aid, b.id AS bid, " +
                            "       ST_GeometryType(ST_Intersection(a.geom, b.geom)) AS cross_type " +
                            "FROM physical_segment a " +
                            "JOIN physical_segment b ON a.id < b.id " +
                            "  AND ST_Intersects(a.geom, b.geom) " +
                            "  AND NOT ST_Touches(a.geom, b.geom) " +
                            "WHERE a.task_id = ? AND a.variant_id = ? " +
                            "LIMIT 1",
                    taskId, variantId);
            if (cross.isEmpty()) break;

            long aid = ((Number) cross.get(0).get("aid")).longValue();
            long bid = ((Number) cross.get(0).get("bid")).longValue();
            String crossType = (String) cross.get(0).get("cross_type");

            if ("ST_Point".equals(crossType)) {
                // X-пересечение двух сегментов — старый путь.
                double[] ixy = jdbc.queryForObject(
                        "SELECT ST_X(ST_Intersection(a.geom, b.geom)) AS x, " +
                                "       ST_Y(ST_Intersection(a.geom, b.geom)) AS y " +
                                "FROM physical_segment a, physical_segment b " +
                                "WHERE a.id = ? AND b.id = ?",
                        (rs, i) -> new double[]{rs.getDouble("x"), rs.getDouble("y")},
                        aid, bid);
                long nodeId = insertTechnicalNode(taskId, variantId, ixy[0], ixy[1]);
                splitSegmentAtPoint(taskId, variantId, aid, nodeId, ixy[0], ixy[1]);
                splitSegmentAtPoint(taskId, variantId, bid, nodeId, ixy[0], ixy[1]);
                total++;
            } else {
                // ST_LineString / ST_MultiLineString — коллинеарное наложение.
                // Проходим по endpoint'ам пересечения и режем каждый сегмент,
                // у которого endpoint строго внутри. Один сплит за итерацию —
                // после splitSegmentAtPoint исходный id удалён, поэтому
                // обязательно break + continue, чтобы перечитать пару.

                List<Map<String, Object>> pts = jdbc.queryForList(
                        "SELECT ST_X(p) AS x, ST_Y(p) AS y FROM ( " +
                                "  SELECT (ST_DumpPoints(ST_Intersection(a.geom, b.geom))).geom AS p " +
                                "  FROM physical_segment a, physical_segment b " +
                                "  WHERE a.id = ? AND b.id = ? " +
                                ") q",
                        aid, bid);

                boolean didSplit = false;
                // Сначала A.
                for (Map<String, Object> pt : pts) {
                    double px = ((Number) pt.get("x")).doubleValue();
                    double py = ((Number) pt.get("y")).doubleValue();
                    if (splitIfInterior(taskId, variantId, aid, px, py)) {
                        didSplit = true;
                        break;
                    }
                }
                if (!didSplit) {
                    // Потом B.
                    for (Map<String, Object> pt : pts) {
                        double px = ((Number) pt.get("x")).doubleValue();
                        double py = ((Number) pt.get("y")).doubleValue();
                        if (splitIfInterior(taskId, variantId, bid, px, py)) {
                            didSplit = true;
                            break;
                        }
                    }
                }
                if (!didSplit) {
                    // Ни один endpoint не внутри — значит один сегмент целиком
                    // лежит внутри другого. Это вырожденный случай: один из них
                    // имеет нулевую или почти нулевую длину. Удаляем более короткий.
                    Long shorter = jdbc.queryForObject(
                            "SELECT CASE WHEN ST_Length(a.geom) <= ST_Length(b.geom) " +
                                    "            THEN a.id ELSE b.id END " +
                                    "FROM physical_segment a, physical_segment b " +
                                    "WHERE a.id = ? AND b.id = ?",
                            Long.class, aid, bid);
                    jdbc.update("DELETE FROM physical_segment WHERE id = ?", shorter);
                    log.warn("[{}][{}] fixCrossings: deleted fully-contained degenerate seg {}",
                            taskId, variantId, shorter);
                }

                // После любой правки — прогон dedup: он съест появившиеся
                // средние дубликаты (когда A и B после сплита совпали концами).
                dedupParallelSegments(taskId, variantId);
                total++;
            }

            // V77.4: если за итерацию не удалось уменьшить число unresolved пар —
            // выходим. Продолжать смысла нет, каждая итерация плодит узлы.
            Long unresolvedNow = jdbc.queryForObject(
                    "SELECT count(*) FROM physical_segment a " +
                            "JOIN physical_segment b ON a.task_id = b.task_id " +
                            "  AND a.variant_id = b.variant_id AND a.id < b.id " +
                            "WHERE a.task_id = ? AND a.variant_id = ? " +
                            "  AND ST_Intersects(a.geom, b.geom) " +
                            "  AND NOT ST_Touches(a.geom, b.geom)",
                    Long.class, taskId, variantId);
            if (unresolvedNow != null && unresolvedNow >= prevUnresolved) {
                log.warn("[{}][{}] fixCrossings: stuck at {} unresolved pairs — breaking",
                        taskId, variantId, unresolvedNow);
                break;
            }
            prevUnresolved = unresolvedNow;
        }
        return total;
    }

    private boolean splitIfInterior(UUID taskId, String variantId,
                                    long segId, double px, double py) {
        Double frac = jdbc.queryForObject(
                "SELECT ST_LineLocatePoint(geom, ST_SetSRID(ST_MakePoint(?, ?), 32637)) " +
                        "FROM physical_segment WHERE id = ?",
                Double.class, px, py, segId);
        if (frac == null || frac < 0.001 || frac > 0.999) return false;

        long nodeId = insertTechnicalNode(taskId, variantId, px, py);
        splitSegmentAtPoint(taskId, variantId, segId, nodeId, px, py);
        return true;
    }

    private long insertTechnicalNode(UUID taskId, String variantId,
                                     double x, double y) {
        return jdbc.queryForObject(
                "INSERT INTO physical_node (task_id, variant_id, geom, node_type, degree) " +
                        "VALUES (?, ?, ST_SetSRID(ST_MakePoint(?, ?), 32637), 'technical_node', 4) " +
                        "RETURNING id",
                Long.class, taskId, variantId, x, y);
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
        Double frac = jdbc.queryForObject(
                "SELECT ST_LineLocatePoint(geom, " +
                        "  ST_SetSRID(ST_MakePoint(?, ?), 32637)) " +
                        "FROM physical_segment WHERE id = ?",
                Double.class, ix, iy, segId);

        if (frac == null || frac < 0.001 || frac > 0.999) {
            // Точка слишком близко к концу. Не разрезаем — только переназначаем
            // node_id и стягиваем тот конец, что уже рядом, в геометрию узла.
            jdbc.update(
                    "UPDATE physical_segment SET " +
                            "  start_node_id = CASE WHEN ST_DWithin(ST_StartPoint(geom), " +
                            "    ST_SetSRID(ST_MakePoint(?, ?), 32637), 1.0) THEN ? ELSE start_node_id END, " +
                            "  end_node_id   = CASE WHEN ST_DWithin(ST_EndPoint(geom), " +
                            "    ST_SetSRID(ST_MakePoint(?, ?), 32637), 1.0) THEN ? ELSE end_node_id END, " +
                            "  geom = CASE " +
                            "    WHEN ST_DWithin(ST_StartPoint(geom), " +
                            "         ST_SetSRID(ST_MakePoint(?, ?), 32637), 1.0) " +
                            "      THEN ST_SetPoint(geom, 0, ST_SetSRID(ST_MakePoint(?, ?), 32637)) " +
                            "    WHEN ST_DWithin(ST_EndPoint(geom), " +
                            "         ST_SetSRID(ST_MakePoint(?, ?), 32637), 1.0) " +
                            "      THEN ST_SetPoint(geom, ST_NumPoints(geom) - 1, ST_SetSRID(ST_MakePoint(?, ?), 32637)) " +
                            "    ELSE geom END " +
                            "WHERE id = ?",
                    ix, iy, nodeId, ix, iy, nodeId,
                    ix, iy, ix, iy, ix, iy, ix, iy,
                    segId);
            return;
        }

        // Разрезаем: INSERT двух частей, DELETE оригинала.
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

        jdbc.update("DELETE FROM physical_segment WHERE id = ?", segId);

        // V77.4: ST_LineSubstring даёт endpoint с погрешностью ~1e-9 м
        // относительно ST_MakePoint(ix, iy), использованного для узла.
        // Без snap ST_Touches ломается, и fixCrossings уходит в цикл.
        // Стягиваем начало всех сегментов, стартующих в новом узле,
        // и конец всех, заканчивающихся в нём.
        jdbc.update(
                "UPDATE physical_segment SET " +
                        "  geom = ST_SetPoint(geom, 0, ST_SetSRID(ST_MakePoint(?, ?), 32637)), " +
                        "  length_m = ST_Length(ST_SetPoint(geom, 0, ST_SetSRID(ST_MakePoint(?, ?), 32637))) " +
                        "WHERE task_id = ? AND variant_id = ? AND start_node_id = ?",
                ix, iy, ix, iy, taskId, variantId, nodeId);

        jdbc.update(
                "UPDATE physical_segment SET " +
                        "  geom = ST_SetPoint(geom, ST_NumPoints(geom) - 1, ST_SetSRID(ST_MakePoint(?, ?), 32637)), " +
                        "  length_m = ST_Length(ST_SetPoint(geom, ST_NumPoints(geom) - 1, ST_SetSRID(ST_MakePoint(?, ?), 32637))) " +
                        "WHERE task_id = ? AND variant_id = ? AND end_node_id = ?",
                ix, iy, ix, iy, taskId, variantId, nodeId);
    }

    // ====================================================================
    // Стоимость камер (ТЗ 3.2)
    // ====================================================================

    private void computeChamberCosts(UUID taskId, String variantId) {
        // (A) Новые камеры (new_terminal_chamber, branch_chamber).
        //     Стоимость — по таблице 3.2 ТЗ, шаг от max(ДУ) примыкающих сегментов.
        jdbc.update(
                "UPDATE physical_node pn SET chamber_cost = " +
                        "  CASE " +
                        "    WHEN sub.max_du <= 200  THEN 3000000  " +
                        "    WHEN sub.max_du <= 500  THEN 5000000  " +
                        "    WHEN sub.max_du <= 1000 THEN 8000000  " +
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

        // (B) Врезки в существующие камеры (existing_tie_in).
        //     По разъяснению 13: каждый НОВЫЙ линейный участок, заканчивающийся
        //     в существующей камере, — одна врезка стоимостью 5 000 000 руб.
        //     Все сегменты в physical_segment — новые (existing network отдельно),
        //     поэтому COUNT(s.id) = число врезок в эту камеру.
        jdbc.update(
                "UPDATE physical_node pn SET chamber_cost = sub.cnt * 5000000 " +
                        "FROM ( " +
                        "  SELECT n.id, COUNT(s.id)::bigint AS cnt " +
                        "  FROM physical_node n " +
                        "  JOIN physical_segment s " +
                        "    ON s.task_id = n.task_id AND s.variant_id = n.variant_id " +
                        "   AND (s.start_node_id = n.id OR s.end_node_id = n.id) " +
                        "  WHERE n.task_id = ? AND n.variant_id = ? " +
                        "    AND n.node_type = 'existing_tie_in' " +
                        "  GROUP BY n.id " +
                        ") sub WHERE pn.id = sub.id",
                taskId, variantId);

        // Диагностика: посчитать и залогировать результат.
        Map<String, Object> sums = jdbc.queryForMap(
                "SELECT " +
                        "  COALESCE(SUM(chamber_cost) FILTER (WHERE node_type IN " +
                        "    ('new_terminal_chamber','branch_chamber')), 0) AS new_cost, " +
                        "  COALESCE(SUM(chamber_cost) FILTER (WHERE node_type = 'existing_tie_in'), 0) AS tiein_cost, " +
                        "  COUNT(*) FILTER (WHERE node_type IN ('new_terminal_chamber','branch_chamber')) AS new_cnt, " +
                        "  COUNT(*) FILTER (WHERE node_type = 'existing_tie_in') AS tiein_cnt " +
                        "FROM physical_node WHERE task_id = ? AND variant_id = ?",
                taskId, variantId);
        log.info("[{}][{}] computeChamberCosts: new chambers={} ({} ₽), tie-ins={} ({} ₽)",
                taskId, variantId,
                sums.get("new_cnt"), sums.get("new_cost"),
                sums.get("tiein_cnt"), sums.get("tiein_cost"));
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
                        "INSERT INTO physical_node (task_id, variant_id, node_type, geom) " +
                                "VALUES (?, ?, 'branch_chamber', ST_SetSRID(ST_MakePoint(?, ?), 32637)) RETURNING id",
                        Long.class, taskId, variantId, tx, ty);

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
                            "  AND ST_DWithin(a.geom, b.geom, 0.05) " +
                            "WHERE a.task_id = ? AND a.variant_id = ? " +
                            "  AND NOT (a.node_type = 'oks' AND b.node_type = 'oks') " +
                            "LIMIT 1",
                    taskId, variantId);

            if (pair.isEmpty()) break;

            long keepId = ((Number) pair.get(0).get("keep_id")).longValue();
            long dropId = ((Number) pair.get(0).get("drop_id")).longValue();

            // Запомнить позицию keep ДО UPDATE — нужна для снапа геометрии.
            String keepWkt = jdbc.queryForObject(
                    "SELECT ST_AsText(geom) FROM physical_node WHERE id = ?",
                    String.class, keepId);

            // Шаг 1: снап геометрии всех сегментов, висящих на drop, в точку keep.
            jdbc.update(
                    "UPDATE physical_segment SET geom = CASE " +
                            "  WHEN start_node_id = ? THEN ST_SetPoint(geom, 0, ST_GeomFromText(?, 32637)) " +
                            "  WHEN end_node_id   = ? THEN ST_SetPoint(geom, ST_NumPoints(geom) - 1, ST_GeomFromText(?, 32637)) " +
                            "  ELSE geom END " +
                            "WHERE task_id = ? AND variant_id = ? " +
                            "  AND (start_node_id = ? OR end_node_id = ?)",
                    dropId, keepWkt, dropId, keepWkt,
                    taskId, variantId, dropId, dropId);

            // Шаг 2: перенаправить FK + пересчитать length_m по уже снапнутой geom.
            jdbc.update(
                    "UPDATE physical_segment SET " +
                            "  start_node_id = CASE WHEN start_node_id = ? THEN ? ELSE start_node_id END, " +
                            "  end_node_id   = CASE WHEN end_node_id   = ? THEN ? ELSE end_node_id END, " +
                            "  length_m = ST_Length(geom) " +
                            "WHERE task_id = ? AND variant_id = ? " +
                            "  AND (start_node_id = ? OR end_node_id = ?)",
                    dropId, keepId, dropId, keepId,
                    taskId, variantId, dropId, dropId);

            // Шаг 3: удалить drop-узел.
            jdbc.update("DELETE FROM physical_node WHERE id = ?", dropId);
            total++;
        }
        return total;
    }

    private int dropZeroLengthSegments(UUID taskId, String variantId) {
        return jdbc.update(
                "DELETE FROM physical_segment " +
                        "WHERE task_id = ? AND variant_id = ? AND ST_Length(geom) < 0.01",
                taskId, variantId);
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