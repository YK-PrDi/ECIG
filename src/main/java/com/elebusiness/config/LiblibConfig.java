package com.elebusiness.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * liblib AI 配置类
 * 用于管理 Liblib OpenAPI 调用参数。
 */
@Data
@Component
@ConfigurationProperties(prefix = "app.liblib")
public class LiblibConfig {

    public static final String STAR3_TEXT_TO_IMAGE_TEMPLATE_UUID = "5d7e67009b344550bc1aa6ccbfa1d7f4";
    public static final String STAR3_IMAGE_TO_IMAGE_TEMPLATE_UUID = "07e00af4fc464c7ab55ff906f8acf1b7";
    public static final String LORA_TEXT_TO_IMAGE_TEMPLATE_UUID = "6f7c4652458d4802969f8d089cf5b91f";
    public static final String LORA_IMAGE_TO_IMAGE_TEMPLATE_UUID = "63b72710c9574457ba303d9d9b8df8bd";
    public static final String DEFAULT_CHECKPOINT_ID = "412b427ddb674b4dbab9e5abd5ae6057";
    public static final String LEGACY_LORA_MODEL_PAGE_UUID = "7e286ca9554e462f94ffde591d21a6fb";
    public static final String DEFAULT_LORA_MODEL_VERSION_UUID = "8aef8e5223a14c1a8808bebdcf128c46";
    public static final String DEFAULT_LORA_PRESET = "default";

    /**
     * 旧版单 Key 配置：保留兼容本地历史配置。
     */
    private String apiKey;

    /**
     * liblib 开放平台 AccessKey。
     */
    private String accessKey;

    /**
     * liblib 开放平台 SecretKey。
     */
    private String secretKey;

    /**
     * API 基础 URL。
     */
    private String baseUrl = "https://openapi.liblibai.cloud";

    /**
     * 使用的基础模型，仅保留兼容旧配置；星流模板不直接使用该字段。
     */
    private String model = "sdxl";

    /**
     * LoRA 模型 ID，仅保留兼容旧配置；星流 Star-3 Alpha 模板不直接使用该字段。
     */
    private String loraModelId;

    /**
     * 旧版单模板 UUID。新代码优先使用 textToImageTemplateUuid / imageToImageTemplateUuid。
     */
    private String templateUuid;

    /**
     * 星流 Star-3 Alpha 文生图模板 UUID。
     */
    private String textToImageTemplateUuid = LORA_TEXT_TO_IMAGE_TEMPLATE_UUID;

    /**
     * 星流 Star-3 Alpha 图生图模板 UUID。
     */
    private String imageToImageTemplateUuid = LORA_IMAGE_TO_IMAGE_TEMPLATE_UUID;

    private String checkpointId = DEFAULT_CHECKPOINT_ID;

    /**
     * LoRA 权重（0.0-1.0），星流模板不直接使用该字段。
     */
    private double loraWeight = 0.9;

    /**
     * 默认生成步数。
     */
    private int defaultSteps = 30;

    /**
     * 默认图片尺寸。
     */
    private String defaultSize = "1024x1024";

    /**
     * CFG Scale，星流模板不直接使用该字段。
     */
    private double cfgScale = 7.5;

    /**
     * 负面提示词，星流模板不直接使用该字段。
     */
    private String negativePrompt = "worst quality, low quality, blurry, text, watermark, logo, deformed, malformed, distorted, artifacts";

    /**
     * 检查配置是否启用。
     */
    public boolean isEnabled() {
        return hasText(apiKey) || (hasText(accessKey) && hasText(secretKey));
    }

    /**
     * 实际请求密钥：优先使用 SecretKey，兼容旧 apiKey。
     */
    public String effectiveSecretKey() {
        return hasText(secretKey) ? secretKey : apiKey;
    }

    public boolean hasAccessSecretPair() {
        return hasText(accessKey) && hasText(secretKey);
    }

    /**
     * 检查 LoRA 是否已配置。
     */
    public boolean isLoraConfigured() {
        return hasText(effectiveLoraModelId());
    }

    public String effectiveLoraModelId() {
        if (!hasText(loraModelId)) {
            return "";
        }
        if (LEGACY_LORA_MODEL_PAGE_UUID.equals(loraModelId)) {
            return DEFAULT_LORA_MODEL_VERSION_UUID;
        }
        return loraModelId;
    }

    public String effectiveTemplateUuid(boolean hasReferenceImage) {
        if (hasReferenceImage) {
            if (hasText(imageToImageTemplateUuid)) return normalizeTemplateUuid(imageToImageTemplateUuid, true);
            if (hasText(templateUuid)) return normalizeTemplateUuid(templateUuid, true);
            return LORA_IMAGE_TO_IMAGE_TEMPLATE_UUID;
        }
        if (hasText(textToImageTemplateUuid)) return normalizeTemplateUuid(textToImageTemplateUuid, false);
        if (hasText(templateUuid)) return normalizeTemplateUuid(templateUuid, false);
        return LORA_TEXT_TO_IMAGE_TEMPLATE_UUID;
    }

    /**
     * 当前 Liblib LoRA 只开放一个统一电商摄影调性。
     * 旧前端可能仍会提交 studio/lifestyle/minimal，先统一归一，后续扩展可在这里接入 preset -> templateUuid。
     */
    public static String normalizeLoraPreset(String ignoredPreset) {
        return DEFAULT_LORA_PRESET;
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private static String normalizeTemplateUuid(String value, boolean hasReferenceImage) {
        if (hasReferenceImage && STAR3_IMAGE_TO_IMAGE_TEMPLATE_UUID.equals(value)) {
            return LORA_IMAGE_TO_IMAGE_TEMPLATE_UUID;
        }
        if (!hasReferenceImage && STAR3_TEXT_TO_IMAGE_TEMPLATE_UUID.equals(value)) {
            return LORA_TEXT_TO_IMAGE_TEMPLATE_UUID;
        }
        return value;
    }
}
