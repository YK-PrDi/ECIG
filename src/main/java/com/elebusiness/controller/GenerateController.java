package com.elebusiness.controller;

import com.elebusiness.config.AppProperties;
import com.elebusiness.model.DingTalkRecord;
import com.elebusiness.model.GenerateRequest;
import com.elebusiness.model.GenerationTask;
import com.elebusiness.model.ProductInfo;
import com.elebusiness.service.CivitaiLoraService;
import com.elebusiness.service.DingTalkService;
import com.elebusiness.service.HistoryService;
import com.elebusiness.service.ImageGenerationService;
import com.elebusiness.service.TaskService;
import com.elebusiness.service.agent.GptImageAgent;
import com.elebusiness.service.CosService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.apache.poi.xssf.usermodel.XSSFPictureData;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.multipart.MultipartHttpServletRequest;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;

/**
 * 图像生成核心接口：标准模式 / 自定义模式 / 局部重绘 / GPT-Image 直连。
 * 从 ApiController 拆出（A.1 重构），业务逻辑零变动。
 */
@RestController
public class GenerateController {

    private static final Logger log = LoggerFactory.getLogger(GenerateController.class);

    private final DingTalkService dingTalkService;
    private final ImageGenerationService imageGenerationService;
    private final AppProperties appProperties;
    private final TaskService taskService;
    private final GptImageAgent gptImageAgent;
    private final HistoryService historyService;
    private final CosService cosService;
    private final CivitaiLoraService civitaiLoraService;

    public GenerateController(DingTalkService dingTalkService,
                              ImageGenerationService imageGenerationService,
                              AppProperties appProperties, TaskService taskService,
                              GptImageAgent gptImageAgent,
                              HistoryService historyService, CosService cosService,
                              CivitaiLoraService civitaiLoraService) {
        this.dingTalkService = dingTalkService;
        this.imageGenerationService = imageGenerationService;
        this.appProperties = appProperties;
        this.taskService = taskService;
        this.gptImageAgent = gptImageAgent;
        this.historyService = historyService;
        this.cosService = cosService;
        this.civitaiLoraService = civitaiLoraService;
    }

