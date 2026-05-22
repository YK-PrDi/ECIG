package com.elebusiness.service;

import com.elebusiness.config.AppProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import okhttp3.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.net.InetSocketAddress;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * 用 Gemini 文本端点把超长电商提示词压缩到 GPT-Image 友好的长度（目标 ≤400 字），
 * 同时保留画面主体、场景、材质、光影、文字排版等关键视觉元素。
 * 结果以 SHA-1(原文) 为 key 缓存在内存，重复调用零延迟。
 */
@Service
public class PromptCondenser {

    private static final Logger log = LoggerFactory.getLogger(PromptCondenser.class);
    private static final String MODEL = "gemini-2.5-flash"; // 文本模型；图模型不适合做文本压缩
    private static final String BASE_URL = "https://generativelanguage.googleapis.com/v1beta/models/";
    private static final MediaType JSON_TYPE = MediaType.get("application/json; charset=utf-8");
    private static final int MAX_INPUT_LEN = 1200; // 小于此长度直接跳过压缩；GPT-Image 实测能稳定吃 ~1500 字

    private static final String SYSTEM_RULE = """
        你是电商主图生图提示词的压缩专家。请把用户给的长提示词压缩到 2000 字以内（GPT-Image 实测稳定吃 ~2000-3000 字，超过 3000 字注意力会衰减、关键约束被淹没），同时严格遵守：
        1. 必须保留：主体产品形态、安装结构、材质与颜色、文字/卖点标语及其位置、画面分区/构图（如"右上角两行""2×2 网格"等）、特效（蓝色发光、箭头、对比勾叉等）、背景元素（墙面材质、配饰、光影调性）。
        2. 【最高优先级·禁止压缩或改写】凡用【...】方括号包裹的段落（如【主体一致性·xx-最高优先级】【禁止·xx】等），整段照抄到输出，禁止改写、合并、删字。这部分已计入 2000 字预算，请把"非方括号正文"控制在剩余预算内。
        3. 必须删除或合并的冗余：(a) 重复的修饰性形容词（"细腻""通透""自然柔和""清晰可见"叠用 3 次以上的留 1 次）；(b) 把同一意思用不同字眼说两遍的句子（"主次层级清晰"和"层级分明"留一句）；(c) "画面焦点为绝对视觉中心""所有元素无遮挡核心主体""空间层级分明"这类模板话术；(d) 解释性括号里的同义重复；(e) 把"拍摄角度：xxx；产品摆放：xxx；场景布局：xxx"这种字段标签拼成自然段，不要保留"xxx：xxx"的 K-V 重复。
        4. 禁止：换一种说法改写非【】段、添加原文没有的元素、翻译成英文、输出 JSON 或任何结构化格式。
        5. 输出必须是完整、闭合、可直接送给图像模型的中文段落 —— 禁止以未完成的列表编号（如"3)"）、省略号、半句话或冒号收尾；如果剩余预算不够写完一段，就把这段砍掉重新组织，确保整体闭合。
        6. 如果原文已经在 2200 字以内，无需强行压缩，原样返回即可（保留所有【】并把字段标签拼成自然段就够了），避免为了凑数硬删导致信息丢失。
        7. 直接输出压缩后的中文提示词，不要加任何前缀、引号、解释或元注释。第一行就是正文。
        """;

    private final AppProperties appProperties;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ConcurrentHashMap<String, String> cache = new ConcurrentHashMap<>();
    private volatile OkHttpClient sharedClient;
    private volatile String sharedClientProxyKey = null;

    public PromptCondenser(AppProperties appProperties) {
        this.appProperties = appProperties;
    }

    /** 压缩结果：thought 可能为空 */
    public record Condensed(String thought, String compressed) {}

    /** 压缩 prompt；失败或过短时原样返回。永远不会抛异常。 */
    public String condense(String prompt) {
        return condenseDetailed(prompt).compressed();
    }

