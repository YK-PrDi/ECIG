package com.elebusiness.controller;

import com.elebusiness.model.GenerationTask;
import com.elebusiness.service.TaskService;
import com.elebusiness.service.VideoGenerationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.File;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;

@RestController
@RequestMapping("/api/video")
public class VideoController {

    private static final Logger log = LoggerFactory.getLogger(VideoController.class);
    private static final String OUTPUT_DIR = "./生成结果/视频";

    private final VideoGenerationService videoGenerationService;
    private final TaskService taskService;

    public VideoController(VideoGenerationService videoGenerationService, TaskService taskService) {
        this.videoGenerationService = videoGenerationService;
        this.taskService = taskService;
    }

    @PostMapping("/generate")
    public ResponseEntity<Map<String, Object>> generate(@RequestBody Map<String, String> body) {
        String prompt = body.getOrDefault("prompt", "").trim();
        String aspectRatio = body.getOrDefault("aspectRatio", "16:9");
        int durationSeconds = 0;
        try { durationSeconds = Integer.parseInt(body.getOrDefault("durationSeconds", "0")); } catch (NumberFormatException ignored) {}

        if (prompt.isEmpty()) {
            return ResponseEntity.badRequest()
                    .body(Map.of("success", false, "message", "提示词不能为空"));
        }

        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
        String outputPath = OUTPUT_DIR + "/video_" + timestamp + ".mp4";

        GenerationTask task = taskService.createTask(1);
        final int dur = durationSeconds;
        taskService.submit(task, () -> {
            try {
                String savedPath = videoGenerationService.generateVideo(prompt, aspectRatio, dur, outputPath);
                String filename = new File(savedPath).getName();
                task.addResult(Map.of("type", "video", "filename", filename, "status", "success"));
                task.incrementProgress();
            } catch (Exception e) {
                log.error("视频模式失败: {}", e.getMessage(), e);
                task.addResult(Map.of("type", "video", "status", "error", "message", e.getMessage()));
                throw new RuntimeException(e);
            }
        });

        return ResponseEntity.ok(Map.of("taskId", task.getId()));
    }

    @GetMapping("/file")
    public ResponseEntity<FileSystemResource> file(@RequestParam String filename) {
        // 防止路径遍历
        if (filename.contains("..") || filename.contains("/") || filename.contains("\\")) {
            return ResponseEntity.badRequest().build();
        }
        File f = new File(OUTPUT_DIR + "/" + filename);
        if (!f.exists()) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + filename + "\"")
                .contentType(MediaType.parseMediaType("video/mp4"))
                .body(new FileSystemResource(f));
    }
}
