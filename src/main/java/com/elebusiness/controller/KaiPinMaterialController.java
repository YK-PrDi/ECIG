package com.elebusiness.controller;

import com.elebusiness.config.AppProperties;
import com.elebusiness.model.GenerationTask;
import com.elebusiness.model.entity.KaiPinMaterial;
import com.elebusiness.service.CosService;
import com.elebusiness.service.HistoryService;
import com.elebusiness.service.ImageGenerationService;
import com.elebusiness.service.KaiPinMaterialService;
import com.elebusiness.service.auth.CurrentUserService;
import com.elebusiness.service.billing.BillingService;
import com.elebusiness.service.billing.GenerationPricingService;
import com.elebusiness.service.workspace.UserStorageService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;

@RestController
public class KaiPinMaterialController {

    private static final Logger log = LoggerFactory.getLogger(KaiPinMaterialController.class);

    private final KaiPinMaterialService materialService;
    private final ImageGenerationService imageGenerationService;
    private final AppProperties appProperties;
    private final com.elebusiness.service.TaskService taskService;
    private final HistoryService historyService;
    private final CosService cosService;
    private final CurrentUserService currentUserService;
    private final UserStorageService userStorageService;
    private final BillingService billingService;
    private final GenerationPricingService pricingService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public KaiPinMaterialController(KaiPinMaterialService materialService,
                                    ImageGenerationService imageGenerationService,
                                    AppProperties appProperties,
                                    com.elebusiness.service.TaskService taskService,
                                    HistoryService historyService,
                                    CosService cosService,
                                    CurrentUserService currentUserService,
                                    UserStorageService userStorageService,
                                    BillingService billingService,
                                    GenerationPricingService pricingService) {
        this.materialService = materialService;
        this.imageGenerationService = imageGenerationService;
        this.appProperties = appProperties;
        this.taskService = taskService;
        this.historyService = historyService;
        this.cosService = cosService;
        this.currentUserService = currentUserService;
        this.userStorageService = userStorageService;
        this.billingService = billingService;
        this.pricingService = pricingService;
    }

