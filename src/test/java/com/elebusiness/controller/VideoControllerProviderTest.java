package com.elebusiness.controller;

import com.elebusiness.config.AppProperties;
import com.elebusiness.model.entity.GenerationUsageLog;
import com.elebusiness.service.HistoryService;
import com.elebusiness.service.SeedanceVideoService;
import com.elebusiness.service.TaskService;
import com.elebusiness.service.VideoGenerationService;
import com.elebusiness.service.auth.AuthService;
import com.elebusiness.service.auth.CurrentUserService;
import com.elebusiness.service.billing.BillingService;
import com.elebusiness.service.billing.GenerationPricingService;
import com.elebusiness.service.video.OpenAiCompatibleVideoService;
import com.elebusiness.service.video.VideoModelCatalog;
import com.elebusiness.service.video.VideoOutputNormalizer;
import com.elebusiness.service.workspace.UserStorageService;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class VideoControllerProviderTest {

    @Test
    void listsSixModelsWithoutExposingCredentials() throws Exception {
        Fixture fixture = fixture(true, true);

        ResponseEntity<List<Map<String, Object>>> response = fixture.controller.models();

        assertEquals(200, response.getStatusCode().value());
        assertEquals(6, response.getBody().size());
        assertEquals("grok-imagine-video", response.getBody().get(2).get("id"));
        assertEquals("suixiang-grok-text", response.getBody().get(2).get("providerId"));
        assertEquals("text_only", response.getBody().get(2).get("inputMode"));
        assertEquals("suixiang-grok-image", response.getBody().get(3).get("providerId"));
        assertEquals("image_only", response.getBody().get(3).get("inputMode"));
        assertEquals(0, response.getBody().get(2).get("level"));
        assertEquals(0, response.getBody().get(3).get("level"));
        assertEquals(Boolean.TRUE, response.getBody().get(2).get("configured"));
        assertTrue(response.getBody().stream().noneMatch(item -> item.keySet().stream()
                .anyMatch(key -> key.toLowerCase().contains("key"))));
        assertTrue(response.getBody().stream().noneMatch(item -> item.values().stream()
                .anyMatch(value -> "grok-secret".equals(value) || "jimeng-secret".equals(value))));
    }

    @Test
    void rejectsUnconfiguredProviderBeforeCreatingBillingUsage() throws Exception {
        Fixture fixture = fixture(true, false);

        ResponseEntity<Map<String, Object>> response = fixture.controller.generate(
                null, "video-ds-2.0", "test", "16:9", 8,
                null, null, true, "s1", fixture.session);

        assertEquals(400, response.getStatusCode().value());
        assertTrue(String.valueOf(response.getBody().get("message")).contains("SUIXIANG_JIMENG_VIDEO_API_KEY"));
        verify(fixture.billingService, never()).recordGenerationStarted(anyLong(), anyString(), anyString(), anyString(), anyInt());
        verify(fixture.compatibleVideoService, never()).generateVideo(any(), anyString(), anyList(), anyString(), anyInt(), anyString());
    }

    @Test
    void rejectsInvalidGrokInputsBeforeCreatingBillingUsage() throws Exception {
        Fixture fixture = fixture(true, true);
        MockMultipartFile image = new MockMultipartFile("images", "input.png", "image/png", new byte[]{1});

        ResponseEntity<Map<String, Object>> textResponse = fixture.controller.generate(
                List.of(image), "grok-imagine-video", "test", "16:9", 4,
                null, null, true, "s1", fixture.session);
        ResponseEntity<Map<String, Object>> imageResponse = fixture.controller.generate(
                null, "grok-imagine-video-1.5", "test", "16:9", 4,
                null, null, true, "s1", fixture.session);

        assertEquals(400, textResponse.getStatusCode().value());
        assertTrue(String.valueOf(textResponse.getBody().get("message")).contains("文生视频"));
        assertEquals(400, imageResponse.getStatusCode().value());
        assertTrue(String.valueOf(imageResponse.getBody().get("message")).contains("一张参考图"));
        verify(fixture.billingService, never()).recordGenerationStarted(anyLong(), anyString(), anyString(), anyString(), anyInt());
        verify(fixture.compatibleVideoService, never()).generateVideo(any(), anyString(), anyList(), anyString(), anyInt(), anyString());
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void dispatchesAllFourSuiXiangModelsToCompatibleVideoService() throws Exception {
        Fixture fixture = fixture(true, true);
        List<String> modelIds = List.of(
                "grok-imagine-video",
                "grok-imagine-video-1.5",
                "as-sd2.0-fast",
                "video-ds-2.0");
        CountDownLatch called = new CountDownLatch(modelIds.size());
        ConcurrentLinkedQueue<String> dispatched = new ConcurrentLinkedQueue<>();
        when(fixture.compatibleVideoService.generateVideo(any(), anyString(), anyList(), anyString(), anyInt(), anyString()))
                .thenAnswer(invocation -> {
                    VideoModelCatalog.ModelView model = invocation.getArgument(0);
                    String outputPath = invocation.getArgument(5);
                    dispatched.add(model.id());
                    called.countDown();
                    return outputPath;
                });

        MockMultipartFile image = new MockMultipartFile("images", "input.png", "image/png", new byte[]{1});
        for (String modelId : modelIds) {
            List<MockMultipartFile> images = "grok-imagine-video-1.5".equals(modelId)
                    ? List.of(image)
                    : null;
            ResponseEntity<Map<String, Object>> response = fixture.controller.generate(
                    (List) images, modelId, "test " + modelId, "16:9", 8,
                    null, null, true, "s1", fixture.session);
            assertEquals(200, response.getStatusCode().value());
            assertFalse(String.valueOf(response.getBody().get("taskId")).isBlank());
        }

        assertTrue(called.await(3, TimeUnit.SECONDS));
        assertEquals(Set.copyOf(modelIds), Set.copyOf(dispatched));
    }

    private Fixture fixture(boolean grokConfigured, boolean jimengConfigured) throws Exception {
        AppProperties properties = new AppProperties();
        properties.getApi().setAdminMaxConcurrentTasks(10);
        properties.getPaths().setUserDataDir(Files.createTempDirectory("video-provider").toString());
        if (grokConfigured) properties.getSuiXiangVideo().getGrok().setApiKey("grok-secret");
        if (jimengConfigured) properties.getSuiXiangVideo().getJimeng().setApiKey("jimeng-secret");
        TaskService taskService = new TaskService(properties);
        BillingService billingService = mock(BillingService.class);
        AtomicLong usageId = new AtomicLong(1);
        when(billingService.recordGenerationStarted(anyLong(), anyString(), anyString(), anyString(), anyInt()))
                .thenAnswer(invocation -> {
                    GenerationUsageLog log = new GenerationUsageLog();
                    log.setId(usageId.getAndIncrement());
                    return log;
                });
        CurrentUserService currentUserService = new CurrentUserService();
        MockHttpSession session = new MockHttpSession();
        currentUserService.bind(session, new AuthService.AuthUser(1001L, "admin", "Admin", "ADMIN", 1L));
        VideoModelCatalog catalog = new VideoModelCatalog(properties);
        OpenAiCompatibleVideoService compatible = mock(OpenAiCompatibleVideoService.class);
        VideoController controller = new VideoController(
                mock(VideoGenerationService.class),
                mock(SeedanceVideoService.class),
                taskService,
                properties,
                mock(HistoryService.class),
                currentUserService,
                new UserStorageService(properties),
                billingService,
                new GenerationPricingService(properties),
                compatible,
                catalog,
                passthroughNormalizer()
        );
        return new Fixture(controller, session, billingService, compatible);
    }

    private record Fixture(VideoController controller,
                           MockHttpSession session,
                           BillingService billingService,
                           OpenAiCompatibleVideoService compatibleVideoService) {
    }

    private VideoOutputNormalizer passthroughNormalizer() throws Exception {
        VideoOutputNormalizer normalizer = mock(VideoOutputNormalizer.class);
        when(normalizer.normalize(anyString(), anyString())).thenAnswer(invocation -> invocation.getArgument(0));
        return normalizer;
    }
}
