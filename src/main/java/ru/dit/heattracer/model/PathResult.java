package ru.dit.heattracer.model;

import java.util.List;

/**
 * Результат поиска пути от одной вершины графа видимости до другой.
 * Используется в D2 (A*) и далее — в D4 (Steiner), D6 (разбиение на участки).
 */
public class PathResult {

    private final long fromVertex;
    private final long toVertex;
    private final double totalCost;
    private final double totalLength;
    private final int edgeCount;
    private final String pathWkt;         // WKT геометрии (для отладки и экспорта)
    private final List<Long> edgeIds;     // ID рёбер в порядке следования
    private final boolean found;

    public PathResult(long fromVertex, long toVertex,
                      double totalCost, double totalLength, int edgeCount,
                      String pathWkt, List<Long> edgeIds, boolean found) {
        this.fromVertex = fromVertex;
        this.toVertex = toVertex;
        this.totalCost = totalCost;
        this.totalLength = totalLength;
        this.edgeCount = edgeCount;
        this.pathWkt = pathWkt;
        this.edgeIds = edgeIds;
        this.found = found;
    }

    public static PathResult notFound(long fromVertex, long toVertex) {
        return new PathResult(fromVertex, toVertex, 0, 0, 0, null, List.of(), false);
    }

    public long getFromVertex()     { return fromVertex; }
    public long getToVertex()       { return toVertex; }
    public double getTotalCost()    { return totalCost; }
    public double getTotalLength()  { return totalLength; }
    public int getEdgeCount()       { return edgeCount; }
    public String getPathWkt()      { return pathWkt; }
    public List<Long> getEdgeIds()  { return edgeIds; }
    public boolean isFound()        { return found; }

    @Override
    public String toString() {
        if (!found) {
            return "PathResult{from=" + fromVertex + ", to=" + toVertex + ", NOT FOUND}";
        }
        return "PathResult{from=" + fromVertex + ", to=" + toVertex
                + ", cost=" + String.format("%.2f", totalCost)
                + ", length=" + String.format("%.2f", totalLength)
                + ", edges=" + edgeCount + "}";
    }
}