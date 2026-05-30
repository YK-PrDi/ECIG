package com.elebusiness.controller;

import com.elebusiness.config.AppProperties;
import com.elebusiness.model.DingTalkRecord;
import com.elebusiness.model.GenerateRequest;
import com.elebusiness.model.GenerationTask;
import com.elebusiness.model.ProductInfo;
import com.elebusiness.service.DingTalkService;
import com.elebusiness.service.HistoryService;
import com.elebusiness.service.ImageGenerationService;
import com.elebusiness.service.PromptCondenser;
import com.elebusiness.service.TaskService;
import com.elebusiness.service.agent.GptImageAgent;
import com.elebusiness.service.CosService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.multipart.MultipartHttpServletRequest;

import java.io.File;
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
    private final PromptCondenser promptCondenser;
    private final HistoryService historyService;
    private final CosService cosService;

    public GenerateController(DingTalkService dingTalkService,
                              ImageGenerationService imageGenerationService,
                              AppProperties appProperties, TaskService taskService,
                              GptImageAgent gptImageAgent, PromptCondenser promptCondenser,
                              HistoryService historyService, CosService cosService) {
        this.dingTalkService = dingTalkService;
        this.imageGenerationService = imageGenerationService;
        this.appProperties = appProperties;
        this.taskService = taskService;
        this.gptImageAgent = gptImageAgent;
        this.promptCondenser = promptCondenser;
        this.historyService = historyService;
        this.cosService = cosService;
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

    @PostMapping("/api/custom_generate")
    public ResponseEntity<Map<String, Object>> customGenerate(
            @RequestParam(value = "images", required = false) List<MultipartFile> images,
            @RequestParam(value = "count", defaultValue = "1") int count,
            @RequestParam(value = "agentId", defaultValue = "gemini") String agentId,
            @RequestParam(value = "pairImages", defaultValue = "false") boolean pairImages,
            @RequestParam(value = "aspect", required = false) String aspect,
            @RequestParam(value = "sessionId", defaultValue = "default") String sessionId,
            @RequestParam(value = "mode", defaultValue = "custom") String mode,
            @RequestParam(value = "configJson", required = false) String configJson,
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

        // 诊断日志：51 任务排查 — 看清楚这次请求到底来了几条 prompt / 几张图 / 是否 pair
        log.info("[custom_generate 入参] prompts={}, images={}, pairImages={}, count={}, agentId={}, aspect={}",
                prompts.size(),
                images == null ? 0 : images.size(),
                pairImages, count, agentId, aspect);

        // 过滤空 prompt
        List<String> promptList = new ArrayList<>();
        for (String p : prompts) if (p != null && !p.isBlank()) promptList.add(p);
        if (promptList.isEmpty() && !hasImages) {
            return ResponseEntity.badRequest().body(Map.of("error", "纯文生图模式需要提供提示词"));
        }
        if (promptList.isEmpty()) promptList.add("");

        // GPT-Image 对长 prompt 敏感（>550 字易超时），用 Gemini 提前批量压缩；失败自动降级原文
        // 同时收集 LLM 思考过程，回传前端展示
        List<String> thoughts = new ArrayList<>();
        if (agentId != null && agentId.startsWith("gpt-image")) {
            List<String> compressed = new ArrayList<>(promptList.size());
            for (String p : promptList) {
                PromptCondenser.Condensed c = promptCondenser.condenseDetailed(p);
                compressed.add(c.compressed());
                if (c.thought() == null || c.thought().isBlank()) {
                    thoughts.add(
                        "【提示词直送（未经 LLM 处理）】\n模型: " + agentId
                        + "\n长度: " + p.length() + " 字\n\n【最终提示词】\n" + p
                    );
                } else {
                    thoughts.add(
                        "【Gemini 压缩思考】\n" + c.thought()
                        + "\n\n【原提示词（" + p.length() + " 字）】\n" + p
                        + "\n\n【压缩后（" + c.compressed().length() + " 字，实际发给 GPT-Image）】\n" + c.compressed()
                    );
                }
            }
            promptList = compressed;
        } else {
            for (String p : promptList) {
                thoughts.add(
                    "【提示词直送（未经 LLM 处理）】\n模型: " + agentId
                    + "\n长度: " + p.length() + " 字\n\n【最终提示词】\n" + p
                );
            }
        }

        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
        // Phase 1：先落到临时归档，2 小时后自动清理；用户在前端点 💾 才 copy 到永久 outputDir
        String outputDir = new File(appProperties.getPaths().getTempOutputDir(),
                "自定义模式生成/" + timestamp).getAbsolutePath();
        new File(outputDir).mkdirs();

        // multipart 必须在请求线程内落盘，提交到异步任务前先把上传文件持久化
        File refTempFile = null;
        List<File> whiteTempFiles = new ArrayList<>();
        List<File> pairRefs        = new ArrayList<>();
        if (hasImages) {
            try {
                if (pairImages) {
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
        int total = pairImages ? (pairN * count) : (promptList.size() * count);

        GenerationTask task = taskService.createTask(total);
        final File refFinal = refTempFile;
        final List<File> whiteFinal = whiteTempFiles;
        final List<File> pairRefsFinal = pairRefs;
        final int pairNFinal = pairN;
        final boolean hasImagesFinal = hasImages;
        final boolean pairFinal = pairImages;
        final List<String> promptsFinal = promptList;
        final List<String> thoughtsFinal = thoughts;

        taskService.submit(task, () -> {
            ExecutorService executor = imageGenerationService.getExecutor();
            List<CompletableFuture<String>> futures = new ArrayList<>();
            int[] idx = {1};

            if (pairFinal && hasImagesFinal) {
                for (int i = 0; i < pairNFinal; i++) {
                    final File refI = pairRefsFinal.get(i);
                    final String promptFinal = promptsFinal.get(i);
                    for (int c = 0; c < count; c++) {
                        final int n = idx[0]++;
                        final String outputPath = new File(outputDir, n + ".jpg").getAbsolutePath();
                        futures.add(CompletableFuture.supplyAsync(() -> {
                            if (task.isCancelled()) return "失败: 已取消";
                            boolean ok = imageGenerationService.generateImageMulti(
                                    promptFinal, List.of(refI.getAbsolutePath()), null, outputPath, agentId, aspect);
                            return ok ? outputPath : "失败: 生成未返回图片";
                        }, executor));
                    }
                }
            } else if (!hasImagesFinal) {
                for (String p : promptsFinal) {
                    for (int i = 0; i < count; i++) {
                        final int n = idx[0]++;
                        final String outputPath = new File(outputDir, n + ".jpg").getAbsolutePath();
                        final String promptFinal = p;
                        futures.add(CompletableFuture.supplyAsync(() -> {
                            if (task.isCancelled()) return "失败: 已取消";
                            boolean ok = imageGenerationService.generateImageMulti(
                                    promptFinal, List.of(), null, outputPath, agentId, aspect);
                            return ok ? outputPath : "失败: 生成未返回图片";
                        }, executor));
                    }
                }
            } else {
                List<String> allRefs = new ArrayList<>();
                allRefs.add(refFinal.getAbsolutePath());
                for (File wf : whiteFinal) allRefs.add(wf.getAbsolutePath());
                for (String p : promptsFinal) {
                    for (int i = 0; i < count; i++) {
                        final int n = idx[0]++;
                        final String outputPath = new File(outputDir, n + ".jpg").getAbsolutePath();
                        final String promptFinal = p;
                        final List<String> refsFinal = allRefs;
                        futures.add(CompletableFuture.supplyAsync(() -> {
                            if (task.isCancelled()) return "失败: 已取消";
                            boolean ok = imageGenerationService.generateImageMulti(
                                    promptFinal, refsFinal, null, outputPath, agentId, aspect);
                            return ok ? outputPath : "失败: 生成未返回图片";
                        }, executor));
                    }
                }
            }

            int n = 1;
            for (CompletableFuture<String> f : futures) {
                String r;
                try {
                    r = f.get(5, TimeUnit.MINUTES);
                } catch (TimeoutException te) {
                    f.cancel(true);
                    r = "失败: 单张图超时 (>5 分钟)";
                } catch (Exception e) {
                    r = "失败: " + e.getMessage();
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
            historyService.recordGeneration(sessionId, mode, promptJoined, agentId,
                    archive.refPaths, outputDir, configJson);
        } catch (Exception e) {
            log.warn("写历史记录失败（不影响生图）: {}", e.getMessage());
        }

        return ResponseEntity.ok(Map.of("taskId", task.getId(), "output_dir", outputDir));
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

            boolean ok = gptImageAgent.generateWithMask(prompt, imageTmp, maskTmp, outputPath);
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
