package com.elebusiness.controller;

import com.elebusiness.config.AppProperties;
import com.elebusiness.model.GenerationTask;
import com.elebusiness.service.HistoryService;
import com.elebusiness.service.SeedanceVideoService;
import com.elebusiness.service.TaskService;
import com.elebusiness.service.VideoGenerationService;
import com.elebusiness.service.auth.CurrentUserService;
import com.elebusiness.service.billing.BillingService;
import com.elebusiness.service.billing.GenerationPricingService;
import com.elebusiness.service.workspace.UserStorageService;
import jakarta.servlet.http.HttpSession;
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

    private final VideoGenerationService videoGenerationService;
    private final SeedanceVideoService seedanceVideoService;
    private final TaskService taskService;
    private final AppProperties appProperties;
    private final HistoryService historyService;
    private final CurrentUserService currentUserService;
    private final UserStorageService userStorageService;
    private final BillingService billingService;
    private final GenerationPricingService pricingService;

    public VideoController(VideoGenerationService videoGenerationService,
                           SeedanceVideoService seedanceVideoService,
                           TaskService taskService,
                           AppProperties appProperties,
                           HistoryService historyService,
                           CurrentUserService currentUserService,
                           UserStorageService userStorageService,
                           BillingService billingService,
                           GenerationPricingService pricingService) {
        this.videoGenerationService = videoGenerationService;
        this.seedanceVideoService = seedanceVideoService;
        this.taskService = taskService;
        this.appProperties = appProperties;
        this.historyService = historyService;
        this.currentUserService = currentUserService;
        this.userStorageService = userStorageService;
        this.billingService = billingService;
        this.pricingService = pricingService;
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
            @RequestParam(value = "generateAudio", defaultValue = "true") boolean generateAudio,
            @RequestParam(value = "sessionId", defaultValue = "default") String sessionId,
            HttpSession httpSession) {
        long userId = currentUserService.requireUserId(httpSession);

        String prompt = promptParam == null ? "" : promptParam.trim();
        if (prompt.isEmpty()) {
            return ResponseEntity.badRequest()
                    .body(Map.of("success", false, "message", "提示词不能为空"));
        }

        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
        File videoTempDir = userStorageService.ensureDirectory(
                userStorageService.tempOutputRoot(userId).resolve("视频")).toFile();
        String outputPath = new File(videoTempDir, "video_" + timestamp + ".mp4").getAbsolutePath();

        GenerationTask task = taskService.createTask(userId, 1);
        int estimatedPoints = pricingService.estimateVideoGeneration(model, durationSeconds);
        task.setUsageLogId(billingService.recordGenerationStarted(userId, task.getId(), "video", model, estimatedPoints).getId());
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
                // Phase 2：归档参考图（如果有）+ 写历史
                try {
                    java.util.List<java.io.File> refs = new java.util.ArrayList<>();
                    if (images != null) {
                        for (MultipartFile mf : images) {
                            if (mf == null || mf.isEmpty()) continue;
                            java.io.File t = java.io.File.createTempFile("video_ref_", ".jpg");
                            mf.transferTo(t);
                            refs.add(t);
                        }
                    }
                    HistoryService.ArchiveResult archive = historyService.archiveRefFiles(userId, refs);
                    String configJson = "{\"model\":\"" + model + "\",\"aspectRatio\":\"" + aspectRatio
                            + "\",\"durationSeconds\":" + dur + "}";
                    historyService.recordGeneration(userId, sessionId, "video", prompt, model,
                            archive.refPaths, savedPath, configJson);
                    for (java.io.File r : refs) r.delete();
                } catch (Exception he) {
                    log.warn("video 写历史失败: {}", he.getMessage());
                }
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
    public ResponseEntity<FileSystemResource> file(@RequestParam String filename, HttpSession httpSession) {
        long userId = currentUserService.requireUserId(httpSession);
        if (filename.contains("..") || filename.contains("/") || filename.contains("\\")) {
            return ResponseEntity.badRequest().build();
        }
        File f = userStorageService.tempOutputRoot(userId).resolve("视频").resolve(filename).toFile();
        if (!f.exists()) {
            f = userStorageService.galleryRoot(userId).resolve("视频").resolve(filename).toFile();
        }
        if (!f.exists()) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + filename + "\"")
                .contentType(MediaType.parseMediaType("video/mp4"))
                .body(new FileSystemResource(f));
    }
}
