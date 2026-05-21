package com.elebusiness.controller;

import com.elebusiness.model.GenerationTask;
import com.elebusiness.service.SeedanceVideoService;
import com.elebusiness.service.TaskService;
import com.elebusiness.service.VideoGenerationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/video")
public class VideoController {

    private static final Logger log = LoggerFactory.getLogger(VideoController.class);
    private static final String OUTPUT_DIR = "./生成结果/视频";

    private final VideoGenerationService videoGenerationService;
    private final SeedanceVideoService seedanceVideoService;
    private final TaskService taskService;

    public VideoController(VideoGenerationService videoGenerationService,
                           SeedanceVideoService seedanceVideoService,
                           TaskService taskService) {
        this.videoGenerationService = videoGenerationService;
        this.seedanceVideoService = seedanceVideoService;
        this.taskService = taskService;
    }

    @PostMapping(value = "/generate", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Map<String, Object>> generate(
            @RequestParam(value = "images", required = false) List<MultipartFile> images,
            @RequestParam(value = "model", defaultValue = "veo-3.1-generate-preview") String model,
            @RequestParam(value = "prompt", defaultValue = "") String promptParam,
            @RequestParam(value = "aspectRatio", defaultValue = "16:9") String aspectRatio,
            @RequestParam(value = "durationSeconds", defaultValue = "8") int durationSeconds,
            @RequestParam(value = "videoUrl", required = false) String videoUrl,
            @RequestParam(value = "audioUrl", required = false) String audioUrl,
            @RequestParam(value = "generateAudio", defaultValue = "true") boolean generateAudio) {

        String prompt = promptParam == null ? "" : promptParam.trim();
        if (prompt.isEmpty()) {
            return ResponseEntity.badRequest()
                    .body(Map.of("success", false, "message", "提示词不能为空"));
        }

        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
        String outputPath = OUTPUT_DIR + "/video_" + timestamp + ".mp4";

        GenerationTask task = taskService.createTask(1);
        final int dur = durationSeconds;
        final boolean isSeedance = model.startsWith("doubao-seedance");

        final SeedanceVideoService.Request seedReq;
        if (isSeedance) {
            seedReq = new SeedanceVideoService.Request();
            seedReq.prompt = prompt;
            seedReq.aspectRatio = aspectRatio;
            seedReq.durationSeconds = dur;
            seedReq.videoUrl = videoUrl;
            seedReq.audioUrl = audioUrl;
            seedReq.generateAudio = generateAudio;
            seedReq.imageUrls = filesToDataUris(images);
        } else {
            seedReq = null;
        }

        taskService.submit(task, () -> {
            try {
                String savedPath;
                if (isSeedance) {
                    savedPath = seedanceVideoService.generateVideo(seedReq, outputPath);
                } else {
                    savedPath = videoGenerationService.generateVideo(prompt, aspectRatio, dur, outputPath);
                }
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

    private List<String> filesToDataUris(List<MultipartFile> files) {
        if (files == null || files.isEmpty()) return null;
        List<String> uris = new ArrayList<>();
        for (MultipartFile f : files) {
            if (f == null || f.isEmpty()) continue;
            try {
                String contentType = f.getContentType();
                if (contentType == null || contentType.isBlank()) contentType = "image/png";
                String b64 = Base64.getEncoder().encodeToString(f.getBytes());
                uris.add("data:" + contentType + ";base64," + b64);
            } catch (Exception e) {
                log.warn("读取图片失败: {}", f.getOriginalFilename(), e);
            }
        }
        return uris.isEmpty() ? null : uris;
    }

    @GetMapping("/file")
    public ResponseEntity<FileSystemResource> file(@RequestParam String filename) {
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
