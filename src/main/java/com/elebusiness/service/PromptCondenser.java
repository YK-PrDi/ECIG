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

    private final AppProperties appProperties;
    private final PromptTemplateLoader promptTemplateLoader;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ConcurrentHashMap<String, String> cache = new ConcurrentHashMap<>();
    private volatile OkHttpClient sharedClient;
    private volatile String sharedClientProxyKey = null;

    public PromptCondenser(AppProperties appProperties, PromptTemplateLoader promptTemplateLoader) {
        this.appProperties = appProperties;
        this.promptTemplateLoader = promptTemplateLoader;
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
            sysInst.putArray("parts").addObject().put("text",
                    promptTemplateLoader.load("prompt/prompt-condenser-system.txt", ""));
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
