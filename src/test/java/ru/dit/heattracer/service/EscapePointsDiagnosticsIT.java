package ru.dit.heattracer.service;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * NEXT-1: диагностика escape-инварианта графа видимости (без падений).
 *
 * Отвечает на вопросы, нужные для решения о миграции V35:
 *   (1) Есть ли у каждой oks-вершины ребро к escape-точке СВОЕГО полигона?
 *   (2) Какие пары типов вершин фактически связаны рёбрами?
 *   (3) Симметрия: уникальных пар vs. всего рёбер.
 *   (4) Схема visibility_edge: индексы и constraints (ищем, что блокирует зеркало).
 *   (5) Сколько пар имеют ТОЛЬКО одно направление (при корректном зеркале = 0).
 *
 * <p>Диагностика, не валидация: assertTrue не применяется. При отсутствии
 * завершённых задач тест пропускается (Assumptions), не падая.
 */
@DisplayName("NEXT-1: диагностика escape-инварианта графа видимости")
class EscapePointsDiagnosticsIT extends BasePostgresIntegrationTest {

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    @DisplayName("Диагностика: oks→escape, пары типов, симметрия, схема visibility_edge")
    void diagnose() {
        UUID taskId = latestFinishedTaskIdOrNull();
        Assumptions.assumeTrue(taskId != null,
                "Нет завершённых задач — сначала прогони HybridConnectivityIT");

        Integer oksTotal = jdbc.queryForObject(
                "SELECT COUNT(*) FROM visibility_vertex " +
                        "WHERE task_id = ? AND vertex_type = 'oks'",
                Integer.class, taskId);
        Integer escTotal = jdbc.queryForObject(
                "SELECT COUNT(*) FROM visibility_vertex " +
                        "WHERE task_id = ? AND vertex_type = 'escape_point'",
                Integer.class, taskId);
        Long totalEdges = jdbc.queryForObject(
                "SELECT COUNT(*) FROM visibility_edge WHERE task_id = ?",
                Long.class, taskId);

        System.out.printf("%n[DIAG] task=%s: oks=%d, escape_points=%d, edges=%d%n",
                taskId, oksTotal, escTotal, totalEdges);

        // ===== (1) У каждого OKS есть ребро к own escape_point? =====
        List<Map<String, Object>> perOks = jdbc.queryForList(
                "WITH oks_v AS ( " +
                        "  SELECT id, own_polygon_id AS poly_id " +
                        "  FROM visibility_vertex " +
                        "  WHERE task_id = ? AND vertex_type = 'oks' " +
                        "), own_esc AS ( " +
                        "  SELECT id, own_polygon_id AS poly_id " +
                        "  FROM visibility_vertex " +
                        "  WHERE task_id = ? AND vertex_type = 'escape_point' " +
                        ") " +
                        "SELECT ov.id AS oks_vid, ov.poly_id, " +
                        "       COUNT(e.id) AS own_escape_edges " +
                        "FROM oks_v ov " +
                        "LEFT JOIN own_esc oe ON oe.poly_id = ov.poly_id " +
                        "LEFT JOIN visibility_edge e ON " +
                        "  (e.source_vertex = ov.id AND e.target_vertex = oe.id) OR " +
                        "  (e.target_vertex = ov.id AND e.source_vertex = oe.id) " +
                        "GROUP BY ov.id, ov.poly_id " +
                        "ORDER BY own_escape_edges, ov.id",
                taskId, taskId);

        int zeroOwnEsc = 0;
        int minEdges = Integer.MAX_VALUE;
        for (Map<String, Object> r : perOks) {
            long n = ((Number) r.get("own_escape_edges")).longValue();
            if (n == 0) zeroOwnEsc++;
            if (n < minEdges) minEdges = (int) n;
        }
        System.out.printf("[DIAG] (1) OKS без рёбер к собственному escape_point: %d из %d " +
                        "(min own_escape_edges = %s)%n",
                zeroOwnEsc, perOks.size(),
                perOks.isEmpty() ? "n/a" : String.valueOf(minEdges));

        // ===== (2) Сводка по парам типов вершин =====
        List<Map<String, Object>> typePairs = jdbc.queryForList(
                "SELECT sv.vertex_type AS src, tv.vertex_type AS tgt, COUNT(*) AS n " +
                        "FROM visibility_edge e " +
                        "JOIN visibility_vertex sv ON sv.id = e.source_vertex " +
                        "JOIN visibility_vertex tv ON tv.id = e.target_vertex " +
                        "WHERE e.task_id = ? " +
                        "GROUP BY sv.vertex_type, tv.vertex_type " +
                        "ORDER BY n DESC",
                taskId);

        System.out.println("[DIAG] (2) Рёбра по парам типов вершин:");
        for (Map<String, Object> r : typePairs) {
            System.out.printf("          %-16s -> %-16s : %s%n",
                    r.get("src"), r.get("tgt"), r.get("n"));
        }

        // ===== (3) Симметрия зеркалирования =====
        Long uniquePairs = jdbc.queryForObject(
                "SELECT COUNT(*) FROM ( " +
                        "  SELECT LEAST(source_vertex, target_vertex) AS a, " +
                        "         GREATEST(source_vertex, target_vertex) AS b " +
                        "  FROM visibility_edge " +
                        "  WHERE task_id = ? " +
                        "  GROUP BY 1, 2 " +
                        ") x",
                Long.class, taskId);

        double ratio = (uniquePairs == null || uniquePairs == 0)
                ? 0.0
                : totalEdges.doubleValue() / uniquePairs.doubleValue();

        System.out.printf("[DIAG] (3) Уникальных пар: %d, всего рёбер: %d, ratio=%.3f%n",
                uniquePairs, totalEdges, ratio);
        if (ratio < 1.5 || ratio > 2.5) {
            System.out.printf("[DIAG] (3) ВНИМАНИЕ: ratio вне [1.5..2.5] — зеркалирование " +
                    "V33/V34 не сработало или создало дубликаты%n");
        }

        // ===== (4) Схема visibility_edge: индексы и constraints =====
        List<Map<String, Object>> indexes = jdbc.queryForList(
                "SELECT indexname, indexdef FROM pg_indexes WHERE tablename = 'visibility_edge'");
        System.out.println("[DIAG] (4a) Индексы visibility_edge:");
        for (Map<String, Object> r : indexes) {
            System.out.printf("          %s : %s%n", r.get("indexname"), r.get("indexdef"));
        }

        List<Map<String, Object>> constraints = jdbc.queryForList(
                "SELECT conname, contype::text AS ctype, " +
                        "       pg_get_constraintdef(oid) AS def " +
                        "FROM pg_constraint WHERE conrelid = 'visibility_edge'::regclass");
        System.out.println("[DIAG] (4b) Constraints visibility_edge:");
        for (Map<String, Object> r : constraints) {
            System.out.printf("          %s [%s] : %s%n",
                    r.get("conname"), r.get("ctype"), r.get("def"));
        }

        // ===== (5) Однонаправленные пары =====
        Long unidir = jdbc.queryForObject(
                "SELECT COUNT(*) FROM ( " +
                        "  SELECT LEAST(source_vertex, target_vertex) AS a, " +
                        "         GREATEST(source_vertex, target_vertex) AS b " +
                        "  FROM visibility_edge " +
                        "  WHERE task_id = ? " +
                        "  GROUP BY 1, 2 HAVING COUNT(*) = 1 " +
                        ") x",
                Long.class, taskId);
        System.out.printf("[DIAG] (5) Пар только в одном направлении: %d " +
                        "(при корректном зеркале должно быть 0)%n",
                unidir);

        System.out.println("[DIAG] Диагностика завершена. Тест не падает по дизайну.");
    }

