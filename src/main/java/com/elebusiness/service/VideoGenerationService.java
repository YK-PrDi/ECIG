package com.elebusiness.service;

import com.elebusiness.config.AppProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import okhttp3.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.*;
import java.net.InetSocketAddress;
import java.util.Base64;
import java.util.concurrent.TimeUnit;

@Service
public class VideoGenerationService {

    private static final Logger log = LoggerFactory.getLogger(VideoGenerationService.class);
    private static final MediaType JSON_TYPE = MediaType.get("application/json; charset=utf-8");

    private final AppProperties appProperties;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public VideoGenerationService(AppProperties appProperties) {
        this.appProperties = appProperties;
    }

    /**
     * 调用 Veo 3.1 生成视频，保存到本地文件，返回文件路径。
     *
     * @param prompt      文本提示词
     * @param aspectRatio 宽高比，如 "16:9" 或 "9:16"
     * @param outputPath  输出 .mp4 文件路径
     */
    public String generateVideo(String prompt, String aspectRatio, int durationSeconds, String outputPath) throws Exception {
        OkHttpClient client = buildClient();

        String operationName = submitTask(client, prompt, aspectRatio, durationSeconds);
        log.info("Veo 任务已提交，operation={}", operationName);

        String videoBase64 = pollOperation(client, operationName);
        saveVideo(videoBase64, outputPath);
        log.info("视频已保存: {}", outputPath);
        return outputPath;
    }

    private OkHttpClient buildClient() {
        OkHttpClient.Builder builder = new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS);

        AppProperties.Proxy proxyCfg = appProperties.getProxy();
        if (proxyCfg.isEnabled()) {
            // 显式配置代理：强制走该代理
            builder.proxy(new java.net.Proxy(
                    java.net.Proxy.Type.HTTP,
                    new InetSocketAddress(proxyCfg.getHost(), proxyCfg.getPort())));
            log.debug("VideoGen 使用代理 {}:{}", proxyCfg.getHost(), proxyCfg.getPort());
        } else {
            // host 留空：跟随 JVM 系统代理（让加速器/系统代理自动生效；OkHttp 默认不读系统代理，必须显式调用）
            builder.proxySelector(java.net.ProxySelector.getDefault());
        }
        return builder.build();
    }

    private String submitTask(OkHttpClient client, String prompt, String aspectRatio, int durationSeconds) throws IOException {
        String apiKey = appProperties.getGemini().getApiKey();
        AppProperties.Veo veo = appProperties.getVeo();

        ObjectNode instance = objectMapper.createObjectNode().put("prompt", prompt);
        ObjectNode parameters = objectMapper.createObjectNode()
                .put("aspectRatio", aspectRatio)
                .put("durationSeconds", durationSeconds > 0 ? durationSeconds : veo.getDurationSeconds())
                .put("resolution", veo.getResolution())
                .put("generateAudio", veo.isGenerateAudio());

        ObjectNode body = objectMapper.createObjectNode();
        body.set("instances", objectMapper.createArrayNode().add(instance));
        body.set("parameters", parameters);

        // Veo 使用 Google native 格式，需要 imageBaseUrl（含 /v1beta/models/）
        String baseUrl = appProperties.getGemini().getImageBaseUrl();
        if (baseUrl.endsWith("/models/")) {
            baseUrl = baseUrl.substring(0, baseUrl.length() - 8);
        } else if (baseUrl.endsWith("/models")) {
            baseUrl = baseUrl.substring(0, baseUrl.length() - 7);
        }
        String url = baseUrl + "/models/" + veo.getModel() + ":predictLongRunning?key=" + apiKey;
        Request request = new Request.Builder()
                .url(url)
                .post(RequestBody.create(objectMapper.writeValueAsString(body), JSON_TYPE))
                .build();

        try (Response response = client.newCall(request).execute()) {
            String responseBody = response.body().string();
            if (!response.isSuccessful()) {
                throw new RuntimeException("提交视频任务失败(" + response.code() + "): " + responseBody);
            }
            String name = objectMapper.readTree(responseBody).path("name").asText();
            if (name.isEmpty()) throw new RuntimeException("响应中未包含 operation name: " + responseBody);
            return name;
        }
    }

    private String pollOperation(OkHttpClient client, String operationName) throws IOException, InterruptedException {
        String apiKey = appProperties.getGemini().getApiKey();
        // Veo 使用 Google native 格式，需要 imageBaseUrl（含 /v1beta/models/）
        String baseUrl = appProperties.getGemini().getImageBaseUrl();
        if (baseUrl.endsWith("/models/")) {
            baseUrl = baseUrl.substring(0, baseUrl.length() - 8);
        } else if (baseUrl.endsWith("/models")) {
            baseUrl = baseUrl.substring(0, baseUrl.length() - 7);
        }
        String url = baseUrl + "/" + operationName + "?key=" + apiKey;
        Request request = new Request.Builder().url(url).build();

        // 最多等 10 分钟（120次 × 5秒）
        for (int i = 0; i < 120; i++) {
            Thread.sleep(5000);
            try (Response response = client.newCall(request).execute()) {
                String responseBody = response.body().string();
                JsonNode root = objectMapper.readTree(responseBody);

                if (!response.isSuccessful()) {
                    throw new RuntimeException("查询任务失败(" + response.code() + "): " + responseBody);
                }

                boolean done = root.path("done").asBoolean(false);
                log.info("Veo 轮询第 {} 次，done={}", i + 1, done);

                if (done) {
                    if (root.has("error")) {
                        throw new RuntimeException("视频模式失败: " + root.path("error").toString());
                    }
                    return extractVideoBase64(root, responseBody);
                }
            }
        }
        throw new RuntimeException("视频模式超时（10分钟内未完成）");
    }

    private String extractVideoBase64(JsonNode root, String responseBody) {
        // 路径：response.generateVideoResponse.generatedSamples[0].video.bytesBase64Encoded
        JsonNode samples = root.path("response")
                .path("generateVideoResponse")
                .path("generatedSamples");

        if (samples.isArray() && samples.size() > 0) {
            String b64 = samples.get(0).path("video").path("bytesBase64Encoded").asText();
            if (!b64.isEmpty()) return b64;
        }
        throw new RuntimeException("响应中未找到视频数据: " + responseBody);
    }

    private void saveVideo(String base64Data, String outputPath) throws IOException {
        byte[] bytes = Base64.getDecoder().decode(base64Data);
        File file = new File(outputPath);
        file.getParentFile().mkdirs();
        try (FileOutputStream fos = new FileOutputStream(file)) {
            fos.write(bytes);
        }
    }
}
