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

import java.nio.file.Files;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class VideoControllerConcurrencyTest {

    @Test
    void concurrentVideoTasksUseIndependentTaskScopedOutputPaths() throws Exception {
        AppProperties properties = new AppProperties();
        properties.getApi().setUserMaxConcurrentTasks(3);
        properties.getGemini().setApiKey("test-gemini-key");
        properties.getPaths().setUserDataDir(Files.createTempDirectory("video-concurrency").toString());
        TaskService taskService = new TaskService(properties);
        VideoGenerationService videoService = mock(VideoGenerationService.class);
        CountDownLatch started = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        Map<String, String> outputPaths = new ConcurrentHashMap<>();
        when(videoService.generateVideo(anyString(), anyString(), anyInt(), anyString()))
                .thenAnswer(invocation -> {
                    String prompt = invocation.getArgument(0);
                    String outputPath = invocation.getArgument(3);
                    outputPaths.put(prompt, outputPath);
                    started.countDown();
                    release.await(3, TimeUnit.SECONDS);
                    return outputPath;
                });

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
        currentUserService.bind(session, new AuthService.AuthUser(1001L, "alice", "Alice", "USER"));
        VideoController controller = new VideoController(
                videoService,
                mock(SeedanceVideoService.class),
                taskService,
                properties,
                mock(HistoryService.class),
                currentUserService,
                new UserStorageService(properties),
                billingService,
                new GenerationPricingService(properties),
                mock(OpenAiCompatibleVideoService.class),
                new VideoModelCatalog(properties),
                passthroughNormalizer()
        );

        ResponseEntity<Map<String, Object>> first = controller.generate(
                null, "veo-3.1-generate-preview", "first-video", "16:9", 8,
                null, null, true, "s1", session);
        ResponseEntity<Map<String, Object>> second = controller.generate(
                null, "veo-3.1-generate-preview", "second-video", "16:9", 8,
                null, null, true, "s1", session);

        try {
            assertTrue(started.await(3, TimeUnit.SECONDS));
            String firstTaskId = String.valueOf(first.getBody().get("taskId"));
            String secondTaskId = String.valueOf(second.getBody().get("taskId"));
            assertTrue(outputPaths.get("first-video").contains(firstTaskId));
            assertTrue(outputPaths.get("second-video").contains(secondTaskId));
        } finally {
            release.countDown();
        }
    }

    private VideoOutputNormalizer passthroughNormalizer() throws Exception {
        VideoOutputNormalizer normalizer = mock(VideoOutputNormalizer.class);
        when(normalizer.normalize(anyString(), anyString())).thenAnswer(invocation -> invocation.getArgument(0));
        return normalizer;
    }
}
