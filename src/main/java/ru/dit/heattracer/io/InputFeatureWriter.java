package ru.dit.heattracer.io;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.locationtech.jts.io.WKTWriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import ru.dit.heattracer.model.RawFeature;

import java.io.StringReader;
import java.util.UUID;

/**
 * Потоковый писатель RawFeature в таблицу input_feature.
 * Не является Spring-бином: создаётся отдельно для каждой задачи,
 * чтобы избежать race condition между параллельными расчётами.
 */
public class InputFeatureWriter {

    private static final Logger log = LoggerFactory.getLogger(InputFeatureWriter.class);

    /** Колонки в порядке записи в COPY (без id — BIGSERIAL, без geom_utm — заполним UPDATE). */
    private static final String COLUMNS =
            "(task_id, feature_id, object_type, properties, geom_4326)";

    /** Размер батча. 1000 объектов ≈ 100–500 КБ памяти в StringBuilder. */
    private static final int BATCH_SIZE = 1000;

    private final UUID taskId;
    private final CopyService copyService;
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper = new ObjectMapper();
    private final WKTWriter wktWriter = new WKTWriter();

    private final StringBuilder buffer = new StringBuilder();
    private int bufferedCount = 0;
    private long totalCount = 0;

    public InputFeatureWriter(UUID taskId, CopyService copyService, JdbcTemplate jdbc) {
        this.taskId = taskId;
        this.copyService = copyService;
        this.jdbc = jdbc;
    }

    /**
     * Вызывается GeoJsonStreamReader на каждый Feature.
     */
    public void append(RawFeature f) {
        String json = toJson(f);
        String wkt = f.getGeom4326() != null ? wktWriter.write(f.getGeom4326()) : null;

        buffer.append(escape(taskId.toString())).append('\t')
                .append(escape(f.getFeatureId())).append('\t')
                .append(escape(f.getObjectType())).append('\t')
                .append(escape(json)).append('\t')
                .append(escape(wkt)).append('\n');

        bufferedCount++;

        if (bufferedCount >= BATCH_SIZE) {
            flushBatch();
        }
    }

    /**
     * Сбрасывает остаток буфера и обновляет geom_utm для всей задачи.
     * Возвращает общее количество загруженных объектов.
     */
    public long finish() {
        flushBatch();
        log.info("[{}] Loaded {} features into input_feature", taskId, totalCount);

        long start = System.currentTimeMillis();
        int updated = jdbc.update(
                "UPDATE input_feature " +
                        "SET geom_utm = ST_Transform(geom_4326, 32637) " +
                        "WHERE task_id = ? AND geom_4326 IS NOT NULL",
                taskId);
        log.info("[{}] geom_utm filled for {} rows in {} ms",
                taskId, updated, System.currentTimeMillis() - start);
        return totalCount;
    }

    private void flushBatch() {
        if (bufferedCount == 0) return;
        copyService.copyIn("input_feature", COLUMNS, new StringReader(buffer.toString()));
        totalCount += bufferedCount;
        buffer.setLength(0);
        bufferedCount = 0;
    }

    private String toJson(RawFeature f) {
        try {
            return mapper.writeValueAsString(f.getProperties());
        } catch (Exception e) {
            throw new RuntimeException("Cannot serialize properties to JSON", e);
        }
    }

    /**
     * Экранирование для PostgreSQL COPY в TEXT-формате:
     *  - backslash → \\
     *  - tab → \t
     *  - newline → \n
     *  - carriage return → \r
     *  - null → \N
     */
    private String escape(String s) {
        if (s == null) return "\\N";
        StringBuilder out = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '\\': out.append("\\\\"); break;
                case '\t': out.append("\\t"); break;
                case '\n': out.append("\\n"); break;
                case '\r': out.append("\\r"); break;
                default:   out.append(c);
            }
        }
        return out.toString();
    }
}