package ru.dit.heattracer.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import ru.dit.heattracer.io.GeoJsonStreamWriter;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;

@Service
public class ExportService {

    private static final Logger log = LoggerFactory.getLogger(ExportService.class);

    private final JdbcTemplate jdbc;
    private final GeoJsonStreamWriter writer;
    private final ObjectMapper mapper = new ObjectMapper();

    public ExportService(JdbcTemplate jdbc, GeoJsonStreamWriter writer) {
        this.jdbc = jdbc;
        this.writer = writer;
    }

    /**
     * Читает input_feature для задачи и пишет echo.geojson.
     * Используется как тест корректности streaming-записи.
     */
    public long exportInputFeatures(UUID taskId, Path output) throws IOException {
        return writer.writeCollection(output, emitter ->
                jdbc.query(
                        "SELECT feature_id, object_type, properties, " +
                                "       ST_AsGeoJSON(geom_4326) AS geom_json " +
                                "FROM input_feature " +
                                "WHERE task_id = ? " +
                                "ORDER BY id",
                        rs -> {
                            try {
                                String rawGeom = rs.getString("geom_json");
                                String propsJson = rs.getString("properties");
                                @SuppressWarnings("unchecked")
                                Map<String, Object> props = mapper.readValue(propsJson, Map.class);
                                emitter.featureRawGeometry(rawGeom, props);
                            } catch (IOException e) {
                                throw new RuntimeException(e);
                            }
                        },
                        taskId
                )
        );
    }

    /**
     * Читает variant_feature для задачи и варианта, пишет в output.
     * Используется для реального результата.
     */
    public long exportVariantFeatures(UUID taskId, String variantId, Path output) throws IOException {
        return writer.writeCollection(output, emitter ->
                jdbc.query(
                        "SELECT object_type, properties, " +
                                "       ST_AsGeoJSON(geom_4326) AS geom_json " +
                                "FROM variant_feature " +
                                "WHERE task_id = ? AND variant_id = ? " +
                                "ORDER BY id",
                        rs -> {
                            try {
                                String rawGeom = rs.getString("geom_json");
                                String propsJson = rs.getString("properties");
                                @SuppressWarnings("unchecked")
                                Map<String, Object> props = mapper.readValue(propsJson, Map.class);
                                emitter.featureRawGeometry(rawGeom, props);
                            } catch (IOException e) {
                                throw new RuntimeException(e);
                            }
                        },
                        taskId, variantId
                )
        );
    }
}