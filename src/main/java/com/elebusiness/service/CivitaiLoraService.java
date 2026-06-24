package com.elebusiness.service;

import com.elebusiness.config.CivitaiConfig;
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
 * Civitai LoRA 生图服务
 * 使用 Stable Diffusion XL + 自定义 LoRA 生成专业产品摄影图片
 */
@Service
public class CivitaiLoraService {

    private static final Logger log = LoggerFactory.getLogger(CivitaiLoraService.class);
    private static final String API_BASE = "https://api.civitai.com/v1";
    private static final MediaType JSON_TYPE = MediaType.get("application/json; charset=utf-8");

    private final CivitaiConfig config;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public CivitaiLoraService(CivitaiConfig config) {
        this.config = config;
    }

    /**
     * 使用 LoRA 生成图片
     * @param prompt 提示词
     * @param whiteBgPath 白底产品图路径
     * @param outputPath 输出路径
     * @param aspect 宽高比（1:1, 16:9 等）
     * @param loraPreset LoRA 预设名称（studio, lifestyle, minimal）
     * @return 是否成功
     */
    public boolean generateWithLora(String prompt, String whiteBgPath,
                                    String outputPath, String aspect,
                                    String loraPreset) {
        ImageSize size = mapAspectRatio(aspect);
        log.info("Civitai 使用 size={}x{} (aspect={}), loraPreset={}",
                 size.width(), size.height(), aspect, loraPreset);

        OkHttpClient client = buildClient();

        try {
            // 1. 创建生成任务
            String generationId = createGeneration(client, prompt, whiteBgPath, size, loraPreset);
            log.info("Civitai 任务已创建，generation_id={}, loraPreset={}", generationId, loraPreset);

            // 2. 轮询任务状态
            String imageUrl = pollGeneration(client, generationId);

            // 3. 下载图片
            downloadToFile(client, imageUrl, outputPath);
            log.info("Civitai 图片已保存: {}", outputPath);
            return true;
        } catch (Exception e) {
            log.error("Civitai 生成失败: {}", e.getMessage(), e);
            return false;
        }
    }

    /**
     * 创建生成任务
     */
    private String createGeneration(OkHttpClient client, String prompt,
                                    String imagePath, ImageSize size,
                                    String loraPreset) throws IOException {
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("model", config.getModel());
        payload.put("prompt", prompt);
        payload.put("width", size.width());
        payload.put("height", size.height());
        payload.put("nsfw", false);

        // 添加 LoRA 资源
        ArrayNode resources = buildLoraResources(loraPreset);
        if (resources.size() > 0) {
            payload.set("resources", resources);
        }

        // 如果有参考图，添加 image 字段（base64 编码）
        if (imagePath != null && !imagePath.isEmpty()) {
            File imageFile = new File(imagePath);
            if (imageFile.exists()) {
                String base64 = encodeImageToBase64(imageFile);
                payload.put("image", "data:image/jpeg;base64," + base64);
            }
        }

        RequestBody body = RequestBody.create(payload.toString(), JSON_TYPE);
        Request request = new Request.Builder()
            .url(API_BASE + "/generations")
            .addHeader("Authorization", "Bearer " + config.getApiKey())
            .addHeader("Content-Type", "application/json")
            .post(body)
            .build();

        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                String errorBody = response.body() != null ? response.body().string() : "";
                throw new IOException("Civitai API 错误: " + response.code() + " - " + errorBody);
            }

            JsonNode json = objectMapper.readTree(response.body().string());