    /** 异步提交生成任务，立即返回 taskId，前端轮询 /api/task/{taskId} 获取进度 */
    @PostMapping("/api/generate")
    public ResponseEntity<Map<String, Object>> generate(@RequestBody GenerateRequest request) {
        List<String> selectedIds = request.getProductIds();
        if (selectedIds == null || selectedIds.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "未选择产品"));
        }

        String agentId = request.getAgentId();
        String userPrompt = request.getPrompt();
        String sessionId = request.getSessionId() == null ? "default" : request.getSessionId();
        GenerationTask task = taskService.createTask(selectedIds.size());

        taskService.submit(task, () -> {
            for (String recordId : selectedIds) {
                if (task.isCancelled()) break;

                DingTalkRecord record;
                try {
                    record = dingTalkService.getRecordById(recordId);
                } catch (Exception e) {
                    log.error("获取记录 {} 失败: {}", recordId, e.getMessage());
                    task.addResult(ControllerHelpers.result(recordId, "error", "获取记录失败: " + e.getMessage(), null));
                    task.incrementProgress();
                    continue;
                }

                ProductInfo info = dingTalkService.parseProductInfo(record);
                String productName = info.getName();
                String category = info.getCategory();

                task.setCurrentProduct(productName);

                if (category == null || category.isBlank()) {
                    task.addResult(ControllerHelpers.result(productName, "error", "未找到类别", null));
                    task.incrementProgress();
                    continue;
                }

                List<Map<String, Object>> attachments = record.getFieldAsImageList("图片和附件");
                String whiteBgUrl = null;
                if (attachments != null) {
                    for (Map<String, Object> img : attachments) {
                        if ("image".equals(img.get("type")) && img.get("url") != null) {
                            whiteBgUrl = img.get("url").toString();
                            break;
                        }
                    }
                }

                if (whiteBgUrl == null) {
                    task.addResult(ControllerHelpers.result(productName, "error", "没有白底图", null));
                    task.incrementProgress();
                    continue;
                }

                File categoryDir = new File(appProperties.getPaths().getReferenceDir(), category);
                if (!categoryDir.exists()) {
                    task.addResult(ControllerHelpers.result(productName, "error", "未找到类别 " + category + " 的参考图", null));
                    task.incrementProgress();
                    continue;
                }

                File[] refFolders = categoryDir.listFiles(
                        f -> f.isDirectory() && f.getName().startsWith("参考图"));
                if (refFolders == null || refFolders.length == 0) {
                    task.addResult(ControllerHelpers.result(productName, "error", "未找到参考图文件夹", null));
                    task.incrementProgress();
                    continue;
                }

                File refPath = refFolders[new Random().nextInt(refFolders.length)];
                String cleanName = productName.replaceAll("（.+?）", "");
                // Phase 1：先落到临时归档，2 小时后自动清理；用户在前端点 💾 才 copy 到永久 outputDir
                String categoryOutputDir = new File(appProperties.getPaths().getTempOutputDir(), category).getAbsolutePath();
                int nextNum = imageGenerationService.getNextOutputNumber(categoryOutputDir, cleanName);
                String outputFolder = new File(categoryOutputDir, cleanName + "_" + nextNum).getAbsolutePath();
                new File(outputFolder).mkdirs();

                log.info("开始生成: {} -> {} [agent={}]", productName, outputFolder, agentId);

                int generatedCount = 0;

                if (!info.getSku().isEmpty()) {
                    int before = ControllerHelpers.countImages(new File(outputFolder));
                    imageGenerationService.generateSkuImages(
                            whiteBgUrl, refPath.getAbsolutePath(), outputFolder, info.getSku(), agentId, userPrompt);
                    generatedCount += ControllerHelpers.countImages(new File(outputFolder)) - before;
                }

                List<String> mainImgPaths = imageGenerationService.generateMainImages(
                        whiteBgUrl, refPath.getAbsolutePath(), outputFolder, info.getMain(), agentId, userPrompt);
                generatedCount += mainImgPaths.size();

                for (String mainImgPath : mainImgPaths) {
                    imageGenerationService.generateDetailImages(
                            mainImgPath, outputFolder, whiteBgUrl, refPath.getAbsolutePath(), agentId, userPrompt);
                }

                if (generatedCount == 0) {
                    task.addResult(ControllerHelpers.result(productName, "error", "所有图片生成失败，请检查 API Key 或网络", outputFolder));
                } else {
                    task.addResult(ControllerHelpers.result(productName, "success", null, outputFolder));
                    // Phase 2：标准模式无用户上传 ref（参考图来自 categoryDir，不归档）；只记元信息
                    try {
                        String configJson = "{\"productId\":\"" + recordId + "\",\"productName\":\""
                                + productName.replace("\"", "\\\"") + "\",\"category\":\""
                                + (category == null ? "" : category.replace("\"", "\\\"")) + "\"}";
                        historyService.recordGeneration(sessionId, "standard",
                                userPrompt == null ? "" : userPrompt,
                                agentId, java.util.Collections.emptyList(),
                                outputFolder, configJson);
                    } catch (Exception e) {
                        log.warn("standard 模式写历史失败: {}", e.getMessage());
                    }
                }
                task.incrementProgress();
            }

            // 标准模式的思考信息：展示用户 prompt + 模型
            task.addResult(ControllerHelpers.result("__AI_THOUGHT__0", "info",
                "【提示词直送（未经 LLM 处理）】\n模型: " + agentId
                + "\n\n【用户附加提示词】\n" + (userPrompt == null || userPrompt.isBlank() ? "（无）" : userPrompt)
                + "\n\n注：标准模式下每张图的最终 prompt 由系统根据产品类别、主图/SKU/详情场景模板拼装，详见后台日志。",
                null));
        });

        return ResponseEntity.ok(Map.of("taskId", task.getId()));
    }

    /** 开品模式第一步：上传产品图 + 分析提示词，返回可编辑结构化卡片字段。 */
    @PostMapping("/api/product_analyze")
    public ResponseEntity<Map<String, Object>> productAnalyze(
            @RequestParam(value = "image", required = false) MultipartFile image,
            @RequestParam(value = "prompt", defaultValue = "") String prompt,
            @RequestParam(value = "promptBase64", required = false) String promptBase64,
            @RequestParam(value = "agentId", defaultValue = "gemini") String agentId) {
        prompt = decodePrompt(prompt, promptBase64);
        if (prompt == null || prompt.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "请输入分析提示词"));
        }

        File imageTmp = null;
        try {
            if (image != null && !image.isEmpty()) {
                String suffix = ".jpg";
                String original = image.getOriginalFilename();
                if (original != null) {
                    String lower = original.toLowerCase(Locale.ROOT);
                    if (lower.endsWith(".png")) suffix = ".png";
                    else if (lower.endsWith(".webp")) suffix = ".webp";
                    else if (lower.endsWith(".gif")) suffix = ".gif";
                }
                imageTmp = File.createTempFile("product_analyze_", suffix);
                image.transferTo(imageTmp);
            }
            List<Map<String, String>> fields = imageGenerationService.analyzeProductText(prompt, imageTmp, agentId);
            return ResponseEntity.ok(Map.of("fields", fields));
        } catch (Exception e) {
            log.error("product_analyze 失败: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        } finally {
            if (imageTmp != null && imageTmp.exists()) imageTmp.delete();
        }
    }

    /** 自定义模式：上传白底图 + 用户要求，用 Gemini 扩写为多段可编辑生图提示词。 */
    @PostMapping("/api/custom_analyze")
    public ResponseEntity<Map<String, Object>> customAnalyze(
            @RequestParam(value = "images", required = false) List<MultipartFile> images,
            @RequestParam(value = "prompt", defaultValue = "") String prompt,
            @RequestParam(value = "count", defaultValue = "1") int count,
            @RequestParam(value = "withText", defaultValue = "true") boolean withText) {
        log.info("[custom_analyze 入参] images={}, count={}, withText={}, promptLen={}",
                images == null ? 0 : images.size(), count, withText, prompt == null ? 0 : prompt.length());
        if (images == null || images.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "请先上传白底产品图"));
        }

        List<File> tempFiles = new ArrayList<>();
        try {
            for (MultipartFile image : images) {
                if (image == null || image.isEmpty()) continue;
                File tmp = File.createTempFile("custom_analyze_", getSuffix(image.getOriginalFilename()));
                image.transferTo(tmp);
                tempFiles.add(tmp);
            }
            if (tempFiles.isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of("error", "请先上传有效图片"));
            }
            String text = imageGenerationService.analyzeCustomImagePrompts(prompt, tempFiles, count, withText);
            return ResponseEntity.ok(Map.of("text", text));
        } catch (Exception e) {
            log.error("custom_analyze 失败: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        } finally {
            for (File f : tempFiles) {
                if (f != null && f.exists()) f.delete();
            }
        }
    }

    /**
     * 开品模式外观分析：基于产品图/描述，按内置 Excel 维度生成结构化外观分析卡片。
     * 这是两步流程的第一步，返回可编辑卡片供用户确认后二次生图。
     */
    @PostMapping("/api/kaipin_analyze")
    public ResponseEntity<Map<String, Object>> kaipinAnalyze(
            @RequestParam(value = "imageA", required = false) MultipartFile imageA,
            @RequestParam(value = "imageB", required = false) MultipartFile imageB,
            @RequestParam(value = "productA", defaultValue = "") String productA,
            @RequestParam(value = "productB", defaultValue = "") String productB,
            @RequestParam(value = "selling", defaultValue = "") String selling,
            @RequestParam(value = "focus", defaultValue = "cost") String focus,
            @RequestParam(value = "focusText", required = false) String focusText,
            @RequestParam(value = "style", defaultValue = "") String style,
            @RequestParam(value = "styleText", required = false) String styleText) {

        // 参数规范化
        productA = normalizeMultipartText(productA);
        productB = normalizeMultipartText(productB);
        selling = normalizeMultipartText(selling);
        if (focusText != null) focusText = normalizeMultipartText(focusText);
        if (styleText != null) styleText = normalizeMultipartText(styleText);

        // 新版开品模式：按固定外观维度分析单个产品，至少提供一份文字或图片材料即可。
        boolean hasA = (productA != null && !productA.isBlank()) || (imageA != null && !imageA.isEmpty());
        boolean hasB = (productB != null && !productB.isBlank()) || (imageB != null && !imageB.isEmpty());
        if (!hasA && !hasB) {
            return ResponseEntity.badRequest().body(Map.of("error", "开品模式需要至少提供一张产品图或一段产品描述"));
        }

        File imageATmp = null;
        File imageBTmp = null;
        try {
            // 保存上传的图片到临时文件
            if (imageA != null && !imageA.isEmpty()) {
                String suffixA = getSuffix(imageA.getOriginalFilename());
                imageATmp = File.createTempFile("kaipin_A_", suffixA);
                imageA.transferTo(imageATmp);
            }
            if (imageB != null && !imageB.isEmpty()) {
                String suffixB = getSuffix(imageB.getOriginalFilename());
                imageBTmp = File.createTempFile("kaipin_B_", suffixB);
                imageB.transferTo(imageBTmp);
            }

            // 调用融合分析服务
            List<Map<String, String>> fields = imageGenerationService.analyzeKaiPin(
                    productA, productB, selling, focus, focusText, style, styleText, imageATmp, imageBTmp);

            return ResponseEntity.ok(Map.of("fields", fields));
        } catch (Exception e) {
            log.error("kaipin_analyze 失败: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        } finally {
            if (imageATmp != null && imageATmp.exists()) imageATmp.delete();
            if (imageBTmp != null && imageBTmp.exists()) imageBTmp.delete();
        }
    }

    private String getSuffix(String filename) {
        if (filename == null) return ".jpg";
        String lower = filename.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".png")) return ".png";
        if (lower.endsWith(".webp")) return ".webp";
        if (lower.endsWith(".gif")) return ".gif";
        return ".jpg";
    }

    private String decodePrompt(String prompt, String promptBase64) {
        if (promptBase64 != null && !promptBase64.isBlank()) {
            try {
                return new String(Base64.getDecoder().decode(promptBase64), StandardCharsets.UTF_8);
            } catch (Exception ignored) {}
        }
        return normalizeMultipartText(prompt);
    }

    private String normalizeMultipartText(String value) {
        if (value == null) return "";
        if (!(value.contains("Ã") || value.contains("Â") || value.contains("ä")
                || value.contains("å") || value.contains("ç") || value.contains("ï¿½"))) {
            return value;
        }
        try {
            return new String(value.getBytes(StandardCharsets.ISO_8859_1), StandardCharsets.UTF_8);
        } catch (Exception ignored) {
            return value;
        }
    }

    /**
     * 开品 Excel 批量生成：豆包/视觉分析只对白底产品图执行一轮。
     * Excel 里的图片只作为逐张创新参考图，与白底产品图配对生成。
     */
    @PostMapping("/api/kaipin_excel_generate")
    public ResponseEntity<Map<String, Object>> kaipinExcelGenerate(
            @RequestParam("whiteImage") MultipartFile whiteImage,
            @RequestParam("excel") MultipartFile excel,
            @RequestParam(value = "basePrompt", defaultValue = "") String basePrompt,
            @RequestParam(value = "countPerRef", defaultValue = "1") int countPerRef,
            @RequestParam(value = "agentId", defaultValue = "gpt-image") String agentId,
            @RequestParam(value = "sessionId", defaultValue = "default") String sessionId,
            @RequestParam(value = "configJson", required = false) String configJson) {

        if (whiteImage == null || whiteImage.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Excel 批量开品需要上传白底产品图"));
        }
        if (excel == null || excel.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "请上传包含参考图片的 .xlsx 文件"));
        }

        File whiteTmp = null;
        File excelTmp = null;
        List<File> excelRefs = new ArrayList<>();
        try {
            whiteTmp = File.createTempFile("kaipin_white_", getSuffix(whiteImage.getOriginalFilename()));
            whiteImage.transferTo(whiteTmp);
            excelTmp = File.createTempFile("kaipin_refs_", ".xlsx");
            excel.transferTo(excelTmp);

            excelRefs = extractXlsxImages(excelTmp);
            if (excelRefs.isEmpty()) {
                if (whiteTmp.exists()) whiteTmp.delete();
                if (excelTmp.exists()) excelTmp.delete();
                return ResponseEntity.badRequest().body(Map.of("error", "Excel 中没有提取到图片，请确认图片是插入到 .xlsx 文件内，而不是外部链接"));
            }

            int safeCount = Math.max(1, Math.min(50, countPerRef));
            int total = excelRefs.size() * safeCount;
            GenerationTask task = taskService.createTask(total);
            String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
            String outputDir = new File(appProperties.getPaths().getTempOutputDir(),
                    "开品Excel批量/" + timestamp).getAbsolutePath();
            new File(outputDir).mkdirs();

            final File whiteFinal = whiteTmp;
            final File excelFinal = excelTmp;
            final List<File> refsFinal = new ArrayList<>(excelRefs);
            final int countFinal = safeCount;
            final String promptFinalBase = basePrompt == null || basePrompt.isBlank()
                    ? "基于白底产品图保持产品主体一致，并结合 Excel 参考图提取创新造型、色彩、材质或结构灵感，生成新的开品概念图。"
                    : basePrompt;

            taskService.submit(task, () -> {
                ExecutorService executor = imageGenerationService.getExecutor();
                List<CompletableFuture<String>> futures = new ArrayList<>();
                int[] idx = {1};

                for (int i = 0; i < refsFinal.size(); i++) {
                    File excelRef = refsFinal.get(i);
                    String prompt = buildKaiPinExcelFusionPrompt(promptFinalBase, i + 1, refsFinal.size());
                    String aspect = resolveAutoAspect("auto", List.of(whiteFinal));
                    for (int c = 0; c < countFinal; c++) {
                        final int n = idx[0]++;
                        final File refFinal = excelRef;
                        final String outputPath = new File(outputDir, n + ".jpg").getAbsolutePath();
                        futures.add(CompletableFuture.supplyAsync(() -> {
                            if (task.isCancelled()) return "失败: 已取消";
                            boolean ok = imageGenerationService.generateImageMulti(
                                    prompt,
                                    List.of(whiteFinal.getAbsolutePath(), refFinal.getAbsolutePath()),
                                    null,
                                    outputPath,
                                    agentId,
                                    aspect);
                            return ok ? outputPath : "失败: 生成未返回图片";
                        }, executor));
                    }
                }

                // 【优化】使用 allOf 并行等待所有任务完成，而非串行阻塞
                log.info("[开品 Excel 批量] 等待 {} 张图片并行生成完成", futures.size());
                try {
                    CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                            .get(10, TimeUnit.MINUTES);
                } catch (TimeoutException te) {
                    log.warn("[开品 Excel 批量] 整体超时(>10分钟)，取消未完成任务");
                    for (CompletableFuture<String> f : futures) {
                        if (!f.isDone()) f.cancel(true);
                    }
                } catch (Exception e) {
                    log.error("[开品 Excel 批量] 等待任务完成异常: {}", e.getMessage());
                }

                // 收集所有任务结果
                int n = 1;
                for (CompletableFuture<String> f : futures) {
                    String r;
                    if (f.isCancelled()) {
                        r = "失败: 已取消";
                    } else if (!f.isDone()) {
                        r = "失败: 未完成";
                    } else {
                        try {
                            r = f.get(100, TimeUnit.MILLISECONDS); // 已完成，快速获取
                        } catch (Exception e) {
                            r = "失败: " + e.getMessage();
                        }
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
                        task.addResult(ControllerHelpers.result(name, "success", null, outputRef));
                    }
                    task.incrementProgress();
                }

                task.addResult(ControllerHelpers.result("__OUTPUT_DIR__", "info", null, outputDir));
                task.addResult(ControllerHelpers.result("__AI_THOUGHT__0", "info",
                        "【开品 Excel 批量】豆包/视觉分析仅执行 1 轮：对白底产品图形成结构化卡片；Excel 内 "
                                + refsFinal.size() + " 张图片仅作为逐张创新参考图参与融合生成。", null));

                try {
                    List<File> refsForArchive = new ArrayList<>();
                    refsForArchive.add(whiteFinal);
                    refsForArchive.addAll(refsFinal);
                    HistoryService.ArchiveResult archive = historyService.archiveRefFiles(refsForArchive);
                    historyService.recordGeneration(sessionId, "kaipin",
                            promptFinalBase, agentId, archive.refPaths, outputDir, configJson);
                } catch (Exception e) {
                    log.warn("开品 Excel 批量写历史失败（不影响生图）: {}", e.getMessage());
                }

                if (whiteFinal.exists()) whiteFinal.delete();
                if (excelFinal.exists()) excelFinal.delete();
                for (File ref : refsFinal) if (ref.exists()) ref.delete();
            });

            return ResponseEntity.ok(Map.of(
                    "taskId", task.getId(),
                    "excelImageCount", excelRefs.size(),
                    "output_dir", outputDir
            ));
        } catch (Exception e) {
            log.error("kaipin_excel_generate 失败: {}", e.getMessage(), e);
            if (whiteTmp != null && whiteTmp.exists()) whiteTmp.delete();
            if (excelTmp != null && excelTmp.exists()) excelTmp.delete();
            for (File ref : excelRefs) if (ref.exists()) ref.delete();
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/api/custom_generate")
    public ResponseEntity<Map<String, Object>> customGenerate(
            @RequestParam(value = "images", required = false) List<MultipartFile> images,
            @RequestParam(value = "count", defaultValue = "1") int count,
            @RequestParam(value = "agentId", defaultValue = "gemini") String agentId,
            @RequestParam(value = "pairImages", defaultValue = "false") boolean pairImages,
            @RequestParam(value = "aspect", required = false) String aspect,
            @RequestParam(value = "sessionId", defaultValue = "default") String sessionId,
            @RequestParam(value = "mode", defaultValue = "custom") String mode,
            @RequestParam(value = "multiPrompt", defaultValue = "false") boolean multiPrompt,
            @RequestParam(value = "configJson", required = false) String configJson,
            @RequestParam(value = "promptGroups", required = false) String promptGroups,
            @RequestParam(value = "useLora", defaultValue = "false") boolean useLora,
            @RequestParam(value = "loraPreset", required = false) String loraPreset,
            MultipartHttpServletRequest request) {

        // 不能用 @RequestParam List<String> prompts —— Spring 会按英文逗号自动拆分
        // （ConversionService 默认行为，详见 spring-framework reference 6.2 / RequestHeader 章节，
        //   List<String> 参数会把 comma-separated string 转成 List）。
        // 我们的 prompt 里嵌了大量英文 negative（"worst quality, low quality, blurry, ..."），
        // 一条会被切成 N 条 → 后端 promptList.size() 暴涨 → 一次提交跑出 N 张图。
        // 直接读 multipart 原始 String[]，保留同名字段的原值不做任何拆分。
        String[] rawPrompts = request.getParameterValues("prompt");
        List<String> prompts = rawPrompts == null ? List.of() : Arrays.asList(rawPrompts);

        boolean hasImages = images != null && !images.isEmpty();
        String generationAgentId = "gemini".equalsIgnoreCase(agentId) ? "gpt-image" : agentId;
        if (!Objects.equals(generationAgentId, agentId)) {
            log.info("[custom_generate] Gemini only analyzes prompts; image generation switched to {}", generationAgentId);
        }

        // 诊断日志：51 任务排查 — 看清楚这次请求到底来了几条 prompt / 几张图 / 是否 pair
        log.info("[custom_generate 入参] prompts={}, images={}, pairImages={}, count={}, agentId={}, aspect={}, useLora={}, loraPreset={}",
                prompts.size(),
                images == null ? 0 : images.size(),
                pairImages, count, generationAgentId, aspect, useLora, loraPreset);

        // LoRA 模式：使用 Civitai API 生成
        if (useLora && loraPreset != null && !loraPreset.isBlank()) {
            log.info("[custom_generate] 启用 LoRA 模式，预设={}", loraPreset);

            // LoRA 模式下需要至少一张参考图
            if (images == null || images.isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of("error", "LoRA 模式需要上传产品图"));
            }

            // LoRA 模式只支持单图单提示词简单生成
            String prompt = prompts.isEmpty() ? "" : prompts.get(0);

            try {
                // 保存上传的图片
                File whiteBgFile = File.createTempFile("lora_ref_", ".jpg");
                images.get(0).transferTo(whiteBgFile);

                // 输出路径
                String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
                String outputDir = new File(appProperties.getPaths().getTempOutputDir(),
                        "自定义模式生成/" + timestamp).getAbsolutePath();
                new File(outputDir).mkdirs();
                String outputPath = new File(outputDir, "output_0.jpg").getAbsolutePath();

                // 调用 Civitai LoRA 服务
                boolean success = civitaiLoraService.generateWithLora(
                    prompt,
                    whiteBgFile.getAbsolutePath(),
                    outputPath,
                    aspect,
                    loraPreset
                );

                // 清理临时文件
                whiteBgFile.delete();

                if (success) {
                    // 记录到历史
                    HistoryService.ArchiveResult archiveResult = historyService.archiveRefs(images);
                    historyService.recordGeneration(
                        sessionId,
                        "custom",
                        prompt,
                        "civitai-lora-" + loraPreset,
                        archiveResult.refPaths,
                        outputPath,
                        configJson
                    );

                    return ResponseEntity.ok(Map.of(
                        "success", true,
                        "outputDir", outputDir,
                        "message", "LoRA 生成成功"
                    ));
                } else {
                    return ResponseEntity.ok(Map.of(
                        "success", false,
                        "error", "Civitai LoRA 生成失败，请检查日志"
                    ));
                }
            } catch (Exception e) {
                log.error("[custom_generate] LoRA 模式异常: {}", e.getMessage(), e);
                return ResponseEntity.internalServerError().body(Map.of(
                    "error", "LoRA 生成异常: " + e.getMessage()
                ));
            }
        }

        // 过滤空 prompt
        List<String> promptList = new ArrayList<>();
        for (String p : prompts) if (p != null && !p.isBlank()) promptList.add(p);
        if (promptList.isEmpty() && !hasImages) {
            return ResponseEntity.badRequest().body(Map.of("error", "纯文生图模式需要提供提示词"));
        }
        if (promptList.isEmpty()) promptList.add("");
        int requestedCount = Math.max(1, Math.min(50, count));

        // 自定义模式的“生成数量”是最终总张数，不是 prompt 数量 × 每条 prompt 张数。
        // 防御异常请求带入多条 prompt 时出现 4×4=16 这类倍增。
        if ("custom".equalsIgnoreCase(mode) && multiPrompt && promptList.size() > 1) {
            requestedCount = 1;
            log.info("[custom_generate] 自定义模式显式批量 prompt：{} 条，每条固定生成 1 张", promptList.size());
        } else if ("custom".equalsIgnoreCase(mode) && promptList.size() > 1) {
            log.warn("[custom_generate] 自定义模式收到多条 prompt（{} 条），已收敛为 1 条，避免 count 倍增", promptList.size());
            promptList = new ArrayList<>(List.of(String.join("\n\n", promptList)));
        }

        // 收集提示词思考过程，回传前端展示（已移除 Gemini 压缩步骤，直接透传原文）
        List<String> thoughts = new ArrayList<>();
        for (String p : promptList) {
            thoughts.add(
                "【提示词直送（未经 LLM 处理）】\n模型: " + generationAgentId
                + "\n长度: " + p.length() + " 字\n\n【最终提示词】\n" + p
            );
        }

        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
        // Phase 1：先落到临时归档，2 小时后自动清理；用户在前端点 💾 才 copy 到永久 outputDir
        String outputDir = new File(appProperties.getPaths().getTempOutputDir(),
                "自定义模式生成/" + timestamp).getAbsolutePath();
        new File(outputDir).mkdirs();

        // 解析 promptGroups（批量生图分组配对）：[{promptIdx, imageIndices:[..]}, ...]
        // 非空时走分组分支：第 promptIdx 条 prompt 用 imageIndices 指向的图片子集。
        List<int[]> groupImageIdx = new ArrayList<>(); // 每条 prompt 对应的 image 索引数组
        List<Integer> groupPromptIdx = new ArrayList<>();
        boolean useGroups = promptGroups != null && !promptGroups.isBlank();
        if (useGroups) {
            try {
                com.fasterxml.jackson.databind.JsonNode arr =
                        new com.fasterxml.jackson.databind.ObjectMapper().readTree(promptGroups);
                for (com.fasterxml.jackson.databind.JsonNode g : arr) {
                    int pIdx = g.path("promptIdx").asInt(0);
                    com.fasterxml.jackson.databind.JsonNode ii = g.path("imageIndices");
                    int[] idxs = new int[ii.size()];
                    for (int k = 0; k < ii.size(); k++) idxs[k] = ii.get(k).asInt();
                    groupPromptIdx.add(pIdx);
                    groupImageIdx.add(idxs);
                }
            } catch (Exception e) {
                return ResponseEntity.badRequest().body(Map.of("error", "promptGroups 解析失败: " + e.getMessage()));
            }
        }

        // multipart 必须在请求线程内落盘，提交到异步任务前先把上传文件持久化
        File refTempFile = null;
        List<File> whiteTempFiles = new ArrayList<>();
        List<File> pairRefs        = new ArrayList<>();
        List<File> groupFiles      = new ArrayList<>(); // promptGroups 模式：所有 image 按索引落盘
        if (hasImages) {
            try {
                if (useGroups) {
                    for (int i = 0; i < images.size(); i++) {
                        File gf = File.createTempFile("grp_" + i + "_", ".jpg");
                        images.get(i).transferTo(gf);
                        groupFiles.add(gf);
                    }
                } else if (pairImages) {
                    for (int i = 0; i < images.size(); i++) {
                        File pr = File.createTempFile("pair_" + i + "_", ".jpg");
                        images.get(i).transferTo(pr);
                        pairRefs.add(pr);
                    }
                } else {
                    refTempFile = File.createTempFile("ref_", ".jpg");
                    images.get(0).transferTo(refTempFile);
                    for (int i = 1; i < images.size(); i++) {
                        File wf = File.createTempFile("white_" + i + "_", ".jpg");
                        images.get(i).transferTo(wf);
                        whiteTempFiles.add(wf);
                    }
                }
            } catch (Exception e) {
                return ResponseEntity.internalServerError()
                        .body(Map.of("error", "处理上传文件失败: " + e.getMessage()));
            }
        }

        int pairN = pairImages ? Math.min(pairRefs.size(), promptList.size()) : 0;
        int total = useGroups ? (groupPromptIdx.size() * requestedCount)
                  : pairImages ? (pairN * requestedCount)
                  : (promptList.size() * requestedCount);

        GenerationTask task = taskService.createTask(total);
        final File refFinal = refTempFile;
        final List<File> whiteFinal = whiteTempFiles;
        final List<File> pairRefsFinal = pairRefs;
        final int pairNFinal = pairN;
        final boolean hasImagesFinal = hasImages;
        final boolean pairFinal = pairImages;
        final List<String> promptsFinal = promptList;
        final List<String> thoughtsFinal = thoughts;
        final boolean useGroupsFinal = useGroups;
        final List<File> groupFilesFinal = groupFiles;
        final List<int[]> groupImageIdxFinal = groupImageIdx;
        final List<Integer> groupPromptIdxFinal = groupPromptIdx;
        final String requestedAspect = normalizeAspect(aspect);
        final int countFinal = requestedCount;

        taskService.submit(task, () -> {
            ExecutorService executor = imageGenerationService.getExecutor();
            List<CompletableFuture<String>> futures = new ArrayList<>();
            int[] idx = {1};

            if (useGroupsFinal) {
                // 批量分组：第 g 组用 promptsFinal[promptIdx] + imageIndices 指向的图片子集
                for (int g = 0; g < groupPromptIdxFinal.size(); g++) {
                    int pIdx = groupPromptIdxFinal.get(g);
                    final String promptFinal = (pIdx >= 0 && pIdx < promptsFinal.size())
                            ? promptsFinal.get(pIdx) : promptsFinal.get(0);
                    final List<String> refsFinal = new ArrayList<>();
                    for (int imgIdx : groupImageIdxFinal.get(g)) {
                        if (imgIdx >= 0 && imgIdx < groupFilesFinal.size())
                            refsFinal.add(groupFilesFinal.get(imgIdx).getAbsolutePath());
                    }
                    final String aspectForGroup = resolveAutoAspect(requestedAspect,
                            refsFinal.stream().map(File::new).toList());
                    for (int c = 0; c < countFinal; c++) {
                        final int n = idx[0]++;
                        final String outputPath = new File(outputDir, n + ".jpg").getAbsolutePath();
                        futures.add(CompletableFuture.supplyAsync(() -> {
                            if (task.isCancelled()) return "失败: 已取消";
                            boolean ok = imageGenerationService.generateImageMulti(
                                    promptFinal, refsFinal, null, outputPath, generationAgentId, aspectForGroup);
                            return ok ? outputPath : "失败: 生成未返回图片";
                        }, executor));
                    }
                }
            } else if (pairFinal && hasImagesFinal) {
                for (int i = 0; i < pairNFinal; i++) {
                    final File refI = pairRefsFinal.get(i);
                    final String promptFinal = promptsFinal.get(i);
                    final String aspectForRef = resolveAutoAspect(requestedAspect, List.of(refI));
                    for (int c = 0; c < countFinal; c++) {
                        final int n = idx[0]++;
                        final String outputPath = new File(outputDir, n + ".jpg").getAbsolutePath();
                        futures.add(CompletableFuture.supplyAsync(() -> {
                            if (task.isCancelled()) return "失败: 已取消";
                            boolean ok = imageGenerationService.generateImageMulti(
                                    promptFinal, List.of(refI.getAbsolutePath()), null, outputPath, generationAgentId, aspectForRef);
                            return ok ? outputPath : "失败: 生成未返回图片";
                        }, executor));
                    }
                }
            } else if (!hasImagesFinal) {
                for (String p : promptsFinal) {
                    for (int i = 0; i < countFinal; i++) {
                        final int n = idx[0]++;
                        final String outputPath = new File(outputDir, n + ".jpg").getAbsolutePath();
                        final String promptFinal = p;
                        futures.add(CompletableFuture.supplyAsync(() -> {
                            if (task.isCancelled()) return "失败: 已取消";
                            boolean ok = imageGenerationService.generateImageMulti(
                                    promptFinal, List.of(), null, outputPath, generationAgentId, requestedAspect);
                            return ok ? outputPath : "失败: 生成未返回图片";
                        }, executor));
                    }
                }
            } else {
                // 【自定义模式·混合并行生成】第1张串行，第2-N张并行
                // 策略：第1张用白底图建立基调，第2-N张全部参考白底图+第1张并发生成
                List<String> baseRefs = new ArrayList<>();
                baseRefs.add(refFinal.getAbsolutePath());
                for (File wf : whiteFinal) baseRefs.add(wf.getAbsolutePath());

                List<File> refsForAspect = new ArrayList<>();
                refsForAspect.add(refFinal);
                refsForAspect.addAll(whiteFinal);
                final String aspectForRefs = resolveAutoAspect(requestedAspect, refsForAspect);

                for (String p : promptsFinal) {
                    // Phase 1: 生成第1张（串行）
                    final int n1 = idx[0]++;
                    final String outputPath1 = new File(outputDir, n1 + ".jpg").getAbsolutePath();
                    final List<String> refs1 = new ArrayList<>(baseRefs);
                    final String prompt1 = buildSeriesPrompt(p, 1, countFinal);

                    CompletableFuture<String> future1 = CompletableFuture.supplyAsync(() -> {
                        if (task.isCancelled()) return "失败: 已取消";
                        log.info("[自定义模式] 生成第1张（基调图），参考图数量: {}", refs1.size());
                        boolean ok = imageGenerationService.generateImageMulti(
                                prompt1, refs1, null, outputPath1, generationAgentId, aspectForRefs);
                        return ok ? outputPath1 : "失败: 生成未返回图片";
                    }, executor);

                    futures.add(future1);

                    // 等待第1张完成
                    String result1;
                    try {
                        result1 = future1.get(5, TimeUnit.MINUTES);
                        if (result1.startsWith("失败") || !new File(result1).exists()) {
                            log.warn("[自定义模式] 第1张生成失败: {}，后续图片将降级为仅参考白底图", result1);
                            result1 = null; // 标记为失败，后续不使用
                        } else {
                            log.info("[自定义模式] 第1张生成成功: {}", result1);
                        }
                    } catch (TimeoutException te) {
                        future1.cancel(true);
                        log.error("[自定义模式] 第1张生成超时");
                        result1 = null;
                    } catch (Exception e) {
                        log.error("[自定义模式] 第1张生成异常: {}", e.getMessage());
                        result1 = null;
                    }

                    // Phase 2: 并行生成第2-N张（所有都参考白底图+第1张）
                    if (countFinal > 1) {
                        final String firstImagePath = result1; // final for lambda
                        List<CompletableFuture<String>> parallelFutures = new ArrayList<>();

                        for (int i = 1; i < countFinal; i++) {
                            final int imageIndex = i + 1;
                            final int n = idx[0]++;
                            final String outputPath = new File(outputDir, n + ".jpg").getAbsolutePath();

                            // 构建参考图列表：白底图 + 第1张（如果第1张生成成功）
                            final List<String> currentRefs = new ArrayList<>(baseRefs);
                            if (firstImagePath != null) {
                                currentRefs.add(firstImagePath);
                            }

                            final String enhancedPrompt = buildSeriesPrompt(p, imageIndex, countFinal);

                            CompletableFuture<String> currentFuture = CompletableFuture.supplyAsync(() -> {
                                if (task.isCancelled()) return "失败: 已取消";
                                log.info("[自定义模式并行] 开始生成第 {} 张，参考图数量: {}", imageIndex, currentRefs.size());
                                boolean ok = imageGenerationService.generateImageMulti(
                                        enhancedPrompt, currentRefs, null, outputPath, generationAgentId, aspectForRefs);
                                return ok ? outputPath : "失败: 生成未返回图片";
                            }, executor);

                            futures.add(currentFuture);
                            parallelFutures.add(currentFuture);
                        }

                        // 等待所有并行任务完成（注意：不在这里做额外等待，统一在后面收集结果时处理）
                        log.info("[自定义模式并行] 已提交 {} 张图片并行生成", parallelFutures.size());
                    }
                }
            }

            int n = 1;
            for (CompletableFuture<String> f : futures) {
                String r;
                if (f.isCancelled()) {
                    r = "失败: 已取消";
                } else if (!f.isDone()) {
                    // 任务还未完成，等待它（单张最多10分钟）
                    try {
                        r = f.get(10, TimeUnit.MINUTES);
                    } catch (TimeoutException te) {
                        f.cancel(true);
                        r = "失败: 单张图超时 (>10 分钟)";
                    } catch (CancellationException ce) {
                        r = "失败: 已取消";
                    } catch (Exception e) {
                        r = "失败: " + e.getMessage();
                    }
                } else {
                    // 任务已完成，直接获取结果（不会阻塞）
                    try {
                        r = f.get(100, TimeUnit.MILLISECONDS);
                    } catch (CancellationException ce) {
                        r = "失败: 已取消";
                    } catch (Exception e) {
                        r = "失败: " + e.getMessage();
                    }
                }
                String name = n++ + ".jpg";
                if (r.startsWith("失败")) {
                    task.addResult(ControllerHelpers.result(name, "error", r, null));
                } else {
                    // COS 已配置时上传，返回 URL；否则返回本地路径（开发态兜底）
                    String outputRef = r;
                    if (cosService.isEnabled()) {
                        try {
                            outputRef = cosService.upload(new File(r), name);
                        } catch (Exception ce) {
                            log.warn("COS 上传失败，降级本地路径: {}", ce.getMessage());
                        }
                    }
                    task.addResult(ControllerHelpers.result(name, "success", null, outputRef));
                }
                task.incrementProgress();
            }

            task.addResult(ControllerHelpers.result("__OUTPUT_DIR__", "info", null, outputDir));

            for (int i = 0; i < thoughtsFinal.size(); i++) {
                task.addResult(ControllerHelpers.result("__AI_THOUGHT__" + i, "info", thoughtsFinal.get(i), null));
            }

            if (refFinal != null) refFinal.delete();
            for (File wf : whiteFinal) wf.delete();
            for (File pr : pairRefsFinal) pr.delete();
        });

        // Phase 2：写一条 GenerationHistory（per-batch；outputPath = 批次目录）。
        // 异步任务还没跑完没关系，记录的是"提交了这次生图请求"的元信息；
        // 用户后续点 💾 保存其中一张时，ResourceController.saveToGallery 会按 parent dir 匹配回填 savedPath。
        try {
            // 归档参考图（pair / ref+white / no images 三种入参形态都覆盖）
            List<File> refsForArchive = new ArrayList<>();
            if (refTempFile != null) refsForArchive.add(refTempFile);
            for (File wf : whiteTempFiles) refsForArchive.add(wf);
            for (File pr : pairRefs)       refsForArchive.add(pr);
            // 注意：这里 archiveRefFiles 是同步 copy 一次，跟 lambda 里之后的 .delete() 无竞争
            HistoryService.ArchiveResult archive = historyService.archiveRefFiles(refsForArchive);
            String promptJoined = String.join(" || ", promptList);
            historyService.recordGeneration(sessionId, mode, promptJoined, generationAgentId,
                    archive.refPaths, outputDir, configJson);
        } catch (Exception e) {
            log.warn("写历史记录失败（不影响生图）: {}", e.getMessage());
        }

        return ResponseEntity.ok(Map.of("taskId", task.getId(), "output_dir", outputDir));
    }

    private String normalizeAspect(String aspect) {
        if (aspect == null || aspect.isBlank()) return "auto";
        return aspect.trim();
    }

    /**
     * 自定义模式的“自动”应尽量跟随参考图比例，而不是落回模型默认方图。
     * 用户显式选择 1:1 / 9:16 / 16:9 等比例时不改动，电商详情页强制 9:16 也不受影响。
     */
    private String resolveAutoAspect(String requestedAspect, List<File> refs) {
        String normalized = normalizeAspect(requestedAspect);
        if (!"auto".equalsIgnoreCase(normalized)) return normalized;
        if (refs == null || refs.isEmpty()) return "auto";

        for (File ref : refs) {
            if (ref == null || !ref.exists() || !ref.isFile()) continue;
            try {
                BufferedImage image = ImageIO.read(ref);
                if (image == null || image.getWidth() <= 0 || image.getHeight() <= 0) continue;
                String inferred = nearestAspect(image.getWidth(), image.getHeight());
                log.info("自定义模式 auto 尺寸按参考图推断为 {} ({}x{}, file={})",
                        inferred, image.getWidth(), image.getHeight(), ref.getName());
                return inferred;
            } catch (Exception e) {
                log.warn("读取参考图尺寸失败，保持 auto: {}", e.getMessage());
            }
        }
        return "auto";
    }

    private String nearestAspect(int width, int height) {
        double ratio = width / (double) height;
        Map<String, Double> candidates = new LinkedHashMap<>();
        candidates.put("1:1", 1.0);
        candidates.put("16:9", 16.0 / 9.0);
        candidates.put("9:16", 9.0 / 16.0);
        candidates.put("4:3", 4.0 / 3.0);
        candidates.put("3:4", 3.0 / 4.0);

        String best = "1:1";
        double bestDelta = Double.MAX_VALUE;
        for (Map.Entry<String, Double> entry : candidates.entrySet()) {
            double delta = Math.abs(Math.log(ratio / entry.getValue()));
            if (delta < bestDelta) {
                bestDelta = delta;
                best = entry.getKey();
            }
        }
        return best;
    }

    /**
     * 构建系列连贯性提示词：根据图片序号增强约束，确保同场景多角度拍摄效果
     *
     * @param basePrompt 用户原始提示词
     * @param currentIndex 当前图片序号（1-based）
     * @param totalCount 总图片数量
     * @return 增强后的提示词
     */
    private String buildSeriesPrompt(String basePrompt, int currentIndex, int totalCount) {
        String base = basePrompt == null ? "" : basePrompt.trim();

        // 系列连贯性核心约束
        String seriesConstraint = String.format("""

                【系列连贯性·最高优先级】这是同一场景系列拍摄的第 %d/%d 张：
                1. 产品主体必须100%%一致：外形、颜色、材质、品牌标识、关键结构完全相同
                2. **产品原生文字必须与第1张完全相同**：
                   - 产品本体上的文字：LOGO、品牌名称、型号标识、按钮标签、刻度数字、接口标识等
                   - 这些文字的字形、字体、位置、颜色、清晰度必须保持一致，禁止模糊、变形、消失或改变内容
                   - **注意**：画面上的营销卖点文案（如"持久续航"、"智能降噪"等）属于本图卖点的一部分，每张图可以不同，这是正常的
                3. **两类文字的区分**：
                   - 产品原生文字（必须一致）：产品包装、外壳、屏幕、按键上印刷/显示的文字
                   - 画面营销文案（可以不同）：漂浮在场景中的卖点标题、副标题、标签，用于强调本图的差异化卖点
                4. 场景类型必须完全一致：如果是浴室就保持浴室，厨房就保持厨房，办公室就保持办公室
                5. 整体色调、光线氛围、背景材质必须保持一致，营造"同一时间同一地点"的连续感
                6. 允许变化的部分：
                   - 产品拍摄角度（正面→侧面→俯视→仰视等自然过渡）
                   - 产品在场景中的摆放位置（左→中→右，或前→后景深变化）
                   - 场景中的道具细节（同类型道具的不同摆放，但风格保持一致）
                   - 光照角度的微调（但整体亮度和色温保持一致）
                   - **画面营销文案**：每张图根据本图卖点展示不同的营销标题和标签，这是系列图的核心价值
                7. 禁止出现：场景类型切换、色调剧变、产品变形、产品原生文字错误/模糊/消失、风格跳跃、不同时间段的光线
                8. 最终效果：看起来像是摄影师在同一场景中走动，用不同角度拍摄同一产品的连续镜头；每张图通过不同的拍摄角度和营销文案强调不同卖点，但产品本身始终是同一个
                """.trim(), currentIndex, totalCount);

        // 角度差异化约束（替换原有的位置引导）
        String angleConstraint = buildAngleConstraint(currentIndex, totalCount);

        return base + seriesConstraint + angleConstraint;
    }

    /**
     * 构建角度差异化约束
     * @param currentIndex 当前图片序号（1-based）
     * @param totalCount 总图片数量
     * @return 角度约束提示词
     */
    private String buildAngleConstraint(int currentIndex, int totalCount) {
        if (currentIndex == 1) {
            return """

                【第1张·基调建立】
                产品主视角（正面或正面45度），清晰展示产品正面特征、品牌LOGO、核心卖点。
                这张图将作为后续图片的参考基准，必须完整呈现产品全貌。
                **产品原生文字重点**：清晰展示产品本体上的所有文字、LOGO、品牌标识、按钮标签等细节，确保可读且完整
                **画面营销文案**：根据basePrompt中的卖点，设计本图的营销文案（主标题、副标题、卖点标签），用于强调第1个核心卖点
                """;
        }

        String[] angles = selectAngleSequence(totalCount);
        int angleIndex = (currentIndex - 2) % angles.length;
        String currentAngle = angles[angleIndex];

        return String.format("""

                【第%d张·角度约束·强制执行】
                产品必须采用%s。

                **角度定义**：
                - 保持产品主体完整可见，不得被遮挡或裁切
                - 该角度必须与前面已生成的图片角度明显不同
                - 展示该角度下产品的独特特征和细节
                - 光线和阴影要符合该视角的物理规律

                **禁止**：
                - 禁止使用与第1张相同或相似的正面角度
                - 禁止使用与前面已生成图片重复的角度
                - 禁止因为角度改变而修改产品结构或比例

                **产品原生文字约束**：参考第1张图片，确保产品本体上的文字/LOGO/标识与第1张完全相同，位置、字体、清晰度一致
                **画面营销文案**：可以与前面不同，根据basePrompt设计新的营销标题和标签来强调第%d个卖点或从新角度证明功能
                """, currentIndex, currentAngle, currentIndex);
    }

    /**
     * 根据总图片数量选择合适的角度序列
     * @param totalCount 总图片数量
     * @return 角度描述数组
     */
    private String[] selectAngleSequence(int totalCount) {
        if (totalCount <= 3) {
            // 3张及以下：正面+侧面+俯视/仰视
            return new String[]{
                "侧面90度视角（展示产品左侧或右侧完整轮廓，侧面平行于画面）",
                "俯视45度视角（从斜上方45度向下拍摄，展示产品顶部特征和整体布局）"
            };
        } else if (totalCount <= 5) {
            // 4-5张：正面+左右侧+俯视+微仰视
            return new String[]{
                "左侧面70度视角（产品主体向左旋转70度，展示左侧面和部分正面）",
                "右侧面70度视角（产品主体向右旋转70度，展示右侧面和部分正面）",
                "俯视45度视角（从斜上方45度向下拍摄，展示顶部细节）",
                "正面微仰视30度视角（相机位置略低于产品中心，向上仰拍30度）"
            };
        } else {
            // 6张及以上：全方位多角度
            return new String[]{
                "左侧面90度视角（产品完全侧面展示，左侧面平行于画面）",
                "右侧面90度视角（产品完全侧面展示，右侧面平行于画面）",
                "俯视60度视角（从较陡的斜上方60度向下拍摄，强调顶部视角）",
                "仰视30度视角（相机位置明显低于产品，向上仰拍30度）",
                "左前45度斜视角（从产品左前方45度角拍摄，兼顾正面和左侧）",
                "右后45度斜视角（从产品右后方45度角拍摄，展示背面和右侧）"
            };
        }
    }

    private List<File> extractXlsxImages(File xlsx) throws Exception {
        List<File> files = new ArrayList<>();
        try (XSSFWorkbook workbook = new XSSFWorkbook(xlsx)) {
            int idx = 1;
            for (XSSFPictureData picture : workbook.getAllPictures()) {
                String ext = picture.suggestFileExtension();
                String suffix = (ext == null || ext.isBlank()) ? ".png" : "." + ext.replace(".", "");
                File out = File.createTempFile("kaipin_xlsx_ref_" + idx + "_", suffix);
                try (FileOutputStream fos = new FileOutputStream(out)) {
                    fos.write(picture.getData());
                }
                files.add(out);
                idx++;
            }
        }
        return files;
    }

    private String buildKaiPinExcelFusionPrompt(String basePrompt, int index, int total) {
        return """
                【开品 Excel 批量融合】
                本次豆包/视觉分析只对白底产品图执行一轮。请严格以第 1 张参考图（白底产品图）为产品主体，保持品类、核心结构、功能识别点、比例关系和可制造性一致。
                第 2 张参考图来自 Excel，是第 %d / %d 张创新参考图。不要直接复制或替换成该参考图里的产品，只提取它的造型语言、CMF 色彩策略、材质质感、结构细节、装饰节奏或使用场景作为创新点。

                【白底产品分析结论】
                %s

                【融合要求】
                1. 主体必须仍然是白底产品图里的产品，不丢失原产品身份。
                2. 将 Excel 参考图的创新点融合到主体的外观设计中，形成新的开品概念。
                3. 画面只呈现一个清晰主产品，结构真实、材质可信、光影自然。
                4. 禁止多产品拼贴、低清晰度、畸变、漂浮部件、不可制造结构、品牌 logo、水印、纯文字海报。
                """.formatted(index, total, basePrompt == null ? "" : basePrompt);
    }

    @PostMapping("/api/inpaint")
    public ResponseEntity<Map<String, Object>> inpaint(
            @RequestParam("image")  MultipartFile image,
            @RequestParam("mask")   MultipartFile mask,
            @RequestParam("prompt") String prompt,
            @RequestParam(value = "sessionId", defaultValue = "default") String sessionId) {

        if (image == null || image.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "缺少原图"));
        }
        if (mask == null || mask.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "缺少蒙版"));
        }
        if (prompt == null || prompt.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "缺少提示词"));
        }

        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
        // Phase 1：先落到临时归档，2 小时后自动清理；用户在前端点 💾 才 copy 到永久 outputDir
        String outputDir = new File(appProperties.getPaths().getTempOutputDir(),
                "局部编辑/" + timestamp).getAbsolutePath();
        new File(outputDir).mkdirs();
        String outputPath = new File(outputDir, "1.png").getAbsolutePath();

        File imageTmp = null;
        File maskTmp  = null;
        try {
            // 上传文件落到系统 temp，避免污染用户输出目录；finally 里清理
            imageTmp = File.createTempFile("inpaint_image_", ".png");
            maskTmp  = File.createTempFile("inpaint_mask_",  ".png");
            image.transferTo(imageTmp);
            mask.transferTo(maskTmp);
            log.info("inpaint: image={} mask={}", imageTmp.getAbsolutePath(), maskTmp.getAbsolutePath());

            String inferredAspect = resolveAutoAspect("auto", List.of(imageTmp));
            boolean ok = gptImageAgent.generateWithMask(prompt, imageTmp, maskTmp, outputPath, inferredAspect);
            if (!ok) {
                return ResponseEntity.internalServerError()
                        .body(Map.of("error", "GPT-Image 局部重绘失败，请检查 API Key 或网络"));
            }
            // Phase 2：归档原图 + mask 作为 ref；写历史记录
            try {
                HistoryService.ArchiveResult archive = historyService.archiveRefFiles(
                        java.util.List.of(imageTmp, maskTmp));
                historyService.recordGeneration(sessionId, "inpaint", prompt, "gpt-image",
                        archive.refPaths, outputDir, null);
            } catch (Exception e) {
                log.warn("inpaint 写历史失败: {}", e.getMessage());
            }
            // COS 上传
            String resultRef = outputPath;
            if (cosService.isEnabled()) {
                try { resultRef = cosService.upload(new File(outputPath), "inpaint.png"); }
                catch (Exception ce) { log.warn("inpaint COS 上传失败: {}", ce.getMessage()); }
            }
            return ResponseEntity.ok(Map.of(
                "results", List.of(resultRef),
                "output_dir", outputDir,
                "thought", "【局部重绘 · 提示词直送（未经 LLM 处理）】\n模型: gpt-image\n"
                    + "长度: " + prompt.length() + " 字\n\n【最终提示词】\n" + prompt
            ));
        } catch (Exception e) {
            log.error("inpaint error: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        } finally {
            if (imageTmp != null) imageTmp.delete();
            if (maskTmp  != null) maskTmp.delete();
        }
    }

    @PostMapping("/api/gpt-image/generate")
    public ResponseEntity<Map<String, Object>> gptImageGenerate(@RequestBody Map<String, Object> body) {
        List<String> keys = appProperties.getGptImage().getApiKeys();
        String baseUrl = appProperties.getGptImage().getBaseUrl();
        if (keys == null || keys.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "error", "GPT-Image API Key 未配置"));
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("model", "gpt-image-2");
        payload.put("prompt", body.getOrDefault("prompt", ""));
        payload.put("size",    body.getOrDefault("size",    "1024x1024"));
        payload.put("quality", body.getOrDefault("quality", "auto"));
        payload.put("background",   body.getOrDefault("background",   "auto"));
        payload.put("output_format", body.getOrDefault("output_format", "png"));

        ObjectMapper mapper = new ObjectMapper();
        String jsonBody;
        try { jsonBody = mapper.writeValueAsString(payload); }
        catch (Exception e) { return ResponseEntity.internalServerError().body(Map.of("success", false, "error", e.getMessage())); }

        // 按序轮询 key：遇 429/5xx 或网络异常则尝试下一个；2xx 或其他 4xx 立刻返回
        String lastError = "所有 key 均不可用";
        int lastStatus = 500;
        for (String apiKey : keys) {
            if (apiKey == null || apiKey.isBlank()) continue;
            try {
                URL url = new URL(baseUrl + "/v1/images/generations");
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setRequestProperty("Authorization", "Bearer " + apiKey);
                conn.setDoOutput(true);
                conn.setConnectTimeout(30_000);
                conn.setReadTimeout(120_000);
                try (OutputStream os = conn.getOutputStream()) {
                    os.write(jsonBody.getBytes(StandardCharsets.UTF_8));
                }

                int status = conn.getResponseCode();
                String respBody;
                // try-with-resources 关 stream，conn.disconnect() 不保证关闭内部流（B 阶段审查 #2）
                try (java.io.InputStream is =
                        (status >= 200 && status < 300 ? conn.getInputStream() : conn.getErrorStream())) {
                    respBody = new String(is.readAllBytes(), StandardCharsets.UTF_8);
                } finally {
                    conn.disconnect();
                }

                if (status >= 200 && status < 300) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> resp = mapper.readValue(respBody, Map.class);
                    return ResponseEntity.ok(Map.of("success", true, "data", resp));
                }
                lastStatus = status;
                lastError = respBody;
                if (status != 429 && status < 500) {
                    return ResponseEntity.status(status).body(Map.of("success", false, "error", respBody));
                }
                log.warn("GPT-Image key 尾号[{}] 返回 {}，尝试下一个",
                        apiKey.length() > 4 ? apiKey.substring(0, 4) + "***" : "***", status);
            } catch (Exception e) {
                lastError = e.getMessage();
                log.warn("GPT-Image key 尾号[{}] 异常: {}",
                        apiKey.length() > 4 ? apiKey.substring(0, 4) + "***" : "***", e.getMessage());
            }
        }
        log.error("gptImageGenerate 所有 key 均失败: {}", lastError);
        return ResponseEntity.status(lastStatus).body(Map.of("success", false, "error", lastError));
    }

    // 私有 result() / countImages() 已抽到 ControllerHelpers（B 阶段审查 #10）
}
