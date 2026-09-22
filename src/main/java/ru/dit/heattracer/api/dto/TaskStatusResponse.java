package ru.dit.heattracer.api.dto;

import java.util.UUID;

public class TaskStatusResponse {

    private UUID taskId;
    private String status;
    private String stage;
    private int percent;
    private String errorMessage;

    public TaskStatusResponse() {
    }

    public TaskStatusResponse(UUID taskId, String status, String stage,
                              int percent, String errorMessage) {
        this.taskId = taskId;
        this.status = status;
        this.stage = stage;
        this.percent = percent;
        this.errorMessage = errorMessage;
    }

    public UUID getTaskId() { return taskId; }
    public void setTaskId(UUID taskId) { this.taskId = taskId; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getStage() { return stage; }
    public void setStage(String stage) { this.stage = stage; }

    public int getPercent() { return percent; }
    public void setPercent(int percent) { this.percent = percent; }

    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
}