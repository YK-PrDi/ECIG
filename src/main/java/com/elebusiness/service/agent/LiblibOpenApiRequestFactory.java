package com.elebusiness.service.agent;

import com.elebusiness.config.LiblibConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

final class LiblibOpenApiRequestFactory {

    static final String TEXT_TO_IMAGE_URI = "/api/generate/webui/text2img";
    static final String IMAGE_TO_IMAGE_URI = "/api/generate/webui/img2img";
    static final String STATUS_URI = "/api/generate/webui/status";

    private static final String FALLBACK_ENGLISH_PROMPT = "professional ecommerce product photography, premium product advertising image, clean studio lighting, sharp focus, high detail, commercial composition, no watermark, no text";

    private LiblibOpenApiRequestFactory() {
    }

    static String generateUri(boolean hasReferenceImage) {
        return hasReferenceImage ? IMAGE_TO_IMAGE_URI : TEXT_TO_IMAGE_URI;
    }

    static ObjectNode statusBody(ObjectMapper objectMapper, String generateUuid) {
        return objectMapper.createObjectNode().put("generateUuid", generateUuid);
    }

    static ObjectNode generateBody(ObjectMapper objectMapper, LiblibConfig config, String prompt,
                                   String sourceImageUrl, int width, int height) {
        boolean hasSourceImage = hasText(sourceImageUrl);
        ObjectNode body = objectMapper.createObjectNode();
        body.put("templateUuid", config.effectiveTemplateUuid(hasSourceImage));

        ObjectNode params = body.putObject("generateParams");
        String normalizedPrompt = normalizePrompt(prompt);
        if (hasSourceImage) {
            normalizedPrompt = withSourceProductPreservation(normalizedPrompt);
        }
        params.put("checkPointId", effectiveCheckpointId(config));
        params.put("prompt", normalizedPrompt);
        params.put("negativePrompt", config.getNegativePrompt());
        params.put("clipSkip", 2);
        params.put("sampler", 15);
        params.put("steps", Math.max(1, config.getDefaultSteps()));
        params.put("cfgScale", config.getCfgScale());
        params.put("randnSource", 0);
        params.put("seed", -1);
        params.put("imgCount", 1);
        params.put("restoreFaces", 0);

        appendLora(params, config);

        if (hasSourceImage) {
            params.put("sourceImage", sourceImageUrl);
            params.put("resizeMode", 2);
            params.put("resizedWidth", clamp(width, 128, 2048));
            params.put("resizedHeight", clamp(height, 128, 2048));
            params.put("mode", 0);
            params.put("denoisingStrength", 0.35);
        } else {
            params.put("width", clamp(width, 128, 2048));
            params.put("height", clamp(height, 128, 2048));
        }

        return body;
    }

    static String normalizePrompt(String prompt) {
        String value = prompt == null ? "" : prompt.trim();
        if (value.isBlank()) {
            return FALLBACK_ENGLISH_PROMPT;
        }
        if (isAsciiText(value) && containsAsciiLetter(value)) {
            return value.length() <= 2000 ? value : value.substring(0, 2000);
        }

        String subject = inferEnglishSubject(value);
        String normalized = subject.isBlank()
                ? FALLBACK_ENGLISH_PROMPT
                : subject + ", " + FALLBACK_ENGLISH_PROMPT + ", preserve the source product shape, preserve product structure, keep the same product category";
        return normalized.length() <= 2000 ? normalized : normalized.substring(0, 2000);
    }

    private static String withSourceProductPreservation(String prompt) {
        String preservation = "preserve the exact source product, preserve the source product silhouette, preserve product structure, keep the same product category";
        if (prompt.contains("preserve the exact source product")) {
            return prompt;
        }
        String value = prompt + ", " + preservation;
        return value.length() <= 2000 ? value : value.substring(0, 2000);
    }

    static boolean isSuccessCode(JsonNode root) {
        JsonNode codeNode = root.path("code");
        if (codeNode.isMissingNode() || codeNode.isNull()) {
            return true;
        }
        String code = codeNode.asText();
        return "0".equals(code) || "200".equals(code);
    }

