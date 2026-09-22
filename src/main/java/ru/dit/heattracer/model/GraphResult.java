package ru.dit.heattracer.model;

public class GraphResult {

    private final long nodes;
    private final long edges;
    private final Long sourceNodeId;

    public GraphResult(long nodes, long edges, Long sourceNodeId) {
        this.nodes = nodes;
        this.edges = edges;
        this.sourceNodeId = sourceNodeId;
    }

    public long getNodes()          { return nodes; }
    public long getEdges()          { return edges; }
    public Long getSourceNodeId()   { return sourceNodeId; }

    @Override
    public String toString() {
        return "GraphResult{nodes=" + nodes + ", edges=" + edges
                + ", sourceNodeId=" + sourceNodeId + "}";
    }
}