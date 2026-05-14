package com.elebusiness.controller;

import com.elebusiness.config.AppProperties;
import com.elebusiness.model.DingTalkRecord;
import com.elebusiness.model.GenerateRequest;
import com.elebusiness.model.GenerationTask;
import com.elebusiness.model.ProductInfo;
import com.elebusiness.service.ConfigService;
import com.elebusiness.service.DingTalkService;
import com.elebusiness.service.ImageGenerationService;
import com.elebusiness.service.PromptService;
import com.elebusiness.service.TaskService;
import com.elebusiness.service.agent.GptImageAgent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.CompletableFuture;

@RestController
public class ApiController {

    private static final Logger log = LoggerFactory.getLogger(ApiController.class);

    private final ConfigService configService;
    private final DingTalkService dingTalkService;
    private final ImageGenerationService imageGenerationService;
    private final AppProperties appProperties;
    private final TaskService taskService;
    private final PromptService promptService;
    private final GptImageAgent gptImageAgent;
    private final com.elebusiness.service.PromptCondenser promptCondenser;

    public ApiController(ConfigService configService, DingTalkService dingTalkService,
                         ImageGenerationService imageGenerationService,
                         AppProperties appProperties, TaskService taskService,
                         PromptService promptService, GptImageAgent gptImageAgent,
                         com.elebusiness.service.PromptCondenser promptCondenser) {
        this.configService = configService;
        this.dingTalkService = dingTalkService;
        this.imageGenerationService = imageGenerationService;
        this.appProperties = appProperties;
        this.taskService = taskService;
        this.promptService = promptService;
        this.gptImageAgent = gptImageAgent;
        this.promptCondenser = promptCondenser;
    }

    @GetMapping("/api/prompts")
    public List<Map<String, Object>> getPrompts() {
        return promptService.getTree();
    }

    @GetMapping("/api/agents")
    public List<Map<String, String>> listAgents() {
        return imageGenerationService.listAgents();
    }

    @GetMapping("/")
    public ResponseEntity<Void> index() {
        return ResponseEntity.status(302).header(HttpHeaders.LOCATION, "/index.html").build();
    }


    @GetMapping("/api/config")
    public Map<String, String> getConfig() {
        Map<String, String> result = new java.util.HashMap<>(configService.getDingTalkConfig());
        result.putAll(configService.getProxyConfig());
        return result;
    }

    @PostMapping("/api/config")
    public Map<String, Object> updateConfig(@RequestBody Map<String, String> body) {
        if (body == null || body.isEmpty()) {
            return Map.of("success", false, "error", "请求体为空");
        }
        configService.saveDingTalkConfig(body);
        // 代理配置（可选字段）
        if (body.containsKey("proxy_host")) {
            int port = 7890;
            try { port = Integer.parseInt(body.getOrDefault("proxy_port", "7890")); } catch (NumberFormatException ignored) {}
            configService.saveProxyConfig(body.get("proxy_host"), port);
        }
        dingTalkService.invalidateCache();
        return Map.of("success", true);
    }

