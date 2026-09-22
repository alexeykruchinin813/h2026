package ru.dit.heattracer.service;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Результат построения графа видимости.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class VisibilityGraphResult {
    
    /**
     * Количество вершин в графе.
     */
    private int vertexCount;
    
    /**
     * Количество рёбер в графе.
     */
    private int edgeCount;
    
    /**
     * Время выполнения в миллисекундах.
     */
    private long elapsedMs;
    
    /**
     * ID кластера, для которого построен граф.
     */
    private Integer clusterId;
    
    /**
     * ID задачи.
     */
    private String taskId;
}
