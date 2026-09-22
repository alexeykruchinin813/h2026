package ru.dit.heattracer.io;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.locationtech.jts.geom.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import ru.dit.heattracer.model.RawFeature;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

@Component
public class GeoJsonStreamReader {

    private static final Logger log = LoggerFactory.getLogger(GeoJsonStreamReader.class);

    private final ObjectMapper mapper = new ObjectMapper();
    private final GeometryFactory geometryFactory = new GeometryFactory();

    /**
     * Читает GeoJSON-файл потоково и для каждого Feature вызывает consumer.
     * Память: O(1 Feature), не O(весь файл).
     */
    public void read(Path file, Consumer<RawFeature> consumer) throws IOException {
        long counter = 0;
        try (InputStream in = Files.newInputStream(file);
             JsonParser parser = mapper.getFactory().createParser(in)) {

            // 1. Находим массив "features"
            if (parser.nextToken() != JsonToken.START_OBJECT) {
                throw new IOException("Ожидался объект в корне GeoJSON");
            }

            boolean featuresFound = false;
            while (parser.nextToken() != JsonToken.END_OBJECT) {
                String field = parser.currentName();
                parser.nextToken();  // переходим к значению поля
                if ("features".equals(field)) {
                    if (parser.currentToken() != JsonToken.START_ARRAY) {
                        throw new IOException("'features' — не массив");
                    }
                    featuresFound = true;
                    break;
                } else {
                    parser.skipChildren();  // пропускаем "name", "crs" и т.п.
                }
            }

            if (!featuresFound) {
                throw new IOException("В файле нет массива 'features'");
            }

            // 2. Читаем Feature по одному
            while (parser.nextToken() != JsonToken.END_ARRAY) {
                JsonNode featureNode = mapper.readTree(parser);  // один Feature
                RawFeature feature = parseFeature(featureNode);
                if (feature != null) {
                    consumer.accept(feature);
                    counter++;
                }
            }
        }
        log.info("GeoJSON streaming done: {} features from {}", counter, file);
    }

    private RawFeature parseFeature(JsonNode node) {
        if (!"Feature".equals(node.path("type").asText())) {
            return null;
        }

        JsonNode props = node.get("properties");
        if (props == null || props.isNull()) {
            return null;
        }

        String objectType = props.path("object_type").asText(null);
        if (objectType == null || objectType.isEmpty()) {
            return null;  // объект без типа — пропускаем
        }

        String featureId = props.path("id").asText(null);

        @SuppressWarnings("unchecked")
        Map<String, Object> properties = mapper.convertValue(props, Map.class);

        Geometry geom = null;
        JsonNode geomNode = node.get("geometry");
        try {
            geom = parseGeometry(geomNode);
        } catch (IOException e) {
            log.warn("Не удалось разобрать геометрию для feature_id={}: {}",
                    featureId, e.getMessage());
            return null;
        }

        return new RawFeature(featureId, objectType, properties, geom);
    }

    private Geometry parseGeometry(JsonNode node) throws IOException {
        if (node == null || node.isNull()) return null;

        String type = node.path("type").asText();
        if (type == null || type.isEmpty()) {
            throw new IOException("Геометрия без поля 'type'");
        }

        // GeometryCollection обрабатываем отдельно — у него нет 'coordinates'
        if ("GeometryCollection".equals(type)) {
            return parseGeometryCollection(node);
        }

        JsonNode coords = node.get("coordinates");
        if (coords == null || coords.isNull()) return null;

        switch (type) {
            case "Point":
                return geometryFactory.createPoint(parseCoord(coords));
            case "LineString":
                return geometryFactory.createLineString(parseLine(coords));
            case "Polygon":
                return parseSinglePolygon(coords);
            case "MultiPoint":
                return geometryFactory.createMultiPointFromCoords(parseLine(coords));
            case "MultiLineString":
                return geometryFactory.createMultiLineString(parseMultiLine(coords));
            case "MultiPolygon":
                return geometryFactory.createMultiPolygon(parseMultiPolygon(coords));
            default:
                throw new IOException("Неподдерживаемый тип геометрии: " + type);
        }
    }

    private LineString[] parseMultiLine(JsonNode node) {
        LineString[] lines = new LineString[node.size()];
        for (int i = 0; i < node.size(); i++) {
            lines[i] = geometryFactory.createLineString(parseLine(node.get(i)));
        }
        return lines;
    }

    private Geometry parseGeometryCollection(JsonNode node) throws IOException {
        JsonNode geometries = node.get("geometries");
        if (geometries == null || !geometries.isArray()) {
            throw new IOException("GeometryCollection без массива 'geometries'");
        }

        int n = geometries.size();
        if (n == 0) {
            return geometryFactory.createGeometryCollection(new Geometry[0]);
        }

        Geometry[] parts = new Geometry[n];
        for (int i = 0; i < n; i++) {
            parts[i] = parseGeometry(geometries.get(i));
        }
        return geometryFactory.createGeometryCollection(parts);
    }

    private Coordinate parseCoord(JsonNode node) {
        return new Coordinate(node.get(0).asDouble(), node.get(1).asDouble());
    }

    private Coordinate[] parseLine(JsonNode node) {
        Coordinate[] coords = new Coordinate[node.size()];
        for (int i = 0; i < node.size(); i++) {
            coords[i] = parseCoord(node.get(i));
        }
        return coords;
    }

    private LinearRing parseRing(JsonNode node) {
        return geometryFactory.createLinearRing(parseLine(node));
    }

    private Polygon parseSinglePolygon(JsonNode node) {
        List<LinearRing> rings = new ArrayList<>();
        for (JsonNode ringNode : node) {
            rings.add(parseRing(ringNode));
        }
        LinearRing shell = rings.get(0);
        LinearRing[] holes = rings.size() > 1
                ? rings.subList(1, rings.size()).toArray(new LinearRing[0])
                : new LinearRing[0];
        return geometryFactory.createPolygon(shell, holes);
    }

    private Polygon[] parseMultiPolygon(JsonNode node) {
        Polygon[] polygons = new Polygon[node.size()];
        for (int i = 0; i < node.size(); i++) {
            polygons[i] = parseSinglePolygon(node.get(i));
        }
        return polygons;
    }
}