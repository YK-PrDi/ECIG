package com.elebusiness.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LiblibConfigTest {

    @Test
    void enablesLiblibWhenAccessKeyAndSecretKeyAreConfigured() {
        LiblibConfig config = new LiblibConfig();
        config.setAccessKey("access-key");
        config.setSecretKey("secret-key");

        assertTrue(config.isEnabled());
    }

    @Test
    void defaultsToLiblibOpenApiHost() {
        LiblibConfig config = new LiblibConfig();

        assertEquals("https://openapi.liblibai.cloud", config.getBaseUrl());
    }

    @Test
    void defaultsToAdvancedTemplatesThatSupportLora() {
        LiblibConfig config = new LiblibConfig();

        assertEquals(LiblibConfig.LORA_TEXT_TO_IMAGE_TEMPLATE_UUID, config.effectiveTemplateUuid(false));
        assertEquals(LiblibConfig.LORA_IMAGE_TO_IMAGE_TEMPLATE_UUID, config.effectiveTemplateUuid(true));
        assertEquals(LiblibConfig.DEFAULT_CHECKPOINT_ID, config.getCheckpointId());
    }

    @Test
    void mapsLegacyStar3TemplatesToAdvancedLoraTemplates() {
        LiblibConfig config = new LiblibConfig();
        config.setTextToImageTemplateUuid(LiblibConfig.STAR3_TEXT_TO_IMAGE_TEMPLATE_UUID);
        config.setImageToImageTemplateUuid(LiblibConfig.STAR3_IMAGE_TO_IMAGE_TEMPLATE_UUID);

        assertEquals(LiblibConfig.LORA_TEXT_TO_IMAGE_TEMPLATE_UUID, config.effectiveTemplateUuid(false));
        assertEquals(LiblibConfig.LORA_IMAGE_TO_IMAGE_TEMPLATE_UUID, config.effectiveTemplateUuid(true));
    }

    @Test
    void mapsLegacyFallbackTemplateUuidToAdvancedLoraTemplate() {
        LiblibConfig config = new LiblibConfig();
        config.setTextToImageTemplateUuid("");
        config.setImageToImageTemplateUuid("");
        config.setTemplateUuid(LiblibConfig.STAR3_IMAGE_TO_IMAGE_TEMPLATE_UUID);

        assertEquals(LiblibConfig.LORA_IMAGE_TO_IMAGE_TEMPLATE_UUID, config.effectiveTemplateUuid(true));
    }

    @Test
    void mapsModelPageUuidToCallableLoraVersionUuid() {
        LiblibConfig config = new LiblibConfig();
        config.setLoraModelId(LiblibConfig.LEGACY_LORA_MODEL_PAGE_UUID);

        assertEquals(LiblibConfig.DEFAULT_LORA_MODEL_VERSION_UUID, config.effectiveLoraModelId());
        assertTrue(config.isLoraConfigured());
    }

    @Test
    void normalizesAnyLegacyLoraPresetToSingleDefaultTone() {
        assertEquals(LiblibConfig.DEFAULT_LORA_PRESET, LiblibConfig.normalizeLoraPreset(null));
        assertEquals(LiblibConfig.DEFAULT_LORA_PRESET, LiblibConfig.normalizeLoraPreset(""));
        assertEquals(LiblibConfig.DEFAULT_LORA_PRESET, LiblibConfig.normalizeLoraPreset("studio"));
        assertEquals(LiblibConfig.DEFAULT_LORA_PRESET, LiblibConfig.normalizeLoraPreset("lifestyle"));
        assertEquals(LiblibConfig.DEFAULT_LORA_PRESET, LiblibConfig.normalizeLoraPreset("minimal"));
        assertEquals(LiblibConfig.DEFAULT_LORA_PRESET, LiblibConfig.normalizeLoraPreset("future-custom"));
    }
}
