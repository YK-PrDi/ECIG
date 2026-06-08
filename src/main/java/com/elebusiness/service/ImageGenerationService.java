package com.elebusiness.service;

import com.elebusiness.config.AppProperties;
import com.elebusiness.service.agent.ImageGeneratorAgent;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.File;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class ImageGenerationService {

    private static final Logger log = LoggerFactory.getLogger(ImageGenerationService.class);
    private static final String DEFAULT_AGENT_ID = "gpt-image";
    private static final String DOUBAO_ANALYSIS_MODEL = "doubao-seed-2-0-lite-260215";
    private static final MediaType JSON_TYPE = MediaType.get("application/json; charset=utf-8");

    private final AppProperties appProperties;
    private final Map<String, ImageGeneratorAgent> agentMap;
    private final Random random = new Random();
    private final ExecutorService executor;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public ImageGenerationService(AppProperties appProperties, List<ImageGeneratorAgent> agents) {
        this.appProperties = appProperties;
        this.agentMap = new LinkedHashMap<>();
        agents.forEach(a -> agentMap.put(a.getId(), a));
        this.executor = Executors.newFixedThreadPool(appProperties.getApi().getMaxConcurrent());
        log.info("已注册智能体: {}，并发数: {}", agentMap.keySet(), appProperties.getApi().getMaxConcurrent());
    }

    public ExecutorService getExecutor() { return executor; }

    /** 返回所有已注册智能体的描述列表，供前端展示 */
    public List<Map<String, String>> listAgents() {
        List<Map<String, String>> result = new ArrayList<>();
        agentMap.forEach((id, agent) -> result.add(Map.of(
                "id", id,
                "name", agent.getDisplayName()
        )));
        return result;
    }

    /**
     * 开品模式第一步：复用自定义模式的豆包视觉分析思路，按用户输入动态拆成结构化卡片。
     * 豆包负责从 prompt 中动态识别维度名称，并只返回 JSON 数组。
     */
    public List<Map<String, String>> analyzeProductText(String prompt, File imageFile, String agentId) {
        if (prompt == null || prompt.isBlank()) {
            return List.of();
        }
        try {
            List<Map<String, String>> fields = parseAnalysisFields(requestDoubaoAnalysis(prompt, imageFile, false));
            if (looksLikeRefusal(fields)) {
                log.warn("豆包开品分析返回拒绝式结果，改用强约束提示词重试");
                fields = parseAnalysisFields(requestDoubaoAnalysis(prompt, imageFile, true));
            }
            if (looksLikeRefusal(fields)) {
                log.warn("豆包强约束重试仍返回拒绝式结果，使用本地维度兜底生成可编辑卡片");
                fields = buildFallbackAnalysisFields(prompt);
            }
            return fields;
        } catch (Exception e) {
            log.error("开品结构化分析失败: {}", e.getMessage(), e);
            throw new RuntimeException("开品结构化分析失败: " + e.getMessage(), e);
        }
    }

    private String requestDoubaoAnalysis(String prompt, File imageFile, boolean strictRetry) throws IOException {
        String apiKey = appProperties.getVolcengine().getApiKey();
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("豆包 API Key 未配置");
        }

        ObjectNode root = objectMapper.createObjectNode();
        root.put("model", resolveDoubaoAnalysisModel());
        ArrayNode inputs = root.putArray("input");
        ObjectNode message = inputs.addObject();
        message.put("role", "user");
        ArrayNode content = message.putArray("content");
        if (imageFile != null && imageFile.exists() && imageFile.isFile()) {
            byte[] bytes = Files.readAllBytes(imageFile.toPath());
            content.addObject()
                    .put("type", "input_image")
                    .put("image_url", "data:" + getMimeType(imageFile.getName()) + ";base64,"
                            + Base64.getEncoder().encodeToString(bytes));
        }
        content.addObject().put("type", "input_text").put("text", buildProductAnalysisPrompt(prompt, imageFile != null, strictRetry));

        Request request = new Request.Builder()
                .url(normalizeBaseUrl(appProperties.getVolcengine().getBaseUrl()) + "/responses")
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .post(RequestBody.create(root.toString(), JSON_TYPE))
                .build();

        try (Response response = buildAnalysisClient().newCall(request).execute()) {
            String body = response.body() != null ? response.body().string() : "";
            if (!response.isSuccessful()) {
                throw new RuntimeException("豆包分析失败(" + response.code() + "): " + body);
            }
            return extractDoubaoOutputText(body);
        }
    }

    public boolean generateImage(String prompt, String refImagePath,
                                 String whiteBgPath, String outputPath, String agentId) {
        ImageGeneratorAgent agent = resolveAgent(agentId);
        log.info("使用智能体 [{}] 生成图片", agent.getId());
        return agent.generate(prompt, refImagePath, whiteBgPath, outputPath);
    }

    /**
     * 多参考图重载（品牌/产品一致性场景）。
     * 当 agent 未覆写 generateMulti 时，默认实现会自动降级到单参考图版本。
     */
    public boolean generateImageMulti(String prompt, List<String> refImagePaths,
                                      String whiteBgPath, String outputPath,
                                      String agentId, String aspect) {
        ImageGeneratorAgent agent = resolveAgent(agentId);
        log.info("使用智能体 [{}] 生成图片（refs={}, aspect={}）", agent.getId(),
                refImagePaths == null ? 0 : refImagePaths.size(), aspect);
        return agent.generateMulti(prompt, refImagePaths, whiteBgPath, outputPath, aspect);
    }

    public void generateSkuImages(String whiteBgUrl, String refPath,
                                  String outputFolder, List<String> skuList, String agentId, String userPrompt) {
        File skuRefDir = new File(refPath, "SKU");
        if (!skuRefDir.exists()) { log.warn("SKU 参考图目录不存在: {}", skuRefDir.getAbsolutePath()); return; }
        List<File> skuRefs = listImages(skuRefDir);
        if (skuRefs.isEmpty()) { log.warn("SKU 参考图目录为空"); return; }

        File skuOutputDir = new File(outputFolder, "SKU");
        skuOutputDir.mkdirs();

        List<CompletableFuture<Void>> futures = new ArrayList<>();
        for (int i = 0; i < skuList.size(); i++) {
            final int idx = i;
            final File ref = skuRefs.get(random.nextInt(skuRefs.size()));
            final String prompt = (userPrompt != null && !userPrompt.isBlank())
                    ? userPrompt
                    : "保留参考图的背景，移除参考图中的物品主体，将白底图中的产品替换到背景中。" + skuList.get(i);
            final String outputPath = new File(skuOutputDir, (idx + 1) + ".jpg").getAbsolutePath();
            futures.add(CompletableFuture.runAsync(() -> {
                boolean ok = generateImage(prompt, ref.getAbsolutePath(), whiteBgUrl, outputPath, agentId);
                log.info("SKU 图 {}: {}", idx + 1, ok ? "成功" : "失败");
            }, executor));
        }
        futures.forEach(f -> { try { f.join(); } catch (Exception e) { log.warn("SKU 图生成异常: {}", e.getMessage()); } });
    }

    public List<String> generateMainImages(String whiteBgUrl, String refPath,
                                           String outputFolder, List<String> mainList, String agentId, String userPrompt) {
        if (mainList == null || mainList.isEmpty()) return List.of();

        File mainRefDir = new File(refPath, "主图");
        if (!mainRefDir.exists()) { log.warn("主图参考图目录不存在: {}", mainRefDir.getAbsolutePath()); return List.of(); }
        List<File> mainRefs = listImages(mainRefDir);
        if (mainRefs.isEmpty()) { log.warn("主图参考图目录为空"); return List.of(); }

        File mainOutputDir = new File(outputFolder, "主图");
        mainOutputDir.mkdirs();

        List<CompletableFuture<String>> futures = new ArrayList<>();
        for (int i = 0; i < mainList.size(); i++) {
            final int idx = i;
            final File ref = mainRefs.get(random.nextInt(mainRefs.size()));
            final String prompt = (userPrompt != null && !userPrompt.isBlank())
                    ? userPrompt
                    : "保留参考图的背景，移除参考图中的物品主体，将白底图中的产品替换到背景中。" + mainList.get(i);
            final String outputPath = new File(mainOutputDir, (idx + 1) + ".jpg").getAbsolutePath();
            futures.add(CompletableFuture.supplyAsync(() -> {
                boolean ok = generateImage(prompt, ref.getAbsolutePath(), whiteBgUrl, outputPath, agentId);
                log.info("主图 {}: {}", idx + 1, ok ? "成功" : "失败");
                return ok ? outputPath : null;
            }, executor));
        }

        List<String> outputPaths = new ArrayList<>();
        futures.forEach(f -> {
            try {
                String p = f.join();
                if (p != null) outputPaths.add(p);
            } catch (Exception e) { log.warn("主图生成异常: {}", e.getMessage()); }
        });
        return outputPaths;
    }

    public void generateDetailImages(String mainImgPath, String outputFolder,
                                     String whiteBgUrl, String refPath, String agentId, String userPrompt) {
        if (mainImgPath == null || !new File(mainImgPath).exists()) return;

        File detailRefDir = new File(refPath, "详情图");
        if (!detailRefDir.exists()) { log.warn("详情图参考目录不存在: {}", detailRefDir.getAbsolutePath()); return; }
        List<File> detailRefs = listImages(detailRefDir);
        if (detailRefs.isEmpty()) return;

        File detailOutputDir = new File(outputFolder, "详情图");
        detailOutputDir.mkdirs();

        File randomRef = detailRefs.get(random.nextInt(detailRefs.size()));
        String prompt = (userPrompt != null && !userPrompt.isBlank())
                ? userPrompt
                : "将白底图中的产品重新布局为9:16竖版格式，保持产品主体突出，合理压缩和排布内容，适合详情页展示";
        String outputPath = new File(detailOutputDir, new File(mainImgPath).getName()).getAbsolutePath();
        boolean ok = generateImage(prompt, randomRef.getAbsolutePath(), whiteBgUrl, outputPath, agentId);
        log.info("详情图: {}", ok ? "成功" : "失败");
    }

    public int getNextOutputNumber(String categoryOutputDir, String productName) {
        File dir = new File(categoryOutputDir);
        if (!dir.exists()) return 1;
        File[] folders = dir.listFiles(f -> f.isDirectory() && f.getName().startsWith(productName));
        if (folders == null || folders.length == 0) return 1;
        int maxNum = 0;
        Pattern p = Pattern.compile("_(\\d+)$");
        for (File folder : folders) {
            Matcher m = p.matcher(folder.getName());
            if (m.find()) maxNum = Math.max(maxNum, Integer.parseInt(m.group(1)));
        }
        return maxNum + 1;
    }

    private ImageGeneratorAgent resolveAgent(String agentId) {
        if (agentId != null && agentMap.containsKey(agentId)) {
            return agentMap.get(agentId);
        }
        ImageGeneratorAgent fallback = agentMap.get(DEFAULT_AGENT_ID);
        if (fallback == null) fallback = agentMap.values().iterator().next();
        return fallback;
    }

    private List<File> listImages(File dir) {
        File[] files = dir.listFiles(f -> {
            String name = f.getName().toLowerCase();
            return f.isFile() && (name.endsWith(".jpg") || name.endsWith(".jpeg") || name.endsWith(".png"));
        });
        return files == null ? List.of() : Arrays.asList(files);
    }

    private OkHttpClient buildAnalysisClient() {
        OkHttpClient.Builder builder = new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .readTimeout(90, TimeUnit.SECONDS);
        AppProperties.Proxy proxy = appProperties.getProxy();
        if (proxy.isEnabled()) {
            builder.proxy(new java.net.Proxy(
                    java.net.Proxy.Type.HTTP,
                    new InetSocketAddress(proxy.getHost(), proxy.getPort())));
        } else {
            builder.proxySelector(java.net.ProxySelector.getDefault());
        }
        return builder.build();
    }

    private String extractDoubaoOutputText(String responseBody) throws IOException {
        JsonNode root = objectMapper.readTree(responseBody);
        JsonNode output = root.path("output");
        if (output.isArray()) {
            for (JsonNode item : output) {
                if (!"message".equals(item.path("type").asText())) continue;
                JsonNode content = item.path("content");
                if (!content.isArray()) continue;
                for (JsonNode part : content) {
                    String text = part.path("text").asText("");
                    if ("output_text".equals(part.path("type").asText()) && !text.isBlank()) {
                        return text.trim();
                    }
                }
            }
        }
        String fallback = root.path("output_text").asText("");
        if (!fallback.isBlank()) return fallback.trim();
        throw new IOException("豆包未返回文本内容: " + responseBody);
    }

    private String buildProductAnalysisPrompt(String prompt, boolean hasImage, boolean strictRetry) {
        String dimensionHint = extractDimensionHint(prompt);
        String subjectHint = extractSubjectHint(prompt);
        String strictText = strictRetry ? """

                重要纠偏：
                上一次输出像是在要求补充资料，这是错误的。本轮不是资料完整性诊断，而是把用户文字直接转换为开品分析卡片。
                如果没有图片，用户文字中的产品对象就是分析对象；必须输出可编辑卡片，禁止输出“异常说明”“请补充信息”“无法分析”。
                如果用户写了“从 A、B 角度分析一个 Z”，必须输出 A、B 两个 key，并围绕 Z 填写 value。
                后端已识别维度提示：%s
                后端已识别产品对象提示：%s
                """.formatted(dimensionHint.isBlank() ? "按用户原文自行提取" : dimensionHint,
                subjectHint.isBlank() ? "按用户原文自行识别" : subjectHint) : "";
        return """
                你是资深工业设计与电商开品分析师。
                请像自定义模式里的豆包图片分析一样理解输入，再按用户要求拆成可编辑的开品分析卡片。

                本次是否上传图片：%s
                用户分析提示词：
                %s
                %s
                输出要求：
                1. 从用户提示词中动态提取维度名称，不要硬编码维度；如果出现“从 A、B、C 角度分析”，A/B/C 就是 key。
                2. 输出必须是严格 JSON 数组，格式：[{"key":"维度名","value":"分析内容"}]。
                3. 只输出 JSON，不要 markdown，不要代码块，不要解释。
                4. key 使用中文短标签；value 要能直接拼入后续生图提示词，聚焦可观察的结构、比例、体量、材质、功能线索、使用场景或造型语言。
                5. 即使没有图片，也必须基于用户文字做结构化拆解；不要要求补充资料。
                """.formatted(hasImage ? "是" : "否", prompt, strictText);
    }

    private String extractDimensionHint(String prompt) {
        if (prompt == null) return "";
        Matcher matcher = Pattern.compile("从(.{1,120}?)(?:角度|维度)").matcher(prompt);
        if (!matcher.find()) return "";
        String raw = matcher.group(1)
                .replace("等", "")
                .replace("方面", "")
                .trim();
        List<String> parts = Arrays.stream(raw.split("[、，,；;和与/\\s]+"))
                .map(String::trim)
                .filter(s -> !s.isBlank())
                .limit(8)
                .toList();
        return String.join("、", parts);
    }

    private String extractSubjectHint(String prompt) {
        if (prompt == null) return "";
        Matcher matcher = Pattern.compile("分析(.{1,120}?)(?:，|,|。|；|;|并|$)").matcher(prompt);
        if (!matcher.find()) return "";
        return matcher.group(1).trim();
    }

    private boolean looksLikeRefusal(List<Map<String, String>> fields) {
        if (fields == null || fields.isEmpty()) return true;
        if (fields.size() > 1) return false;
        Map<String, String> only = fields.get(0);
        String key = only.getOrDefault("key", "");
        String value = only.getOrDefault("value", "");
        String text = key + " " + value;
        return text.contains("异常") || text.contains("无法") || text.contains("不能")
                || text.contains("缺少") || text.contains("未提供") || text.contains("请补充")
                || text.contains("重新发起") || text.contains("重试");
    }

    private List<Map<String, String>> buildFallbackAnalysisFields(String prompt) {
        String subject = extractSubjectHint(prompt);
        if (subject.isBlank()) subject = "该产品";
        String dimensionHint = extractDimensionHint(prompt);
        List<String> dimensions = dimensionHint.isBlank()
                ? List.of("几何结构", "体量感", "材质工艺", "使用场景")
                : Arrays.stream(dimensionHint.split("、"))
                    .map(String::trim)
                    .filter(s -> !s.isBlank())
                    .limit(8)
                    .toList();
        List<Map<String, String>> fields = new ArrayList<>();
        for (String dimension : dimensions) {
            fields.add(Map.of("key", dimension, "value", fallbackValueForDimension(subject, dimension)));
        }
        return fields;
    }

    private String fallbackValueForDimension(String subject, String dimension) {
        if (dimension.contains("几何") || dimension.contains("结构") || dimension.contains("造型")) {
            return subject + "以清晰可辨的基础几何轮廓为主体，边缘转折明确，主次结构层级清楚，适合在生图中强化产品外形识别度。";
        }
        if (dimension.contains("材质") || dimension.contains("工艺")) {
            return subject + "表面材质需要明确呈现触感与工艺细节，可使用哑光、亮面、透光、金属或细腻塑胶等质感对比，边缘收口干净。";
        }
        if (dimension.contains("体量") || dimension.contains("比例")) {
            return subject + "整体体量关系稳定，主体比例协调，视觉重心清楚，避免部件过度膨胀或比例失衡，呈现可制造的真实产品尺度。";
        }
        if (dimension.contains("功能") || dimension.contains("分区")) {
            return subject + "功能区域需要在画面中清晰分层，操作区、支撑区、核心工作区有明确边界，结构服务于实际使用动作。";
        }
        if (dimension.contains("场景") || dimension.contains("使用")) {
            return subject + "放置在真实使用场景中展示，环境干净克制，光影自然，画面突出产品主体及其与用户生活方式的关系。";
        }
        return subject + "围绕“" + dimension + "”进行视觉强化，描述应具体落到形态、材质、比例、结构关系和真实使用感，便于后续二次生图。";
    }

    private List<Map<String, String>> parseAnalysisFields(String rawText) throws IOException {
        String json = stripJsonFence(rawText);
        JsonNode root = objectMapper.readTree(json);
        if (!root.isArray()) {
            throw new IOException("豆包未返回 JSON 数组: " + rawText);
        }
        List<Map<String, String>> fields = new ArrayList<>();
        for (JsonNode item : root) {
            String key = item.path("key").asText("").trim();
            String value = item.path("value").asText("").trim();
            if (!key.isBlank() && !value.isBlank()) {
                fields.add(Map.of("key", key, "value", value));
            }
        }
        if (fields.isEmpty()) {
            throw new IOException("豆包返回的分析字段为空: " + rawText);
        }
        return fields;
    }

    private String resolveDoubaoAnalysisModel() {
        String configured = appProperties.getVolcengine().getModel();
        if (configured != null && configured.startsWith("doubao-seed-")) {
            return configured;
        }
        return DOUBAO_ANALYSIS_MODEL;
    }

    private String normalizeBaseUrl(String baseUrl) {
        String url = (baseUrl == null || baseUrl.isBlank())
                ? "https://ark.cn-beijing.volces.com/api/v3"
                : baseUrl.trim();
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    private String stripJsonFence(String text) {
        if (text == null) return "[]";
        String s = text.trim();
        if (s.startsWith("```")) {
            s = s.replaceFirst("^```(?:json)?\\s*", "");
            s = s.replaceFirst("\\s*```$", "");
        }
        int start = s.indexOf('[');
        int end = s.lastIndexOf(']');
        if (start >= 0 && end > start) {
            return s.substring(start, end + 1);
        }
        return s;
    }

    private String getMimeType(String path) {
        String lower = path.toLowerCase();
        if (lower.endsWith(".png")) return "image/png";
        if (lower.endsWith(".webp")) return "image/webp";
        if (lower.endsWith(".gif")) return "image/gif";
        return "image/jpeg";
    }
}
