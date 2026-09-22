package ru.dit.heattracer.model;

import java.util.ArrayList;
import java.util.List;

public class OksCluster {

    private final int clusterId;
    private final List<String> featureIds = new ArrayList<>();
    private double totalFlow;
    private double centroidLon;
    private double centroidLat;

    public OksCluster(int clusterId) {
        this.clusterId = clusterId;
    }

    public int getClusterId()          { return clusterId; }
    public List<String> getFeatureIds() { return featureIds; }
    public double getTotalFlow()       { return totalFlow; }
    public double getCentroidLon()     { return centroidLon; }
    public double getCentroidLat()     { return centroidLat; }

    public void addPoint(String featureId, double flow,
                         double centroidLon, double centroidLat) {
        this.featureIds.add(featureId);
        this.totalFlow += flow;
        this.centroidLon = centroidLon;
        this.centroidLat = centroidLat;
    }

    public int size() {
        return featureIds.size();
    }

    @Override
    public String toString() {
        return "OksCluster{id=" + clusterId
                + ", size=" + featureIds.size()
                + ", flow=" + totalFlow
                + ", centroid=[" + String.format("%.5f", centroidLon)
                + "," + String.format("%.5f", centroidLat) + "]}";
    }
}