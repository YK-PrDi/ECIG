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
    private static final int MAX_INPUT_LEN = 550; // 小于此长度直接跳过压缩

    private static final String SYSTEM_RULE = """
        你是电商主图生图提示词的压缩专家。请把用户给的长提示词压缩到 500 字以内，同时严格遵守：
        1. 必须保留：主体产品形态、安装结构、材质与颜色、文字/卖点标语及其位置、画面分区/构图（如"右上角两行""2×2 网格"等）、特效（蓝色发光、箭头、对比勾叉等）、背景元素（墙面材质、配饰、光影调性）。
        2. 【最高优先级·禁止压缩或改写】涉及"产品主体一致性"的描述必须 1 字不动地保留：包括"主体一致性""禁止改变零件数量/形状/色彩/材质""不要增减喷孔/按键/旋钮/层数/挂钩"以及任何带【...】或【主体一致性-最高优先级】【禁止】等方括号标注的段落，整段照抄到输出中（不计入 500 字限制）。
        3. 可以删除或合并：冗余的修饰性形容词、重复描述、明显的解释性括号、"画面焦点为绝对视觉中心"这类模板话术。
        4. 禁止：换一种说法、添加原文没有的元素、翻译成英文、输出 JSON 或任何结构化格式。
        5. 输出：直接输出压缩后的中文提示词，不要加任何前缀、引号或解释。
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

    /** 同 {@link #condense} 但返回思考过程（Gemini 2.5 thinking）。 */
    public Condensed condenseDetailed(String prompt) {
        if (prompt == null) return new Condensed("", "");
        if (prompt.length() <= MAX_INPUT_LEN) return new Condensed("（提示词未超阈值，直接使用原文）", prompt);

        String key = sha1(prompt);
        String cached = cache.get(key);
        if (cached != null) {
            log.debug("PromptCondenser 缓存命中 (len {} → {})", prompt.length(), cached.length());
            return new Condensed("（命中缓存，直接使用上次压缩结果）", cached);
        }

        String apiKey = appProperties.getGemini().getApiKey();
        if (apiKey == null || apiKey.isBlank()) {
            log.warn("Gemini API Key 未配置，跳过 prompt 压缩");
            return new Condensed("（Gemini API Key 未配置，降级为原文）", prompt);
        }

        try {
            ObjectNode root = objectMapper.createObjectNode();
            ArrayNode contents = root.putArray("contents");
            ObjectNode content = contents.addObject();
            content.put("role", "user");
            ArrayNode parts = content.putArray("parts");
            parts.addObject().put("text", SYSTEM_RULE + "\n\n原提示词：\n" + prompt);
            ObjectNode genCfg = root.putObject("generationConfig");
            genCfg.put("temperature", 0.2);
            genCfg.put("maxOutputTokens", 2000);
            // 打开 Gemini 2.5 思考过程
            genCfg.putObject("thinkingConfig").put("includeThoughts", true);

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
                            .connectTimeout(15, TimeUnit.SECONDS)
                            .writeTimeout(30, TimeUnit.SECONDS)
                            .readTimeout(45, TimeUnit.SECONDS);
                    AppProperties.Proxy p = appProperties.getProxy();
                    if (p.isEnabled()) {
                        b.proxy(new java.net.Proxy(java.net.Proxy.Type.HTTP,
                                new InetSocketAddress(p.getHost(), p.getPort())));
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
