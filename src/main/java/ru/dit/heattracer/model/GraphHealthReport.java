package ru.dit.heattracer.model;

import java.util.ArrayList;
import java.util.List;

public class GraphHealthReport {

    private final long nodes;
    private final long edges;
    private final long sourceId;
    private final long sourceEdges;
    private final long reachable;
    private final List<String> warnings = new ArrayList<>();

    public GraphHealthReport(long nodes, long edges, long sourceId,
                             long sourceEdges, long reachable) {
        this.nodes = nodes;
        this.edges = edges;
        this.sourceId = sourceId;
        this.sourceEdges = sourceEdges;
        this.reachable = reachable;
    }

    public void addWarning(String w) { warnings.add(w); }

    public long getNodes()       { return nodes; }
    public long getEdges()       { return edges; }
    public long getSourceId()    { return sourceId; }
    public long getSourceEdges() { return sourceEdges; }
    public long getReachable()   { return reachable; }
    public List<String> getWarnings() { return warnings; }

    public boolean isHealthy() {
        return sourceId > 0
                && sourceEdges > 0
                && reachable >= (nodes - 2)     // допустим 1-2 изолированных узла
                && warnings.isEmpty();
    }

    @Override
    public String toString() {
        return "GraphHealthReport{" +
                "nodes=" + nodes +
                ", edges=" + edges +
                ", sourceId=" + sourceId +
                ", sourceEdges=" + sourceEdges +
                ", reachable=" + reachable +
                ", warnings=" + warnings.size() +
                '}';
    }
}