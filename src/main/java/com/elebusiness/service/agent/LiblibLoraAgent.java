package com.elebusiness.service.agent;

import com.elebusiness.config.LiblibConfig;
import com.elebusiness.service.CosService;
import com.elebusiness.service.provider.UserProviderCredentialService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Service
public class LiblibLoraAgent implements ImageGeneratorAgent {

    private static final Logger log = LoggerFactory.getLogger(LiblibLoraAgent.class);
    private static final MediaType JSON_TYPE = MediaType.get("application/json; charset=utf-8");
    private static final int MAX_POLL_ATTEMPTS = 90;

    private final LiblibConfig config;
    private final CosService cosService;
    private final UserProviderCredentialService credentialService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public LiblibLoraAgent(LiblibConfig config, CosService cosService,
                           UserProviderCredentialService credentialService) {
        this.config = config;
        this.cosService = cosService;
        this.credentialService = credentialService;
    }

    @Override
    public String getId() {
        return "liblib-lora";
    }

    @Override
    public String getDisplayName() {
        return "liblib AI + LoRA (电商展台)";
    }

    @Override
    public boolean generate(String prompt, String refImagePath, String whiteBgPath, String outputPath) {
        return generateWithAspect(prompt, refImagePath, whiteBgPath, outputPath, null);
    }

    @Override
    public boolean generateMulti(String prompt, List<String> refImagePaths,
                                 String whiteBgPath, String outputPath, String aspect) {
        String firstRef = (refImagePaths != null && !refImagePaths.isEmpty()) ? refImagePaths.get(0) : null;
        return generateWithAspect(prompt, firstRef, whiteBgPath, outputPath, aspect);
    }

    private boolean generateWithAspect(String prompt, String refImagePath, String whiteBgPath,
                                       String outputPath, String aspect) {
        RequestCredential requestCredential = resolveRequestCredential();
        if (!requestCredential.configured()) {
            log.error("liblib OpenAPI AccessKey/SecretKey 未配置");
            return false;
        }

        try {
            String inputImagePath = hasText(refImagePath) ? refImagePath : whiteBgPath;
            String sourceImageUrl = LiblibSourceImageResolver.resolve(inputImagePath, cosService);
            boolean hasReferenceImage = hasText(sourceImageUrl);
            ImageSize size = pickSize(aspect);
            String normalizedPrompt = LiblibOpenApiRequestFactory.normalizePrompt(prompt);
            if (!normalizedPrompt.equals(prompt == null ? "" : prompt.trim())) {
                log.warn("liblib prompt 已转换为英文: originalLen={}, normalizedLen={}",
                        prompt == null ? 0 : prompt.trim().length(), normalizedPrompt.length());
            }

            ObjectNode createBody = LiblibOpenApiRequestFactory.generateBody(
                    objectMapper,
                    config,
                    normalizedPrompt,
                    sourceImageUrl,
                    size.width(),
                    size.height()
            );
            String createUri = LiblibOpenApiRequestFactory.generateUri(hasReferenceImage);

            log.info("liblib 生成开始: uri={}, template={}, checkpoint={}, loraModelId={}, size={}x{}, sourceImage={}, sourceImageHost={}, loraConfigured={}",
                    createUri,
                    config.effectiveTemplateUuid(hasReferenceImage),
                    config.getCheckpointId(),
                    config.effectiveLoraModelId(),
                    size.width(),
                    size.height(),
                    hasReferenceImage,
                    sourceImageHost(sourceImageUrl),
                    config.isLoraConfigured());

            OkHttpClient client = buildClient();
            String generateUuid = submitTask(client, requestCredential, createUri, createBody);
            recordSubmittedGenerateUuid(generateUuid);
            log.info("liblib 任务已提交，generateUuid={}", generateUuid);

            String imageValue = pollTask(client, requestCredential, generateUuid);
            saveImageValue(imageValue, outputPath);
            log.info("liblib 图片已保存: {}", outputPath);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("liblib 任务被中断");
            return false;
        } catch (Exception e) {
            log.error("liblib 生成失败: {}", e.getMessage(), e);
            return false;
        }
    }

    RequestCredential resolveRequestCredential() {
        if (credentialService != null) {
            var userCredential = GenerationInvocationContext.currentUserId()
                    .flatMap(userId -> credentialService.resolveCredential(userId, "liblib", "default"))
                    .map(UserProviderCredentialService.ResolvedCredential::payload)
                    .map(this::credentialFromPayload)
                    .filter(RequestCredential::configured);
            if (userCredential.isPresent()) {
                return userCredential.get();
            }
        }
        if (config.hasAccessSecretPair()) {
            return new RequestCredential("platform", config.getAccessKey(), config.getSecretKey());
        }
        return new RequestCredential("none", "", "");
    }

    void recordSubmittedGenerateUuid(String generateUuid) {
        GenerationProviderCostContext.recordProviderTaskId("liblib", generateUuid);
    }

    private RequestCredential credentialFromPayload(Map<String, Object> payload) {
        return new RequestCredential(
                "user",
                stringValue(payload, "accessKey"),
                stringValue(payload, "secretKey")
        );
    }

    private String stringValue(Map<String, Object> payload, String key) {
        if (payload == null || payload.get(key) == null) {
            return "";
        }
        return String.valueOf(payload.get(key)).trim();
    }

    private String submitTask(OkHttpClient client, RequestCredential credential, String uri, ObjectNode body) throws IOException {
        String responseBody = postJson(client, credential, uri, body);
        JsonNode root = objectMapper.readTree(responseBody);
        if (!LiblibOpenApiRequestFactory.isSuccessCode(root)) {
            throw new IOException("liblib 创建任务失败: " + LiblibOpenApiRequestFactory.message(root) + "，响应: " + responseBody);
        }

        String generateUuid = LiblibOpenApiRequestFactory.extractGenerateUuid(root);
        if (!hasText(generateUuid)) {
            throw new IOException("liblib 创建任务未返回 generateUuid，响应: " + responseBody);
        }
        return generateUuid;
    }

