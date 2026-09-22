package ru.dit.heattracer.model;

public class TieInCandidate {

    private String candidateType;      // heat_chamber | edge_projection | graph_node
    private String existingObjectId;
    private double lon;                // WGS84
    private double lat;
    private double distanceM;
    private int existingDiameter;
    private int requiredDiameter;

    public TieInCandidate() {
    }

    public TieInCandidate(String candidateType, String existingObjectId,
                          double lon, double lat, double distanceM,
                          int existingDiameter, int requiredDiameter) {
        this.candidateType = candidateType;
        this.existingObjectId = existingObjectId;
        this.lon = lon;
        this.lat = lat;
        this.distanceM = distanceM;
        this.existingDiameter = existingDiameter;
        this.requiredDiameter = requiredDiameter;
    }

    public String getCandidateType()      { return candidateType; }
    public String getExistingObjectId()   { return existingObjectId; }
    public double getLon()                { return lon; }
    public double getLat()                { return lat; }
    public double getDistanceM()          { return distanceM; }
    public int getExistingDiameter()      { return existingDiameter; }
    public int getRequiredDiameter()      { return requiredDiameter; }

    public void setCandidateType(String v)      { this.candidateType = v; }
    public void setExistingObjectId(String v)   { this.existingObjectId = v; }
    public void setLon(double v)                { this.lon = v; }
    public void setLat(double v)                { this.lat = v; }
    public void setDistanceM(double v)          { this.distanceM = v; }
    public void setExistingDiameter(int v)      { this.existingDiameter = v; }
    public void setRequiredDiameter(int v)      { this.requiredDiameter = v; }

    @Override
    public String toString() {
        return "TieInCandidate{" +
                "type=" + candidateType +
                ", id=" + existingObjectId +
                ", dist=" + String.format("%.1f", distanceM) + "m" +
                ", existing_DU=" + existingDiameter +
                ", required_DU=" + requiredDiameter +
                ", lon/lat=[" + String.format("%.5f", lon) + "," + String.format("%.5f", lat) + "]" +
                '}';
    }
}