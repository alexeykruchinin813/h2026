package ru.dit.heattracer.api.dto;

import java.util.UUID;

public class UploadResponse {

    private UUID taskId;
    private String status;

    public UploadResponse() {
    }

    public UploadResponse(UUID taskId, String status) {
        this.taskId = taskId;
        this.status = status;
    }

    public UUID getTaskId() {
        return taskId;
    }

    public void setTaskId(UUID taskId) {
        this.taskId = taskId;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }
}