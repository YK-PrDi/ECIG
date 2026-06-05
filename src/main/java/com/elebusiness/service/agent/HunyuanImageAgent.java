package com.elebusiness.service.agent;

import com.elebusiness.config.SiliconFlowConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import okhttp3.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.*;
import java.nio.file.Files;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 混元 HunyuanImage v3.0，通过腾讯 MaaS 平台接入。
 * 接口为异步模式：先 submit 提交任务，再 query 轮询结果。
 */
@Service
public class HunyuanImageAgent implements ImageGeneratorAgent {

    private static final Logger log = LoggerFactory.getLogger(HunyuanImageAgent.class);
    private static final String SUBMIT_URL = "https://tokenhub.tencentmaas.com/v1/api/image/submit";
    private static final String QUERY_URL  = "https://tokenhub.tencentmaas.com/v1/api/image/query";
    private static final MediaType JSON_TYPE = MediaType.get("application/json; charset=utf-8");

    private final SiliconFlowConfig config;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public HunyuanImageAgent(SiliconFlowConfig config) {
        this.config = config;
    }

    @Override
    public String getId() { return "hunyuan"; }

    @Override
    public String getDisplayName() { return "混元 HunyuanImage v3.0（腾讯 MaaS）"; }

    /** 覆写 generateMulti，使用 aspect 参数动态计算 size */
    @Override
    public boolean generateMulti(String prompt, List<String> refImagePaths,
                                 String whiteBgPath, String outputPath, String aspect) {
        String size = pickSize(aspect);
        log.info("混元使用 size={} (aspect={})", size, aspect);
        String firstRef = (refImagePaths != null && !refImagePaths.isEmpty()) ? refImagePaths.get(0) : null;
        return generateWithSize(prompt, firstRef, whiteBgPath, outputPath, size);
    }

    /** 混元 size 映射（腾讯 MaaS 格式 "1024x1024"，小写 x） */
    private String pickSize(String aspect) {
        if (aspect == null || "auto".equals(aspect)) return "1024x1024";
        return switch (aspect) {
            case "9:16", "portrait" -> "1024x1536";
            case "16:9", "landscape" -> "1536x1024";
            case "1:1" -> "1024x1024";
            case "3:4" -> "1024x1365";
            case "4:3" -> "1365x1024";
            default -> "1024x1024";
        };
    }

    /** 内部方法：使用指定 size 生成图片 */
    private boolean generateWithSize(String prompt, String refImagePath, String whiteBgPath,
                                      String outputPath, String size) {
        OkHttpClient client = buildClient();
        try {
            String taskId = submitTask(client, prompt, refImagePath, whiteBgPath, size);
            log.info("混元任务已提交，task_id={}", taskId);
            String imageUrl = pollTask(client, taskId);
            downloadToFile(client, imageUrl, outputPath);
            log.info("混元图片已保存: {}", outputPath);
            return true;
        } catch (Exception e) {
            log.error("混元生成失败: {}", e.getMessage());
            return false;
        }
    }

    @Override
    public boolean generate(String prompt, String refImagePath, String whiteBgPath, String outputPath) {
        return generateWithSize(prompt, refImagePath, whiteBgPath, outputPath, "1024x1024");
    }

    private String submitTask(OkHttpClient client, String prompt,
                               String refImagePath, String whiteBgPath, String size) throws IOException {
        ObjectNode body = objectMapper.createObjectNode()
                .put("model", config.getModel())
                .put("prompt", prompt)
                .put("size", size);

        // 优先白底图，其次参考图
        String refPath = (whiteBgPath != null && !whiteBgPath.isBlank()) ? whiteBgPath : refImagePath;
        if (refPath != null && !refPath.isBlank()) {
            body.put("image", toImageValue(refPath));
        }

        Request request = new Request.Builder()
                .url(SUBMIT_URL)
                .addHeader("Authorization", "Bearer " + config.getApiKey())
                .post(RequestBody.create(objectMapper.writeValueAsString(body), JSON_TYPE))
                .build();

        try (Response response = client.newCall(request).execute()) {
            String responseBody = response.body().string();
            if (!response.isSuccessful()) {
                throw new RuntimeException("混元 submit 失败(" + response.code() + "): " + responseBody);
            }
            return extractTaskId(responseBody);
        }
    }

