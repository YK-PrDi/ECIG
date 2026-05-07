package com.elebusiness.service.agent;

import com.elebusiness.config.AppProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import okhttp3.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.*;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.util.Base64;
import java.util.concurrent.TimeUnit;

@Service
public class GeminiImageAgent implements ImageGeneratorAgent {

    private static final Logger log = LoggerFactory.getLogger(GeminiImageAgent.class);
    private static final String BASE_URL = "https://generativelanguage.googleapis.com/v1beta/models/";
    private static final MediaType JSON_TYPE = MediaType.get("application/json; charset=utf-8");

    private final AppProperties appProperties;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private volatile OkHttpClient sharedClient;
    private volatile String sharedClientProxyKey = null;

    public GeminiImageAgent(AppProperties appProperties) {
        this.appProperties = appProperties;
    }

    private String currentProxyKey() {
        AppProperties.Proxy p = appProperties.getProxy();
        return p.isEnabled() ? p.getHost() + ":" + p.getPort() : "";
    }

    private OkHttpClient getClient(int timeoutSeconds) {
        String proxyKey = currentProxyKey();
        if (sharedClient == null || !proxyKey.equals(sharedClientProxyKey)) {
            synchronized (this) {
                if (sharedClient == null || !proxyKey.equals(sharedClientProxyKey)) {
                    sharedClient = buildClient(timeoutSeconds);
                    sharedClientProxyKey = proxyKey;
                }
            }
        }
        return sharedClient;
    }

    @Override
    public String getId() { return "gemini"; }

    @Override
    public String getDisplayName() { return "Gemini 3.1 Flash（图像编辑）"; }

    @Override
    public boolean generate(String prompt, String refImagePath, String whiteBgPath, String outputPath) {
        if (refImagePath == null || refImagePath.isBlank()) {
            log.warn("Gemini 为图像编辑模型，需要参考图才能生成，已跳过（提示词: {}）", prompt);
            return false;
        }

        AppProperties.Api apiConfig = appProperties.getApi();
        int maxRetries = apiConfig.getMaxRetries();
        int delaySeconds = apiConfig.getDelaySeconds();

        for (int attempt = 0; attempt < maxRetries; attempt++) {
            try {
                if (attempt > 0) Thread.sleep(delaySeconds * 1000L);

                byte[] refImgBytes = Files.readAllBytes(new File(refImagePath).toPath());
                String refImgBase64 = Base64.getEncoder().encodeToString(refImgBytes);
                String refMimeType = getMimeType(refImagePath);

                byte[] whiteImgBytes;
                String whiteMimeType;
                if (whiteBgPath != null && whiteBgPath.startsWith("http")) {
                    whiteImgBytes = downloadBytes(whiteBgPath);
                    whiteMimeType = "image/jpeg";
                } else if (whiteBgPath != null) {
                    whiteImgBytes = Files.readAllBytes(new File(whiteBgPath).toPath());
                    whiteMimeType = getMimeType(whiteBgPath);
                } else {
                    whiteImgBytes = refImgBytes;
                    whiteMimeType = refMimeType;
                }
                String whiteImgBase64 = Base64.getEncoder().encodeToString(whiteImgBytes);

                String requestJson = buildRequest(prompt, refImgBase64, refMimeType, whiteImgBase64, whiteMimeType);
                String apiKey = appProperties.getGemini().getApiKey();
                String model = appProperties.getGemini().getModel();
                String url = BASE_URL + model + ":generateContent?key=" + apiKey;

                OkHttpClient client = getClient(apiConfig.getTimeoutSeconds());

                Request request = new Request.Builder()
                        .url(url)
                        .post(RequestBody.create(requestJson, JSON_TYPE))
                        .build();

                try (Response response = client.newCall(request).execute()) {
                    String responseBody = response.body().string();

                    if (!response.isSuccessful()) {
                        int code = response.code();
                        log.warn("Gemini API 错误 {} (尝试 {}/{}): {}", code, attempt + 1, maxRetries,
                                responseBody.substring(0, Math.min(300, responseBody.length())));
                        if (code == 503 && attempt < maxRetries - 1) {
                            Thread.sleep(60000);
                            continue;
                        } else if (code == 429 && attempt < maxRetries - 1) {
                            Thread.sleep(30000);
                            continue;
                        } else if (attempt < maxRetries - 1) {
                            Thread.sleep(5000L * (attempt + 1));
                            continue;
                        }
                        return false;
                    }

                    boolean saved = extractAndSaveImage(responseBody, outputPath);
                    if (saved) {
                        log.info("Gemini 图片生成成功: {}", outputPath);
                        return true;
                    }
                    log.warn("Gemini 响应中未找到图片数据 (尝试 {}/{})", attempt + 1, maxRetries);
                }

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            } catch (Exception e) {
                log.warn("Gemini 生成失败 (尝试 {}/{}): {}", attempt + 1, maxRetries, e.getMessage());
                if (attempt < maxRetries - 1) {
                    try { Thread.sleep(5000L * (attempt + 1)); }
                    catch (InterruptedException ie) { Thread.currentThread().interrupt(); return false; }
                }
            }
        }
        return false;
    }

