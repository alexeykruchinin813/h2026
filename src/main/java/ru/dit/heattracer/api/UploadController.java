package ru.dit.heattracer.api;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import ru.dit.heattracer.api.dto.TaskStatusResponse;
import ru.dit.heattracer.api.dto.UploadResponse;
import ru.dit.heattracer.service.TaskService;
import ru.dit.heattracer.service.TaskState;

import java.io.IOException;
import java.util.UUID;

@RestController
@RequestMapping("/api")
public class UploadController {

    private final TaskService taskService;

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
}