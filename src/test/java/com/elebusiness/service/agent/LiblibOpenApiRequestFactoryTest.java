package com.elebusiness.service.agent;

import com.elebusiness.config.LiblibConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LiblibOpenApiRequestFactoryTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void usesImg2ImgEndpointThatSupportsLoraWhenReferenceImageExists() {
        assertEquals(
                "/api/generate/webui/img2img",
                LiblibOpenApiRequestFactory.generateUri(true)
        );
    }

    @Test
    void usesText2ImgEndpointThatSupportsLoraWhenNoReferenceImageExists() {
        assertEquals(
                "/api/generate/webui/text2img",
                LiblibOpenApiRequestFactory.generateUri(false)
        );
    }

    @Test
    void buildsStatusRequestBodyWithGenerateUuid() {
        ObjectNode body = LiblibOpenApiRequestFactory.statusBody(objectMapper, "task-123");

        assertEquals("task-123", body.path("generateUuid").asText());
    }

    @Test
    void buildsLoraTextToImageBodyFromAdvancedTemplate() {
        LiblibConfig config = new LiblibConfig();
        config.setTextToImageTemplateUuid("text-template");
        config.setCheckpointId("checkpoint-template");
        config.setDefaultSteps(24);

        ObjectNode body = LiblibOpenApiRequestFactory.generateBody(
                objectMapper,
                config,
                "professional product photo",
                "",
                768,
                1024
        );

        assertEquals("text-template", body.path("templateUuid").asText());
        JsonNode params = body.path("generateParams");
        assertEquals("checkpoint-template", params.path("checkPointId").asText());
        assertEquals("professional product photo", params.path("prompt").asText());
        assertEquals(24, params.path("steps").asInt());
        assertEquals(1, params.path("imgCount").asInt());
        assertEquals(768, params.path("width").asInt());
        assertEquals(1024, params.path("height").asInt());
        assertFalse(params.has("sourceImage"));
        assertFalse(params.has("initImage"));
    }

    @Test
    void buildsLoraImageToImageBodyFromAdvancedTemplate() {
        LiblibConfig config = new LiblibConfig();
        config.setImageToImageTemplateUuid("image-template");
        config.setCheckpointId("checkpoint-template");
        config.setLoraModelId(LiblibConfig.LEGACY_LORA_MODEL_PAGE_UUID);
        config.setLoraWeight(0.9);

        ObjectNode body = LiblibOpenApiRequestFactory.generateBody(
                objectMapper,
                config,
                "professional product photo",
                "https://cdn.example.com/ref.png",
                768,
                1024
        );

        assertEquals("image-template", body.path("templateUuid").asText());
        JsonNode params = body.path("generateParams");
        assertEquals("checkpoint-template", params.path("checkPointId").asText());
        assertTrue(params.path("prompt").asText().contains("professional product photo"));
        assertTrue(params.path("prompt").asText().contains("preserve the exact source product"));
        assertTrue(params.path("prompt").asText().contains("keep the same product category"));
        assertEquals("https://cdn.example.com/ref.png", params.path("sourceImage").asText());
        assertEquals(1, params.path("imgCount").asInt());
        assertEquals(2, params.path("resizeMode").asInt());
        assertEquals(768, params.path("resizedWidth").asInt());
        assertEquals(1024, params.path("resizedHeight").asInt());
        assertEquals(0, params.path("mode").asInt());
        assertEquals(0.35, params.path("denoisingStrength").asDouble(), 0.0001);
        assertEquals(LiblibConfig.DEFAULT_LORA_MODEL_VERSION_UUID, params.path("additionalNetwork").get(0).path("modelId").asText());
        assertEquals(0.9, params.path("additionalNetwork").get(0).path("weight").asDouble(), 0.0001);
        assertFalse(params.has("initImage"));
        assertTrue(params.has("negativePrompt"));
        assertTrue(params.has("cfgScale"));
    }

    @Test
    void keepsProductMeaningWhenPromptIsChinese() {
        String normalized = LiblibOpenApiRequestFactory.normalizePrompt("花洒，高级灰背景，突出产品质感");

        assertTrue(normalized.matches("[\\x00-\\x7F]+"));
        assertTrue(normalized.contains("professional ecommerce product photography"));
        assertTrue(normalized.toLowerCase().contains("shower"));
    }

    @Test
    void extractsGenerateUuidFromOpenApiCreateResponse() throws Exception {
        JsonNode root = objectMapper.readTree("{\"code\":0,\"data\":{\"generateUuid\":\"uuid-123\"}}");

        assertEquals("uuid-123", LiblibOpenApiRequestFactory.extractGenerateUuid(root));
    }

    @Test
    void extractsImageUrlFromOpenApiStatusResponse() throws Exception {
        JsonNode root = objectMapper.readTree("{\"code\":0,\"data\":{\"images\":[{\"imageUrl\":\"https://cdn.example.com/a.png\"}]}}");

        assertEquals("https://cdn.example.com/a.png", LiblibOpenApiRequestFactory.extractImageValue(root));
    }
}
