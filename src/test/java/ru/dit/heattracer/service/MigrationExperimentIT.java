package ru.dit.heattracer.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Эксперимент миграций (диагностика, не валидация).
 *
 * Отвечает на вопросы, критичные для решения о V36:
 *   1. Что реально лежит в flyway_schema_history (какие версии, checksum, success)?
 *   2. Какая функция create_escape_points загружена в БД (сигнатура + первые ~300 символов тела)?
 *   3. Какая задача является последней завершённой (для сверки с DIAG)?
 *
 * Тест ничего не assert'ит, не падает. Если таблиц нет — печатает NULL/NOT_FOUND.
 */
@DisplayName("Эксперимент: миграции и функция create_escape_points")
class MigrationExperimentIT extends BasePostgresIntegrationTest {

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    @DisplayName("Эксперимент: flyway_schema_history + prosrc create_escape_points")
    void experiment() {
        System.out.println("\n================ MIGRATION EXPERIMENT ================");

        // ===== (1) flyway_schema_history — какие миграции применены =====
        try {
            List<Map<String, Object>> rows = jdbc.queryForList(
                    "SELECT installed_rank, version, description, type, success, checksum, " +
                            "       installed_on " +
                            "FROM flyway_schema_history " +
                            "ORDER BY installed_rank DESC " +
                            "LIMIT 10");
            System.out.println("[EXP] (1) Последние 10 записей flyway_schema_history:");
            for (Map<String, Object> r : rows) {
                System.out.printf("          rank=%-4s version=%-6s %-40s type=%-6s success=%s checksum=%s installed=%s%n",
                        r.get("installed_rank"),
                        r.get("version"),
                        r.get("description"),
                        r.get("type"),
                        r.get("success"),
                        r.get("checksum"),
                        r.get("installed_on"));
            }
        } catch (Exception e) {
            System.out.println("[EXP] (1) flyway_schema_history недоступна: " + e.getMessage());
        }

        // ===== (2) Какая create_escape_points активна + первые 500 символов тела =====
        try {
            List<Map<String, Object>> fns = jdbc.queryForList(
                    "SELECT p.oid::regprocedure::text            AS sig, " +
                            "       pg_get_function_arguments(p.oid)     AS args, " +
                            "       LEFT(p.prosrc, 500)                  AS body_head, " +
                            "       LENGTH(p.prosrc)                     AS body_len " +
                            "FROM pg_proc p " +
                            "JOIN pg_namespace n ON n.oid = p.pronamespace " +
                            "WHERE n.nspname = 'public' AND p.proname = 'create_escape_points'");
            System.out.println("[EXP] (2) Активные сигнатуры create_escape_points (" + fns.size() + " шт.):");
            for (Map<String, Object> r : fns) {
                System.out.println("          --- sig: " + r.get("sig"));
                System.out.println("              args: " + r.get("args"));
                System.out.println("              body_len: " + r.get("body_len") + " chars");
                System.out.println("              body_head (first 500):");
                String head = (String) r.get("body_head");
                if (head != null) {
                    for (String line : head.split("\n")) {
                        System.out.println("                | " + line);
                    }
                }
            }
        } catch (Exception e) {
            System.out.println("[EXP] (2) pg_proc недоступна: " + e.getMessage());
        }

        // ===== (3) Маркеры V35 в теле функции: ищем уникальные строки =====
        try {
            Map<String, Object> m = jdbc.queryForMap(
                    "SELECT " +
                            "  COUNT(*) FILTER (WHERE p.prosrc LIKE '%V35%')                          AS v35_marker, " +
                            "  COUNT(*) FILTER (WHERE p.prosrc LIKE '%V36%')                          AS v36_marker, " +
                            "  COUNT(*) FILTER (WHERE p.prosrc LIKE '%both directions%')              AS union_all_hint, " +
                            "  COUNT(*) FILTER (WHERE p.prosrc LIKE '%oks -> own escape_point%')      AS oks_to_escape_hint, " +
                            "  COUNT(*) FILTER (WHERE p.prosrc LIKE '%statement_timeout%')            AS stmt_timeout, " +
                            "  COUNT(*) FILTER (WHERE p.prosrc LIKE '%MATERIALIZED%')                 AS materialized " +
                            "FROM pg_proc p " +
                            "JOIN pg_namespace n ON n.oid = p.pronamespace " +
                            "WHERE n.nspname = 'public' AND p.proname = 'create_escape_points'");
            System.out.println("[EXP] (3) Маркеры в теле функции:");
            System.out.println("          V35 marker            : " + m.get("v35_marker"));
            System.out.println("          V36 marker            : " + m.get("v36_marker"));
            System.out.println("          'both directions'     : " + m.get("union_all_hint"));
            System.out.println("          'oks -> own escape'   : " + m.get("oks_to_escape_hint"));
            System.out.println("          statement_timeout     : " + m.get("stmt_timeout"));
            System.out.println("          MATERIALIZED          : " + m.get("materialized"));
        } catch (Exception e) {
            System.out.println("[EXP] (3) Маркерный запрос упал: " + e.getMessage());
        }

        // ===== (4) Последняя задача =====
        try {
            List<Map<String, Object>> tasks = jdbc.queryForList(
                    "SELECT id, status, created_at, finished_at " +
                            "FROM task " +
                            "ORDER BY COALESCE(finished_at, created_at) DESC " +
                            "LIMIT 3");
            System.out.println("[EXP] (4) Последние 3 задачи:");
            for (Map<String, Object> r : tasks) {
                System.out.printf("          %s status=%s created=%s finished=%s%n",
                        r.get("id"), r.get("status"), r.get("created_at"), r.get("finished_at"));
            }
        } catch (Exception e) {
            System.out.println("[EXP] (4) task недоступна: " + e.getMessage());
        }

        System.out.println("================ END MIGRATION EXPERIMENT ================\n");
    }
}