    static String message(JsonNode root) {
        String msg = root.path("msg").asText("");
        if (!msg.isBlank()) {
            return msg;
        }
        return root.path("message").asText("");
    }

    static String extractGenerateUuid(JsonNode root) {
        return firstText(
                root.path("data").path("generateUuid"),
                root.path("data").path("uuid"),
                root.path("generateUuid"),
                root.path("uuid")
        );
    }

    static String extractImageValue(JsonNode root) {
        String direct = firstText(
                root.path("data").path("imageUrl"),
                root.path("data").path("url"),
                root.path("output").path("url"),
                root.path("imageUrl"),
                root.path("url")
        );
        if (!direct.isBlank()) {
            return direct;
        }

        String dataImage = firstImageFromArray(root.path("data").path("images"));
        if (!dataImage.isBlank()) {
            return dataImage;
        }

        String rootImage = firstImageFromArray(root.path("images"));
        if (!rootImage.isBlank()) {
            return rootImage;
        }

        return firstImageFromArray(root.path("output").path("images"));
    }

    static boolean isFailedStatus(JsonNode root) {
        String status = firstText(
                root.path("data").path("generateStatus"),
                root.path("data").path("status"),
                root.path("status")
        ).toLowerCase();
        return status.contains("fail") || status.contains("error") || "failed".equals(status) || "4".equals(status) || "6".equals(status);
    }

    private static void appendLora(ObjectNode params, LiblibConfig config) {
        String loraModelId = config.effectiveLoraModelId();
        if (!hasText(loraModelId)) {
            return;
        }
        ArrayNode networks = params.putArray("additionalNetwork");
        ObjectNode lora = networks.addObject();
        lora.put("modelId", loraModelId);
        lora.put("weight", config.getLoraWeight());
    }

    private static String effectiveCheckpointId(LiblibConfig config) {
        return hasText(config.getCheckpointId()) ? config.getCheckpointId() : LiblibConfig.DEFAULT_CHECKPOINT_ID;
    }

    private static String inferEnglishSubject(String value) {
        if (value.contains("花洒") || value.contains("淋浴") || value.contains("莲蓬头")) {
            return "shower head product";
        }
        if (value.contains("水龙头") || value.contains("龙头")) {
            return "faucet product";
        }
        if (value.contains("灯") || value.contains("照明")) {
            return "lighting product";
        }
        if (value.contains("椅") || value.contains("凳")) {
            return "chair product";
        }
        if (value.contains("桌")) {
            return "table product";
        }
        if (value.contains("架") || value.contains("置物")) {
            return "storage rack product";
        }
        if (value.contains("杯")) {
            return "cup product";
        }
        if (value.contains("瓶")) {
            return "bottle product";
        }
        if (value.contains("盒")) {
            return "box product";
        }
        if (value.contains("锅")) {
            return "cookware product";
        }
        if (value.contains("浴室") || value.contains("卫浴")) {
            return "bathroom product";
        }
        if (value.contains("产品") || value.contains("商品")) {
            return "product";
        }
        return "";
    }

    private static String firstImageFromArray(JsonNode images) {
        if (!images.isArray() || images.isEmpty()) {
            return "";
        }
        JsonNode first = images.get(0);
        if (first.isTextual()) {
            return first.asText("");
        }
        return firstText(
                first.path("imageUrl"),
                first.path("url"),
                first.path("originalImageUrl"),
                first.path("image")
        );
    }

    private static String firstText(JsonNode... nodes) {
        for (JsonNode node : nodes) {
            if (node != null && !node.isMissingNode() && !node.isNull()) {
                String value = node.asText("");
                if (!value.isBlank()) {
                    return value;
                }
            }
        }
        return "";
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static boolean isAsciiText(String value) {
        for (int i = 0; i < value.length(); i++) {
            if (value.charAt(i) > 127) {
                return false;
            }
        }
        return true;
    }

    private static boolean containsAsciiLetter(String value) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z')) {
                return true;
            }
        }
        return false;
    }
}
