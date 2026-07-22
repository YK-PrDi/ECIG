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
import com.elebusiness.service.video.OpenAiCompatibleVideoService;
import com.elebusiness.service.video.VideoConnectivityTestService;
import com.elebusiness.service.video.VideoModelCatalog;
import com.elebusiness.service.video.VideoOutputNormalizer;
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
    private final OpenAiCompatibleVideoService compatibleVideoService;
    private final VideoModelCatalog videoModelCatalog;
    private final VideoOutputNormalizer videoOutputNormalizer;
    private final VideoConnectivityTestService connectivityTestService;

    public VideoController(VideoGenerationService videoGenerationService,
                           SeedanceVideoService seedanceVideoService,
                           TaskService taskService,
                           AppProperties appProperties,
                           HistoryService historyService,
                           CurrentUserService currentUserService,
                           UserStorageService userStorageService,
                           BillingService billingService,
                           GenerationPricingService pricingService,
                           OpenAiCompatibleVideoService compatibleVideoService,
                           VideoModelCatalog videoModelCatalog,
                           VideoOutputNormalizer videoOutputNormalizer,
                           VideoConnectivityTestService connectivityTestService) {
        this.videoGenerationService = videoGenerationService;
        this.seedanceVideoService = seedanceVideoService;
        this.taskService = taskService;
        this.appProperties = appProperties;
        this.historyService = historyService;
        this.currentUserService = currentUserService;
        this.userStorageService = userStorageService;
        this.billingService = billingService;
        this.pricingService = pricingService;
        this.compatibleVideoService = compatibleVideoService;
        this.videoModelCatalog = videoModelCatalog;
        this.videoOutputNormalizer = videoOutputNormalizer;
        this.connectivityTestService = connectivityTestService;
    }

    @GetMapping("/models")
    public ResponseEntity<List<Map<String, Object>>> models() {
        List<Map<String, Object>> models = videoModelCatalog.models().stream()
                .map(model -> Map.<String, Object>of(
                        "id", model.id(),
                        "name", model.name(),
                        "providerId", model.providerId(),
                        "provider", model.providerLabel(),
                        "level", model.level(),
                        "inputMode", model.inputMode().name().toLowerCase(java.util.Locale.ROOT),
                        "configured", model.configured(),
                        "minDurationSeconds", model.minDurationSeconds(),
                        "maxDurationSeconds", model.maxDurationSeconds()))
                .toList();
        return ResponseEntity.ok(models);
    }

    @GetMapping("/connectivity")
    public ResponseEntity<Map<String, Object>> connectivity() {
        log.info("[connectivity test] 开始测试视频提供商连通性");
        long startTime = System.currentTimeMillis();

        List<VideoConnectivityTestService.ConnectivityResult> results = connectivityTestService.testAll();
        long totalTime = System.currentTimeMillis() - startTime;

        long okCount = results.stream().filter(r -> "OK".equals(r.status())).count();
        long failedCount = results.stream().filter(r -> "FAILED".equals(r.status()) || "AUTH_FAILED".equals(r.status())).count();
        long skippedCount = results.stream().filter(r -> "SKIPPED".equals(r.status())).count();

        log.info("[connectivity test] 完成 - 总耗时{}ms, OK:{}, FAILED:{}, SKIPPED:{}",
                totalTime, okCount, failedCount, skippedCount);

        return ResponseEntity.ok(Map.of(
                "success", true,
                "totalTimeMs", totalTime,
                "summary", Map.of(
                        "ok", okCount,
                        "failed", failedCount,
                        "skipped", skippedCount
                ),
                "results", results
        ));
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
        var currentUser = currentUserService.require(httpSession);
        long userId = currentUser.id();

        final VideoModelCatalog.ModelView selectedModel;
        try {
            selectedModel = videoModelCatalog.require(model);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest()
                    .body(Map.of("success", false, "message", e.getMessage()));
        }
        if (!selectedModel.configured()) {
            return ResponseEntity.badRequest()
                    .body(Map.of("success", false, "message", missingCredentialMessage(selectedModel)));
        }

        // 校验时长是否在模型支持范围内
        if (durationSeconds < selectedModel.minDurationSeconds() || durationSeconds > selectedModel.maxDurationSeconds()) {
            return ResponseEntity.badRequest()
                    .body(Map.of("success", false, "message",
                            selectedModel.name() + " 支持的时长范围为 " + selectedModel.minDurationSeconds() +
                                    "-" + selectedModel.maxDurationSeconds() + " 秒，当前设置 " + durationSeconds + " 秒超出范围"));
        }

        int imageCount = images == null
                ? 0
                : (int) images.stream().filter(image -> image != null && !image.isEmpty()).count();
        if (selectedModel.inputMode() == VideoModelCatalog.InputMode.TEXT_ONLY && imageCount > 0) {
            return ResponseEntity.badRequest()
                    .body(Map.of("success", false, "message", "Grok 文生视频不支持参考图，请移除图片后重试"));
        }
        if (selectedModel.inputMode() == VideoModelCatalog.InputMode.IMAGE_ONLY && imageCount != 1) {
            return ResponseEntity.badRequest()
                    .body(Map.of("success", false, "message", "Grok 图生视频必须上传且只能上传一张参考图"));
        }

        String prompt = promptParam == null ? "" : promptParam.trim();
        if (prompt.isEmpty()) {
            return ResponseEntity.badRequest()
                    .body(Map.of("success", false, "message", "提示词不能为空"));
        }
        log.info("[video generate] userId={}, model={}, provider={}, aspectRatio={}, durationSeconds={}, images={}",
                userId, selectedModel.id(), selectedModel.providerLabel(), aspectRatio, durationSeconds,
                images == null ? 0 : images.size());

        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
        File videoTempDir = userStorageService.ensureDirectory(
                userStorageService.tempOutputRoot(userId).resolve("视频")).toFile();
        GenerationTask task = taskService.createTask(userId, 1, currentUser.role());
        String outputPath = new File(
                videoTempDir,
                "video_" + timestamp + "_" + task.getId() + ".mp4"
        ).getAbsolutePath();

        int estimatedPoints = pricingService.estimateVideoGeneration(model, durationSeconds);
        task.setUsageLogId(billingService.recordGenerationStarted(userId, task.getId(), "video", model, estimatedPoints).getId());
        final int dur = durationSeconds;
        final List<String> imageDataUris = filesToDataUris(images);

        final SeedanceVideoService.Request seedReq;
        if (selectedModel.provider() == VideoModelCatalog.Provider.SEEDANCE) {
            seedReq = new SeedanceVideoService.Request();
            seedReq.prompt = prompt;
            seedReq.aspectRatio = aspectRatio;
            seedReq.durationSeconds = dur;
            seedReq.videoUrl = videoUrl;
            seedReq.audioUrl = audioUrl;
            seedReq.generateAudio = generateAudio;
            seedReq.imageUrls = imageDataUris;
        } else {
            seedReq = null;
        }

        taskService.submit(task, () -> {
            try {
                String savedPath = switch (selectedModel.provider()) {
                    case VEO -> videoGenerationService.generateVideo(prompt, aspectRatio, dur, outputPath);
                    case SEEDANCE -> seedanceVideoService.generateVideo(seedReq, outputPath, task::isCancelled);
                    case SUIXIANG_GROK, SUIXIANG_JIMENG -> compatibleVideoService.generateVideo(
                            selectedModel, prompt, imageDataUris, aspectRatio, dur, outputPath);
                };
                savedPath = videoOutputNormalizer.normalize(savedPath, aspectRatio);
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
        if (files == null || files.isEmpty()) return List.of();
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
        return List.copyOf(uris);
    }

    private String missingCredentialMessage(VideoModelCatalog.ModelView model) {
        String variable = switch (model.provider()) {
            case VEO -> "GEMINI_API_KEY";
            case SEEDANCE -> "VOLCENGINE_API_KEY";
            case SUIXIANG_GROK -> "SUIXIANG_GROK_VIDEO_API_KEY";
            case SUIXIANG_JIMENG -> "SUIXIANG_JIMENG_VIDEO_API_KEY";
        };
        return model.providerLabel() + " API Key 未配置，请填写 " + variable;
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