    @Test
    @DisplayName("Диагностика 2 ОКС без escape-рёбер")
    void diagnoseOrphanOks() {
        java.util.UUID taskId = latestFinishedTaskIdOrNull();
        org.junit.jupiter.api.Assumptions.assumeTrue(taskId != null);

        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT v.id AS oks_vid, v.cluster_id, v.own_polygon_id, " +
                        "       ST_AsText(v.geom) AS oks_geom, " +
                        "       COUNT(DISTINCT e.id) AS own_escape_edges " +
                        "FROM visibility_vertex v " +
                        "LEFT JOIN visibility_edge e ON " +
                        "  (e.source_vertex = v.id OR e.target_vertex = v.id) " +
                        "  AND e.task_id = v.task_id " +
                        "  AND e.target_vertex IN (SELECT id FROM visibility_vertex " +
                        "                          WHERE task_id = v.task_id " +
                        "                            AND vertex_type = 'escape_point' " +
                        "                            AND own_polygon_id = v.own_polygon_id) " +
                        "WHERE v.task_id = ? AND v.vertex_type = 'oks' " +
                        "GROUP BY v.id, v.cluster_id, v.own_polygon_id, v.geom " +
                        "HAVING COUNT(DISTINCT e.id) = 0",
                taskId);

        System.out.println("[DIAG-ORPHAN] ОКС без escape-рёбер: " + rows.size());
        for (Map<String, Object> r : rows) {
            System.out.printf("  oks_vid=%s cluster=%s own_poly=%s geom=%s%n",
                    r.get("oks_vid"), r.get("cluster_id"), r.get("own_polygon_id"), r.get("oks_geom"));
        }

        // Сколько escape-точек у их полигонов в БД
        List<Map<String, Object>> epCount = jdbc.queryForList(
                "SELECT own_polygon_id, COUNT(*) AS n FROM visibility_vertex " +
                        "WHERE task_id = ? AND vertex_type = 'escape_point' " +
                        "GROUP BY own_polygon_id ORDER BY n ASC LIMIT 10",
                taskId);
        System.out.println("[DIAG-ORPHAN] Escape-точек на полигон (нижние 10):");
        for (Map<String, Object> r : epCount) {
            System.out.printf("  poly=%s -> %s escape_points%n",
                    r.get("own_polygon_id"), r.get("n"));
        }
    }

    @Test
    @DisplayName("Диагностика: полное тело create_escape_points")
    void dumpFullFunctionBody() {
        List<Map<String, Object>> fns = jdbc.queryForList(
                "SELECT p.oid::regprocedure::text AS sig, p.prosrc AS body " +
                        "FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace " +
                        "WHERE n.nspname = 'public' AND p.proname = 'create_escape_points'");
        for (Map<String, Object> r : fns) {
            System.out.println("=========== FULL BODY of " + r.get("sig") + " ===========");
            System.out.println(r.get("body"));
            System.out.println("=========== END BODY ===========");
        }
    }

    /**
     * Возвращает id самой свежей завершённой задачи, либо null.
     */
    private UUID latestFinishedTaskIdOrNull() {
        try {
            return jdbc.queryForObject(
                    "SELECT id FROM task " +
                            "WHERE finished_at IS NOT NULL " +
                            "ORDER BY finished_at DESC LIMIT 1",
                    UUID.class);
        } catch (Exception e) {
            return null;
        }
    }
}