    private OkHttpClient buildClient(int timeoutSeconds) {
        OkHttpClient.Builder builder = new OkHttpClient.Builder()
                .readTimeout(timeoutSeconds, TimeUnit.SECONDS)
                .writeTimeout(60, TimeUnit.SECONDS)
                .connectTimeout(30, TimeUnit.SECONDS);

        AppProperties.Proxy proxyCfg = appProperties.getProxy();
        if (proxyCfg.isEnabled()) {
            builder.proxy(new java.net.Proxy(
                    java.net.Proxy.Type.HTTP,
                    new InetSocketAddress(proxyCfg.getHost(), proxyCfg.getPort())));
            log.debug("Gemini 使用代理 {}:{}", proxyCfg.getHost(), proxyCfg.getPort());
        }
        return builder.build();
    }

    private String buildRequest(String prompt,
                                String refBase64, String refMime,
                                String whiteBase64, String whiteMime) {
        ObjectNode root = objectMapper.createObjectNode();
        ArrayNode contents = root.putArray("contents");
        ObjectNode content = contents.addObject();
        content.put("role", "user");
        ArrayNode parts = content.putArray("parts");

        parts.addObject().put("text", prompt);
        parts.addObject().putObject("inlineData").put("mimeType", refMime).put("data", refBase64);
        parts.addObject().putObject("inlineData").put("mimeType", whiteMime).put("data", whiteBase64);

        ArrayNode modalities = root.putObject("generationConfig").putArray("responseModalities");
        modalities.add("TEXT");
        modalities.add("IMAGE");

        return root.toString();
    }

    private boolean extractAndSaveImage(String responseJson, String outputPath) throws Exception {
        JsonNode root = objectMapper.readTree(responseJson);
        JsonNode candidates = root.path("candidates");
        if (!candidates.isArray() || candidates.isEmpty()) return false;

        for (JsonNode part : candidates.get(0).path("content").path("parts")) {
            JsonNode inlineData = part.path("inlineData");
            if (!inlineData.isMissingNode()) {
                byte[] imageBytes = Base64.getDecoder().decode(inlineData.path("data").asText());
                File outputFile = new File(outputPath);
                outputFile.getParentFile().mkdirs();
                try (FileOutputStream fos = new FileOutputStream(outputFile)) {
                    fos.write(imageBytes);
                }
                return true;
            }
        }
        return false;
    }

    private byte[] downloadBytes(String url) throws Exception {
        OkHttpClient client = new OkHttpClient.Builder().readTimeout(30, TimeUnit.SECONDS).build();
        try (Response response = client.newCall(new Request.Builder().url(url).get().build()).execute()) {
            if (!response.isSuccessful()) throw new IOException("下载失败: " + url);
            return response.body().bytes();
        }
    }

    private String getMimeType(String path) {
        String lower = path.toLowerCase();
        if (lower.endsWith(".png")) return "image/png";
        if (lower.endsWith(".gif")) return "image/gif";
        if (lower.endsWith(".webp")) return "image/webp";
        return "image/jpeg";
    }
}
