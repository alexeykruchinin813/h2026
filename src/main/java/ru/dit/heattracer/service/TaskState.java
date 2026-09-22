package ru.dit.heattracer.service;

import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;

public class TaskState {

    public enum Status {
        RUNNING, DONE, FAILED
    }

    private final UUID id;
    private final Path inputPath;
    private volatile Path resultPath;
    private volatile Status status = Status.RUNNING;
    private volatile String stage = "INIT";
    private volatile int percent = 0;
    private volatile String errorMessage;
    private final Instant createdAt = Instant.now();
    private volatile Instant finishedAt;

    public TaskState(UUID id, Path inputPath) {
        this.id = id;
        this.inputPath = inputPath;
    }

    // ==== Геттеры ====
    public UUID getId()               { return id; }
    public Path getInputPath()        { return inputPath; }
    public Path getResultPath()       { return resultPath; }
    public Status getStatus()         { return status; }
    public String getStage()          { return stage; }
    public int getPercent()           { return percent; }
    public String getErrorMessage()   { return errorMessage; }
    public Instant getCreatedAt()     { return createdAt; }
    public Instant getFinishedAt()    { return finishedAt; }

    // ==== Сеттеры (потокобезопасные через volatile) ====
    public void setResultPath(Path resultPath) { this.resultPath = resultPath; }
    public void setStatus(Status status)       { this.status = status; }
    public void setStage(String stage)         { this.stage = stage; }
    public void setPercent(int percent)        { this.percent = percent; }
    public void setErrorMessage(String msg)    { this.errorMessage = msg; }
    public void markFinished()                 { this.finishedAt = Instant.now(); }
}