    @GetMapping("/api/products")
    public ResponseEntity<Map<String, Object>> getProducts() {
        try {
            List<DingTalkRecord> records = dingTalkService.getAllRecords();
            List<Map<String, Object>> products = new ArrayList<>();
            for (DingTalkRecord record : records) {
                ProductInfo info = dingTalkService.parseProductInfo(record);
                if (!info.isHas123()) continue;
                Map<String, Object> product = new LinkedHashMap<>();
                product.put("id", record.getId());
                product.put("name", info.getName());
                product.put("category", info.getCategory());
                product.put("main_count", info.getMain().size());
                product.put("sku_count", info.getSku().size());
                products.add(product);
            }
            return ResponseEntity.ok(Map.of("products", products));
        } catch (Exception e) {
            log.error("获取产品列表失败: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError()
                    .body(Map.of("error", "获取数据失败: " + e.getMessage()));
        }
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
        GenerationTask task = taskService.createTask(selectedIds.size());

        taskService.submit(task, () -> {
            for (String recordId : selectedIds) {
                if (task.isCancelled()) break;

                DingTalkRecord record;
                try {
                    record = dingTalkService.getRecordById(recordId);
                } catch (Exception e) {
                    log.error("获取记录 {} 失败: {}", recordId, e.getMessage());
                    task.addResult(result(recordId, "error", "获取记录失败: " + e.getMessage(), null));
                    task.incrementProgress();
                    continue;
                }

                ProductInfo info = dingTalkService.parseProductInfo(record);
                String productName = info.getName();
                String category = info.getCategory();

                task.setCurrentProduct(productName);

                if (category == null || category.isBlank()) {
                    task.addResult(result(productName, "error", "未找到类别", null));
                    task.incrementProgress();
                    continue;
                }

                List<Map<String, Object>> attachments = record.getFieldAsImageList("图片和附件");
                String whiteBgUrl = null;
                for (Map<String, Object> img : attachments) {
                    if ("image".equals(img.get("type")) && img.get("url") != null) {
                        whiteBgUrl = img.get("url").toString();
                        break;
                    }
                }

                if (whiteBgUrl == null) {
                    task.addResult(result(productName, "error", "没有白底图", null));
                    task.incrementProgress();
                    continue;
                }

                File categoryDir = new File(appProperties.getPaths().getReferenceDir(), category);
                if (!categoryDir.exists()) {
                    task.addResult(result(productName, "error", "未找到类别 " + category + " 的参考图", null));
                    task.incrementProgress();
                    continue;
                }

                File[] refFolders = categoryDir.listFiles(
                        f -> f.isDirectory() && f.getName().startsWith("参考图"));
                if (refFolders == null || refFolders.length == 0) {
                    task.addResult(result(productName, "error", "未找到参考图文件夹", null));
                    task.incrementProgress();
                    continue;
                }

                File refPath = refFolders[new Random().nextInt(refFolders.length)];
                String cleanName = productName.replaceAll("（.+?）", "");
                String categoryOutputDir = new File(appProperties.getPaths().getOutputDir(), category).getAbsolutePath();
                int nextNum = imageGenerationService.getNextOutputNumber(categoryOutputDir, cleanName);
                String outputFolder = new File(categoryOutputDir, cleanName + "_" + nextNum).getAbsolutePath();
                new File(outputFolder).mkdirs();

                log.info("开始生成: {} -> {} [agent={}]", productName, outputFolder, agentId);

                int generatedCount = 0;

                if (!info.getSku().isEmpty()) {
                    int before = countImages(new File(outputFolder));
                    imageGenerationService.generateSkuImages(
                            whiteBgUrl, refPath.getAbsolutePath(), outputFolder, info.getSku(), agentId, userPrompt);
                    generatedCount += countImages(new File(outputFolder)) - before;
                }

                List<String> mainImgPaths = imageGenerationService.generateMainImages(
                        whiteBgUrl, refPath.getAbsolutePath(), outputFolder, info.getMain(), agentId, userPrompt);
                generatedCount += mainImgPaths.size();

                for (String mainImgPath : mainImgPaths) {
                    imageGenerationService.generateDetailImages(
                            mainImgPath, outputFolder, whiteBgUrl, refPath.getAbsolutePath(), agentId, userPrompt);
                }

                if (generatedCount == 0) {
                    task.addResult(result(productName, "error", "所有图片生成失败，请检查 API Key 或网络", outputFolder));
                } else {
                    task.addResult(result(productName, "success", null, outputFolder));
                }
                task.incrementProgress();
            }

            // 标准模式的思考信息：展示用户 prompt + 模型
            task.addResult(result("__AI_THOUGHT__0", "info",
                "【提示词直送（未经 LLM 处理）】\n模型: " + agentId
                + "\n\n【用户附加提示词】\n" + (userPrompt == null || userPrompt.isBlank() ? "（无）" : userPrompt)
                + "\n\n注：标准模式下每张图的最终 prompt 由系统根据产品类别、主图/SKU/详情场景模板拼装，详情见后台日志。",
                null));
        });

        return ResponseEntity.ok(Map.of("taskId", task.getId()));
    }

    /** 查询任务状态 */
    @GetMapping("/api/task/{taskId}")
    public ResponseEntity<Map<String, Object>> getTaskStatus(@PathVariable String taskId) {
        return taskService.getTask(taskId)
                .map(task -> {
                    Map<String, Object> resp = new LinkedHashMap<>();
                    resp.put("taskId", task.getId());
                    resp.put("status", task.getStatus());
                    resp.put("progress", task.getProgress());
                    resp.put("total", task.getTotal());
                    resp.put("currentProduct", task.getCurrentProduct());
                    resp.put("results", task.getResults());
                    return ResponseEntity.ok(resp);
                })
                .orElse(ResponseEntity.notFound().build());
    }

    /** 停止任务 */
    @PostMapping("/api/task/{taskId}/stop")
    public ResponseEntity<Map<String, Object>> stopTask(@PathVariable String taskId) {
        boolean ok = taskService.cancel(taskId);
        return ResponseEntity.ok(Map.of("success", ok));
    }

    @PostMapping("/api/custom_generate")
    public ResponseEntity<Map<String, Object>> customGenerate(
            @RequestParam(value = "images", required = false) List<MultipartFile> images,
            @RequestParam("prompt") List<String> prompts,
            @RequestParam(value = "count", defaultValue = "1") int count,
            @RequestParam(value = "agentId", defaultValue = "gemini") String agentId,
            @RequestParam(value = "pairImages", defaultValue = "false") boolean pairImages,
            @RequestParam(value = "aspect", required = false) String aspect) {

        boolean hasImages = images != null && !images.isEmpty();

        // 过滤空 prompt
        List<String> promptList = new ArrayList<>();
        if (prompts != null) for (String p : prompts) if (p != null && !p.isBlank()) promptList.add(p);
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
                com.elebusiness.service.PromptCondenser.Condensed c = promptCondenser.condenseDetailed(p);
                compressed.add(c.compressed());
                // thought 为空 ⇒ 未触发 Gemini 压缩（短路/缓存未命中且 key 未配/未超阈值）：
                // 按"提示词直送"格式展示，避免误导用户以为走过 LLM
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
            // 其他 agent：不走 LLM 压缩，但展示"实际发给模型的完整 prompt"作为思考信息
            for (String p : promptList) {
                thoughts.add(
                    "【提示词直送（未经 LLM 处理）】\n模型: " + agentId
                    + "\n长度: " + p.length() + " 字\n\n【最终提示词】\n" + p
                );
            }
        }

        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
        String outputDir = new File(appProperties.getPaths().getOutputDir(),
                "自定义模式生成/" + timestamp).getAbsolutePath();
        new File(outputDir).mkdirs();

        // multipart 必须在请求线程内落盘，提交到异步任务前先把上传文件持久化
        //  - 默认模式：第 1 张作为 ref，其余作为 white/背景（与 ref 做 bgGroups 笛卡尔积）
        //  - pairImages=true：所有上传图各自独立作为 ref，不做 bgGroups 扩展
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

        // pairImages 模式：N = min(len(images), len(prompts))，长的一方多出的丢弃
        // 非配对有图模式：所有上传图联合作为 image[]，每个 prompt 出 count 张（不再做 bgGroups 笛卡尔积）
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
                // 配对模式：images[i] × prompts[i]，共生成 N 张；多余部分丢弃
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
                // 非配对、有图：把所有上传图作为 image[] 联合参考（增强品牌/产品一致性）
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

            // 单张图硬超时 5 分钟，挂死任务直接放弃，不再拖累整体进度
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
                    task.addResult(result(name, "error", r, null));
                } else {
                    task.addResult(result(name, "success", null, r));
                }
                task.incrementProgress();
            }

            // 把输出目录作为最后一条 info 结果，便于前端调 /api/gallery 渲染
            task.addResult(result("__OUTPUT_DIR__", "info", null, outputDir));

            // 把思考过程塞进 results，前端渲染为可折叠灰字块
            for (int i = 0; i < thoughtsFinal.size(); i++) {
                task.addResult(result("__AI_THOUGHT__" + i, "info", thoughtsFinal.get(i), null));
            }

            if (refFinal != null) refFinal.delete();
            for (File wf : whiteFinal) wf.delete();
            for (File pr : pairRefsFinal) pr.delete();
        });

        return ResponseEntity.ok(Map.of("taskId", task.getId(), "output_dir", outputDir));
    }

    @PostMapping("/api/inpaint")
    public ResponseEntity<Map<String, Object>> inpaint(
            @RequestParam("image")  MultipartFile image,
            @RequestParam("mask")   MultipartFile mask,
            @RequestParam("prompt") String prompt) {

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
        String outputDir = new File(appProperties.getPaths().getOutputDir(),
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
            return ResponseEntity.ok(Map.of(
                "results", List.of(outputPath),
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

    @GetMapping("/api/image")
    public ResponseEntity<FileSystemResource> getImage(@RequestParam String path) {
        File file = new File(path);
        if (!file.exists() || !file.isFile()) return ResponseEntity.notFound().build();
        String mimeType = path.toLowerCase().endsWith(".png") ? "image/png" : "image/jpeg";
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(mimeType))
                .body(new FileSystemResource(file));
    }

    @GetMapping("/api/download")
    public ResponseEntity<FileSystemResource> downloadImage(@RequestParam String path) {
        File file = new File(path);
        if (!file.exists() || !file.isFile()) return ResponseEntity.notFound().build();
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + file.getName() + "\"")
                .body(new FileSystemResource(file));
    }

    private Map<String, Object> result(String name, String status, String message, String output) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", name);
        m.put("status", status);
        if (message != null) m.put("message", message);
        if (output != null) m.put("output", output);
        return m;
    }

    // ── 画廊：列出目录 ──────────────────────────────────────────────────

    @GetMapping("/api/gallery")
    public ResponseEntity<Map<String, Object>> listGallery(
            @RequestParam(required = false) String path) {
        try {
            File rootDir = new File(appProperties.getPaths().getOutputDir()).getCanonicalFile();
            File target = (path == null || path.isBlank())
                    ? rootDir
                    : new File(path).getCanonicalFile();

            // 安全检查：目标必须在 outputDir 内
            if (!target.getAbsolutePath().startsWith(rootDir.getAbsolutePath())) {
                return ResponseEntity.badRequest().body(Map.of("error", "非法路径"));
            }
            if (!target.exists()) {
                return ResponseEntity.ok(Map.of("path", target.getAbsolutePath(), "items", List.of()));
            }

            File[] children = target.listFiles();
            List<Map<String, Object>> items = new ArrayList<>();
            if (children != null) {
                Arrays.sort(children, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
                for (File child : children) {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("name", child.getName());
                    item.put("path", child.getAbsolutePath());
                    item.put("modified", child.lastModified());
                    if (child.isDirectory()) {
                        item.put("type", "folder");
                        item.put("count", countImages(child));
                        String thumb = findFirstImage(child);
                        if (thumb != null) item.put("thumbnail", thumb);
                    } else {
                        String lname = child.getName().toLowerCase();
                        if (lname.endsWith(".jpg") || lname.endsWith(".jpeg") || lname.endsWith(".png")) {
                            item.put("type", "image");
                        } else {
                            continue; // 跳过非图片文件
                        }
                    }
                    items.add(item);
                }
            }
            Map<String, Object> resp = new LinkedHashMap<>();
            resp.put("path", target.getAbsolutePath());
            resp.put("root", rootDir.getAbsolutePath());
            resp.put("items", items);
            return ResponseEntity.ok(resp);
        } catch (Exception e) {
            log.error("listGallery error: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    @DeleteMapping("/api/gallery")
    public ResponseEntity<Map<String, Object>> deleteGalleryItem(@RequestParam String path) {
        try {
            File rootDir = new File(appProperties.getPaths().getOutputDir()).getCanonicalFile();
            File target = new File(path).getCanonicalFile();
            if (!target.getAbsolutePath().startsWith(rootDir.getAbsolutePath())) {
                return ResponseEntity.badRequest().body(Map.of("success", false, "error", "非法路径"));
            }
            if (!target.exists()) {
                return ResponseEntity.ok(Map.of("success", false, "error", "文件不存在"));
            }
            deleteRecursively(target);
            return ResponseEntity.ok(Map.of("success", true));
        } catch (Exception e) {
            log.error("deleteGalleryItem error: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError().body(Map.of("success", false, "error", e.getMessage()));
        }
    }

    // ── GPT-Image API ──

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

        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
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
                String respBody = new String(
                    (status >= 200 && status < 300 ? conn.getInputStream() : conn.getErrorStream()).readAllBytes(),
                    StandardCharsets.UTF_8
                );
                conn.disconnect();

                if (status >= 200 && status < 300) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> resp = mapper.readValue(respBody, Map.class);
                    return ResponseEntity.ok(Map.of("success", true, "data", resp));
                }
                lastStatus = status;
                lastError = respBody;
                // 429 限流 / 5xx 服务端问题 → 换下一个 key；其他 4xx 直接终止
                if (status != 429 && status < 500) {
                    return ResponseEntity.status(status).body(Map.of("success", false, "error", respBody));
                }
                log.warn("GPT-Image key 尾号[{}] 返回 {}，尝试下一个",
                        apiKey.length() > 6 ? apiKey.substring(apiKey.length() - 6) : apiKey, status);
            } catch (Exception e) {
                lastError = e.getMessage();
                log.warn("GPT-Image key 尾号[{}] 异常: {}",
                        apiKey.length() > 6 ? apiKey.substring(apiKey.length() - 6) : apiKey, e.getMessage());
            }
        }
        log.error("gptImageGenerate 所有 key 均失败: {}", lastError);
        return ResponseEntity.status(lastStatus).body(Map.of("success", false, "error", lastError));
    }

    private int countImages(File dir) {
        int count = 0;
        File[] children = dir.listFiles();
        if (children == null) return 0;
        for (File child : children) {
            if (child.isDirectory()) {
                count += countImages(child);
            } else {
                String n = child.getName().toLowerCase();
                if (n.endsWith(".jpg") || n.endsWith(".jpeg") || n.endsWith(".png")) count++;
            }
        }
        return count;
    }

    private String findFirstImage(File dir) {
        File[] children = dir.listFiles();
        if (children == null) return null;
        Arrays.sort(children);
        for (File child : children) {
            if (child.isFile()) {
                String n = child.getName().toLowerCase();
                if (n.endsWith(".jpg") || n.endsWith(".jpeg") || n.endsWith(".png")) {
                    return child.getAbsolutePath();
                }
            }
        }
        for (File child : children) {
            if (child.isDirectory()) {
                String found = findFirstImage(child);
                if (found != null) return found;
            }
        }
        return null;
    }

    private void deleteRecursively(File file) {
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) for (File child : children) deleteRecursively(child);
        }
        file.delete();
    }

    @PostMapping("/api/feedback")
    public ResponseEntity<Map<String, Object>> saveFeedback(@RequestBody Map<String, Object> body) {
        try {
            String prompt    = String.valueOf(body.getOrDefault("prompt", ""));
            String imagePath = String.valueOf(body.getOrDefault("imagePath", ""));
            String rating    = String.valueOf(body.getOrDefault("rating", ""));
            String time      = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));

            String line = String.format("[%s] %s | 图片: %s | Prompt: %s%n", time, rating, imagePath, prompt);

            File dir = new File(appProperties.getPaths().getOutputDir());
            dir.mkdirs();
            File feedbackFile = new File(dir, "feedback.txt");
            java.nio.file.Files.writeString(feedbackFile.toPath(), line,
                    StandardCharsets.UTF_8,
                    java.nio.file.StandardOpenOption.CREATE,
                    java.nio.file.StandardOpenOption.APPEND);

            return ResponseEntity.ok(Map.of("success", true));
        } catch (Exception e) {
            return ResponseEntity.ok(Map.of("success", false, "error", e.getMessage()));
        }
    }
}
