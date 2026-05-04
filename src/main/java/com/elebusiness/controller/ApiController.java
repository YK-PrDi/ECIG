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

    public ApiController(ConfigService configService, DingTalkService dingTalkService,
                         ImageGenerationService imageGenerationService,
                         AppProperties appProperties, TaskService taskService,
                         PromptService promptService) {
        this.configService = configService;
        this.dingTalkService = dingTalkService;
        this.imageGenerationService = imageGenerationService;
        this.appProperties = appProperties;
        this.taskService = taskService;
        this.promptService = promptService;
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
            @RequestParam("prompt") String prompt,
            @RequestParam(value = "count", defaultValue = "1") int count,
            @RequestParam(value = "agentId", defaultValue = "gemini") String agentId) {

        boolean hasImages = images != null && !images.isEmpty();

        // 纯文生图时 prompt 必填；有图片时 prompt 可为空（模型自行发挥）
        if (!hasImages && (prompt == null || prompt.isBlank())) {
            return ResponseEntity.badRequest().body(Map.of("error", "纯文生图模式需要提供提示词"));
        }

        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
        String outputDir = new File(appProperties.getPaths().getOutputDir(),
                "自定义生成/" + timestamp).getAbsolutePath();
        new File(outputDir).mkdirs();

        // images[0] = 参考图（可选），images[1..n] = 白底图（可选）
        File refTempFile = null;
        List<File> whiteTempFiles = new ArrayList<>();
        if (hasImages) {
            try {
                refTempFile = File.createTempFile("ref_", ".jpg");
                images.get(0).transferTo(refTempFile);
                for (int i = 1; i < images.size(); i++) {
                    File wf = File.createTempFile("white_" + i + "_", ".jpg");
                    images.get(i).transferTo(wf);
                    whiteTempFiles.add(wf);
                }
            } catch (Exception e) {
                return ResponseEntity.internalServerError()
                        .body(Map.of("error", "处理上传文件失败: " + e.getMessage()));
            }
        }

        // 构建所有任务后并发执行
        ExecutorService executor = imageGenerationService.getExecutor();
        List<CompletableFuture<String>> futures = new ArrayList<>();
        int[] idx = {1};

        if (!hasImages) {
            for (int i = 0; i < count; i++) {
                final String outputPath = new File(outputDir, idx[0]++ + ".jpg").getAbsolutePath();
                futures.add(CompletableFuture.supplyAsync(() -> {
                    boolean ok = imageGenerationService.generateImage(prompt, null, null, outputPath, agentId);
                    return ok ? outputPath : "失败: 生成未返回图片";
                }, executor));
            }
        } else {
            List<File> bgFiles = whiteTempFiles.isEmpty() ? List.of(refTempFile) : whiteTempFiles;
            final String refPath = refTempFile.getAbsolutePath();
            for (File bgFile : bgFiles) {
                for (int i = 0; i < count; i++) {
                    final File bg = bgFile;
                    final String outputPath = new File(outputDir, idx[0]++ + ".jpg").getAbsolutePath();
                    futures.add(CompletableFuture.supplyAsync(() -> {
                        boolean ok = imageGenerationService.generateImage(
                                prompt, refPath, bg.getAbsolutePath(), outputPath, agentId);
                        return ok ? outputPath : "失败: 生成未返回图片";
                    }, executor));
                }
            }
        }

        List<Object> results = new ArrayList<>();
        for (CompletableFuture<String> f : futures) {
            try { results.add(f.get()); }
            catch (Exception e) { results.add("失败: " + e.getMessage()); }
        }

        if (refTempFile != null) refTempFile.delete();
        for (File wf : whiteTempFiles) wf.delete();

        return ResponseEntity.ok(Map.of("results", results, "output_dir", outputDir));
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
        try {
            String apiKey  = appProperties.getGptImage().getApiKey();
            String baseUrl = appProperties.getGptImage().getBaseUrl();
            if (apiKey == null || apiKey.isBlank()) {
                return ResponseEntity.badRequest().body(Map.of("success", false, "error", "GPT-Image API Key 未配置"));
            }

            // 构造请求体，透传前端参数
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("model", "gpt-image-2");
            payload.put("prompt", body.getOrDefault("prompt", ""));
            payload.put("size",    body.getOrDefault("size",    "1024x1024"));
            payload.put("quality", body.getOrDefault("quality", "auto"));
            payload.put("background",   body.getOrDefault("background",   "auto"));
            payload.put("output_format", body.getOrDefault("output_format", "png"));

            String jsonBody = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(payload);

            URL url = new URL(baseUrl + "/v1/images/generations");
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("Authorization", "Bearer " + apiKey);
            conn.setDoOutput(true);
            conn.setConnectTimeout(30000);
            conn.setReadTimeout(120000);
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
                Map<String, Object> resp = new com.fasterxml.jackson.databind.ObjectMapper().readValue(respBody, Map.class);
                return ResponseEntity.ok(Map.of("success", true, "data", resp));
            } else {
                return ResponseEntity.status(status).body(Map.of("success", false, "error", respBody));
            }
        } catch (Exception e) {
            log.error("gptImageGenerate error: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError().body(Map.of("success", false, "error", e.getMessage()));
        }
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
}