    @PostMapping("/api/kaipin_material_prompt")
    public ResponseEntity<Map<String, Object>> generateMaterialPrompt(
            @RequestParam("image") MultipartFile image,
            @RequestParam(value = "note", defaultValue = "") String note,
            @RequestParam(value = "agentId", defaultValue = "gemini") String agentId) {
        if (image == null || image.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "请先上传素材图片"));
        }
        File tmp = null;
        try {
            tmp = File.createTempFile("kp_material_", suffix(image.getOriginalFilename()));
            image.transferTo(tmp);
            String analysisPrompt = """
                    请把这张图片分析成一段可保存到开品素材库的“创意参考提示词”。
                    用户补充：%s
                    请从造型亮点、结构创新、CMF 色彩材质、可借鉴创意点、融合禁忌五个角度分析。
                    输出为结构化 JSON 数组，每个 value 要能直接参与后续 Image2Image 生图。
                    """.formatted(note == null || note.isBlank() ? "无" : note);
            List<Map<String, String>> fields = imageGenerationService.analyzeProductText(analysisPrompt, tmp, agentId);
            String prompt = buildMaterialPrompt(fields, note);
            return ResponseEntity.ok(Map.of("fields", fields, "prompt", prompt));
        } catch (Exception e) {
            log.error("kaipin_material_prompt 失败: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        } finally {
            if (tmp != null && tmp.exists()) tmp.delete();
        }
    }

    @PostMapping("/api/kaipin_materials")
    public ResponseEntity<Map<String, Object>> saveMaterial(
            @RequestParam("image") MultipartFile image,
            @RequestParam(value = "title", defaultValue = "") String title,
            @RequestParam(value = "prompt", defaultValue = "") String prompt,
            HttpSession httpSession) {
        long userId = currentUserService.requireUserId(httpSession);
        try {
            KaiPinMaterial item = materialService.save(userId, image, title, prompt);
            return ResponseEntity.ok(Map.of("success", true, "item", materialService.toDto(item)));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "error", e.getMessage()));
        }
    }

    @GetMapping("/api/kaipin_materials")
    public ResponseEntity<Map<String, Object>> listMaterials(
            @RequestParam(value = "limit", defaultValue = "120") int limit,
            HttpSession httpSession) {
        long userId = currentUserService.requireUserId(httpSession);
        List<Map<String, Object>> items = materialService.list(userId, limit).stream()
                .map(materialService::toDto)
                .toList();
        return ResponseEntity.ok(Map.of("items", items));
    }

    @DeleteMapping("/api/kaipin_materials/{id}")
    public ResponseEntity<Map<String, Object>> deleteMaterial(@PathVariable Long id, HttpSession httpSession) {
        long userId = currentUserService.requireUserId(httpSession);
        materialService.delete(userId, id);
        return ResponseEntity.ok(Map.of("success", true));
    }

    @PostMapping("/api/kaipin_material_generate")
    public ResponseEntity<Map<String, Object>> generateWithMaterials(
            @RequestParam("whiteImage") MultipartFile whiteImage,
            @RequestParam(value = "materialIds", defaultValue = "") String materialIds,
            @RequestParam(value = "basePrompt", defaultValue = "") String basePrompt,
            @RequestParam(value = "agentId", defaultValue = "gpt-image") String agentId,
            @RequestParam(value = "sessionId", defaultValue = "default") String sessionId,
            @RequestParam(value = "materialPromptOverrides", required = false) String materialPromptOverrides,
            @RequestParam(value = "configJson", required = false) String configJson,
            HttpSession httpSession) {
        long userId = currentUserService.requireUserId(httpSession);
        if (whiteImage == null || whiteImage.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "请先上传白底产品图"));
        }
        List<Long> ids = parseIds(materialIds);
        if (ids.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "请至少选择一个素材库参考"));
        }
        String generationAgentId = "gemini".equalsIgnoreCase(agentId) ? "gpt-image" : agentId;
        if (!Objects.equals(generationAgentId, agentId)) {
            log.info("[kaipin_material_generate] Gemini only analyzes prompts; image generation switched to {}", generationAgentId);
        }
        File whiteTmp = null;
        try {
            whiteTmp = File.createTempFile("kp_white_", suffix(whiteImage.getOriginalFilename()));
            whiteImage.transferTo(whiteTmp);

            Map<Long, KaiPinMaterial> byId = new LinkedHashMap<>();
            for (KaiPinMaterial item : materialService.findAllByIds(userId, ids)) {
                byId.put(item.getId(), item);
            }
            List<KaiPinMaterial> selected = ids.stream()
                    .map(byId::get)
                    .filter(Objects::nonNull)
                    .filter(item -> item.getImagePath() != null && new File(item.getImagePath()).isFile())
                    .toList();
            if (selected.isEmpty()) {
                if (whiteTmp.exists()) whiteTmp.delete();
                return ResponseEntity.badRequest().body(Map.of("error", "选中的素材图片不存在，请刷新素材库后重试"));
            }

            GenerationTask task = taskService.createTask(userId, selected.size());
            int estimatedPoints = pricingService.estimateImageGeneration("kaipin", generationAgentId, selected.size());
            task.setUsageLogId(billingService.recordGenerationStarted(userId, task.getId(), "kaipin", generationAgentId, estimatedPoints).getId());
            String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
            String outputDir = userStorageService.ensureDirectory(
                    userStorageService.tempOutputRoot(userId).resolve("开品素材库融合").resolve(timestamp)
            ).toFile().getAbsolutePath();

            final File whiteFinal = whiteTmp;
            final List<KaiPinMaterial> selectedFinal = new ArrayList<>(selected);
            final String basePromptFinal = basePrompt == null || basePrompt.isBlank()
                    ? "基于白底产品图保持产品主体一致，并结合素材库参考图的创意提示词生成新的开品概念图。"
                    : basePrompt;
            final Map<Long, String> materialPromptOverridesFinal = parseMaterialPromptOverrides(materialPromptOverrides);
            final String aspect = resolveAutoAspect("auto", List.of(whiteFinal));

            taskService.submit(task, () -> {
                ExecutorService executor = imageGenerationService.getExecutor();
                List<CompletableFuture<String>> futures = new ArrayList<>();
                int index = 1;
                for (KaiPinMaterial material : selectedFinal) {
                    final int n = index++;
                    final File materialImage = new File(material.getImagePath());
                    final String prompt = buildMaterialFusionPrompt(basePromptFinal, material, n, selectedFinal.size(), materialPromptOverridesFinal);
                    final String outputPath = new File(outputDir, n + ".jpg").getAbsolutePath();
                    futures.add(CompletableFuture.supplyAsync(() -> {
                        if (task.isCancelled()) return "失败: 已取消";
                        boolean ok = imageGenerationService.generateImageMulti(
                                userId,
                                prompt,
                                List.of(whiteFinal.getAbsolutePath(), materialImage.getAbsolutePath()),
                                null,
                                outputPath,
                                generationAgentId,
                                aspect);
                        return ok ? outputPath : "失败: 生成未返回图片";
                    }, executor));
                }

                int n = 1;
                for (CompletableFuture<String> f : futures) {
                    String r;
                    try {
                        r = f.get(5, TimeUnit.MINUTES);
                    } catch (TimeoutException te) {
                        f.cancel(true);
                        r = "失败: 单张图超时(>5 分钟)";
                    } catch (Exception e) {
                        r = "失败: " + e.getMessage();
                    }
                    String name = n++ + ".jpg";
                    if (r.startsWith("失败")) {
                        task.addResult(ControllerHelpers.result(name, "error", r, null));
                    } else {
                        String outputRef = r;
                        if (cosService.isEnabled()) {
                            try {
                                outputRef = cosService.upload(new File(r), name);
                            } catch (Exception ce) {
                                log.warn("COS 上传失败，降级本地路径: {}", ce.getMessage());
                            }
                        }
                        task.addResult(ControllerHelpers.result(name, "success", null, outputRef, r));
                    }
                    task.incrementProgress();
                }

                task.addResult(ControllerHelpers.result("__OUTPUT_DIR__", "info", null, outputDir));
                task.addResult(ControllerHelpers.result("__AI_THOUGHT__0", "info",
                        "【开品素材库融合】每个选中素材执行 1 次融合：白底产品图保证主体一致，素材图和素材提示词只提供创新参考。", null));

                try {
                    List<File> refs = new ArrayList<>();
                    refs.add(whiteFinal);
                    for (KaiPinMaterial material : selectedFinal) refs.add(new File(material.getImagePath()));
                    HistoryService.ArchiveResult archive = historyService.archiveRefFiles(userId, refs);
                    historyService.recordGeneration(userId, sessionId, "kaipin",
                            basePromptFinal, generationAgentId, archive.refPaths, outputDir, configJson);
                } catch (Exception e) {
                    log.warn("开品素材库融合写历史失败（不影响生图）: {}", e.getMessage());
                }

                if (whiteFinal.exists()) whiteFinal.delete();
            });

            return ResponseEntity.ok(Map.of(
                    "taskId", task.getId(),
                    "materialCount", selected.size(),
                    "output_dir", outputDir
            ));
        } catch (Exception e) {
            log.error("kaipin_material_generate 失败: {}", e.getMessage(), e);
            if (whiteTmp != null && whiteTmp.exists()) whiteTmp.delete();
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    private String buildMaterialPrompt(List<Map<String, String>> fields, String note) {
        List<String> lines = new ArrayList<>();
        lines.add("【开品素材库创意参考】");
        if (note != null && !note.isBlank()) lines.add("用户补充：" + note.trim());
        for (Map<String, String> field : fields) {
            String key = field.getOrDefault("key", "").trim();
            String value = field.getOrDefault("value", "").trim();
            if (!key.isBlank() && !value.isBlank()) lines.add(key + "：" + value);
        }
        lines.add("使用方式：后续生成时只借鉴该图的创意、结构、材质、色彩或氛围，不照抄品牌 logo，不改变白底产品图的主体识别特征。");
        return String.join("\n", lines);
    }

    private String buildMaterialFusionPrompt(String basePrompt, KaiPinMaterial material, int index, int total,
                                             Map<Long, String> promptOverrides) {
        String materialPrompt = "";
        if (promptOverrides != null && material.getId() != null) {
            materialPrompt = promptOverrides.getOrDefault(material.getId(), "");
        }
        if (materialPrompt == null || materialPrompt.isBlank()) {
            materialPrompt = material.getPrompt();
        }
        return String.join("\n",
                "【开品模式·素材库融合生成】",
                "请以第 1 张白底产品图为唯一产品主体，严格保持它的外形轮廓、比例、结构细节、颜色、材质和识别特征。",
                "第 2 张素材库图片只作为创新参考：提取它的造型语言、结构亮点、CMF、氛围或使用场景创意，不要直接复制品牌标识，不要把两张图简单拼贴。",
                "【白底产品分析与用户编辑提示】",
                basePrompt,
                "【素材库参考 " + index + "/" + total + "】" + material.getTitle(),
                materialPrompt,
                "【输出要求】生成一个新的单体产品概念图，主体来自白底图，创新点来自素材库图和素材提示词；画面真实、可制造、结构清晰、商业摄影质感。",
                "【禁止】多个无关产品拼贴、产品主体变形、品牌 logo、水印、文字海报、低清晰度、不可制造结构。"
        );
    }

    private List<Long> parseIds(String raw) {
        if (raw == null || raw.isBlank()) return List.of();
        List<Long> ids = new ArrayList<>();
        for (String part : raw.split(",")) {
            try {
                long id = Long.parseLong(part.trim());
                if (id > 0) ids.add(id);
            } catch (Exception ignored) {
            }
        }
        return ids;
    }

    private Map<Long, String> parseMaterialPromptOverrides(String raw) {
        if (raw == null || raw.isBlank()) return Map.of();
        try {
            Map<String, String> parsed = objectMapper.readValue(raw, new TypeReference<Map<String, String>>() {});
            Map<Long, String> result = new HashMap<>();
            for (Map.Entry<String, String> entry : parsed.entrySet()) {
                if (entry.getValue() == null || entry.getValue().isBlank()) continue;
                try {
                    long id = Long.parseLong(entry.getKey());
                    if (id > 0) result.put(id, entry.getValue().trim());
                } catch (NumberFormatException ignored) {
                }
            }
            return result;
        } catch (Exception e) {
            log.warn("materialPromptOverrides 解析失败，继续使用数据库原始提示词: {}", e.getMessage());
            return Map.of();
        }
    }

    private String resolveAutoAspect(String aspect, List<File> refs) {
        if (aspect != null && !"auto".equalsIgnoreCase(aspect)) return aspect;
        if (refs != null && !refs.isEmpty()) {
            try {
                BufferedImage img = ImageIO.read(refs.get(0));
                if (img != null && img.getWidth() > 0 && img.getHeight() > 0) {
                    double ratio = img.getWidth() * 1.0 / img.getHeight();
                    if (ratio > 1.25) return "16:9";
                    if (ratio < 0.8) return "9:16";
                }
            } catch (Exception ignored) {
            }
        }
        return "1:1";
    }

    private String suffix(String original) {
        String lower = original == null ? "" : original.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".png")) return ".png";
        if (lower.endsWith(".webp")) return ".webp";
        if (lower.endsWith(".jpeg")) return ".jpeg";
        return ".jpg";
    }
}
