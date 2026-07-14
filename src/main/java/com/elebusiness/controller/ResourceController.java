package com.elebusiness.controller;

import com.elebusiness.config.AppProperties;
import com.elebusiness.model.DingTalkRecord;
import com.elebusiness.model.ProductInfo;
import com.elebusiness.service.DingTalkService;
import com.elebusiness.service.HistoryService;
import com.elebusiness.service.UserPrefsService;
import com.elebusiness.service.auth.CurrentUserService;
import com.elebusiness.service.workspace.UserStorageService;
import jakarta.servlet.http.HttpSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * 静态资源 / 产品列表 / 画廊 / 反馈 / xlsx 导入相关接口。
 * 从 ApiController 拆出（A.1 重构），业务逻辑零变动。
 */
@RestController
public class ResourceController {

    private static final Logger log = LoggerFactory.getLogger(ResourceController.class);

    private final DingTalkService dingTalkService;
    private final AppProperties appProperties;
    private final UserPrefsService userPrefsService;
    private final HistoryService historyService;
    private final CurrentUserService currentUserService;
    private final UserStorageService userStorageService;

    public ResourceController(DingTalkService dingTalkService, AppProperties appProperties,
                              UserPrefsService userPrefsService, HistoryService historyService,
                              CurrentUserService currentUserService, UserStorageService userStorageService) {
        this.dingTalkService = dingTalkService;
        this.appProperties = appProperties;
        this.userPrefsService = userPrefsService;
        this.historyService = historyService;
        this.currentUserService = currentUserService;
        this.userStorageService = userStorageService;
    }