    private String extractTaskId(String responseBody) throws IOException {
        JsonNode root = objectMapper.readTree(responseBody);
        // 常见格式: {"data":{"id":"xxx"}} 或 {"id":"xxx"}
        JsonNode data = root.path("data");
        if (!data.isMissingNode()) {
            String id = data.path("id").asText("");
            if (!id.isEmpty()) return id;
        }
        String id = root.path("id").asText("");
        if (!id.isEmpty()) return id;
        throw new RuntimeException("submit 响应中未找到 task id: " + responseBody);
    }

    private String pollTask(OkHttpClient client, String taskId)
            throws IOException, InterruptedException {
        for (int i = 0; i < 90; i++) {
            Thread.sleep(i < 3 ? 5000 : 10000);

            ObjectNode body = objectMapper.createObjectNode()
                    .put("model", config.getModel())
                    .put("id", taskId);

            Request request = new Request.Builder()
                    .url(QUERY_URL)
                    .addHeader("Authorization", "Bearer " + config.getApiKey())
                    .post(RequestBody.create(objectMapper.writeValueAsString(body), JSON_TYPE))
                    .build();

            try (Response response = client.newCall(request).execute()) {
                String responseBody = response.body().string();
                if (!response.isSuccessful()) {
                    throw new RuntimeException("混元 query 失败(" + response.code() + "): " + responseBody);
                }

                JsonNode root = objectMapper.readTree(responseBody);
                String status = root.path("status").asText("");
                log.info("混元轮询第 {} 次，状态: {}，响应: {}", i + 1, status, responseBody);

                if ("success".equalsIgnoreCase(status) || "succeeded".equalsIgnoreCase(status)
                        || "completed".equalsIgnoreCase(status)) {
                    return extractImageUrl(root, responseBody);
                }
                if ("failed".equalsIgnoreCase(status) || "error".equalsIgnoreCase(status)) {
                    throw new RuntimeException("混元任务失败: " + responseBody);
                }
            }
        }
        throw new RuntimeException("混元任务超时（250秒内未完成）");
    }

    private String extractImageUrl(JsonNode root, String raw) {
        // 腾讯 MaaS 实际格式: {"status":"completed","data":[{"url":"..."}]}
        JsonNode data = root.path("data");
        if (data.isArray() && data.size() > 0) {
            String url = data.get(0).path("url").asText("");
            if (!url.isEmpty()) return url;
        }
        // 兼容格式: images[0].url
        JsonNode images = root.path("images");
        if (images.isArray() && images.size() > 0) {
            String url = images.get(0).path("url").asText("");
            if (!url.isEmpty()) return url;
        }
        // 兼容格式: image_url
        String url = root.path("image_url").asText("");
        if (!url.isEmpty()) return url;

        throw new RuntimeException("query 响应中未找到图片 URL: " + raw);
    }

    private void downloadToFile(OkHttpClient client, String imageUrl, String outputPath) throws IOException {
        try (Response response = client.newCall(new Request.Builder().url(imageUrl).build()).execute()) {
            if (!response.isSuccessful()) throw new IOException("下载图片失败: " + response.code());
            byte[] bytes = response.body().bytes();
            File file = new File(outputPath);
            file.getParentFile().mkdirs();
            try (FileOutputStream fos = new FileOutputStream(file)) {
                fos.write(bytes);
            }
        }
    }

    /** 本地文件转 base64 data URL；HTTP URL 直接返回 */
    private String toImageValue(String imagePath) throws IOException {
        if (imagePath.startsWith("http")) return imagePath;
        byte[] bytes = Files.readAllBytes(new File(imagePath).toPath());
        String base64 = Base64.getEncoder().encodeToString(bytes);
        return "data:" + getMimeType(imagePath) + ";base64," + base64;
    }

    private OkHttpClient buildClient() {
        return new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(120, TimeUnit.SECONDS)
                .build();
    }

    private String getMimeType(String path) {
        String lower = path.toLowerCase();
        if (lower.endsWith(".png"))  return "image/png";
        if (lower.endsWith(".webp")) return "image/webp";
        if (lower.endsWith(".gif"))  return "image/gif";
        return "image/jpeg";
    }
}