    private String pollTask(OkHttpClient client, RequestCredential credential, String generateUuid) throws IOException, InterruptedException {
        for (int i = 0; i < MAX_POLL_ATTEMPTS; i++) {
            Thread.sleep(i < 5 ? 2000L : 3000L);

            ObjectNode body = LiblibOpenApiRequestFactory.statusBody(objectMapper, generateUuid);
            String responseBody = postJson(client, credential, LiblibOpenApiRequestFactory.STATUS_URI, body);
            JsonNode root = objectMapper.readTree(responseBody);
            if (!LiblibOpenApiRequestFactory.isSuccessCode(root)) {
                throw new IOException("liblib 状态查询失败: " + LiblibOpenApiRequestFactory.message(root) + "，响应: " + responseBody);
            }

            String imageValue = LiblibOpenApiRequestFactory.extractImageValue(root);
            if (hasText(imageValue)) {
                return imageValue;
            }
            if (LiblibOpenApiRequestFactory.isFailedStatus(root)) {
                throw new IOException("liblib 任务失败，响应: " + responseBody);
            }

            String status = root.path("data").path("generateStatus").asText(root.path("status").asText("unknown"));
            log.info("liblib 轮询第 {} 次，状态={}", i + 1, status);
        }
        throw new IOException("liblib 任务超时，未在轮询窗口内返回图片");
    }

    private String postJson(OkHttpClient client, RequestCredential credential, String uri, ObjectNode body) throws IOException {
        String url = LiblibOpenApiSigner.signedUrl(
                config.getBaseUrl(),
                uri,
                credential.accessKey(),
                credential.secretKey(),
                String.valueOf(System.currentTimeMillis()),
                UUID.randomUUID().toString().replace("-", "")
        );

        Request request = new Request.Builder()
                .url(url)
                .post(RequestBody.create(objectMapper.writeValueAsString(body), JSON_TYPE))
                .build();

        try (Response response = client.newCall(request).execute()) {
            String responseBody = response.body() == null ? "" : response.body().string();
            if (!response.isSuccessful()) {
                log.error("liblib API HTTP 错误: status={}, body={}", response.code(), responseBody);
                throw new IOException("liblib API HTTP 请求失败: " + response.code());
            }
            return responseBody;
        }
    }

    private void saveImageValue(String imageValue, String outputPath) throws IOException {
        if (imageValue.startsWith("http://") || imageValue.startsWith("https://")) {
            downloadImage(imageValue, outputPath);
        } else {
            saveBase64Image(imageValue, outputPath);
        }
    }

    private void saveBase64Image(String base64Data, String outputPath) throws IOException {
        if (base64Data.contains(",")) {
            base64Data = base64Data.substring(base64Data.indexOf(',') + 1);
        }

        byte[] imageBytes = Base64.getDecoder().decode(base64Data);
        File outputFile = new File(outputPath);
        File parent = outputFile.getParentFile();
        if (parent != null) {
            parent.mkdirs();
        }

        try (FileOutputStream fos = new FileOutputStream(outputFile)) {
            fos.write(imageBytes);
        }
    }

    private void downloadImage(String imageUrl, String outputPath) throws IOException {
        OkHttpClient client = buildClient();
        Request request = new Request.Builder().url(imageUrl).get().build();

        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("下载图片失败: " + response.code());
            }
            byte[] imageBytes = response.body() == null ? new byte[0] : response.body().bytes();

            File outputFile = new File(outputPath);
            File parent = outputFile.getParentFile();
            if (parent != null) {
                parent.mkdirs();
            }
            try (FileOutputStream fos = new FileOutputStream(outputFile)) {
                fos.write(imageBytes);
            }
        }
    }

    private ImageSize pickSize(String aspect) {
        if (!hasText(aspect) || "auto".equalsIgnoreCase(aspect)) {
            return parseSize(config.getDefaultSize());
        }
        return switch (aspect) {
            case "9:16", "portrait" -> new ImageSize(768, 1024);
            case "16:9", "landscape" -> new ImageSize(1280, 720);
            case "3:4" -> new ImageSize(768, 1024);
            case "4:3" -> new ImageSize(1024, 768);
            case "1:1", "square" -> new ImageSize(1024, 1024);
            default -> parseSize(config.getDefaultSize());
        };
    }

    private ImageSize parseSize(String value) {
        if (hasText(value)) {
            String normalized = value.toLowerCase().replace('*', 'x');
            String[] parts = normalized.split("x");
            if (parts.length == 2) {
                try {
                    return new ImageSize(Integer.parseInt(parts[0].trim()), Integer.parseInt(parts[1].trim()));
                } catch (NumberFormatException ignored) {
                    // 使用兜底尺寸。
                }
            }
        }
        return new ImageSize(1024, 1024);
    }

    private OkHttpClient buildClient() {
        return new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .build();
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private static String sourceImageHost(String sourceImageUrl) {
        if (!hasText(sourceImageUrl)) {
            return "";
        }
        try {
            return java.net.URI.create(sourceImageUrl).getHost();
        } catch (IllegalArgumentException ignored) {
            return "invalid-url";
        }
    }

    private record ImageSize(int width, int height) {
    }

    public record RequestCredential(String source, String accessKey, String secretKey) {
        public boolean configured() {
            return hasText(accessKey) && hasText(secretKey);
        }
    }
}