    /** 同 {@link #condense} 但返回思考过程（Gemini 2.5 thinking）。
     *  约定：未触发压缩（短路、降级、未配置）时 thought 返回空串，调用方用此区分"是否走过 LLM 压缩"。
     */
    public Condensed condenseDetailed(String prompt) {
        if (prompt == null) return new Condensed("", "");
        if (prompt.length() <= MAX_INPUT_LEN) return new Condensed("", prompt);

        String key = sha1(prompt);
        String cached = cache.get(key);
        if (cached != null) {
            log.debug("PromptCondenser 缓存命中 (len {} → {})", prompt.length(), cached.length());
            return new Condensed("（命中缓存，直接使用上次压缩结果）", cached);
        }

        String apiKey = appProperties.getGemini().getApiKey();
        if (apiKey == null || apiKey.isBlank()) {
            log.warn("Gemini API Key 未配置，跳过 prompt 压缩");
            return new Condensed("", prompt);
        }

        try {
            ObjectNode root = objectMapper.createObjectNode();
            // systemInstruction 字段比把规则塞进 user content 服从度更高（Gemini 官方推荐做法）
            ObjectNode sysInst = root.putObject("systemInstruction");
            sysInst.putArray("parts").addObject().put("text", SYSTEM_RULE);
            ArrayNode contents = root.putArray("contents");
            ObjectNode content = contents.addObject();
            content.put("role", "user");
            content.putArray("parts").addObject().put("text", "原提示词：\n" + prompt);
            ObjectNode genCfg = root.putObject("generationConfig");
            genCfg.put("temperature", 0.2);
            // 上一版 2000 + thinking 开启 → thinking 吃掉 ~1000 tokens，正文被截在 "3)" 处。
            // 现在关 thinking、加大预算到 4000，1500 中文字 ≈ 2500 tokens，留足缓冲。
            genCfg.put("maxOutputTokens", 4000);
            // thinkingBudget=0：Gemini 2.5 Flash 完全关闭思考，所有 token 都用于正文输出
            genCfg.putObject("thinkingConfig").put("thinkingBudget", 0);

            Request req = new Request.Builder()
                    .url(BASE_URL + MODEL + ":generateContent?key=" + apiKey)
                    .post(RequestBody.create(root.toString(), JSON_TYPE))
                    .build();

            try (Response resp = getClient().newCall(req).execute()) {
                String body = resp.body() != null ? resp.body().string() : "";
                if (!resp.isSuccessful()) {
                    log.warn("压缩 API 失败 {}: {}", resp.code(),
                            body.substring(0, Math.min(200, body.length())));
                    return new Condensed("（Gemini 返回 " + resp.code() + "，降级为原文）", prompt);
                }
                String[] split = extractThoughtAndText(body);
                String thought = split[0];
                String out = split[1];
                if (out == null || out.isBlank()) {
                    log.warn("压缩返回空，使用原文");
                    return new Condensed(thought.isEmpty() ? "（Gemini 返回空，降级为原文）" : thought, prompt);
                }
                log.info("压缩完成 {} → {} 字", prompt.length(), out.length());
                cache.put(key, out);
                return new Condensed(thought, out);
            }
        } catch (Exception e) {
            log.warn("压缩异常，降级到原文: {}", e.getMessage());
            return new Condensed("（压缩异常：" + e.getMessage() + "，降级为原文）", prompt);
        }
    }

    /** 返回 [thought, text]；任一可能为空字符串。 */
    private String[] extractThoughtAndText(String responseBody) throws Exception {
        JsonNode root = objectMapper.readTree(responseBody);
        JsonNode parts = root.path("candidates").path(0).path("content").path("parts");
        if (!parts.isArray()) return new String[]{"", ""};
        StringBuilder thoughts = new StringBuilder();
        StringBuilder texts = new StringBuilder();
        for (JsonNode p : parts) {
            JsonNode t = p.path("text");
            if (t.isMissingNode()) continue;
            if (p.path("thought").asBoolean(false)) {
                thoughts.append(t.asText()).append('\n');
            } else {
                texts.append(t.asText());
            }
        }
        return new String[]{thoughts.toString().trim(), texts.toString().trim()};
    }

    private OkHttpClient getClient() {
        String proxyKey = currentProxyKey();
        if (sharedClient == null || !proxyKey.equals(sharedClientProxyKey)) {
            synchronized (this) {
                if (sharedClient == null || !proxyKey.equals(sharedClientProxyKey)) {
                    OkHttpClient.Builder b = new OkHttpClient.Builder()
                            .connectTimeout(30, TimeUnit.SECONDS)
                            .writeTimeout(30, TimeUnit.SECONDS)
                            .readTimeout(60, TimeUnit.SECONDS);
                    AppProperties.Proxy p = appProperties.getProxy();
                    if (p.isEnabled()) {
                        // 显式配置代理：强制走该代理
                        b.proxy(new java.net.Proxy(java.net.Proxy.Type.HTTP,
                                new InetSocketAddress(p.getHost(), p.getPort())));
                    } else {
                        // host 留空：跟随 JVM 系统代理（与 yml 注释保持一致）
                        // OkHttp 默认不读系统代理，必须显式 ProxySelector.getDefault()
                        b.proxySelector(java.net.ProxySelector.getDefault());
                    }
                    sharedClient = b.build();
                    sharedClientProxyKey = proxyKey;
                }
            }
        }
        return sharedClient;
    }

    private String currentProxyKey() {
        AppProperties.Proxy p = appProperties.getProxy();
        return p.isEnabled() ? p.getHost() + ":" + p.getPort() : "";
    }

    private static String sha1(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            return HexFormat.of().formatHex(md.digest(s.getBytes()));
        } catch (Exception e) {
            return Integer.toHexString(s.hashCode());
        }
    }
}
