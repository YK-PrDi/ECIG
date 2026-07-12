package com.elebusiness.test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import okhttp3.*;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Base64;
import java.util.concurrent.TimeUnit;

/**
 * liblib AI + LoRA 测试程序
 * 独立运行，测试 API 调用和 LoRA 接入
 */
public class LiblibLoraTest {

    private static final String API_KEY = "VhJ23mT6RFBPykIArYAOYWfdkb66sp3G";
    private static final String BASE_URL = "https://api.liblib.art/v1";

    // LoRA 配置（从 URL 提取的 ID）
    private static final String LORA_MODEL_ID = "7e286ca9554e462f94ffde591d21a6fb"; // 或 "8aef8e5223a14c1a8808bebdcf128c46"
    private static final double LORA_WEIGHT = 0.9;

    private static final ObjectMapper mapper = new ObjectMapper();

    public static void main(String[] args) {
        System.out.println("========================================");
        System.out.println("  liblib AI + LoRA 测试");
        System.out.println("========================================");
        System.out.println();

        try {
            // 测试 1: 检查 API 连通性
            System.out.println("[1/3] 测试 API 连通性...");
            if (!testApiConnection()) {
                System.err.println("✗ API 连接失败，请检查 API Key");
                return;
            }
            System.out.println("✓ API 连接成功");
            System.out.println();

            // 测试 2: 文生图（不使用 LoRA）
            System.out.println("[2/3] 测试基础文生图...");
            String output1 = "test_output_basic.png";
            if (testTextToImage(false, output1)) {
                System.out.println("✓ 基础文生图成功: " + output1);
            } else {
                System.err.println("✗ 基础文生图失败");
            }
            System.out.println();

            // 测试 3: 文生图（使用 LoRA）
            System.out.println("[3/3] 测试 LoRA 文生图...");
            String output2 = "test_output_lora.png";
            if (testTextToImage(true, output2)) {
                System.out.println("✓ LoRA 文生图成功: " + output2);
            } else {
                System.err.println("✗ LoRA 文生图失败");
            }
            System.out.println();

            System.out.println("========================================");
            System.out.println("  测试完成！");
            System.out.println("  请查看生成的图片对比效果");
            System.out.println("========================================");

        } catch (Exception e) {
            System.err.println("测试失败: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * 测试 API 连通性
     */
    private static boolean testApiConnection() {
        try {
            OkHttpClient client = buildClient();

            Request request = new Request.Builder()
                    .url(BASE_URL + "/models")
                    .header("Authorization", "Bearer " + API_KEY)
                    .get()
                    .build();

            try (Response response = client.newCall(request).execute()) {
                if (response.isSuccessful()) {
                    System.out.println("  API 响应: " + response.code());
                    return true;
                } else {
                    System.err.println("  API 错误: " + response.code() + " - " + response.body().string());
                    return false;
                }
            }
        } catch (Exception e) {
            System.err.println("  连接异常: " + e.getMessage());
            return false;
        }
    }

    /**
     * 测试文生图
     */
    private static boolean testTextToImage(boolean useLora, String outputPath) {
        try {
            OkHttpClient client = buildClient();

            // 构建请求 JSON
            ObjectNode request = mapper.createObjectNode();
            request.put("model", "sdxl");
            request.put("prompt", "professional product photography, white background, studio lighting, high quality");
            request.put("negative_prompt", "worst quality, low quality, blurry, text, watermark");
            request.put("steps", 30);
            request.put("width", 1024);
            request.put("height", 1024);
            request.put("cfg_scale", 7.5);

            // 添加 LoRA 配置
            if (useLora) {
                ObjectNode loraNode = request.putObject("lora");
                loraNode.put("model_id", LORA_MODEL_ID);
                loraNode.put("weight", LORA_WEIGHT);
                System.out.println("  使用 LoRA: " + LORA_MODEL_ID + " (权重: " + LORA_WEIGHT + ")");
            } else {
                System.out.println("  不使用 LoRA");
            }

            System.out.println("  发送请求...");
            String requestBody = request.toString();
            System.out.println("  请求体: " + requestBody);

            RequestBody body = RequestBody.create(requestBody, MediaType.get("application/json"));
            Request httpRequest = new Request.Builder()
                    .url(BASE_URL + "/txt2img")
                    .header("Authorization", "Bearer " + API_KEY)
                    .post(body)
                    .build();

            try (Response response = client.newCall(httpRequest).execute()) {
                String responseBody = response.body().string();

                System.out.println("  响应状态: " + response.code());

                if (!response.isSuccessful()) {
                    System.err.println("  API 错误响应: " + responseBody);
                    return false;
                }

                System.out.println("  响应内容: " + responseBody.substring(0, Math.min(200, responseBody.length())) + "...");

                // 解析响应
                JsonNode root = mapper.readTree(responseBody);

                // 尝试多种可能的响应格式
                String imageData = extractImageData(root);

                if (imageData != null) {
                    saveImage(imageData, outputPath);
                    System.out.println("  图片已保存: " + outputPath);
                    return true;
                } else {
                    System.err.println("  无法从响应中提取图片数据");
                    System.err.println("  完整响应: " + responseBody);
                    return false;
                }
            }

        } catch (Exception e) {
            System.err.println("  生成失败: " + e.getMessage());
            e.printStackTrace();
            return false;
        }
    }

    /**
     * 从响应中提取图片数据（支持多种格式）
     */
    private static String extractImageData(JsonNode root) {
        // 格式 1: { "images": ["base64..."] }
        JsonNode images = root.path("images");
        if (images.isArray() && images.size() > 0) {
            return images.get(0).asText();
        }

        // 格式 2: { "data": { "images": [...] } }
        JsonNode dataImages = root.path("data").path("images");
        if (dataImages.isArray() && dataImages.size() > 0) {
            return dataImages.get(0).asText();
        }

        // 格式 3: { "output": { "url": "https://..." } }
        JsonNode url = root.path("output").path("url");
        if (!url.isMissingNode()) {
            return downloadImageFromUrl(url.asText());
        }

        // 格式 4: { "url": "https://..." }
        JsonNode directUrl = root.path("url");
        if (!directUrl.isMissingNode()) {
            return downloadImageFromUrl(directUrl.asText());
        }

        // 格式 5: { "image": "base64..." }
        JsonNode image = root.path("image");
        if (!image.isMissingNode()) {
            return image.asText();
        }

        return null;
    }

    /**
     * 下载图片 URL 并转为 base64
     */
    private static String downloadImageFromUrl(String imageUrl) {
        try {
            OkHttpClient client = buildClient();
            Request request = new Request.Builder().url(imageUrl).get().build();

            try (Response response = client.newCall(request).execute()) {
                if (response.isSuccessful()) {
                    byte[] bytes = response.body().bytes();
                    return Base64.getEncoder().encodeToString(bytes);
                }
            }
        } catch (Exception e) {
            System.err.println("  下载图片失败: " + e.getMessage());
        }
        return null;
    }

    /**
     * 保存图片
     */
    private static void saveImage(String base64Data, String outputPath) throws IOException {
        // 移除可能的 data URL 前缀
        if (base64Data.contains(",")) {
            base64Data = base64Data.substring(base64Data.indexOf(",") + 1);
        }

        byte[] imageBytes = Base64.getDecoder().decode(base64Data);

        File outputFile = new File(outputPath);
        outputFile.getParentFile().mkdirs();

        try (FileOutputStream fos = new FileOutputStream(outputFile)) {
            fos.write(imageBytes);
        }
    }

    /**
     * 构建 HTTP 客户端
     */
    private static OkHttpClient buildClient() {
        return new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(180, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .build();
    }
}