            // Civitai API 返回 { "id": "generation_id" }
            if (json.has("id")) {
                return json.get("id").asText();
            } else {
                throw new IOException("响应中没有 generation ID: " + json.toString());
            }
        }
    }

    /**
     * 轮询任务状态
     */
    private String pollGeneration(OkHttpClient client, String generationId)
            throws IOException, InterruptedException {
        int maxAttempts = config.getMaxPollSeconds() / 3;  // 每 3 秒轮询一次

        for (int i = 0; i < maxAttempts; i++) {
            Request request = new Request.Builder()
                .url(API_BASE + "/generations/" + generationId)
                .addHeader("Authorization", "Bearer " + config.getApiKey())
                .get()
                .build();

            try (Response response = client.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    throw new IOException("轮询失败: " + response.code());
                }

                JsonNode json = objectMapper.readTree(response.body().string());
                String status = json.has("status") ? json.get("status").asText() : "unknown";

                log.debug("Civitai 任务状态: {} (尝试 {}/{})", status, i + 1, maxAttempts);

                if ("completed".equals(status) || "succeeded".equals(status)) {
                    // 提取图片 URL
                    JsonNode images = json.get("images");
                    if (images != null && images.isArray() && images.size() > 0) {
                        JsonNode firstImage = images.get(0);
                        if (firstImage.has("url")) {
                            return firstImage.get("url").asText();
                        }
                    }
                    throw new IOException("响应中没有图片 URL: " + json.toString());
                }

                if ("failed".equals(status) || "cancelled".equals(status)) {
                    String error = json.has("error") ? json.get("error").asText() : "未知错误";
                    throw new IOException("任务失败: " + status + " - " + error);
                }

                // 等待 3 秒后重试
                TimeUnit.SECONDS.sleep(3);
            }
        }

        throw new IOException("轮询超时（超过 " + config.getMaxPollSeconds() + " 秒）");
    }

    /**
     * 构建 LoRA 资源数组
     */
    private ArrayNode buildLoraResources(String preset) {
        ArrayNode resources = objectMapper.createArrayNode();

        List<CivitaiConfig.LoraResource> loras = config.getLorasByPreset(preset);
        for (CivitaiConfig.LoraResource lora : loras) {
            if (lora.getId() != null && lora.getId() > 0) {  // ID 为 0 表示未配置
                ObjectNode resource = objectMapper.createObjectNode();
                resource.put("type", "lora");
                resource.put("id", lora.getId());
                resource.put("weight", lora.getWeight());
                resources.add(resource);
                log.debug("添加 LoRA: id={}, name={}, weight={}",
                         lora.getId(), lora.getName(), lora.getWeight());
            }
        }

        return resources;
    }

    /**
     * 宽高比映射
     */
    private record ImageSize(int width, int height) {}

    private ImageSize mapAspectRatio(String aspect) {
        if (aspect == null || "auto".equals(aspect)) {
            return new ImageSize(1024, 1024);
        }
        return switch (aspect) {
            case "1:1" -> new ImageSize(1024, 1024);
            case "16:9", "landscape" -> new ImageSize(1920, 1080);
            case "9:16", "portrait" -> new ImageSize(1080, 1920);
            case "3:4" -> new ImageSize(768, 1024);
            case "4:3" -> new ImageSize(1024, 768);
            default -> new ImageSize(1024, 1024);
        };
    }

    /**
     * 创建 HTTP 客户端
     */
    private OkHttpClient buildClient() {
        return new OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build();
    }

    /**
     * 图片转 base64
     */
    private String encodeImageToBase64(File imageFile) throws IOException {
        byte[] bytes = Files.readAllBytes(imageFile.toPath());
        return Base64.getEncoder().encodeToString(bytes);
    }

    /**
     * 下载图片到文件
     */
    private void downloadToFile(OkHttpClient client, String url, String outputPath) throws IOException {
        Request request = new Request.Builder().url(url).get().build();

        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("下载图片失败: " + response.code());
            }

            File outputFile = new File(outputPath);
            outputFile.getParentFile().mkdirs();

            try (InputStream in = response.body().byteStream();
                 FileOutputStream out = new FileOutputStream(outputFile)) {
                byte[] buffer = new byte[8192];
                int len;
                while ((len = in.read(buffer)) != -1) {
                    out.write(buffer, 0, len);
                }
            }
        }
    }
}
