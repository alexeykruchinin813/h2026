package ru.dit.heattracer.model;

public class VisibilityGraphResult {

    private final long vertices;
    private final long edges;
    private final int elapsedMs;

    public VisibilityGraphResult(long vertices, long edges, int elapsedMs) {
        this.vertices = vertices;
        this.edges = edges;
        this.elapsedMs = elapsedMs;
    }

    public long getVertices()  { return vertices; }
    public long getEdges()     { return edges; }
    public int getElapsedMs()  { return elapsedMs; }

    @Override
    public String toString() {
        return "VisibilityGraphResult{vertices=" + vertices
                + ", edges=" + edges
                + ", elapsed=" + elapsedMs + "ms}";
    }
}