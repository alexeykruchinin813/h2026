package ru.dit.heattracer.model;

import org.locationtech.jts.geom.Geometry;

import java.util.Map;

/**
 * Один объект из входного GeoJSON — "сырой" Feature.
 * Хранит только то, что нужно для дальнейшей загрузки в БД.
 */
public class RawFeature {

    private String featureId;              // properties.id
    private String objectType;             // properties.object_type
    private Map<String, Object> properties; // все properties целиком
    private Geometry geom4326;              // геометрия в EPSG:4326

    public RawFeature() {
    }

    public RawFeature(String featureId, String objectType,
                      Map<String, Object> properties, Geometry geom4326) {
        this.featureId = featureId;
        this.objectType = objectType;
        this.properties = properties;
        this.geom4326 = geom4326;
    }

    public String getFeatureId() { return featureId; }
    public void setFeatureId(String featureId) { this.featureId = featureId; }

    public String getObjectType() { return objectType; }
    public void setObjectType(String objectType) { this.objectType = objectType; }

    public Map<String, Object> getProperties() { return properties; }
    public void setProperties(Map<String, Object> properties) { this.properties = properties; }

    public Geometry getGeom4326() { return geom4326; }
    public void setGeom4326(Geometry geom4326) { this.geom4326 = geom4326; }
}