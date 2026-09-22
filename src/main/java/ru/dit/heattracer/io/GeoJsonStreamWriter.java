package ru.dit.heattracer.io;

import com.fasterxml.jackson.core.JsonEncoding;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Map;

@Component
public class GeoJsonStreamWriter {

    private static final Logger log = LoggerFactory.getLogger(GeoJsonStreamWriter.class);

    private final ObjectMapper mapper = new ObjectMapper();

    /**
     * Стриминговая запись FeatureCollection в файл.
     * Producer получает emitter и добавляет features один за другим.
     *
     * @return количество записанных features
     */
    public long writeCollection(Path output, FeatureProducer producer) throws IOException {
        long[] count = {0};
        long start = System.currentTimeMillis();

        try (OutputStream out = Files.newOutputStream(output,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE);
             JsonGenerator gen = mapper.getFactory().createGenerator(out, JsonEncoding.UTF8)) {

            gen.writeStartObject();
            gen.writeStringField("type", "FeatureCollection");
            gen.writeArrayFieldStart("features");

            producer.produce(new FeatureEmitter(gen, mapper, count));

            gen.writeEndArray();
            gen.writeEndObject();
        }

        log.info("GeoJSON written to {}: {} features in {} ms",
                output, count[0], System.currentTimeMillis() - start);
        return count[0];
    }

    @FunctionalInterface
    public interface FeatureProducer {
        void produce(FeatureEmitter emitter) throws IOException;
    }

    /**
     * Emitter, через который producer добавляет features.
     */
    public static class FeatureEmitter {

        private final JsonGenerator gen;
        private final ObjectMapper mapper;
        private final long[] count;

        FeatureEmitter(JsonGenerator gen, ObjectMapper mapper, long[] count) {
            this.gen = gen;
            this.mapper = mapper;
            this.count = count;
        }

        /**
         * Пишет один Feature с геометрией из PostGIS.
         * rawGeometryJson — JSON-строка из ST_AsGeoJSON, например
         *   {"type":"Point","coordinates":[37.63,55.69]}
         * Передаётся как raw — без экранирования.
         * Если null — пишется geometry=null (для variant_summary).
         */
        public void featureRawGeometry(String rawGeometryJson, Map<String, Object> properties)
                throws IOException {
            gen.writeStartObject();
            gen.writeStringField("type", "Feature");

            if (rawGeometryJson == null) {
                gen.writeNullField("geometry");
            } else {
                gen.writeFieldName("geometry");
                gen.writeRawValue(rawGeometryJson);
            }

            gen.writeFieldName("properties");
            gen.writeObject(properties);

            gen.writeEndObject();
            count[0]++;
        }
    }
}