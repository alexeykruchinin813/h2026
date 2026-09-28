package ru.dit.heattracer.api;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import ru.dit.heattracer.api.dto.TaskStatusResponse;
import ru.dit.heattracer.api.dto.UploadResponse;
import ru.dit.heattracer.service.TaskService;
import ru.dit.heattracer.service.TaskState;

import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

import java.nio.file.Files;
import java.nio.file.Path;

import java.io.IOException;
import java.util.UUID;

@RestController
@RequestMapping("/api")
public class UploadController {

    private final TaskService taskService;

    @Autowired
    public UploadController(TaskService taskService) {
        this.taskService = taskService;
    }

    @PostMapping("/upload")
    public ResponseEntity<UploadResponse> upload(@RequestParam("file") MultipartFile file)
            throws IOException {
        UUID taskId = taskService.submit(file);
        return ResponseEntity.ok(new UploadResponse(taskId, "RUNNING"));
    }

    @GetMapping("/task/{taskId}")
    public ResponseEntity<TaskStatusResponse> status(@PathVariable UUID taskId) {
        return taskService.get(taskId)
                .map(s -> ResponseEntity.ok(new TaskStatusResponse(
                        s.getId(),
                        s.getStatus().name(),
                        s.getStage(),
                        s.getPercent(),
                        s.getErrorMessage()
                )))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * Скачивание result.geojson для задачи.
     * Возвращает FeatureCollection со всеми вариантами (раздел 7 ТЗ).
     */
    @GetMapping("/task/{taskId}/result")
    public ResponseEntity<Resource> result(@PathVariable UUID taskId) throws IOException {
        TaskState state = taskService.get(taskId).orElse(null);
        if (state == null) {
            return ResponseEntity.notFound().build();
        }
        Path path = state.getResultPath();
        if (path == null || !Files.exists(path)) {
            return ResponseEntity.notFound().build();
        }
        Resource resource = new ByteArrayResource(Files.readAllBytes(path));
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"result_" + taskId + ".geojson\"")
                .contentType(MediaType.APPLICATION_JSON)
                .contentLength(resource.contentLength())
                .body(resource);
    }

}