    @GetMapping("/api/products")
    public ResponseEntity<Map<String, Object>> getProducts() {
        if (!dingTalkService.isConfigured()) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of(
                    "error", "钉钉产品目录尚未配置，请在设置中完成钉钉参数后再同步",
                    "code", "DINGTALK_NOT_CONFIGURED",
                    "products", List.of()
            ));
        }
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
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                    .body(Map.of("error", "钉钉产品目录同步失败: " + e.getMessage(),
                            "code", "DINGTALK_PRODUCTS_UNAVAILABLE"));
        }
    }

    @GetMapping("/api/image")
    public ResponseEntity<FileSystemResource> getImage(@RequestParam String path, HttpSession httpSession) {
        long userId = currentUserService.requireUserId(httpSession);
        File file = new File(path).getAbsoluteFile();
        if (!userStorageService.isInsideUserFiles(userId, file.toPath())) {
            return ResponseEntity.badRequest().build();
        }
        if (!file.exists() || !file.isFile()) return ResponseEntity.notFound().build();
        String lower = path.toLowerCase();
        String mimeType = lower.endsWith(".png") ? "image/png"
                : lower.endsWith(".webp") ? "image/webp"
                : "image/jpeg";
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(mimeType))
                .body(new FileSystemResource(file));
    }

    @GetMapping("/api/download")
    public ResponseEntity<FileSystemResource> downloadImage(@RequestParam String path, HttpSession httpSession) {
        long userId = currentUserService.requireUserId(httpSession);
        File file = new File(path).getAbsoluteFile();
        if (!userStorageService.isInsideUserFiles(userId, file.toPath())) {
            return ResponseEntity.badRequest().build();
        }
        if (!file.exists() || !file.isFile()) return ResponseEntity.notFound().build();
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + file.getName() + "\"")
                .body(new FileSystemResource(file));
    }

    @GetMapping("/api/proxy-download")
    public ResponseEntity<byte[]> proxyDownload(@RequestParam String url,
                                                 @RequestParam(required = false) String filename) {
        try {
            if (!url.startsWith("http://") && !url.startsWith("https://")) {
                return ResponseEntity.badRequest().build();
            }
            java.net.HttpURLConnection conn = (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
            conn.setConnectTimeout(15_000);
            conn.setReadTimeout(60_000);
            conn.setRequestProperty("User-Agent", "Mozilla/5.0");
            byte[] bytes;
            try (java.io.InputStream in = conn.getInputStream()) {
                bytes = in.readAllBytes();
            } finally {
                conn.disconnect();
            }
            String name = filename;
            if (name == null || name.isBlank()) {
                name = url.substring(url.lastIndexOf('/') + 1);
                if (!name.contains(".")) name = name + ".jpg";
            }
            String encodedName = java.net.URLEncoder.encode(name, StandardCharsets.UTF_8).replace("+", "%20");
            return ResponseEntity.ok()
                    .contentType(MediaType.APPLICATION_OCTET_STREAM)
                    .header(HttpHeaders.CONTENT_DISPOSITION,
                            "attachment; filename*=UTF-8''" + encodedName)
                    .body(bytes);
        } catch (Exception e) {
            log.error("proxy-download 失败: {}", e.getMessage());
            return ResponseEntity.status(502).build();
        }
    }

    // ── 画廊：列出目录 ──────────────────────────────────────────────────

    @GetMapping("/api/gallery")
    public ResponseEntity<Map<String, Object>> listGallery(
            @RequestParam(required = false) String path,
            HttpSession httpSession) {
        long userId = currentUserService.requireUserId(httpSession);
        try {
            Path galleryRoot = normalizeAbsolutePath(
                    userPrefsService.resolveOutputDir(userId, appProperties.getPaths().getOutputDir()).getPath());
            Path tempRoot = userStorageService.tempOutputRoot(userId).toAbsolutePath().normalize();
            Path targetPath = (path == null || path.isBlank())
                    ? galleryRoot
                    : normalizeAbsolutePath(path);
            File target = targetPath.toFile();
            File rootDir = galleryRoot.toFile();

            // 安全检查：目标必须在 outputDir 内
            if (!targetPath.startsWith(galleryRoot) && !targetPath.startsWith(tempRoot)) {
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
                        item.put("count", ControllerHelpers.countImages(child));
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
            resp.put("tempRoot", tempRoot.toString());
            resp.put("items", items);
            return ResponseEntity.ok(resp);
        } catch (Exception e) {
            log.error("listGallery error: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    static Path normalizeAbsolutePath(String rawPath) {
        return java.nio.file.Paths.get(rawPath).toAbsolutePath().normalize();
    }

    /**
     * 列出所有可用品类 .js 文件 slug，前端 boot 时按这个清单 dynamic import。
     * 来源：userDataDir/data/categories/（用户导入产物，打包态可写）+ 静态 frontend/data/categories/（内置）
     * 同名 slug 时 userDataDir 优先（WebMvcConfig 已经把它放第一位 serve）。
     */
    @GetMapping("/api/categories/index")
    public ResponseEntity<Map<String, Object>> categoriesIndex() {
        java.util.Set<String> slugs = new java.util.LinkedHashSet<>();
        // 用户目录（如果存在）
        try {
            String userData = appProperties.getPaths().getUserDataDir();
            if (userData != null && !userData.isBlank()) {
                File userCat = new File(userData, "data/categories");
                File[] kids = userCat.listFiles();
                if (kids != null) for (File k : kids) {
                    String n = k.getName();
                    if (k.isFile() && n.endsWith(".js")) slugs.add(n.substring(0, n.length() - 3));
                }
            }
        } catch (Exception e) {
            log.warn("枚举用户品类目录失败: {}", e.getMessage());
        }
        // 内置 frontend/data/categories/
        // 源码态：user.dir/frontend/data/categories/
        // 打包态：electron 通过 -Dspring.web.resources.static-locations=file:<resourcesPath>/frontend/ 注入，
        //         从该属性解析出 frontend 根目录，再拼 data/categories/
        try {
            File builtIn = resolveBuiltInCategoriesDir();
            if (builtIn != null) {
                File[] kids = builtIn.listFiles();
                if (kids != null) for (File k : kids) {
                    String n = k.getName();
                    if (k.isFile() && n.endsWith(".js")) slugs.add(n.substring(0, n.length() - 3));
                }
            }
        } catch (Exception e) {
            log.warn("枚举内置品类目录失败: {}", e.getMessage());
        }
        return ResponseEntity.ok(Map.of("slugs", new ArrayList<>(slugs)));
    }

    @DeleteMapping("/api/gallery")
    public ResponseEntity<Map<String, Object>> deleteGalleryItem(@RequestParam String path, HttpSession httpSession) {
        long userId = currentUserService.requireUserId(httpSession);
        try {
            File rootDir = userPrefsService.resolveOutputDir(userId, appProperties.getPaths().getOutputDir()).getCanonicalFile();
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

    /**
     * Phase 1：用户在前端点 💾 时调用，把临时归档里的图/视频 copy 到永久 outputDir。
     * 不删原文件 —— 让 cleaner 在 2h TTL 时自然清理；这样用户多次点 💾 也安全。
     * body: { tempPath: 临时绝对路径, subDir?: 永久目录下的子目录名（默认按日期 yyyyMMdd） }
     */
    @PostMapping("/api/save-to-gallery")
    public ResponseEntity<Map<String, Object>> saveToGallery(@RequestBody Map<String, String> body, HttpSession httpSession) {
        long userId = currentUserService.requireUserId(httpSession);
        String tempPath = body == null ? null : body.get("tempPath");
        if (tempPath == null || tempPath.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "error", "缺少 tempPath"));
        }
        log.info("save-to-gallery 请求: tempPath={}, subDir={}", tempPath, body.get("subDir"));
        try {
            // 如果 tempPath 是 URL（COS 等），直接下载到 gallery
            if (tempPath.startsWith("http://") || tempPath.startsWith("https://")) {
                String subDir = body.get("subDir");
                if (subDir == null || subDir.isBlank()) {
                    subDir = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"));
                }
                File baseDir = userPrefsService.resolveOutputDir(userId, appProperties.getPaths().getOutputDir());
                log.info("解析输出目录: {}", baseDir.getAbsolutePath());
                File targetDir = new File(baseDir, subDir);
                if (!targetDir.exists()) {
                    boolean created = targetDir.mkdirs();
                    if (!created && !targetDir.exists()) {
                        log.error("无法创建目标目录: {}", targetDir.getAbsolutePath());
                        return ResponseEntity.ok(Map.of("success", false,
                            "error", "无法创建保存目录：" + targetDir.getAbsolutePath() + "。请检查磁盘权限或在设置中更改保存位置。"));
                    }
                }
                String urlName = tempPath.substring(tempPath.lastIndexOf('/') + 1);
                if (!urlName.contains(".")) urlName = urlName + ".jpg";
                File target = new File(targetDir, urlName);
                int dotIdx = urlName.lastIndexOf('.');
                String base = (dotIdx > 0) ? urlName.substring(0, dotIdx) : urlName;
                String ext  = (dotIdx > 0) ? urlName.substring(dotIdx)    : "";
                int suffix = 1;
                while (target.exists()) { target = new File(targetDir, base + "_" + suffix + ext); suffix++; }
                java.net.HttpURLConnection conn = (java.net.HttpURLConnection) new java.net.URL(tempPath).openConnection();
                conn.setConnectTimeout(15_000); conn.setReadTimeout(60_000);
                // 图床（COS/CDN）常对裸服务器请求做防盗链 → 返回 403。带上 UA 并跟随重定向，
                // 与 /api/proxy-download 的处理保持一致。
                conn.setRequestProperty("User-Agent", "Mozilla/5.0");
                conn.setInstanceFollowRedirects(true);
                try (java.io.InputStream in = conn.getInputStream()) {
                    java.nio.file.Files.copy(in, target.toPath());
                } finally { conn.disconnect(); }
                log.info("save-to-gallery (url): {} -> {}", tempPath, target.getAbsolutePath());
                return ResponseEntity.ok(Map.of("success", true, "savedPath", target.getAbsolutePath(), "savedName", target.getName()));
            }
            // 用 Path.normalize().toAbsolutePath() 替代 File.getCanonicalFile() —— 后者在 Windows 上
            // 走 native WinNTFileSystem.canonicalize0，对 "./" + 中文路径 + 正斜杠 的组合偶发抛
            // IOException("文件名、目录名或卷标语法不正确")。Path.normalize 是纯字符串规范化，跨平台稳定。
            // 前端把 \\ 替换成 / 后路径形如 D:/code/ele-business-java/./.temp-output/自定义模式生成/.../1.jpg，
            // normalize 会把 ./ 抹掉，得到干净的绝对路径。
            java.nio.file.Path tempRoot = userStorageService.tempOutputRoot(userId)
                    .toAbsolutePath().normalize();
            java.nio.file.Path source = java.nio.file.Paths.get(tempPath)
                    .toAbsolutePath().normalize();

            // 详细日志：帮助诊断打包后路径校验失败的问题
            log.info("路径校验 - tempPath原始: {}", tempPath);
            log.info("路径校验 - tempRoot: {}", tempRoot);
            log.info("路径校验 - source标准化: {}", source);
            log.info("路径校验 - source.startsWith(tempRoot): {}", source.startsWith(tempRoot));

            // 安全检查：source 必须在 tempOutputDir 内，防路径穿越
            if (!source.startsWith(tempRoot)) {
                log.error("路径校验失败 - source: {}, tempRoot: {}", source, tempRoot);
                return ResponseEntity.badRequest().body(Map.of("success", false,
                    "error", "非法路径：必须在临时归档目录内。source=" + source + ", tempRoot=" + tempRoot));
            }
            if (!java.nio.file.Files.exists(source) || !java.nio.file.Files.isRegularFile(source)) {
                log.warn("源文件不存在: {}", source);
                return ResponseEntity.ok(Map.of("success", false,
                    "error", "源文件不存在或已被清理（临时文件 2 小时后自动删除）。路径：" + source));
            }
            String subDir = body.get("subDir");
            if (subDir == null || subDir.isBlank()) {
                subDir = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"));
            }
            // 用户在 ⚙️ 设置 里自选的保存位置；空 / 不可写时 resolveOutputDir 自动 fallback 到默认 output-dir (A2)
            File baseDir = userPrefsService.resolveOutputDir(userId, appProperties.getPaths().getOutputDir());
            log.info("解析输出目录: {}", baseDir.getAbsolutePath());
            File targetDir = new File(baseDir, subDir);
            if (!targetDir.exists()) {
                boolean created = targetDir.mkdirs();
                if (!created && !targetDir.exists()) {
                    log.error("无法创建目标目录: {}", targetDir.getAbsolutePath());
                    return ResponseEntity.ok(Map.of("success", false,
                        "error", "无法创建保存目录：" + targetDir.getAbsolutePath() + "。请检查磁盘权限或在设置中更改保存位置。"));
                }
            }
            // 同名冲突时加 _1 / _2 后缀，绝不覆盖
            String name = source.getFileName().toString();
            File target = new File(targetDir, name);
            int dotIdx = name.lastIndexOf('.');
            String base = (dotIdx > 0) ? name.substring(0, dotIdx) : name;
            String ext  = (dotIdx > 0) ? name.substring(dotIdx)    : "";
            int suffix = 1;
            while (target.exists()) {
                target = new File(targetDir, base + "_" + suffix + ext);
                suffix++;
            }
            java.nio.file.Files.copy(source, target.toPath());
            log.info("save-to-gallery: {} -> {}", source, target.getAbsolutePath());
            // Phase 2：回填 savedPath 到对应历史条目（按批次目录匹配；outputPath 存的是批次目录）
            try {
                java.nio.file.Path batchDir = source.getParent();
                if (batchDir != null) {
                    historyService.markSaved(userId, batchDir.toString(), target.getAbsolutePath());
                }
            } catch (Exception markErr) {
                log.warn("markSaved 失败（不影响保存主流程）: {}", markErr.getMessage());
            }
            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "savedPath", target.getAbsolutePath(),
                    "savedName", target.getName()
            ));
        } catch (Exception e) {
            log.error("save-to-gallery 失败: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError()
                    .body(Map.of("success", false, "error", e.getMessage()));
        }
    }

    @PostMapping("/api/feedback")
    public ResponseEntity<Map<String, Object>> saveFeedback(@RequestBody Map<String, Object> body,
                                                            HttpSession httpSession) {
        long userId = currentUserService.requireUserId(httpSession);
        try {
            Map<String, Object> safeBody = body == null ? Map.of() : body;
            String prompt    = String.valueOf(safeBody.getOrDefault("prompt", ""));
            String imagePath = String.valueOf(safeBody.getOrDefault("imagePath", ""));
            String rating    = String.valueOf(safeBody.getOrDefault("rating", ""));
            String time      = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));

            String line = String.format("[%s] %s | 图片: %s | Prompt: %s%n", time, rating, imagePath, prompt);

            Path feedbackFile = userStorageService.ensureDirectory(userStorageService.filesRoot(userId))
                    .resolve("feedback.txt");
            java.nio.file.Files.writeString(feedbackFile, line,
                    StandardCharsets.UTF_8,
                    java.nio.file.StandardOpenOption.CREATE,
                    java.nio.file.StandardOpenOption.APPEND);

            return ResponseEntity.ok(Map.of("success", true));
        } catch (Exception e) {
            return ResponseEntity.ok(Map.of("success", false, "error", e.getMessage()));
        }
    }

    /** 把上传的 xlsx 落盘到项目根，调用 import-tool.exe（或 python tools/import_category_xlsx.py），返回脚本 stdout。 */
    @PostMapping("/api/import/xlsx")
    public ResponseEntity<Map<String, Object>> importXlsx(@RequestParam("files") List<MultipartFile> files) {
        // 打包态：写到 userDataDir/data/categories/（外部可写）；源码态写 frontend/data/categories/
        boolean packaged = "true".equalsIgnoreCase(System.getProperty("app.packaged", "false"));
        File outDir = null;
        if (packaged) {
            String userData = appProperties.getPaths().getUserDataDir();
            if (userData == null || userData.isBlank()) {
                return ResponseEntity.ok(Map.of("success", false,
                        "error", "缺少 user-data-dir 配置；请联系开发者更新到支持外部写入的版本"));
            }
            outDir = new File(userData).getAbsoluteFile();
        }
        File projectRoot = new File(System.getProperty("user.dir"));
        // 打包态：import-tool.exe 在 resources/ 下（通过 extraResources 打入）
        // 源码态：tools/dist/import-tool.exe 或项目根 import-tool.exe
        String resourcesPath = System.getProperty("app.resources-path");
        File exeInResources = (resourcesPath != null && !resourcesPath.isBlank())
                ? new File(resourcesPath, "import-tool.exe") : null;
        File pyScript = new File(projectRoot, "tools/import_category_xlsx.py");
        File exeProd = new File(projectRoot, "import-tool.exe");
        File exeDev  = new File(projectRoot, "tools/dist/import-tool.exe");
        File runner;
        boolean useExe = true;
        // 优先级：resources/import-tool.exe（打包态）> python 脚本 > 项目根 exe > tools/dist exe
        if (exeInResources != null && exeInResources.exists()) runner = exeInResources;
        else if (pyScript.exists() && hasPython())  { runner = pyScript; useExe = false; }
        else if (exeProd.exists())             runner = exeProd;
        else if (exeDev.exists())              runner = exeDev;
        else if (pyScript.exists()){ runner = pyScript; useExe = false; }
        else {
            return ResponseEntity.ok(Map.of("success", false,
                    "error", "找不到 import-tool.exe（应在项目根或 tools/dist/）；也找不到 tools/import_category_xlsx.py。"));
        }
        List<String> savedPaths = new ArrayList<>();
        // 上传文件落到 .uploads/{时间戳}_{原名}，绝不跟项目根的同名文件冲突。
        // 之前直接保存到项目根（覆盖原文件）时，只要任何进程（Excel 预览窗格、杀软、
        // OneDrive、Windows Search Indexer）持有 15.xlsx 的句柄就会 FileSystemException。
        File uploadDir = new File(projectRoot, ".uploads");
        uploadDir.mkdirs();
        String ts = java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
        try {
            int idx = 0;
            for (MultipartFile mf : files) {
                String original = mf.getOriginalFilename();
                if (original == null || original.isBlank()) original = "upload.xlsx";
                // 用时间戳 + 序号前缀，同一秒内多文件也不会重名
                String safeName = ts + "_" + (idx++) + "_" + original;
                File target = new File(uploadDir, safeName);
                // 不用 mf.transferTo —— Tomcat 内部 commons-fileupload 会走 part.write，
                // 当 storeLocation 为 null 时会抛 "Cannot write uploaded file to disk"。
                // Files.copy(InputStream) 直接流式落盘，绕过这条路径。
                try (java.io.InputStream is = mf.getInputStream()) {
                    java.nio.file.Files.copy(is, target.toPath(),
                            java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
                // 给 import 脚本的是 ".uploads/xxx.xlsx" 相对路径（脚本以 projectRoot 为 cwd）
                savedPaths.add(".uploads/" + safeName);
            }
        } catch (java.nio.file.FileSystemException e) {
            // 改用 .uploads 子目录后这条几乎不会再触发，留作兜底（极端情况：磁盘只读 / 杀软拦写入）
            log.warn("xlsx 上传时目标文件被占用: {}", e.getFile());
            String fname = new java.io.File(e.getFile() == null ? "" : e.getFile()).getName();
            return ResponseEntity.ok(Map.of("success", false, "error",
                    "文件 " + fname + " 写入被拒（可能磁盘只读 / 杀软拦截）。请检查 .uploads 目录权限后重试。"));
        } catch (Exception e) {
            log.error("xlsx 上传保存失败: {}", e.getMessage(), e);
            return ResponseEntity.ok(Map.of("success", false, "error", "保存上传文件失败：" + e.getMessage()));
        }
        List<String> cmd = new ArrayList<>();
        if (useExe) {
            cmd.add(runner.getAbsolutePath());
        } else {
            cmd.add(System.getenv().getOrDefault("PYTHON", "python"));
            cmd.add("tools/import_category_xlsx.py");
        }
        // 打包态把外部 dataDir 注入；源码态保持脚本默认行为（写 frontend/data/）
        if (outDir != null) {
            cmd.add("--out-dir");
            cmd.add(outDir.getAbsolutePath());
        }
        cmd.addAll(savedPaths);
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd)
                    .directory(projectRoot)
                    .redirectErrorStream(true);
            pb.environment().put("PYTHONIOENCODING", "utf-8");
            Process proc = pb.start();
            // try-with-resources 关 stdout 流；Process.destroyForcibly 不保证关闭关联 stream（B 阶段审查 #3）
            String output;
            try (java.io.InputStream is = proc.getInputStream()) {
                output = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            }
            boolean done = proc.waitFor(120, java.util.concurrent.TimeUnit.SECONDS);
            int exit = done ? proc.exitValue() : -1;
            if (!done) proc.destroyForcibly();
            return ResponseEntity.ok(Map.of(
                    "success", exit == 0,
                    "exitCode", exit,
                    "runner", runner.getName(),
                    "output", output,
                    "savedFiles", savedPaths
            ));
        } catch (Exception e) {
            return ResponseEntity.ok(Map.of("success", false, "error",
                    "执行 import 失败：" + e.getMessage()));
        }
    }

    // ── 私有辅助方法（首图查找、递归删除）；countImages 已抽到 ControllerHelpers（B 阶段审查 #10） ──

    /**
     * 定位内置 frontend/data/categories/ 目录。
     * 打包态：electron 通过 -Dspring.web.resources.static-locations=file:<path>/frontend/ 注入，
     *         从该属性解析出 frontend 根目录。
     * 源码态：user.dir/frontend/data/categories/
     */
    private File resolveBuiltInCategoriesDir() {
        // 优先从 spring.web.resources.static-locations 解析（打包态 electron 注入的绝对路径）
        String staticLoc = System.getProperty("spring.web.resources.static-locations");
        if (staticLoc != null && !staticLoc.isBlank()) {
            // 格式：file:/path/to/frontend/  或  file:/path/to/frontend/,classpath:/static/
            for (String loc : staticLoc.split(",")) {
                loc = loc.trim();
                if (loc.startsWith("file:")) {
                    String dir = loc.substring(5).replaceAll("[/\\\\]+$", "");
                    File catDir = new File(dir, "data/categories");
                    if (catDir.isDirectory()) return catDir;
                }
            }
        }
        // 源码态 fallback
        return new File(System.getProperty("user.dir"), "frontend/data/categories");
    }

    /** 检测系统是否有可用的 python 解释器。优先 PYTHON 环境变量，再退回 python --version。 */
    private boolean hasPython() {
        String envPy = System.getenv("PYTHON");
        String cmd = (envPy != null && !envPy.isBlank()) ? envPy : "python";
        try {
            Process p = new ProcessBuilder(cmd, "--version").redirectErrorStream(true).start();
            // 5 秒够检测 python --version 是否能跑（超时也算没装）
            boolean done = p.waitFor(5, java.util.concurrent.TimeUnit.SECONDS);
            if (!done) { p.destroyForcibly(); return false; }
            return p.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
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
