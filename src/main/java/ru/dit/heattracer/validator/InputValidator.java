package ru.dit.heattracer.validator;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
public class InputValidator {

    private static final Logger log = LoggerFactory.getLogger(InputValidator.class);

    private final JdbcTemplate jdbc;

    public InputValidator(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Проверяет входные данные для задачи.
     * Схема по актуальному ТЗ:
     *   source                — id, object_type
     *   heat_network          — id, object_type, diameter
     *   heat_chamber          — id, object_type
     *   oks_connection_point  — id, object_type, flow_tph
     *   restriction           — id, object_type, restriction_type
     */
    public ValidationReport validate(UUID taskId) {
        ValidationReport report = new ValidationReport();

        Long total = jdbc.queryForObject(
                "SELECT count(*) FROM input_feature WHERE task_id = ?",
                Long.class, taskId);
        report.setTotalFeatures(total == null ? 0 : total);

        if (total == null || total == 0) {
            report.addError("input_feature пуста для task_id=" + taskId);
            return report;
        }

        checkIdAttribute(taskId, report);
        checkObjectType(taskId, report);
        checkHeatNetwork(taskId, report);
        checkOksConnectionPoint(taskId, report);
        checkRestriction(taskId, report);

        return report;
    }

    // ============================================================
    // 1. Общие атрибуты (все типы)
    // ============================================================

    private void checkIdAttribute(UUID taskId, ValidationReport r) {
        Long bad = jdbc.queryForObject(
                "SELECT count(*) FROM input_feature " +
                        "WHERE task_id = ? AND (feature_id IS NULL OR feature_id = '')",
                Long.class, taskId);
        if (bad != null && bad > 0) {
            r.addWarning("Объектов без feature_id (properties.id): " + bad);
        }
    }

    private void checkObjectType(UUID taskId, ValidationReport r) {
        Long bad = jdbc.queryForObject(
                "SELECT count(*) FROM input_feature " +
                        "WHERE task_id = ? AND (object_type IS NULL OR object_type = '')",
                Long.class, taskId);
        if (bad != null && bad > 0) {
            r.addError("Объектов без object_type: " + bad
                    + " — эти объекты не будут обработаны");
        }

        // Неизвестные типы объектов
        Long unknown = jdbc.queryForObject(
                "SELECT count(*) FROM input_feature " +
                        "WHERE task_id = ? " +
                        "  AND object_type NOT IN " +
                        "      ('source', 'heat_network', 'heat_chamber', " +
                        "       'oks_connection_point', 'restriction')",
                Long.class, taskId);
        if (unknown != null && unknown > 0) {
            r.addWarning("Объектов с неизвестным object_type: " + unknown);
        }
    }

    // ============================================================
    // 2. Обязательные атрибуты по типам
    // ============================================================

    private void checkHeatNetwork(UUID taskId, ValidationReport r) {
        Long noDiameter = jdbc.queryForObject(
                "SELECT count(*) FROM input_feature " +
                        "WHERE task_id = ? AND object_type = 'heat_network' " +
                        "  AND (properties->>'diameter') IS NULL",
                Long.class, taskId);
        if (noDiameter != null && noDiameter > 0) {
            r.addError("heat_network без diameter: " + noDiameter
                    + " — невозможно рассчитать ДУ существующей сети");
        }

        // Проверим, что diameter — целое положительное число
        Long badDiameter = jdbc.queryForObject(
                "SELECT count(*) FROM input_feature " +
                        "WHERE task_id = ? AND object_type = 'heat_network' " +
                        "  AND (properties->>'diameter') IS NOT NULL " +
                        "  AND (properties->>'diameter')::numeric <= 0",
                Long.class, taskId);
        if (badDiameter != null && badDiameter > 0) {
            r.addWarning("heat_network с diameter ≤ 0: " + badDiameter);
        }
    }

    private void checkOksConnectionPoint(UUID taskId, ValidationReport r) {
        Long noFlow = jdbc.queryForObject(
                "SELECT count(*) FROM input_feature " +
                        "WHERE task_id = ? AND object_type = 'oks_connection_point' " +
                        "  AND (properties->>'flow_tph') IS NULL",
                Long.class, taskId);
        if (noFlow != null && noFlow > 0) {
            r.addError("oks_connection_point без flow_tph: " + noFlow
                    + " — невозможно рассчитать расход");
        }

        // Проверим, что flow_tph — положительное число
        Long badFlow = jdbc.queryForObject(
                "SELECT count(*) FROM input_feature " +
                        "WHERE task_id = ? AND object_type = 'oks_connection_point' " +
                        "  AND (properties->>'flow_tph') IS NOT NULL " +
                        "  AND (properties->>'flow_tph')::numeric <= 0",
                Long.class, taskId);
        if (badFlow != null && badFlow > 0) {
            r.addWarning("oks_connection_point с flow_tph ≤ 0: " + badFlow);
        }
    }

    private void checkRestriction(UUID taskId, ValidationReport r) {
        Long noType = jdbc.queryForObject(
                "SELECT count(*) FROM input_feature " +
                        "WHERE task_id = ? AND object_type = 'restriction' " +
                        "  AND (properties->>'restriction_type') IS NULL",
                Long.class, taskId);
        if (noType != null && noType > 0) {
            r.addWarning("restriction без restriction_type: " + noType
                    + " — правила обхода/пересечения не будут применены");
        }

        // Неизвестные типы ограничений (вне Таблицы 2)
        Long unknownType = jdbc.queryForObject(
                "SELECT count(*) FROM input_feature " +
                        "WHERE task_id = ? AND object_type = 'restriction' " +
                        "  AND (properties->>'restriction_type') IS NOT NULL " +
                        "  AND (properties->>'restriction_type') NOT IN " +
                        "      ('oks', 'park', 'social_area', 'prohibited_site', " +
                        "       'water', 'railway', 'road', 'tram_tracks', " +
                        "       'gas_pipeline', 'power_cable', 'heat_network')",
                Long.class, taskId);
        if (unknownType != null && unknownType > 0) {
            r.addWarning("restriction с типом вне Таблицы 2: " + unknownType
                    + " (поддержка необязательна, будут проигнорированы)");
        }
    }
}