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
    private static final String GEMINI_BASE_URL = "https://generativelanguage.googleapis.com/v1beta/models/";
    private static final String GEMINI_ANALYSIS_MODEL = "gemini-2.5-flash";
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
     * 开品模式第一步：复用 Gemini 的文字/视觉能力，按用户输入动态拆成结构化卡片。
     * Gemini 负责从 prompt 中动态识别维度名称，并只返回 JSON 数组。
     */
    public List<Map<String, String>> analyzeProductText(String prompt, File imageFile, String agentId) {
        if (prompt == null || prompt.isBlank()) {
            return List.of();
        }
        try {
            List<Map<String, String>> fields = parseAnalysisFields(requestGeminiAnalysis(prompt, imageFile, false));
            if (looksLikeRefusal(fields)) {
                log.warn("Gemini 开品分析返回拒绝式结果，改用强约束提示词重试");
                fields = parseAnalysisFields(requestGeminiAnalysis(prompt, imageFile, true));
            }
            if (looksLikeRefusal(fields)) {
                log.warn("Gemini 强约束重试仍返回拒绝式结果，使用本地维度兜底生成可编辑卡片");
                fields = buildFallbackAnalysisFields(prompt);
            }
            return fields;
        } catch (Exception e) {
            log.error("开品结构化分析失败: {}", e.getMessage(), e);
            throw new RuntimeException("开品结构化分析失败: " + e.getMessage(), e);
        }
    }

    /**
     * 开品模式融合分析：基于产品 A/B、卖点、方案侧重、视觉风格，综合生成结构化分析卡片。
     * 这是两步流程的第一步，返回可编辑卡片供用户确认后二次生图。
     *
     * @param productA   产品 A 的文字描述
     * @param productB   产品 B 的文字描述
     * @param selling    核心卖点
     * @param focus      方案侧重（cost/premium/disruptive/custom）
     * @param focusText  自定义方案侧重文本（focus=custom 时使用）
     * @param style      视觉风格（dopamine/wood/cartoon/ins/minimal/cyberpunk/custom）
     * @param styleText  自定义视觉风格文本（style=custom 时使用）
     * @param imageA     产品 A 参考图（可选）
     * @param imageB     产品 B 参考图（可选）
     * @return 结构化分析卡片 [{key, value}, ...]
     */
    public List<Map<String, String>> analyzeKaiPin(
            String productA, String productB, String selling,
            String focus, String focusText, String style, String styleText,
            File imageA, File imageB) {

        try {
            // 构建综合分析 prompt
            String analysisPrompt = buildKaiPinAnalysisPrompt(productA, productB, selling, focus, focusText, style, styleText);

            // 调用 Gemini 分析（支持双图）
            List<Map<String, String>> fields = parseAnalysisFields(
                    requestGeminiKaiPinAnalysis(analysisPrompt, imageA, imageB, false));

            // 检查拒绝式结果，重试
            if (looksLikeRefusal(fields)) {
                log.warn("开品融合分析返回拒绝式结果，改用强约束提示词重试");
                fields = parseAnalysisFields(
                        requestGeminiKaiPinAnalysis(analysisPrompt, imageA, imageB, true));
            }

            // 最终兜底
            if (looksLikeRefusal(fields) || fields.isEmpty()) {
                log.warn("开品融合分析重试仍失败，使用本地维度兜底");
                fields = buildFallbackKaiPinFields(productA, productB, selling, focus, style);
            }

            return fields;
        } catch (Exception e) {
            log.error("开品融合分析失败: {}", e.getMessage(), e);
            // 异常时返回兜底卡片
            return buildFallbackKaiPinFields(productA, productB, selling, focus, style);
        }
    }

    /**
     * 构建开品融合分析的 prompt
     */
    private String buildKaiPinAnalysisPrompt(
            String productA, String productB, String selling,
            String focus, String focusText, String style, String styleText) {

        // 方案侧重文本
        String focusPrompt = switch (focus) {
            case "cost" -> "【方案侧重】成本与量产：CMF 与结构在不牺牲核心功能的前提下尽量降低成本与开模难度；优先采用注塑/钣金等成熟工艺；颜色与材质走简洁实用风。";
            case "premium" -> "【方案侧重】颜值与溢价：放大造型语言的高端感；CMF 选择高级材质（拉丝金属/真皮/木纹/陶瓷釉面）；表面工艺精致；体现“摆在客厅当艺术品也不违和”的格调。";
            case "disruptive" -> "【方案侧重】颠覆性创新：允许突破常规结构去拥抱产品 B 的造型；可加入隐藏机构、模块化、可拆解、智能化等新颖设计语言；视觉冲击优先。";
            case "custom" -> focusText != null && !focusText.isBlank()
                    ? "【方案侧重·自定义】" + focusText
                    : "【方案侧重】（用户未指定，请根据产品特性自行判断合适的设计取向）";
            default -> "【方案侧重】成本与量产：CMF 与结构在不牺牲核心功能的前提下尽量降低成本与开模难度；优先采用注塑/钣金等成熟工艺；颜色与材质走简洁实用风。";
        };

        // 视觉风格文本
        String stylePrompt = switch (style) {
            case "dopamine" -> "【视觉风格】多巴胺：高饱和撞色（柠檬黄/珊瑚红/薄荷绿）；圆润边角；活泼趣味造型细节；色彩大胆跳跃，充满视觉能量感；场景道具也选用高饱和色彩。";
            case "wood" -> "【视觉风格】木元素：产品本体的外壳/主体部分改为原木材质（橡木/胡桃木/竹材纹理）；金属或塑料区域用木纹饰面替代；保留产品功能细节；暖米色与原木棕色调，体现温润自然感。";
            case "cartoon" -> "【视觉风格】卡通：产品造型圆润可爱化；色彩亮丽柔和；增加拟人化趣味细节；场景配以简洁卡通风格背景元素；整体呈现亲切活泼的儿童/年轻用户氛围。";
            case "ins" -> "【视觉风格】ins 风：清新奶油色系（象牙白/浅粉/哑光米灰）；柔和弥散光；干净留白构图；产品与鲜花/绿植/咖啡等生活道具搭配；小红书/Instagram 高颜值打卡风格。";
            case "minimal" -> "【视觉风格】极简：背景纯净（白/浅灰/米）；产品居中大量留白；去除一切多余装饰；线条简洁；色彩克制（单色或双色）；体现“少即是多”的设计哲学。";
            case "cyberpunk" -> "【视觉风格】赛博朋克：深色背景配霓虹灯光（紫/青/粉）；发光线条与光效；金属质感与电路纹路；高对比度；呈现科技感十足的未来都市氛围。";
            case "custom" -> styleText != null && !styleText.isBlank()
                    ? "【视觉风格·自定义】" + styleText
                    : "";
            default -> ""; // 不指定风格
        };

        return """
                你是资深跨界开品设计师，擅长把产品 A 的功能内核与产品 B 的造型语言融合，生成新概念产品。

                请分析以下输入，并输出结构化的设计分析卡片：

                【产品 A · 功能本体】
                %s

                【产品 B · 造型灵感】
                %s

                【核心卖点】
                %s

                %s
                %s

                输出要求：
                1. 从设计角度动态提取维度，推荐维度：产品定位、融合方向、造型语言、材质工艺、功能布局、使用场景、视觉氛围。
                2. 每个维度的 value 要具体描述可落地的设计特征，便于后续直接生成产品使用场景图。
                3. 融合 A 的功能特点与 B 的造型特点，生成有创新性的新概念产品描述。
                4. 输出必须是严格 JSON 数组，格式：[{"key":"维度名","value":"分析内容"}]。
                5. 只输出 JSON，不要 markdown，不要代码块，不要解释。
                """.formatted(
                productA == null || productA.isBlank() ? "（文字未提供，请从参考图中提取功能特征）" : productA,
                productB == null || productB.isBlank() ? "（文字未提供，请从参考图中提取造型特征）" : productB,
                selling == null || selling.isBlank() ? "（未提供卖点，请根据产品特性自行提炼）" : selling,
                focusPrompt,
                stylePrompt.isEmpty() ? "" : stylePrompt
        );
    }

    /**
     * 调用 Gemini 进行开品融合分析（支持双图）
     */
    private String requestGeminiKaiPinAnalysis(String prompt, File imageA, File imageB, boolean strictRetry) throws IOException {
        String apiKey = appProperties.getGemini().getApiKey();
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("Gemini API Key 未配置");
        }

        ObjectNode root = objectMapper.createObjectNode();
        ObjectNode systemInstruction = root.putObject("systemInstruction");
        String systemText = "你是资深跨界开品设计师，擅长把产品 A 的功能内核与产品 B 的造型语言融合。你必须只返回严格 JSON 数组，格式为 [{\"key\":\"维度名\",\"value\":\"分析内容\"}]。";
        if (strictRetry) {
            systemText += "\n\n重要：本轮不是资料完整性诊断。即使部分信息缺失，也必须基于已有信息输出可编辑分析卡片，禁止输出“异常说明”“请补充信息”“无法分析”。";
        }
        systemInstruction.putArray("parts").addObject().put("text", systemText);

        ArrayNode contents = root.putArray("contents");
        ObjectNode content = contents.addObject();
        content.put("role", "user");
        ArrayNode parts = content.putArray("parts");
        parts.addObject().put("text", prompt);

        // 添加产品 A 图片
        if (imageA != null && imageA.exists() && imageA.isFile()) {
            byte[] bytesA = Files.readAllBytes(imageA.toPath());
            parts.addObject()
                    .putObject("inlineData")
                    .put("mimeType", getMimeType(imageA.getName()))
                    .put("data", Base64.getEncoder().encodeToString(bytesA));
        }

        // 添加产品 B 图片
        if (imageB != null && imageB.exists() && imageB.isFile()) {
            byte[] bytesB = Files.readAllBytes(imageB.toPath());
            parts.addObject()
                    .putObject("inlineData")
                    .put("mimeType", getMimeType(imageB.getName()))
                    .put("data", Base64.getEncoder().encodeToString(bytesB));
        }

        ObjectNode generationConfig = root.putObject("generationConfig");
        generationConfig.put("temperature", 0.3);
        generationConfig.put("maxOutputTokens", 4000);
        generationConfig.putObject("thinkingConfig").put("thinkingBudget", 0);

        Request request = new Request.Builder()
                .url(GEMINI_BASE_URL + GEMINI_ANALYSIS_MODEL + ":generateContent?key=" + apiKey)
                .post(RequestBody.create(root.toString(), JSON_TYPE))
                .build();

        try (Response response = buildAnalysisClient().newCall(request).execute()) {
            String body = response.body() != null ? response.body().string() : "";
            if (!response.isSuccessful()) {
                throw new RuntimeException("Gemini 开品融合分析失败(" + response.code() + "): " + body);
            }
            return extractGeminiOutputText(body);
        }
    }

    /**
     * 开品融合分析的本地兜底卡片
     */
    private List<Map<String, String>> buildFallbackKaiPinFields(
            String productA, String productB, String selling, String focus, String style) {

        String subjectA = productA != null && !productA.isBlank()
                ? productA.substring(0, Math.min(50, productA.length()))
                : "产品 A";
        String subjectB = productB != null && !productB.isBlank()
                ? productB.substring(0, Math.min(50, productB.length()))
                : "产品 B";

        List<Map<String, String>> fields = new ArrayList<>();

        fields.add(Map.of("key", "产品定位",
                "value", "以" + subjectA + "的功能内核为核心，融合" + subjectB + "的造型语言，生成面向年轻/科技人群的新概念产品。"));

        fields.add(Map.of("key", "融合方向",
                "value", "保留" + subjectA + "的核心功能结构和操作逻辑，将" + subjectB + "的线条比例、视觉语言、材质感迁移到新产品外形。"));

        fields.add(Map.of("key", "造型语言",
                "value", "整体轮廓参考" + subjectB + "，边角过渡明确，主次结构层级清晰，视觉重心稳定，呈现可制造的真实产品尺度。"));

        fields.add(Map.of("key", "材质工艺",
                "value", "表面材质结合" + focus + "取向，可选哑光/亮面/金属/塑胶等质感对比，边缘收口干净，工艺细节精致。"));

        fields.add(Map.of("key", "功能布局",
                "value", "功能区域清晰分层，操作区、支撑区、核心工作区有明确边界，结构服务于实际使用动作，人机交互直观。"));

        fields.add(Map.of("key", "使用场景",
                "value", "放置在真实使用场景中展示，环境干净克制，光影自然，画面突出产品主体及其与用户生活方式的关系。"));

        if (selling != null && !selling.isBlank()) {
            fields.add(Map.of("key", "核心卖点呈现",
                    "value", selling + " — 在画面中通过产品姿态、环境道具或视觉符号强化这一卖点。"));
        }

        if (style != null && !style.isBlank()) {
            String styleDesc = switch (style) {
                case "dopamine" -> "高饱和撞色、活泼趣味造型细节";
                case "wood" -> "原木材质纹理、温润自然色调";
                case "cartoon" -> "圆润可爱化、拟人化趣味细节";
                case "ins" -> "清新奶油色系、柔和弥散光";
                case "minimal" -> "纯净背景、大量留白、线条简洁";
                case "cyberpunk" -> "霓虹灯光、金属质感、高对比度";
                default -> "";
            };
            if (!styleDesc.isBlank()) {
                fields.add(Map.of("key", "视觉氛围",
                        "value", styleDesc + "，整体呈现符合目标人群审美的视觉调性。"));
            }
        }

        return fields;
    }

    private String requestGeminiAnalysis(String prompt, File imageFile, boolean strictRetry) throws IOException {
        String apiKey = appProperties.getGemini().getApiKey();
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("Gemini API Key 未配置");
        }

        ObjectNode root = objectMapper.createObjectNode();
        ObjectNode systemInstruction = root.putObject("systemInstruction");
        systemInstruction.putArray("parts").addObject().put("text",
                "你是资深工业设计与电商开品分析师。你必须只返回严格 JSON 数组，格式为 [{\"key\":\"维度名\",\"value\":\"分析内容\"}]。");

        ArrayNode contents = root.putArray("contents");
        ObjectNode content = contents.addObject();
        content.put("role", "user");
        ArrayNode parts = content.putArray("parts");
        parts.addObject().put("text", buildProductAnalysisPrompt(prompt, imageFile != null, strictRetry));
        if (imageFile != null && imageFile.exists() && imageFile.isFile()) {
            byte[] bytes = Files.readAllBytes(imageFile.toPath());
            parts.addObject()
                    .putObject("inlineData")
                    .put("mimeType", getMimeType(imageFile.getName()))
                    .put("data", Base64.getEncoder().encodeToString(bytes));
        }

        ObjectNode generationConfig = root.putObject("generationConfig");
        generationConfig.put("temperature", 0.2);
        generationConfig.put("maxOutputTokens", 4000);
        generationConfig.putObject("thinkingConfig").put("thinkingBudget", 0);

        Request request = new Request.Builder()
                .url(GEMINI_BASE_URL + GEMINI_ANALYSIS_MODEL + ":generateContent?key=" + apiKey)
                .post(RequestBody.create(root.toString(), JSON_TYPE))
                .build();

        try (Response response = buildAnalysisClient().newCall(request).execute()) {
            String body = response.body() != null ? response.body().string() : "";
            if (!response.isSuccessful()) {
                throw new RuntimeException("Gemini 分析失败(" + response.code() + "): " + body);
            }
            return extractGeminiOutputText(body);
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

    private String extractGeminiOutputText(String responseBody) throws IOException {
        JsonNode root = objectMapper.readTree(responseBody);
        StringBuilder texts = new StringBuilder();
        JsonNode candidates = root.path("candidates");
        if (candidates.isArray()) {
            for (JsonNode candidate : candidates) {
                JsonNode parts = candidate.path("content").path("parts");
                if (!parts.isArray()) continue;
                for (JsonNode part : parts) {
                    String text = part.path("text").asText("");
                    if (!text.isBlank()) texts.append(text);
                }
            }
        }
        String out = texts.toString().trim();
        if (!out.isBlank()) return out;
        throw new IOException("Gemini 未返回文本内容: " + responseBody);
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
                请参考自定义模式的图片分析思路理解输入，再按用户要求拆成可编辑的开品分析卡片。

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
            throw new IOException("Gemini 未返回 JSON 数组: " + rawText);
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
            throw new IOException("Gemini 返回的分析字段为空: " + rawText);
        }
        return fields;
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
