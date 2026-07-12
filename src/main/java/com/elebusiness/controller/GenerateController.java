package com.elebusiness.controller;

import com.elebusiness.config.AppProperties;
import com.elebusiness.config.LiblibConfig;
import com.elebusiness.model.DingTalkRecord;
import com.elebusiness.model.GenerateRequest;
import com.elebusiness.model.GenerationTask;
import com.elebusiness.model.entity.GenerationUsageLog;
import com.elebusiness.model.ProductInfo;
import com.elebusiness.service.CivitaiLoraService;
import com.elebusiness.service.DingTalkService;
import com.elebusiness.service.HistoryService;
import com.elebusiness.service.ImageGenerationService;
import com.elebusiness.service.TaskService;
import com.elebusiness.service.auth.CurrentUserService;
import com.elebusiness.service.agent.GenerationInvocationContext;
import com.elebusiness.service.agent.GptImageAgent;
import com.elebusiness.service.agent.ImageGeneratorAgent;
import com.elebusiness.service.CosService;
import com.elebusiness.service.billing.BillingService;
import com.elebusiness.service.billing.GenerationPricingService;
import com.elebusiness.service.workspace.UserStorageService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpSession;
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
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;

/**
 * 鍥惧儚鐢熸垚鏍稿績鎺ュ彛锛氭爣鍑嗘ā寮?/ 鑷畾涔夋ā寮?/ 灞€閮ㄩ噸缁?/ GPT-Image 鐩磋繛銆?
 * 浠?ApiController 鎷嗗嚭锛圓.1 閲嶆瀯锛夛紝涓氬姟閫昏緫闆跺彉鍔ㄣ€?
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
    private final List<ImageGeneratorAgent> agents;
    private final CurrentUserService currentUserService;
    private final UserStorageService userStorageService;
    private final BillingService billingService;
    private final GenerationPricingService pricingService;

    public GenerateController(DingTalkService dingTalkService,
                              ImageGenerationService imageGenerationService,
                              AppProperties appProperties, TaskService taskService,
                              GptImageAgent gptImageAgent,
                              HistoryService historyService, CosService cosService,
                              CivitaiLoraService civitaiLoraService,
                              List<ImageGeneratorAgent> agents,
                              CurrentUserService currentUserService,
                              UserStorageService userStorageService,
                              BillingService billingService,
                              GenerationPricingService pricingService) {
        this.dingTalkService = dingTalkService;
        this.imageGenerationService = imageGenerationService;
        this.appProperties = appProperties;
        this.taskService = taskService;
        this.gptImageAgent = gptImageAgent;
        this.historyService = historyService;
        this.cosService = cosService;
        this.civitaiLoraService = civitaiLoraService;
        this.agents = agents;
        this.currentUserService = currentUserService;
        this.userStorageService = userStorageService;
        this.billingService = billingService;
        this.pricingService = pricingService;
    }

    /** 寮傛鎻愪氦鐢熸垚浠诲姟锛岀珛鍗宠繑鍥?taskId锛屽墠绔疆璇?/api/task/{taskId} 鑾峰彇杩涘害 */
    @PostMapping("/api/generate")
    public ResponseEntity<Map<String, Object>> generate(@RequestBody GenerateRequest request, HttpSession httpSession) {
        long userId = currentUserService.requireUserId(httpSession);
        List<String> selectedIds = request.getProductIds();
        if (selectedIds == null || selectedIds.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "鏈€夋嫨浜у搧"));
        }

        String agentId = request.getAgentId();
        String userPrompt = request.getPrompt();
        String sessionId = request.getSessionId() == null ? "default" : request.getSessionId();
        GenerationTask task = taskService.createTask(userId, selectedIds.size());
        int estimatedPoints = pricingService.estimateImageGeneration("standard", agentId, selectedIds.size());
        task.setUsageLogId(billingService.recordGenerationStarted(userId, task.getId(), "standard", agentId, estimatedPoints).getId());

        taskService.submit(task, () -> {
            for (String recordId : selectedIds) {
                if (task.isCancelled()) break;

                DingTalkRecord record;
                try {
                    record = dingTalkService.getRecordById(recordId);
                } catch (Exception e) {
                    log.error("鑾峰彇璁板綍 {} 澶辫触: {}", recordId, e.getMessage());
                    task.addResult(ControllerHelpers.result(recordId, "error", "鑾峰彇璁板綍澶辫触: " + e.getMessage(), null));
                    task.incrementProgress();
                    continue;
                }

                ProductInfo info = dingTalkService.parseProductInfo(record);
                String productName = info.getName();
                String category = info.getCategory();

                task.setCurrentProduct(productName);

                if (category == null || category.isBlank()) {
                    task.addResult(ControllerHelpers.result(productName, "error", "?????", null));
                    task.incrementProgress();
                    continue;
                }

                List<Map<String, Object>> attachments = record.getFieldAsImageList("?????");
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
                    task.addResult(ControllerHelpers.result(productName, "error", "?????", null));
                    task.incrementProgress();
                    continue;
                }

                File categoryDir = new File(appProperties.getPaths().getReferenceDir(), category);
                if (!categoryDir.exists()) {
                    task.addResult(ControllerHelpers.result(productName, "error", "????? " + category + " ????", null));
                    task.incrementProgress();
                    continue;
                }

                File[] refFolders = categoryDir.listFiles(
                        f -> f.isDirectory() && f.getName().startsWith("鍙傝€冨浘"));
                if (refFolders == null || refFolders.length == 0) {
                    task.addResult(ControllerHelpers.result(productName, "error", "?????????", null));
                    task.incrementProgress();
                    continue;
                }

                File refPath = refFolders[new Random().nextInt(refFolders.length)];
                String cleanName = productName.replaceAll("?.+??", "");
                // Phase 1锛氬厛钀藉埌涓存椂褰掓。锛? 灏忔椂鍚庤嚜鍔ㄦ竻鐞嗭紱鐢ㄦ埛鍦ㄥ墠绔偣 馃捑 鎵?copy 鍒版案涔?outputDir
                String categoryOutputDir = userTempOutputDir(userId, category);
                int nextNum = imageGenerationService.getNextOutputNumber(categoryOutputDir, cleanName);
                String outputFolder = new File(categoryOutputDir, cleanName + "_" + nextNum).getAbsolutePath();
                new File(outputFolder).mkdirs();

                log.info("寮€濮嬬敓鎴? {} -> {} [agent={}]", productName, outputFolder, agentId);

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
                    task.addResult(ControllerHelpers.result(productName, "error", "???????????? API Key ???", outputFolder));
                } else {
                    task.addResult(ControllerHelpers.result(productName, "success", null, outputFolder));
                    // Phase 2锛氭爣鍑嗘ā寮忔棤鐢ㄦ埛涓婁紶 ref锛堝弬鑰冨浘鏉ヨ嚜 categoryDir锛屼笉褰掓。锛夛紱鍙鍏冧俊鎭?
                    try {
                        String configJson = "{\"productId\":\"" + recordId + "\",\"productName\":\""
                                + productName.replace("\"", "\\\"") + "\",\"category\":\""
                                + (category == null ? "" : category.replace("\"", "\\\"")) + "\"}";
                        historyService.recordGeneration(userId, sessionId, "standard",
                                userPrompt == null ? "" : userPrompt,
                                agentId, java.util.Collections.emptyList(),
                                outputFolder, configJson);
                    } catch (Exception e) {
                        log.warn("standard 妯″紡鍐欏巻鍙插け璐? {}", e.getMessage());
                    }
                }
                task.incrementProgress();
            }

            // 鏍囧噯妯″紡鐨勬€濊€冧俊鎭細灞曠ず鐢ㄦ埛 prompt + 妯″瀷
            task.addResult(ControllerHelpers.result("__AI_THOUGHT__0", "info",
                "????????? LLM ????\n??: " + agentId
                + "\n\n?????????\n" + (userPrompt == null || userPrompt.isBlank() ? "???" : userPrompt)
                + "\n\n????????????? prompt ????????????/SKU/????????????????",
                null));
        });

        return ResponseEntity.ok(Map.of("taskId", task.getId()));
    }

    /** 寮€鍝佹ā寮忕涓€姝ワ細涓婁紶浜у搧鍥?+ 鍒嗘瀽鎻愮ず璇嶏紝杩斿洖鍙紪杈戠粨鏋勫寲鍗＄墖瀛楁銆?*/
    @PostMapping("/api/product_analyze")
    public ResponseEntity<Map<String, Object>> productAnalyze(
            @RequestParam(value = "image", required = false) MultipartFile image,
            @RequestParam(value = "prompt", defaultValue = "") String prompt,
            @RequestParam(value = "promptBase64", required = false) String promptBase64,
            @RequestParam(value = "agentId", defaultValue = "gemini") String agentId) {
        prompt = decodePrompt(prompt, promptBase64);
        if (prompt == null || prompt.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "璇疯緭鍏ュ垎鏋愭彁绀鸿瘝"));
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
            log.error("product_analyze 澶辫触: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        } finally {
            if (imageTmp != null && imageTmp.exists()) imageTmp.delete();
        }
    }

    /** 鑷畾涔夋ā寮忥細涓婁紶鐧藉簳鍥?+ 鐢ㄦ埛瑕佹眰锛岀敤 Gemini 鎵╁啓涓哄娈靛彲缂栬緫鐢熷浘鎻愮ず璇嶃€?*/
    @PostMapping("/api/custom_analyze")
    public ResponseEntity<Map<String, Object>> customAnalyze(
            @RequestParam(value = "images", required = false) List<MultipartFile> images,
            @RequestParam(value = "prompt", defaultValue = "") String prompt,
            @RequestParam(value = "count", defaultValue = "1") int count,
            @RequestParam(value = "withText", defaultValue = "true") boolean withText) {
        log.info("[custom_analyze 鍏ュ弬] images={}, count={}, withText={}, promptLen={}",
                images == null ? 0 : images.size(), count, withText, prompt == null ? 0 : prompt.length());
        if (images == null || images.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "?????????"));
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
                return ResponseEntity.badRequest().body(Map.of("error", "璇峰厛涓婁紶鏈夋晥鍥剧墖"));
            }
            String text = imageGenerationService.analyzeCustomImagePrompts(prompt, tempFiles, count, withText);
            return ResponseEntity.ok(Map.of("text", text));
        } catch (Exception e) {
            log.error("custom_analyze 澶辫触: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        } finally {
            for (File f : tempFiles) {
                if (f != null && f.exists()) f.delete();
            }
        }
    }

    /** 视频模式：把用户脚本用 Gemini 优化成 Seedance 规范中文视频提示词（≤500字），返回给前端确认/编辑。 */
    @PostMapping("/api/video/optimize_prompt")
    public ResponseEntity<Map<String, Object>> optimizeVideoPrompt(
            @RequestParam(value = "images", required = false) List<MultipartFile> images,
            @RequestParam(value = "prompt", defaultValue = "") String prompt,
            @RequestParam(value = "durationSeconds", defaultValue = "8") int durationSeconds) {
        if (prompt == null || prompt.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "请先输入视频脚本或想法"));
        }
        List<File> tempFiles = new ArrayList<>();
        try {
            if (images != null) {
                for (MultipartFile image : images) {
                    if (image == null || image.isEmpty()) continue;
                    File tmp = File.createTempFile("video_opt_", getSuffix(image.getOriginalFilename()));
                    image.transferTo(tmp);
                    tempFiles.add(tmp);
                }
            }
            String optimized = imageGenerationService.optimizeVideoPrompt(prompt, tempFiles, durationSeconds);
            return ResponseEntity.ok(Map.of("prompt", optimized));
        } catch (Exception e) {
            log.error("optimize_prompt 失败: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        } finally {
            for (File f : tempFiles) {
                if (f != null && f.exists()) f.delete();
            }
        }
    }

    /**
     * 寮€鍝佹ā寮忓瑙傚垎鏋愶細鍩轰簬浜у搧鍥?鎻忚堪锛屾寜鍐呯疆 Excel 缁村害鐢熸垚缁撴瀯鍖栧瑙傚垎鏋愬崱鐗囥€?
     * 杩欐槸涓ゆ娴佺▼鐨勭涓€姝ワ紝杩斿洖鍙紪杈戝崱鐗囦緵鐢ㄦ埛纭鍚庝簩娆＄敓鍥俱€?
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

        // 鍙傛暟瑙勮寖鍖?
        productA = normalizeMultipartText(productA);
        productB = normalizeMultipartText(productB);
        selling = normalizeMultipartText(selling);
        if (focusText != null) focusText = normalizeMultipartText(focusText);
        if (styleText != null) styleText = normalizeMultipartText(styleText);

        // 鏂扮増寮€鍝佹ā寮忥細鎸夊浐瀹氬瑙傜淮搴﹀垎鏋愬崟涓骇鍝侊紝鑷冲皯鎻愪緵涓€浠芥枃瀛楁垨鍥剧墖鏉愭枡鍗冲彲銆?
        boolean hasA = (productA != null && !productA.isBlank()) || (imageA != null && !imageA.isEmpty());
        boolean hasB = (productB != null && !productB.isBlank()) || (imageB != null && !imageB.isEmpty());
        if (!hasA && !hasB) {
            return ResponseEntity.badRequest().body(Map.of("error", "??????????????????????"));
        }

        File imageATmp = null;
        File imageBTmp = null;
        try {
            // 淇濆瓨涓婁紶鐨勫浘鐗囧埌涓存椂鏂囦欢
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

            // 璋冪敤铻嶅悎鍒嗘瀽鏈嶅姟
            List<Map<String, String>> fields = imageGenerationService.analyzeKaiPin(
                    productA, productB, selling, focus, focusText, style, styleText, imageATmp, imageBTmp);

            return ResponseEntity.ok(Map.of("fields", fields));
        } catch (Exception e) {
            log.error("kaipin_analyze 澶辫触: {}", e.getMessage(), e);
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
        if (!(value.contains("脙") || value.contains("脗") || value.contains("盲")
                || value.contains("氓") || value.contains("莽") || value.contains("茂驴陆"))) {
            return value;
        }
        try {
            return new String(value.getBytes(StandardCharsets.ISO_8859_1), StandardCharsets.UTF_8);
        } catch (Exception ignored) {
            return value;
        }
    }

    /**
     * 寮€鍝?Excel 鎵归噺鐢熸垚锛氳眴鍖?瑙嗚鍒嗘瀽鍙鐧藉簳浜у搧鍥炬墽琛屼竴杞€?
     * Excel 閲岀殑鍥剧墖鍙綔涓洪€愬紶鍒涙柊鍙傝€冨浘锛屼笌鐧藉簳浜у搧鍥鹃厤瀵圭敓鎴愩€?
     */
    @PostMapping("/api/kaipin_excel_generate")
    public ResponseEntity<Map<String, Object>> kaipinExcelGenerate(
            @RequestParam("whiteImage") MultipartFile whiteImage,
            @RequestParam("excel") MultipartFile excel,
            @RequestParam(value = "basePrompt", defaultValue = "") String basePrompt,
            @RequestParam(value = "countPerRef", defaultValue = "1") int countPerRef,
            @RequestParam(value = "agentId", defaultValue = "gpt-image") String agentId,
            @RequestParam(value = "sessionId", defaultValue = "default") String sessionId,
            @RequestParam(value = "configJson", required = false) String configJson,
            HttpSession httpSession) {
        long userId = currentUserService.requireUserId(httpSession);

        if (whiteImage == null || whiteImage.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Excel 鎵归噺寮€鍝侀渶瑕佷笂浼犵櫧搴曚骇鍝佸浘"));
        }
        if (excel == null || excel.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "璇蜂笂浼犲寘鍚弬鑰冨浘鐗囩殑 .xlsx 鏂囦欢"));
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
                return ResponseEntity.badRequest().body(Map.of("error", "Excel ????????????????? .xlsx ???"));
            }

            int safeCount = Math.max(1, Math.min(50, countPerRef));
            int total = excelRefs.size() * safeCount;
            GenerationTask task = taskService.createTask(userId, total);
            int estimatedPoints = pricingService.estimateImageGeneration("kaipin", agentId, total);
            task.setUsageLogId(billingService.recordGenerationStarted(userId, task.getId(), "kaipin", agentId, estimatedPoints).getId());
            String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
            String outputDir = userTempOutputDir(userId, "开品Excel批量/" + timestamp);

            final File whiteFinal = whiteTmp;
            final File excelFinal = excelTmp;
            final List<File> refsFinal = new ArrayList<>(excelRefs);
            final int countFinal = safeCount;
            final String promptFinalBase = basePrompt == null || basePrompt.isBlank()
                    ? "??????????????????? Excel ???????????????????????????????"
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
                            if (task.isCancelled()) return "??: ???";
                            boolean ok = imageGenerationService.generateImageMulti(
                                    userId,
                                    prompt,
                                    List.of(whiteFinal.getAbsolutePath(), refFinal.getAbsolutePath()),
                                    null,
                                    outputPath,
                                    agentId,
                                    aspect);
                            return ok ? outputPath : "??: ???????";
                        }, executor));
                    }
                }

                // 銆愪紭鍖栥€戜娇鐢?allOf 骞惰绛夊緟鎵€鏈変换鍔″畬鎴愶紝鑰岄潪涓茶闃诲
                log.info("[?? Excel ??] ?? {} ?????????", futures.size());
                try {
                    CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                            .get(10, TimeUnit.MINUTES);
                } catch (TimeoutException te) {
                    log.warn("[寮€鍝?Excel 鎵归噺] 鏁翠綋瓒呮椂(>10鍒嗛挓)锛屽彇娑堟湭瀹屾垚浠诲姟");
                    for (CompletableFuture<String> f : futures) {
                        if (!f.isDone()) f.cancel(true);
                    }
                } catch (Exception e) {
                    log.error("[寮€鍝?Excel 鎵归噺] 绛夊緟浠诲姟瀹屾垚寮傚父: {}", e.getMessage());
                }

                // 鏀堕泦鎵€鏈変换鍔＄粨鏋?
                int n = 1;
                for (CompletableFuture<String> f : futures) {
                    String r;
                    if (f.isCancelled()) {
                        r = "??: ???";
                    } else if (!f.isDone()) {
                        r = "??: ???";
                    } else {
                        try {
                            r = f.get(100, TimeUnit.MILLISECONDS); // 宸插畬鎴愶紝蹇€熻幏鍙?
                        } catch (Exception e) {
                            r = "澶辫触: " + e.getMessage();
                        }
                    }
                    String name = n++ + ".jpg";
                    if (r.startsWith("澶辫触")) {
                        task.addResult(ControllerHelpers.result(name, "error", r, null));
                    } else {
                        String outputRef = r;
                        if (cosService.isEnabled()) {
                            try {
                                outputRef = cosService.upload(new File(r), name);
                            } catch (Exception ce) {
                                log.warn("COS 涓婁紶澶辫触锛岄檷绾ф湰鍦拌矾寰? {}", ce.getMessage());
                            }
                        }
                        task.addResult(ControllerHelpers.result(name, "success", null, outputRef, r));
                    }
                    task.incrementProgress();
                }

                task.addResult(ControllerHelpers.result("__OUTPUT_DIR__", "info", null, outputDir));
                task.addResult(ControllerHelpers.result("__AI_THOUGHT__0", "info",
                        "??? Excel ?????????? 1 ??Excel ???????????????????? "
                                + refsFinal.size() + " ??????", null));

                try {
                    List<File> refsForArchive = new ArrayList<>();
                    refsForArchive.add(whiteFinal);
                    refsForArchive.addAll(refsFinal);
                    HistoryService.ArchiveResult archive = historyService.archiveRefFiles(userId, refsForArchive);
                    historyService.recordGeneration(userId, sessionId, "kaipin",
                            promptFinalBase, agentId, archive.refPaths, outputDir, configJson);
                } catch (Exception e) {
                    log.warn("寮€鍝?Excel 鎵归噺鍐欏巻鍙插け璐ワ紙涓嶅奖鍝嶇敓鍥撅級: {}", e.getMessage());
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
            log.error("kaipin_excel_generate 澶辫触: {}", e.getMessage(), e);
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
        long userId = currentUserService.requireUserId(request.getSession());

        // 涓嶈兘鐢?@RequestParam List<String> prompts 鈥斺€?Spring 浼氭寜鑻辨枃閫楀彿鑷姩鎷嗗垎
        // 锛圕onversionService 榛樿琛屼负锛岃瑙?spring-framework reference 6.2 / RequestHeader 绔犺妭锛?
        //   List<String> 鍙傛暟浼氭妸 comma-separated string 杞垚 List锛夈€?
        // 鎴戜滑鐨?prompt 閲屽祵浜嗗ぇ閲忚嫳鏂?negative锛?worst quality, low quality, blurry, ..."锛夛紝
        // 涓€鏉′細琚垏鎴?N 鏉?鈫?鍚庣 promptList.size() 鏆存定 鈫?涓€娆℃彁浜よ窇鍑?N 寮犲浘銆?
        // 鐩存帴璇?multipart 鍘熷 String[]锛屼繚鐣欏悓鍚嶅瓧娈电殑鍘熷€间笉鍋氫换浣曟媶鍒嗐€?
        String[] rawPrompts = request.getParameterValues("prompt");
        List<String> prompts = rawPrompts == null ? List.of() : Arrays.asList(rawPrompts);

        boolean hasImages = images != null && !images.isEmpty();
        String effectiveLoraPreset = LiblibConfig.normalizeLoraPreset(loraPreset);
        boolean loraMode = useLora;
        String requestedAgentId = "gemini".equalsIgnoreCase(agentId) ? "gpt-image" : agentId;
        String generationAgentId = loraMode ? "liblib-lora" : requestedAgentId;
        if (!Objects.equals(requestedAgentId, agentId)) {
            log.info("[custom_generate] Gemini only analyzes prompts; image generation switched to {}", requestedAgentId);
        }
        if (loraMode && !Objects.equals(generationAgentId, requestedAgentId)) {
            log.info("[custom_generate] LoRA enabled; forcing image generation agent to {} (requested={})", generationAgentId, requestedAgentId);
        }

        log.info("[custom_generate input] prompts={}, images={}, pairImages={}, count={}, agentId={}, aspect={}, useLora={}, loraPreset={}",
                prompts.size(),
                images == null ? 0 : images.size(),
                pairImages, count, generationAgentId, aspect, useLora, effectiveLoraPreset);

        // LoRA mode uses one unified liblib-lora tone for now; preset is kept only for future expansion.
        if (loraMode) {
            log.info("[custom_generate] using liblib-lora, tone={}, rawPreset={}", effectiveLoraPreset, loraPreset);
            if (!hasImages) {
                return ResponseEntity.badRequest().body(Map.of("error", "LoRA mode requires product image"));
            }
        }

        // 杩囨护绌?prompt
        List<String> promptList = new ArrayList<>();
        for (String p : prompts) if (p != null && !p.isBlank()) promptList.add(p);
        if (promptList.isEmpty() && !hasImages) {
            return ResponseEntity.badRequest().body(Map.of("error", "绾枃鐢熷浘妯″紡闇€瑕佹彁渚涙彁绀鸿瘝"));
        }
        if (promptList.isEmpty()) promptList.add("");
        int requestedCount = Math.max(1, Math.min(50, count));

        // 鑷畾涔夋ā寮忕殑鈥滅敓鎴愭暟閲忊€濇槸鏈€缁堟€诲紶鏁帮紝涓嶆槸 prompt 鏁伴噺 脳 姣忔潯 prompt 寮犳暟銆?
        // 闃插尽寮傚父璇锋眰甯﹀叆澶氭潯 prompt 鏃跺嚭鐜?4脳4=16 杩欑被鍊嶅銆?
        if ("custom".equalsIgnoreCase(mode) && multiPrompt && promptList.size() > 1) {
            requestedCount = 1;
            log.info("[custom_generate] ????????? prompt?{} ???????? 1 ?", promptList.size());
        } else if ("custom".equalsIgnoreCase(mode) && promptList.size() > 1) {
            log.warn("[custom_generate] 鑷畾涔夋ā寮忔敹鍒板鏉?prompt锛坽} 鏉★級锛屽凡鏀舵暃涓?1 鏉★紝閬垮厤 count 鍊嶅", promptList.size());
            promptList = new ArrayList<>(List.of(String.join("\n\n", promptList)));
        }

        // 鏀堕泦鎻愮ず璇嶆€濊€冭繃绋嬶紝鍥炰紶鍓嶇灞曠ず锛堝凡绉婚櫎 Gemini 鍘嬬缉姝ラ锛岀洿鎺ラ€忎紶鍘熸枃锛?
        List<String> thoughts = new ArrayList<>();
        for (String p : promptList) {
            thoughts.add(
                "銆愭彁绀鸿瘝鐩撮€侊紙鏈粡 LLM 澶勭悊锛夈€慭n妯″瀷: " + generationAgentId
                + "\n闀垮害: " + p.length() + " 瀛梊n\n銆愭渶缁堟彁绀鸿瘝銆慭n" + p
            );
        }

        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
        String outputDir = userTempOutputDir(userId, "自定义模式生成/" + timestamp);

        // 瑙ｆ瀽 promptGroups锛堟壒閲忕敓鍥惧垎缁勯厤瀵癸級锛歔{promptIdx, imageIndices:[..]}, ...]
        // 闈炵┖鏃惰蛋鍒嗙粍鍒嗘敮锛氱 promptIdx 鏉?prompt 鐢?imageIndices 鎸囧悜鐨勫浘鐗囧瓙闆嗐€?
        List<int[]> groupImageIdx = new ArrayList<>(); // 姣忔潯 prompt 瀵瑰簲鐨?image 绱㈠紩鏁扮粍
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
                return ResponseEntity.badRequest().body(Map.of("error", "promptGroups 瑙ｆ瀽澶辫触: " + e.getMessage()));
            }
        }

        // multipart 蹇呴』鍦ㄨ姹傜嚎绋嬪唴钀界洏锛屾彁浜ゅ埌寮傛浠诲姟鍓嶅厛鎶婁笂浼犳枃浠舵寔涔呭寲
        File refTempFile = null;
        List<File> whiteTempFiles = new ArrayList<>();
        List<File> pairRefs        = new ArrayList<>();
        List<File> groupFiles      = new ArrayList<>(); // promptGroups 妯″紡锛氭墍鏈?image 鎸夌储寮曡惤鐩?
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
                        .body(Map.of("error", "澶勭悊涓婁紶鏂囦欢澶辫触: " + e.getMessage()));
            }
        }

        int pairN = pairImages ? Math.min(pairRefs.size(), promptList.size()) : 0;
        int total = useGroups ? (groupPromptIdx.size() * requestedCount)
                  : pairImages ? (pairN * requestedCount)
                  : (promptList.size() * requestedCount);

        GenerationTask task = taskService.createTask(userId, total);
        int estimatedPoints = pricingService.estimateImageGeneration(mode, generationAgentId, total);
        task.setUsageLogId(billingService.recordGenerationStarted(userId, task.getId(), mode, generationAgentId, estimatedPoints).getId());
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
                // 鎵归噺鍒嗙粍锛氱 g 缁勭敤 promptsFinal[promptIdx] + imageIndices 鎸囧悜鐨勫浘鐗囧瓙闆?
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
                            if (task.isCancelled()) return "??: ???";
                            boolean ok = imageGenerationService.generateImageMulti(
                                    userId, promptFinal, refsFinal, null, outputPath, generationAgentId, aspectForGroup);
                            return ok ? outputPath : "??: ???????";
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
                            if (task.isCancelled()) return "??: ???";
                            boolean ok = imageGenerationService.generateImageMulti(
                                    userId, promptFinal, List.of(refI.getAbsolutePath()), null, outputPath, generationAgentId, aspectForRef);
                            return ok ? outputPath : "??: ???????";
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
                            if (task.isCancelled()) return "??: ???";
                            boolean ok = imageGenerationService.generateImageMulti(
                                    userId, promptFinal, List.of(), null, outputPath, generationAgentId, requestedAspect);
                            return ok ? outputPath : "??: ???????";
                        }, executor));
                    }
                }
            } else {
                // 銆愯嚜瀹氫箟妯″紡路娣峰悎骞惰鐢熸垚銆戠1寮犱覆琛岋紝绗?-N寮犲苟琛?
                // 绛栫暐锛氱1寮犵敤鐧藉簳鍥惧缓绔嬪熀璋冿紝绗?-N寮犲叏閮ㄥ弬鑰冪櫧搴曞浘+绗?寮犲苟鍙戠敓鎴?
                List<String> baseRefs = new ArrayList<>();
                baseRefs.add(refFinal.getAbsolutePath());
                for (File wf : whiteFinal) baseRefs.add(wf.getAbsolutePath());

                List<File> refsForAspect = new ArrayList<>();
                refsForAspect.add(refFinal);
                refsForAspect.addAll(whiteFinal);
                final String aspectForRefs = resolveAutoAspect(requestedAspect, refsForAspect);

                for (String p : promptsFinal) {
                    // Phase 1: 鐢熸垚绗?寮狅紙涓茶锛?
                    final int n1 = idx[0]++;
                    final String outputPath1 = new File(outputDir, n1 + ".jpg").getAbsolutePath();
                    final List<String> refs1 = new ArrayList<>(baseRefs);
                    final String prompt1 = buildSeriesPrompt(p, 1, countFinal);

                    CompletableFuture<String> future1 = CompletableFuture.supplyAsync(() -> {
                        if (task.isCancelled()) return "??: ???";
                        log.info("[鑷畾涔夋ā寮廬 鐢熸垚绗?寮狅紙鍩鸿皟鍥撅級锛屽弬鑰冨浘鏁伴噺: {}", refs1.size());
                        boolean ok = imageGenerationService.generateImageMulti(
                                userId, prompt1, refs1, null, outputPath1, generationAgentId, aspectForRefs);
                        return ok ? outputPath1 : "??: ???????";
                    }, executor);

                    futures.add(future1);

                    // 绛夊緟绗?寮犲畬鎴?
                    String result1;
                    try {
                        result1 = future1.get(5, TimeUnit.MINUTES);
                        if (result1.startsWith("澶辫触") || !new File(result1).exists()) {
                            log.warn("[鑷畾涔夋ā寮廬 绗?寮犵敓鎴愬け璐? {}锛屽悗缁浘鐗囧皢闄嶇骇涓轰粎鍙傝€冪櫧搴曞浘", result1);
                            result1 = null; // 鏍囪涓哄け璐ワ紝鍚庣画涓嶄娇鐢?
                        } else {
                            log.info("[鑷畾涔夋ā寮廬 绗?寮犵敓鎴愭垚鍔? {}", result1);
                        }
                    } catch (TimeoutException te) {
                        future1.cancel(true);
                        log.error("[?????] ?1?????");
                        result1 = null;
                    } catch (Exception e) {
                        log.error("[鑷畾涔夋ā寮廬 绗?寮犵敓鎴愬紓甯? {}", e.getMessage());
                        result1 = null;
                    }

                    // Phase 2: 骞惰鐢熸垚绗?-N寮狅紙鎵€鏈夐兘鍙傝€冪櫧搴曞浘+绗?寮狅級
                    if (countFinal > 1) {
                        final String firstImagePath = result1; // final for lambda
                        List<CompletableFuture<String>> parallelFutures = new ArrayList<>();

                        for (int i = 1; i < countFinal; i++) {
                            final int imageIndex = i + 1;
                            final int n = idx[0]++;
                            final String outputPath = new File(outputDir, n + ".jpg").getAbsolutePath();

                            // 鏋勫缓鍙傝€冨浘鍒楄〃锛氱櫧搴曞浘 + 绗?寮狅紙濡傛灉绗?寮犵敓鎴愭垚鍔燂級
                            final List<String> currentRefs = new ArrayList<>(baseRefs);
                            if (firstImagePath != null) {
                                currentRefs.add(firstImagePath);
                            }

                            final String enhancedPrompt = buildSeriesPrompt(p, imageIndex, countFinal);

                            CompletableFuture<String> currentFuture = CompletableFuture.supplyAsync(() -> {
                                if (task.isCancelled()) return "??: ???";
                                log.info("[鑷畾涔夋ā寮忓苟琛宂 寮€濮嬬敓鎴愮 {} 寮狅紝鍙傝€冨浘鏁伴噺: {}", imageIndex, currentRefs.size());
                                boolean ok = imageGenerationService.generateImageMulti(
                                        userId, enhancedPrompt, currentRefs, null, outputPath, generationAgentId, aspectForRefs);
                                return ok ? outputPath : "??: ???????";
                            }, executor);

                            futures.add(currentFuture);
                            parallelFutures.add(currentFuture);
                        }

                        // 绛夊緟鎵€鏈夊苟琛屼换鍔″畬鎴愶紙娉ㄦ剰锛氫笉鍦ㄨ繖閲屽仛棰濆绛夊緟锛岀粺涓€鍦ㄥ悗闈㈡敹闆嗙粨鏋滄椂澶勭悊锛?
                        log.info("[???????] ??? {} ???????", parallelFutures.size());
                    }
                }
            }

            int n = 1;
            for (CompletableFuture<String> f : futures) {
                String r;
                if (f.isCancelled()) {
                    r = "??: ???";
                } else if (!f.isDone()) {
                    // 浠诲姟杩樻湭瀹屾垚锛岀瓑寰呭畠锛堝崟寮犳渶澶?0鍒嗛挓锛?
                    try {
                        r = f.get(10, TimeUnit.MINUTES);
                    } catch (TimeoutException te) {
                        f.cancel(true);
                        r = "澶辫触: 鍗曞紶鍥捐秴鏃?(>10 鍒嗛挓)";
                    } catch (CancellationException ce) {
                        r = "??: ???";
                    } catch (Exception e) {
                        r = "澶辫触: " + e.getMessage();
                    }
                } else {
                    // 浠诲姟宸插畬鎴愶紝鐩存帴鑾峰彇缁撴灉锛堜笉浼氶樆濉烇級
                    try {
                        r = f.get(100, TimeUnit.MILLISECONDS);
                    } catch (CancellationException ce) {
                        r = "??: ???";
                    } catch (Exception e) {
                        r = "澶辫触: " + e.getMessage();
                    }
                }
                String name = n++ + ".jpg";
                if (r.startsWith("澶辫触")) {
                    task.addResult(ControllerHelpers.result(name, "error", r, null));
                } else {
                    // COS 宸查厤缃椂涓婁紶锛岃繑鍥?URL锛涘惁鍒欒繑鍥炴湰鍦拌矾寰勶紙寮€鍙戞€佸厹搴曪級
                    String outputRef = r;
                    if (cosService.isEnabled()) {
                        try {
                            outputRef = cosService.upload(new File(r), name);
                        } catch (Exception ce) {
                            log.warn("COS 涓婁紶澶辫触锛岄檷绾ф湰鍦拌矾寰? {}", ce.getMessage());
                        }
                    }
                    task.addResult(ControllerHelpers.result(name, "success", null, outputRef, r));
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

        // Phase 2锛氬啓涓€鏉?GenerationHistory锛坧er-batch锛沷utputPath = 鎵规鐩綍锛夈€?
        // 寮傛浠诲姟杩樻病璺戝畬娌″叧绯伙紝璁板綍鐨勬槸"鎻愪氦浜嗚繖娆＄敓鍥捐姹?鐨勫厓淇℃伅锛?
        // 鐢ㄦ埛鍚庣画鐐?馃捑 淇濆瓨鍏朵腑涓€寮犳椂锛孯esourceController.saveToGallery 浼氭寜 parent dir 鍖归厤鍥炲～ savedPath銆?
        try {
            // 褰掓。鍙傝€冨浘锛坧air / ref+white / no images 涓夌鍏ュ弬褰㈡€侀兘瑕嗙洊锛?
            List<File> refsForArchive = new ArrayList<>();
            if (refTempFile != null) refsForArchive.add(refTempFile);
            for (File wf : whiteTempFiles) refsForArchive.add(wf);
            for (File pr : pairRefs)       refsForArchive.add(pr);
            // 娉ㄦ剰锛氳繖閲?archiveRefFiles 鏄悓姝?copy 涓€娆★紝璺?lambda 閲屼箣鍚庣殑 .delete() 鏃犵珵浜?
            HistoryService.ArchiveResult archive = historyService.archiveRefFiles(userId, refsForArchive);
            String promptJoined = String.join(" || ", promptList);
            historyService.recordGeneration(userId, sessionId, mode, promptJoined, generationAgentId,
                    archive.refPaths, outputDir, configJson);
        } catch (Exception e) {
            log.warn("鍐欏巻鍙茶褰曞け璐ワ紙涓嶅奖鍝嶇敓鍥撅級: {}", e.getMessage());
        }

        return ResponseEntity.ok(Map.of("taskId", task.getId(), "output_dir", outputDir));
    }

    private String normalizeAspect(String aspect) {
        if (aspect == null || aspect.isBlank()) return "auto";
        return aspect.trim();
    }

    /**
     * 鑷畾涔夋ā寮忕殑鈥滆嚜鍔ㄢ€濆簲灏介噺璺熼殢鍙傝€冨浘姣斾緥锛岃€屼笉鏄惤鍥炴ā鍨嬮粯璁ゆ柟鍥俱€?
     * 鐢ㄦ埛鏄惧紡閫夋嫨 1:1 / 9:16 / 16:9 绛夋瘮渚嬫椂涓嶆敼鍔紝鐢靛晢璇︽儏椤靛己鍒?9:16 涔熶笉鍙楀奖鍝嶃€?
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
                log.info("鑷畾涔夋ā寮?auto 灏哄鎸夊弬鑰冨浘鎺ㄦ柇涓?{} ({}x{}, file={})",
                        inferred, image.getWidth(), image.getHeight(), ref.getName());
                return inferred;
            } catch (Exception e) {
                log.warn("璇诲彇鍙傝€冨浘灏哄澶辫触锛屼繚鎸?auto: {}", e.getMessage());
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
     * 鏋勫缓绯诲垪杩炶疮鎬ф彁绀鸿瘝锛氭牴鎹浘鐗囧簭鍙峰寮虹害鏉燂紝纭繚鍚屽満鏅瑙掑害鎷嶆憚鏁堟灉
     *
     * @param basePrompt 鐢ㄦ埛鍘熷鎻愮ず璇?
     * @param currentIndex 褰撳墠鍥剧墖搴忓彿锛?-based锛?
     * @param totalCount 鎬诲浘鐗囨暟閲?
     * @return 澧炲己鍚庣殑鎻愮ず璇?
     */
    private String buildSeriesPrompt(String basePrompt, int currentIndex, int totalCount) {
        String base = basePrompt == null ? "" : basePrompt.trim();

        // 绯诲垪杩炶疮鎬ф牳蹇冪害鏉?
        String seriesConstraint = String.format("""

                銆愮郴鍒楄繛璐€锋渶楂樹紭鍏堢骇銆戣繖鏄悓涓€鍦烘櫙绯诲垪鎷嶆憚鐨勭 %d/%d 寮狅細
                1. 浜у搧涓讳綋蹇呴』100%%涓€鑷达細澶栧舰銆侀鑹层€佹潗璐ㄣ€佸搧鐗屾爣璇嗐€佸叧閿粨鏋勫畬鍏ㄧ浉鍚?
                2. **浜у搧鍘熺敓鏂囧瓧蹇呴』涓庣1寮犲畬鍏ㄧ浉鍚?*锛?
                   - 浜у搧鏈綋涓婄殑鏂囧瓧锛歀OGO銆佸搧鐗屽悕绉般€佸瀷鍙锋爣璇嗐€佹寜閽爣绛俱€佸埢搴︽暟瀛椼€佹帴鍙ｆ爣璇嗙瓑
                   - 杩欎簺鏂囧瓧鐨勫瓧褰€佸瓧浣撱€佷綅缃€侀鑹层€佹竻鏅板害蹇呴』淇濇寔涓€鑷达紝绂佹妯＄硦銆佸彉褰€佹秷澶辨垨鏀瑰彉鍐呭
                   - **娉ㄦ剰**锛氱敾闈笂鐨勮惀閿€鍗栫偣鏂囨锛堝"鎸佷箙缁埅"銆?鏅鸿兘闄嶅櫔"绛夛級灞炰簬鏈浘鍗栫偣鐨勪竴閮ㄥ垎锛屾瘡寮犲浘鍙互涓嶅悓锛岃繖鏄甯哥殑
                3. **涓ょ被鏂囧瓧鐨勫尯鍒?*锛?
                   - 浜у搧鍘熺敓鏂囧瓧锛堝繀椤讳竴鑷达級锛氫骇鍝佸寘瑁呫€佸澹炽€佸睆骞曘€佹寜閿笂鍗板埛/鏄剧ず鐨勬枃瀛?
                   - 鐢婚潰钀ラ攢鏂囨锛堝彲浠ヤ笉鍚岋級锛氭紓娴湪鍦烘櫙涓殑鍗栫偣鏍囬銆佸壇鏍囬銆佹爣绛撅紝鐢ㄤ簬寮鸿皟鏈浘鐨勫樊寮傚寲鍗栫偣
                4. 鍦烘櫙绫诲瀷蹇呴』瀹屽叏涓€鑷达細濡傛灉鏄荡瀹ゅ氨淇濇寔娴村锛屽帹鎴垮氨淇濇寔鍘ㄦ埧锛屽姙鍏灏变繚鎸佸姙鍏
                5. 鏁翠綋鑹茶皟銆佸厜绾挎皼鍥淬€佽儗鏅潗璐ㄥ繀椤讳繚鎸佷竴鑷达紝钀ラ€?鍚屼竴鏃堕棿鍚屼竴鍦扮偣"鐨勮繛缁劅
                6. 鍏佽鍙樺寲鐨勯儴鍒嗭細
                   - 浜у搧鎷嶆憚瑙掑害锛堟闈⑩啋渚ч潰鈫掍刊瑙嗏啋浠拌绛夎嚜鐒惰繃娓★級
                   - 浜у搧鍦ㄥ満鏅腑鐨勬憜鏀句綅缃紙宸︹啋涓啋鍙筹紝鎴栧墠鈫掑悗鏅繁鍙樺寲锛?
                   - 鍦烘櫙涓殑閬撳叿缁嗚妭锛堝悓绫诲瀷閬撳叿鐨勪笉鍚屾憜鏀撅紝浣嗛鏍间繚鎸佷竴鑷达級
                   - 鍏夌収瑙掑害鐨勫井璋冿紙浣嗘暣浣撲寒搴﹀拰鑹叉俯淇濇寔涓€鑷达級
                   - **鐢婚潰钀ラ攢鏂囨**锛氭瘡寮犲浘鏍规嵁鏈浘鍗栫偣灞曠ず涓嶅悓鐨勮惀閿€鏍囬鍜屾爣绛撅紝杩欐槸绯诲垪鍥剧殑鏍稿績浠峰€?
                7. 绂佹鍑虹幇锛氬満鏅被鍨嬪垏鎹€佽壊璋冨墽鍙樸€佷骇鍝佸彉褰€佷骇鍝佸師鐢熸枃瀛楅敊璇?妯＄硦/娑堝け銆侀鏍艰烦璺冦€佷笉鍚屾椂闂存鐨勫厜绾?
                8. 鏈€缁堟晥鏋滐細鐪嬭捣鏉ュ儚鏄憚褰卞笀鍦ㄥ悓涓€鍦烘櫙涓蛋鍔紝鐢ㄤ笉鍚岃搴︽媿鎽勫悓涓€浜у搧鐨勮繛缁暅澶达紱姣忓紶鍥鹃€氳繃涓嶅悓鐨勬媿鎽勮搴﹀拰钀ラ攢鏂囨寮鸿皟涓嶅悓鍗栫偣锛屼絾浜у搧鏈韩濮嬬粓鏄悓涓€涓?
                """.trim(), currentIndex, totalCount);

        // 瑙掑害宸紓鍖栫害鏉燂紙鏇挎崲鍘熸湁鐨勪綅缃紩瀵硷級
        String angleConstraint = buildAngleConstraint(currentIndex, totalCount);

        return base + seriesConstraint + angleConstraint;
    }

    /**
     * 鏋勫缓瑙掑害宸紓鍖栫害鏉?
     * @param currentIndex 褰撳墠鍥剧墖搴忓彿锛?-based锛?
     * @param totalCount 鎬诲浘鐗囨暟閲?
     * @return 瑙掑害绾︽潫鎻愮ず璇?
     */
    private String buildAngleConstraint(int currentIndex, int totalCount) {
        if (currentIndex == 1) {
            return """

                銆愮1寮犅峰熀璋冨缓绔嬨€?
                浜у搧涓昏瑙掞紙姝ｉ潰鎴栨闈?5搴︼級锛屾竻鏅板睍绀轰骇鍝佹闈㈢壒寰併€佸搧鐗孡OGO銆佹牳蹇冨崠鐐广€?
                杩欏紶鍥惧皢浣滀负鍚庣画鍥剧墖鐨勫弬鑰冨熀鍑嗭紝蹇呴』瀹屾暣鍛堢幇浜у搧鍏ㄨ矊銆?
                **浜у搧鍘熺敓鏂囧瓧閲嶇偣**锛氭竻鏅板睍绀轰骇鍝佹湰浣撲笂鐨勬墍鏈夋枃瀛椼€丩OGO銆佸搧鐗屾爣璇嗐€佹寜閽爣绛剧瓑缁嗚妭锛岀‘淇濆彲璇讳笖瀹屾暣
                **鐢婚潰钀ラ攢鏂囨**锛氭牴鎹産asePrompt涓殑鍗栫偣锛岃璁℃湰鍥剧殑钀ラ攢鏂囨锛堜富鏍囬銆佸壇鏍囬銆佸崠鐐规爣绛撅級锛岀敤浜庡己璋冪1涓牳蹇冨崠鐐?
                """;
        }

        String[] angles = selectAngleSequence(totalCount);
        int angleIndex = (currentIndex - 2) % angles.length;
        String currentAngle = angles[angleIndex];

        return String.format("""

                銆愮%d寮犅疯搴︾害鏉熉峰己鍒舵墽琛屻€?
                浜у搧蹇呴』閲囩敤%s銆?

                **瑙掑害瀹氫箟**锛?
                - 淇濇寔浜у搧涓讳綋瀹屾暣鍙锛屼笉寰楄閬尅鎴栬鍒?
                - 璇ヨ搴﹀繀椤讳笌鍓嶉潰宸茬敓鎴愮殑鍥剧墖瑙掑害鏄庢樉涓嶅悓
                - 灞曠ず璇ヨ搴︿笅浜у搧鐨勭嫭鐗圭壒寰佸拰缁嗚妭
                - 鍏夌嚎鍜岄槾褰辫绗﹀悎璇ヨ瑙掔殑鐗╃悊瑙勫緥

                **绂佹**锛?
                - 绂佹浣跨敤涓庣1寮犵浉鍚屾垨鐩镐技鐨勬闈㈣搴?
                - 绂佹浣跨敤涓庡墠闈㈠凡鐢熸垚鍥剧墖閲嶅鐨勮搴?
                - 绂佹鍥犱负瑙掑害鏀瑰彉鑰屼慨鏀逛骇鍝佺粨鏋勬垨姣斾緥

                **浜у搧鍘熺敓鏂囧瓧绾︽潫**锛氬弬鑰冪1寮犲浘鐗囷紝纭繚浜у搧鏈綋涓婄殑鏂囧瓧/LOGO/鏍囪瘑涓庣1寮犲畬鍏ㄧ浉鍚岋紝浣嶇疆銆佸瓧浣撱€佹竻鏅板害涓€鑷?
                **鐢婚潰钀ラ攢鏂囨**锛氬彲浠ヤ笌鍓嶉潰涓嶅悓锛屾牴鎹産asePrompt璁捐鏂扮殑钀ラ攢鏍囬鍜屾爣绛炬潵寮鸿皟绗?d涓崠鐐规垨浠庢柊瑙掑害璇佹槑鍔熻兘
                """, currentIndex, currentAngle, currentIndex);
    }

    /**
     * 鏍规嵁鎬诲浘鐗囨暟閲忛€夋嫨鍚堥€傜殑瑙掑害搴忓垪
     * @param totalCount 鎬诲浘鐗囨暟閲?
     * @return 瑙掑害鎻忚堪鏁扮粍
     */
    private String[] selectAngleSequence(int totalCount) {
        if (totalCount <= 3) {
            // 3寮犲強浠ヤ笅锛氭闈?渚ч潰+淇/浠拌
            return new String[]{
                "渚ч潰90搴﹁瑙掞紙灞曠ず浜у搧宸︿晶鎴栧彸渚у畬鏁磋疆寤擄紝渚ч潰骞宠浜庣敾闈級",
                "??45????????45????????????????????"
            };
        } else if (totalCount <= 5) {
            // 4-5寮狅細姝ｉ潰+宸﹀彸渚?淇+寰话瑙?
            return new String[]{
                "???70????????????70?????????????",
                "???70????????????70?????????????",
                "??45????????45?????????????",
                "姝ｉ潰寰话瑙?0搴﹁瑙掞紙鐩告満浣嶇疆鐣ヤ綆浜庝骇鍝佷腑蹇冿紝鍚戜笂浠版媿30搴︼級"
            };
        } else {
            // 6寮犲強浠ヤ笂锛氬叏鏂逛綅澶氳搴?
            return new String[]{
                "宸︿晶闈?0搴﹁瑙掞紙浜у搧瀹屽叏渚ч潰灞曠ず锛屽乏渚ч潰骞宠浜庣敾闈級",
                "鍙充晶闈?0搴﹁瑙掞紙浜у搧瀹屽叏渚ч潰灞曠ず锛屽彸渚ч潰骞宠浜庣敾闈級",
                "??60???????????60?????????????",
                "浠拌30搴﹁瑙掞紙鐩告満浣嶇疆鏄庢樉浣庝簬浜у搧锛屽悜涓婁话鎷?0搴︼級",
                "??45???????????45?????????????",
                "??45???????????45?????????????"
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
                銆愬紑鍝?Excel 鎵归噺铻嶅悎銆?
                鏈璞嗗寘/瑙嗚鍒嗘瀽鍙鐧藉簳浜у搧鍥炬墽琛屼竴杞€傝涓ユ牸浠ョ 1 寮犲弬鑰冨浘锛堢櫧搴曚骇鍝佸浘锛変负浜у搧涓讳綋锛屼繚鎸佸搧绫汇€佹牳蹇冪粨鏋勩€佸姛鑳借瘑鍒偣銆佹瘮渚嬪叧绯诲拰鍙埗閫犳€т竴鑷淬€?
                绗?2 寮犲弬鑰冨浘鏉ヨ嚜 Excel锛屾槸绗?%d / %d 寮犲垱鏂板弬鑰冨浘銆備笉瑕佺洿鎺ュ鍒舵垨鏇挎崲鎴愯鍙傝€冨浘閲岀殑浜у搧锛屽彧鎻愬彇瀹冪殑閫犲瀷璇█銆丆MF 鑹插僵绛栫暐銆佹潗璐ㄨ川鎰熴€佺粨鏋勭粏鑺傘€佽楗拌妭濂忔垨浣跨敤鍦烘櫙浣滀负鍒涙柊鐐广€?

                銆愮櫧搴曚骇鍝佸垎鏋愮粨璁恒€?
                %s

                銆愯瀺鍚堣姹傘€?
                1. 涓讳綋蹇呴』浠嶇劧鏄櫧搴曚骇鍝佸浘閲岀殑浜у搧锛屼笉涓㈠け鍘熶骇鍝佽韩浠姐€?
                2. 灏?Excel 鍙傝€冨浘鐨勫垱鏂扮偣铻嶅悎鍒颁富浣撶殑澶栬璁捐涓紝褰㈡垚鏂扮殑寮€鍝佹蹇点€?
                3. 鐢婚潰鍙憟鐜颁竴涓竻鏅颁富浜у搧锛岀粨鏋勭湡瀹炪€佹潗璐ㄥ彲淇°€佸厜褰辫嚜鐒躲€?
                4. 绂佹澶氫骇鍝佹嫾璐淬€佷綆娓呮櫚搴︺€佺暩鍙樸€佹紓娴儴浠躲€佷笉鍙埗閫犵粨鏋勩€佸搧鐗?logo銆佹按鍗般€佺函鏂囧瓧娴锋姤銆?
                """.formatted(index, total, basePrompt == null ? "" : basePrompt);
    }

    @PostMapping("/api/inpaint")
    public ResponseEntity<Map<String, Object>> inpaint(
            @RequestParam("image")  MultipartFile image,
            @RequestParam("mask")   MultipartFile mask,
            @RequestParam("prompt") String prompt,
            @RequestParam(value = "sessionId", defaultValue = "default") String sessionId,
            HttpSession httpSession) {
        long userId = currentUserService.requireUserId(httpSession);

        if (image == null || image.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "缂哄皯鍘熷浘"));
        }
        if (mask == null || mask.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "缂哄皯钂欑増"));
        }
        if (prompt == null || prompt.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "?????"));
        }

        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
        String outputDir = userTempOutputDir(userId, "局部编辑/" + timestamp);
        String outputPath = new File(outputDir, "1.png").getAbsolutePath();

        File imageTmp = null;
        File maskTmp  = null;
        try {
            // 涓婁紶鏂囦欢钀藉埌绯荤粺 temp锛岄伩鍏嶆薄鏌撶敤鎴疯緭鍑虹洰褰曪紱finally 閲屾竻鐞?
            imageTmp = File.createTempFile("inpaint_image_", ".png");
            maskTmp  = File.createTempFile("inpaint_mask_",  ".png");
            image.transferTo(imageTmp);
            mask.transferTo(maskTmp);
            log.info("inpaint: image={} mask={}", imageTmp.getAbsolutePath(), maskTmp.getAbsolutePath());

            String inferredAspect = resolveAutoAspect("auto", List.of(imageTmp));
            File finalImageTmp = imageTmp;
            File finalMaskTmp = maskTmp;
            boolean ok = GenerationInvocationContext.withUserId(userId,
                    () -> gptImageAgent.generateWithMask(prompt, finalImageTmp, finalMaskTmp, outputPath, inferredAspect));
            if (!ok) {
                return ResponseEntity.internalServerError()
                        .body(Map.of("error", "GPT-Image ?????????? API Key ???"));
            }
            // Phase 2锛氬綊妗ｅ師鍥?+ mask 浣滀负 ref锛涘啓鍘嗗彶璁板綍
            try {
                HistoryService.ArchiveResult archive = historyService.archiveRefFiles(userId,
                        java.util.List.of(imageTmp, maskTmp));
                historyService.recordGeneration(userId, sessionId, "inpaint", prompt, "gpt-image",
                        archive.refPaths, outputDir, null);
            } catch (Exception e) {
                log.warn("inpaint 鍐欏巻鍙插け璐? {}", e.getMessage());
            }
            // COS 涓婁紶
            String resultRef = outputPath;
            if (cosService.isEnabled()) {
                try { resultRef = cosService.upload(new File(outputPath), "inpaint.png"); }
                catch (Exception ce) { log.warn("inpaint COS 涓婁紶澶辫触: {}", ce.getMessage()); }
            }
            return ResponseEntity.ok(Map.of(
                "results", List.of(resultRef),
                "output_dir", outputDir,
                "thought", "銆愬眬閮ㄩ噸缁?路 鎻愮ず璇嶇洿閫侊紙鏈粡 LLM 澶勭悊锛夈€慭n妯″瀷: gpt-image\n"
                    + "闀垮害: " + prompt.length() + " 瀛梊n\n銆愭渶缁堟彁绀鸿瘝銆慭n" + prompt
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
    public ResponseEntity<Map<String, Object>> gptImageGenerate(@RequestBody Map<String, Object> body,
                                                                HttpSession httpSession) {
        long userId = currentUserService.requireUserId(httpSession);
        Map<String, Object> safeBody = body == null ? Map.of() : body;
        String agentId = String.valueOf(safeBody.getOrDefault("model", "gpt-image-2"));
        int estimatedPoints = pricingService.estimateImageGeneration("direct-gpt-image", agentId, 1);
        GenerationUsageLog usageLog = billingService.recordGenerationStarted(
                userId, "", "direct-gpt-image", agentId, estimatedPoints);

        List<GptImageAgent.RequestCredential> credentials = GenerationInvocationContext.withUserId(
                userId,
                gptImageAgent::resolveRequestCredentials
        );
        if (credentials.isEmpty()) {
            billingService.markGenerationFailed(usageLog.getId(), "GPT-Image API Key 未配置");
            return ResponseEntity.badRequest().body(Map.of("success", false, "error", "GPT-Image API Key ???"));
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("model", agentId);
        payload.put("prompt", safeBody.getOrDefault("prompt", ""));
        payload.put("size",    safeBody.getOrDefault("size",    "1024x1024"));
        payload.put("quality", safeBody.getOrDefault("quality", "auto"));
        payload.put("background",   safeBody.getOrDefault("background",   "auto"));
        payload.put("output_format", safeBody.getOrDefault("output_format", "png"));

        ObjectMapper mapper = new ObjectMapper();
        String jsonBody;
        try { jsonBody = mapper.writeValueAsString(payload); }
        catch (Exception e) {
            billingService.markGenerationFailed(usageLog.getId(), e.getMessage());
            return ResponseEntity.internalServerError().body(Map.of("success", false, "error", e.getMessage()));
        }

        // 鎸夊簭杞 key锛氶亣 429/5xx 鎴栫綉缁滃紓甯稿垯灏濊瘯涓嬩竴涓紱2xx 鎴栧叾浠?4xx 绔嬪埢杩斿洖
        String lastError = "鎵€鏈?key 鍧囦笉鍙敤";
        int lastStatus = 500;
        for (GptImageAgent.RequestCredential credential : credentials) {
            String apiKey = credential.apiKey();
            String baseUrl = credential.baseUrl();
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
                // try-with-resources 鍏?stream锛宑onn.disconnect() 涓嶄繚璇佸叧闂唴閮ㄦ祦锛圔 闃舵瀹℃煡 #2锛?
                try (java.io.InputStream is =
                        (status >= 200 && status < 300 ? conn.getInputStream() : conn.getErrorStream())) {
                    respBody = new String(is.readAllBytes(), StandardCharsets.UTF_8);
                } finally {
                    conn.disconnect();
                }

                if (status >= 200 && status < 300) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> resp = mapper.readValue(respBody, Map.class);
                    billingService.markGenerationSucceeded(usageLog.getId(), 0);
                    return ResponseEntity.ok(Map.of("success", true, "data", resp));
                }
                lastStatus = status;
                lastError = respBody;
                if (status != 429 && status < 500) {
                    billingService.markGenerationFailed(usageLog.getId(), respBody);
                    return ResponseEntity.status(status).body(Map.of("success", false, "error", respBody));
                }
                log.warn("GPT-Image key ??[{}] ?? {}??????",
                        apiKey.length() > 4 ? apiKey.substring(0, 4) + "***" : "***", status);
            } catch (Exception e) {
                lastError = e.getMessage();
                log.warn("GPT-Image key 灏惧彿[{}] 寮傚父: {}",
                        apiKey.length() > 4 ? apiKey.substring(0, 4) + "***" : "***", e.getMessage());
            }
        }
        log.error("gptImageGenerate 鎵€鏈?key 鍧囧け璐? {}", lastError);
        billingService.markGenerationFailed(usageLog.getId(), lastError);
        return ResponseEntity.status(lastStatus).body(Map.of("success", false, "error", lastError));
    }

    private String userTempOutputDir(long userId, String relativeDir) {
        try {
            Path dir = userStorageService.tempOutputRoot(userId).resolve(relativeDir).normalize();
            Files.createDirectories(dir);
            return dir.toFile().getAbsolutePath();
        } catch (Exception e) {
            throw new IllegalStateException("无法创建用户临时输出目录: " + relativeDir, e);
        }
    }

    // 绉佹湁 result() / countImages() 宸叉娊鍒?ControllerHelpers锛圔 闃舵瀹℃煡 #10锛?
}
