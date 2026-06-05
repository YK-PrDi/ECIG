package com.elebusiness.service.agent;

import com.elebusiness.config.DashScopeConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
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
 * 通义 Qwen-Image 2.0 智能体（文生图 / 图像编辑）。
 * 使用与万相相同的 DashScope API Key，端点不同。
 */
@Service
public class QwenImageAgent implements ImageGeneratorAgent {

    private static final Logger log = LoggerFactory.getLogger(QwenImageAgent.class);
    private static final String MODEL = "qwen-image-2.0";
    private static final String CREATE_URL =
            "https://dashscope.aliyuncs.com/api/v1/services/aigc/multimodal-generation/generation";
    private static final String QUERY_URL =
            "https://dashscope.aliyuncs.com/api/v1/tasks/";
    private static final MediaType JSON_TYPE = MediaType.get("application/json; charset=utf-8");

    private final DashScopeConfig config;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public QwenImageAgent(DashScopeConfig config) {
        this.config = config;
    }

    @Override
    public String getId() { return "qwen-image"; }

    @Override
    public String getDisplayName() { return "通义 Qwen-Image 2.0（图像编辑）"; }

    /** 覆写 generateMulti，使用 aspect 参数动态计算 size */
    @Override
    public boolean generateMulti(String prompt, List<String> refImagePaths,
                                 String whiteBgPath, String outputPath, String aspect) {
        String size = pickSize(aspect);
        log.info("Qwen-Image 使用 size={} (aspect={})", size, aspect);
        String firstRef = (refImagePaths != null && !refImagePaths.isEmpty()) ? refImagePaths.get(0) : null;
        return generateWithSize(prompt, firstRef, whiteBgPath, outputPath, size);
    }

    /** DashScope size 格式为 "1024*1024"（星号） */
    private String pickSize(String aspect) {
        if (aspect == null || "auto".equals(aspect)) return config.getImageSize();
        return switch (aspect) {
            case "9:16", "portrait" -> "1024*1536";
            case "16:9", "landscape" -> "1536*1024";
            case "1:1" -> "1024*1024";
            case "3:4" -> "1024*1365";
            case "4:3" -> "1365*1024";
            default -> config.getImageSize();
        };
    }

    /** 内部方法：使用指定 size 生成图片 */
    private boolean generateWithSize(String prompt, String refImagePath, String whiteBgPath,
                                      String outputPath, String size) {
        OkHttpClient client = buildClient();
        try {
            String taskId = createTask(client, prompt, refImagePath, whiteBgPath, size);
            log.info("Qwen-Image 任务已创建，task_id={}", taskId);
            String imageUrl = pollTask(client, taskId);
            downloadToFile(client, imageUrl, outputPath);
            log.info("Qwen-Image 图片已保存: {}", outputPath);
            return true;
        } catch (Exception e) {
            log.error("Qwen-Image 生成失败: {}", e.getMessage());
            return false;
        }
    }

    @Override
    public boolean generate(String prompt, String refImagePath, String whiteBgPath, String outputPath) {
        return generateWithSize(prompt, refImagePath, whiteBgPath, outputPath, config.getImageSize());
    }

    private String createTask(OkHttpClient client, String prompt,
                               String refImagePath, String whiteBgPath, String size) throws IOException {
        ArrayNode content = objectMapper.createArrayNode();
        addImage(content, refImagePath);
        addImage(content, whiteBgPath);
        content.addObject().put("text", prompt);

        ObjectNode message = objectMapper.createObjectNode().put("role", "user");
        message.set("content", content);

        ObjectNode input = objectMapper.createObjectNode();
        input.set("messages", objectMapper.createArrayNode().add(message));

        // Qwen-Image multimodal 端点通过 parameters 传递 size
        ObjectNode parameters = objectMapper.createObjectNode()
                .put("size", size)
                .put("n", 1);

        ObjectNode body = objectMapper.createObjectNode().put("model", MODEL);
        body.set("input", input);
        body.set("parameters", parameters);

        Request request = new Request.Builder()
                .url(CREATE_URL)
                .post(RequestBody.create(objectMapper.writeValueAsString(body), JSON_TYPE))
                .addHeader("Authorization", "Bearer " + config.getApiKey())
                .addHeader("X-DashScope-Async", "enable")
                .addHeader("X-DashScope-OssResourceResolve", "enable")
                .build();

        try (Response response = client.newCall(request).execute()) {
            String responseBody = response.body().string();
            if (!response.isSuccessful()) {
                throw new RuntimeException("创建任务失败(" + response.code() + "): " + responseBody);
            }
            String taskId = objectMapper.readTree(responseBody).path("output").path("task_id").asText();
            if (taskId.isEmpty()) throw new RuntimeException("响应中未包含 task_id: " + responseBody);
            return taskId;
        }
    }

    /** 将本地文件或 HTTP URL 追加为图片节点 */
    private void addImage(ArrayNode content, String imagePath) throws IOException {
        if (imagePath == null || imagePath.isBlank()) return;
        if (imagePath.startsWith("http")) {
            content.addObject().put("image", imagePath);
        } else {
            byte[] bytes = Files.readAllBytes(new File(imagePath).toPath());
            String base64 = Base64.getEncoder().encodeToString(bytes);
            content.addObject().put("image", "data:" + getMimeType(imagePath) + ";base64," + base64);
        }
    }

    private String pollTask(OkHttpClient client, String taskId) throws IOException, InterruptedException {
        Request request = new Request.Builder()
                .url(QUERY_URL + taskId)
                .addHeader("Authorization", "Bearer " + config.getApiKey())
                .build();

        for (int i = 0; i < 90; i++) {
            Thread.sleep(i < 5 ? 2000 : 3000);
            try (Response response = client.newCall(request).execute()) {
                String responseBody = response.body().string();
                JsonNode root = objectMapper.readTree(responseBody);
                String status = root.path("output").path("task_status").asText();
                log.info("Qwen-Image 轮询第 {} 次，状态: {}", i + 1, status);

                if ("SUCCEEDED".equals(status)) return extractImageUrl(root, responseBody);
                if ("FAILED".equals(status)) throw new RuntimeException("Qwen-Image 任务失败: " + responseBody);
            }
        }
        throw new RuntimeException("Qwen-Image 任务超时（250秒内未完成）");
    }

    private String extractImageUrl(JsonNode root, String responseBody) {
        JsonNode output = root.path("output");
        JsonNode results = output.path("results");
        if (results.isArray() && results.size() > 0) {
            String url = results.get(0).path("url").asText();
            if (!url.isEmpty()) return url;
        }
        JsonNode choices = output.path("choices");
        if (choices.isArray() && choices.size() > 0) {
            for (JsonNode item : choices.get(0).path("message").path("content")) {
                String url = item.path("image").asText();
                if (!url.isEmpty()) return url;
            }
        }
        throw new RuntimeException("SUCCEEDED 但未找到图片URL，响应: " + responseBody);
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

    private OkHttpClient buildClient() {
        return new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
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
