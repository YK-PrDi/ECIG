package com.elebusiness.service.video;

import com.elebusiness.config.AppProperties;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VideoModelCatalogTest {

    @Test
    void exposesSixModelsAndKeepsProviderCredentialsIndependent() {
        AppProperties properties = new AppProperties();
        properties.getGemini().setApiKey("veo-key");
        properties.getVolcengine().setApiKey("seedance-key");
        properties.getSuiXiangVideo().getGrok().setApiKey("grok-key");

        VideoModelCatalog catalog = new VideoModelCatalog(properties);
        List<VideoModelCatalog.ModelView> models = catalog.models();

        assertEquals(List.of(
                        "veo-3.1-generate-preview",
                        "doubao-seedance-2-0-260128",
                        "grok-imagine-video",
                        "grok-imagine-video-1.5",
                        "as-sd2.0-fast",
                        "video-ds-2.0"),
                models.stream().map(VideoModelCatalog.ModelView::id).toList());
        assertEquals(VideoModelCatalog.Provider.SUIXIANG_GROK,
                catalog.require("grok-imagine-video-1.5").provider());
        assertEquals(VideoModelCatalog.Provider.SUIXIANG_JIMENG,
                catalog.require("video-ds-2.0").provider());
        assertEquals("suixiang-grok-text", catalog.require("grok-imagine-video").providerId());
        assertEquals("Grok 文生视频", catalog.require("grok-imagine-video").providerLabel());
        assertEquals("suixiang-grok-image", catalog.require("grok-imagine-video-1.5").providerId());
        assertEquals("Grok 图生视频", catalog.require("grok-imagine-video-1.5").providerLabel());
        assertEquals(VideoModelCatalog.InputMode.TEXT_ONLY,
                catalog.require("grok-imagine-video").inputMode());
        assertEquals(VideoModelCatalog.InputMode.IMAGE_ONLY,
                catalog.require("grok-imagine-video-1.5").inputMode());
        assertEquals("suixiang-jimeng", catalog.require("video-ds-2.0").providerId());
        assertTrue(catalog.require("grok-imagine-video").configured());
        assertFalse(catalog.require("as-sd2.0-fast").configured());
        assertEquals("grok-key", catalog.credentialFor(catalog.require("grok-imagine-video")).getApiKey());
        assertEquals("", catalog.credentialFor(catalog.require("video-ds-2.0")).getApiKey());
    }

    @Test
    void rejectsUnknownVideoModels() {
        VideoModelCatalog catalog = new VideoModelCatalog(new AppProperties());

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> catalog.require("unknown-video-model"));

        assertEquals("不支持的视频模型: unknown-video-model", error.getMessage());
    }
}
