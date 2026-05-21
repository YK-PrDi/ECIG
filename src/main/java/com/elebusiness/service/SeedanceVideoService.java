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

import java.io.*;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Service
public class SeedanceVideoService {

    private static final Logger log = LoggerFactory.getLogger(SeedanceVideoService.class);
    private static final MediaType JSON_TYPE = MediaType.get("application/json; charset=utf-8");
    private static final long POLL_INTERVAL_MS = 5_000L;
    private static final long MAX_WAIT_MS = 10 * 60 * 1000L;

    private final AppProperties appProperties;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public SeedanceVideoService(AppProperties appProperties) {
        this.appProperties = appProperties;
    }

    public static class Request {
        public String prompt;
        public String aspectRatio = "16:9";
        public int durationSeconds = 8;
        public List<String> imageUrls;
        public String videoUrl;
        public String audioUrl;
        public boolean generateAudio = true;
    }

    public String generateVideo(Request req, String outputPath) throws Exception {
        OkHttpClient client = buildClient();
        String taskId = submitTask(client, req);
        log.info("Seedance 任务已提交，taskId={}", taskId);
        String videoUrl = pollTask(client, taskId);
        downloadVideo(client, videoUrl, outputPath);
        log.info("Seedance 视频已保存: {}", outputPath);
        return outputPath;
    }

    private OkHttpClient buildClient() {
        OkHttpClient.Builder builder = new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(120, TimeUnit.SECONDS);
        AppProperties.Proxy proxyCfg = appProperties.getProxy();
        if (proxyCfg.isEnabled()) {
            builder.proxy(new java.net.Proxy(
                    java.net.Proxy.Type.HTTP,
                    new InetSocketAddress(proxyCfg.getHost(), proxyCfg.getPort())));
        } else {
            builder.proxySelector(java.net.ProxySelector.getDefault());
        }
        return builder.build();
    }

    private String submitTask(OkHttpClient client, Request req) throws IOException {
        AppProperties.Volcengine cfg = appProperties.getVolcengine();

        ArrayNode content = objectMapper.createArrayNode();
        content.add(objectMapper.createObjectNode().put("type", "text").put("text", req.prompt));

        if (req.imageUrls != null) {
            for (String url : req.imageUrls) {
                if (url == null || url.isBlank()) continue;
                ObjectNode item = objectMapper.createObjectNode().put("type", "image_url");
                item.set("image_url", objectMapper.createObjectNode().put("url", url));
                item.put("role", "reference_image");
                content.add(item);
            }
        }
        if (req.videoUrl != null && !req.videoUrl.isBlank()) {
            ObjectNode item = objectMapper.createObjectNode().put("type", "video_url");
            item.set("video_url", objectMapper.createObjectNode().put("url", req.videoUrl));
            item.put("role", "reference_video");
            content.add(item);
        }
        if (req.audioUrl != null && !req.audioUrl.isBlank()) {
            ObjectNode item = objectMapper.createObjectNode().put("type", "audio_url");
            item.set("audio_url", objectMapper.createObjectNode().put("url", req.audioUrl));
            item.put("role", "reference_audio");
            content.add(item);
        }

        ObjectNode body = objectMapper.createObjectNode();
        body.put("model", cfg.getModel());
        body.set("content", content);
        body.put("generate_audio", req.generateAudio);
        body.put("ratio", req.aspectRatio == null || req.aspectRatio.isBlank() ? "16:9" : req.aspectRatio);
        body.put("duration", req.durationSeconds > 0 ? req.durationSeconds : 8);
        body.put("watermark", false);

        okhttp3.Request httpReq = new okhttp3.Request.Builder()
                .url(cfg.getBaseUrl() + "/contents/generations/tasks")
                .header("Authorization", "Bearer " + cfg.getApiKey())
                .post(RequestBody.create(objectMapper.writeValueAsString(body), JSON_TYPE))
                .build();

        try (Response response = client.newCall(httpReq).execute()) {
            String responseBody = response.body() != null ? response.body().string() : "";
            if (!response.isSuccessful()) {
                throw new RuntimeException("提交 Seedance 任务失败(" + response.code() + "): " + responseBody);
            }
            String id = objectMapper.readTree(responseBody).path("id").asText();
            if (id.isEmpty()) throw new RuntimeException("响应未包含 task id: " + responseBody);
            return id;
        }
    }

    private String pollTask(OkHttpClient client, String taskId) throws IOException, InterruptedException {
        AppProperties.Volcengine cfg = appProperties.getVolcengine();
        String url = cfg.getBaseUrl() + "/contents/generations/tasks/" + taskId;
        long deadline = System.currentTimeMillis() + MAX_WAIT_MS;
        int attempts = 0;

        while (System.currentTimeMillis() < deadline) {
            attempts++;
            okhttp3.Request httpReq = new okhttp3.Request.Builder()
                    .url(url)
                    .header("Authorization", "Bearer " + cfg.getApiKey())
                    .get()
                    .build();

            String responseBody;
            try (Response response = client.newCall(httpReq).execute()) {
                responseBody = response.body() != null ? response.body().string() : "";
                if (!response.isSuccessful()) {
                    throw new RuntimeException("查询 Seedance 任务失败(" + response.code() + "): " + responseBody);
                }
            }

            JsonNode node = objectMapper.readTree(responseBody);
            String status = node.path("status").asText();
            log.debug("Seedance 轮询#{} status={}", attempts, status);

            if ("succeeded".equalsIgnoreCase(status)) {
                String videoUrl = node.path("content").path("video_url").asText();
                if (videoUrl.isEmpty()) throw new RuntimeException("succeeded 但未返回 video_url: " + responseBody);
                log.info("Seedance 任务完成，轮询 {} 次", attempts);
                return videoUrl;
            }
            if ("failed".equalsIgnoreCase(status) || "cancelled".equalsIgnoreCase(status)) {
                String msg = node.path("error").path("message").asText("(无错误信息)");
                throw new RuntimeException("Seedance 任务失败: " + msg + " | raw=" + responseBody);
            }
            Thread.sleep(POLL_INTERVAL_MS);
        }
        throw new RuntimeException("Seedance 任务轮询超时(" + (MAX_WAIT_MS / 1000) + "s)");
    }

    private void downloadVideo(OkHttpClient client, String url, String outputPath) throws IOException {
        File outFile = new File(outputPath);
        File parent = outFile.getParentFile();
        if (parent != null && !parent.exists()) parent.mkdirs();

        okhttp3.Request httpReq = new okhttp3.Request.Builder().url(url).get().build();
        try (Response response = client.newCall(httpReq).execute()) {
            if (!response.isSuccessful() || response.body() == null) {
                throw new RuntimeException("下载视频失败(" + response.code() + ")");
            }
            try (InputStream in = response.body().byteStream()) {
                Files.copy(in, Paths.get(outputPath), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }
}
