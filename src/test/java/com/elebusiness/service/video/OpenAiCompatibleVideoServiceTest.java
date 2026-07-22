package com.elebusiness.service.video;

import com.elebusiness.config.AppProperties;
import com.elebusiness.service.agent.GenerationCancellationContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OpenAiCompatibleVideoServiceTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private HttpServer server;
    private HttpServer mediaProxyServer;
    private ExecutorService serverExecutor;
    private ExecutorService mediaProxyExecutor;
    private ExecutorService callerExecutor;

    @TempDir
    Path tempDir;

    @AfterEach
    void tearDown() {
        GenerationCancellationContext.clearTask("video-cancel-task");
        if (server != null) server.stop(0);
        if (mediaProxyServer != null) mediaProxyServer.stop(0);
        if (serverExecutor != null) serverExecutor.shutdownNow();
        if (mediaProxyExecutor != null) mediaProxyExecutor.shutdownNow();
        if (callerExecutor != null) callerExecutor.shutdownNow();
    }

    @Test
    void submitsGrokImageVideoWithProxyCompatibleImageUrlAndPollsRequestId() throws Exception {
        byte[] expectedVideo = "grok-video".getBytes(StandardCharsets.UTF_8);
        AtomicReference<String> authorization = new AtomicReference<>();
        AtomicReference<String> requestJson = new AtomicReference<>();
        startServer();
        server.createContext("/v1/videos/generations", exchange -> {
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            requestJson.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            respondJson(exchange, "{\"request_id\":\"grok-task-1\"}");
        });
        server.createContext("/v1/videos/grok-task-1", exchange -> {
            String videoUrl = serverUrl("/grok-result.mp4");
            respondJson(exchange, "{\"status\":\"done\",\"video\":{\"url\":\"" + videoUrl + "\"}}");
        });
        server.createContext("/grok-result.mp4", exchange -> respondBytes(exchange, expectedVideo));

        AppProperties properties = configuredProperties("grok-key", "jimeng-key");
        VideoModelCatalog catalog = new VideoModelCatalog(properties);
        OpenAiCompatibleVideoService service = service(properties, catalog, Duration.ofSeconds(2));
        Path output = tempDir.resolve("grok.mp4");

        String saved = service.generateVideo(
                catalog.require("grok-imagine-video-1.5"),
                "产品旋转展示",
                List.of("data:image/png;base64,aW1hZ2U="),
                "16:9",
                4,
                output.toString());

        assertEquals(output.toString(), saved);
        assertArrayEquals(expectedVideo, Files.readAllBytes(output));
        assertEquals("Bearer grok-key", authorization.get());
        JsonNode body = objectMapper.readTree(requestJson.get());
        assertEquals("grok-imagine-video-1.5", body.path("model").asText());
        assertEquals("产品旋转展示", body.path("prompt").asText());
        assertEquals(4, body.path("duration").asInt());
        assertEquals("16:9", body.path("aspect_ratio").asText());
        assertEquals("720p", body.path("resolution").asText());
        assertEquals("data:image/png;base64,aW1hZ2U=", body.path("image_url").asText());
        assertFalse(body.has("image"));
        assertFalse(body.has("messages"));
    }

    @Test
    void rejectsReferenceImageForGrokTextVideoBeforeCallingUpstream() throws Exception {
        startServer();
        AppProperties properties = configuredProperties("grok-key", "jimeng-key");
        VideoModelCatalog catalog = new VideoModelCatalog(properties);
        OpenAiCompatibleVideoService service = service(properties, catalog, Duration.ofSeconds(2));

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> service.generateVideo(
                catalog.require("grok-imagine-video"),
                "产品旋转展示",
                List.of("data:image/png;base64,aW1hZ2U="),
                "16:9",
                4,
                tempDir.resolve("text-video.mp4").toString()));

        assertTrue(error.getMessage().contains("文生视频"));
    }

    @Test
    void rejectsMissingReferenceImageForGrokImageVideoBeforeCallingUpstream() throws Exception {
        startServer();
        AppProperties properties = configuredProperties("grok-key", "jimeng-key");
        VideoModelCatalog catalog = new VideoModelCatalog(properties);
        OpenAiCompatibleVideoService service = service(properties, catalog, Duration.ofSeconds(2));

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> service.generateVideo(
                catalog.require("grok-imagine-video-1.5"),
                "产品旋转展示",
                List.of(),
                "16:9",
                4,
                tempDir.resolve("image-video.mp4").toString()));

        assertTrue(error.getMessage().contains("一张参考图"));
    }

    @Test
    void submitsJimengMultipartAndDownloadsCompletedContentWithAuthorization() throws Exception {
        byte[] expectedVideo = "jimeng-video".getBytes(StandardCharsets.UTF_8);
        AtomicReference<String> createBody = new AtomicReference<>();
        AtomicReference<String> createContentType = new AtomicReference<>();
        AtomicReference<String> contentAuthorization = new AtomicReference<>();
        startServer();
        server.createContext("/v1/videos", exchange -> {
            createContentType.set(exchange.getRequestHeaders().getFirst("Content-Type"));
            createBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            respondJson(exchange, "{\"id\":\"jimeng-task-1\",\"status\":\"queued\"}");
        });
        server.createContext("/v1/videos/jimeng-task-1", exchange ->
                respondJson(exchange, "{\"id\":\"jimeng-task-1\",\"status\":\"completed\"}"));
        server.createContext("/v1/videos/jimeng-task-1/content", exchange -> {
            contentAuthorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            respondBytes(exchange, expectedVideo);
        });

        AppProperties properties = configuredProperties("grok-key", "jimeng-key");
        VideoModelCatalog catalog = new VideoModelCatalog(properties);
        OpenAiCompatibleVideoService service = service(properties, catalog, Duration.ofSeconds(2));
        Path output = tempDir.resolve("jimeng.mp4");

        // 即梦只支持文生视频,不传图片
        service.generateVideo(
                catalog.require("video-ds-2.0"),
                "镜头缓慢推进",
                List.of(),  // 即梦不支持图片
                "9:16",
                6,
                output.toString());

        assertTrue(createContentType.get().startsWith("multipart/form-data; boundary="));
        assertMultipartField(createBody.get(), "model", "video-ds-2.0");
        assertMultipartField(createBody.get(), "prompt", "镜头缓慢推进");
        assertMultipartField(createBody.get(), "seconds", "6");
        assertMultipartField(createBody.get(), "aspect_ratio", "9:16");
        assertFalse(createBody.get().contains("input_reference"), "即梦不应包含图片字段");
        assertEquals("Bearer jimeng-key", contentAuthorization.get());
        assertArrayEquals(expectedVideo, Files.readAllBytes(output));
    }

    @Test
    void surfacesFailedProviderStatusWithoutSavingOutput() throws Exception {
        startServer();
        server.createContext("/v1/videos/generations", exchange ->
                respondJson(exchange, "{\"request_id\":\"failed-task\"}"));
        server.createContext("/v1/videos/failed-task", exchange ->
                respondJson(exchange, "{\"status\":\"failed\",\"error\":{\"message\":\"上游生成失败\"}}"));
        AppProperties properties = configuredProperties("grok-key", "jimeng-key");
        VideoModelCatalog catalog = new VideoModelCatalog(properties);
        OpenAiCompatibleVideoService service = service(properties, catalog, Duration.ofSeconds(2));
        Path output = tempDir.resolve("failed.mp4");

        IllegalStateException error = assertThrows(IllegalStateException.class, () -> service.generateVideo(
                catalog.require("grok-imagine-video"), "test", List.of(), "16:9", 4, output.toString()));

        assertTrue(error.getMessage().contains("上游生成失败"));
        assertFalse(Files.exists(output));
    }

    @Test
    void retriesRateLimitedStatusPollingInsteadOfFailingCreatedTask() throws Exception {
        byte[] expectedVideo = "rate-limit-recovered".getBytes(StandardCharsets.UTF_8);
        AtomicInteger pollCount = new AtomicInteger();
        startServer();
        server.createContext("/v1/videos/generations", exchange ->
                respondJson(exchange, "{\"request_id\":\"rate-limited-task\"}"));
        server.createContext("/v1/videos/rate-limited-task", exchange -> {
            if (pollCount.getAndIncrement() == 0) {
                exchange.getResponseHeaders().set("Retry-After", "0");
                respondJson(exchange, 429, "{\"error\":{\"message\":\"rate limited\"}}");
                return;
            }
            respondJson(exchange, "{\"status\":\"done\",\"video\":{\"url\":\""
                    + serverUrl("/recovered.mp4") + "\"}}");
        });
        server.createContext("/recovered.mp4", exchange -> respondBytes(exchange, expectedVideo));
        AppProperties properties = configuredProperties("grok-key", "jimeng-key");
        VideoModelCatalog catalog = new VideoModelCatalog(properties);
        OpenAiCompatibleVideoService service = service(properties, catalog, Duration.ofSeconds(2));
        Path output = tempDir.resolve("rate-limit-recovered.mp4");

        service.generateVideo(
                catalog.require("grok-imagine-video"), "test", List.of(), "16:9", 4, output.toString());

        assertEquals(2, pollCount.get());
        assertArrayEquals(expectedVideo, Files.readAllBytes(output));
    }

    @Test
    void retriesMediaDownloadWithoutSubmittingGenerationAgain() throws Exception {
        byte[] expectedVideo = "media-retry-recovered".getBytes(StandardCharsets.UTF_8);
        AtomicInteger submissionCount = new AtomicInteger();
        AtomicInteger mediaAttemptCount = new AtomicInteger();
        startServer();
        server.createContext("/v1/videos/generations", exchange -> {
            submissionCount.incrementAndGet();
            respondJson(exchange, "{\"video_url\":\"" + serverUrl("/retry-media.mp4") + "\"}");
        });
        server.createContext("/retry-media.mp4", exchange -> {
            if (mediaAttemptCount.incrementAndGet() < 3) {
                respondJson(exchange, 503, "{\"error\":\"temporary media failure\"}");
                return;
            }
            respondBytes(exchange, expectedVideo);
        });
        AppProperties properties = configuredProperties("grok-key", "jimeng-key");
        VideoModelCatalog catalog = new VideoModelCatalog(properties);
        OpenAiCompatibleVideoService service = service(properties, catalog, Duration.ofSeconds(2));
        Path output = tempDir.resolve("media-retry.mp4");

        service.generateVideo(
                catalog.require("grok-imagine-video"), "test", List.of(), "16:9", 4, output.toString());

        assertEquals(1, submissionCount.get());
        assertEquals(3, mediaAttemptCount.get());
        assertArrayEquals(expectedVideo, Files.readAllBytes(output));
    }

    @Test
    void usesMediaProxyOnlyForDownloadsWhileProviderApiStaysDirect() throws Exception {
        AtomicInteger submissionCount = new AtomicInteger();
        AtomicInteger mediaProxyRequestCount = new AtomicInteger();
        startServer();
        startMediaProxyServer(mediaProxyRequestCount);
        server.createContext("/v1/videos/generations", exchange -> {
            submissionCount.incrementAndGet();
            respondJson(exchange, "{\"video_url\":\"" + serverUrl("/proxied-media.mp4") + "\"}");
        });
        server.createContext("/proxied-media.mp4", exchange -> respondBytes(
                exchange, "must-not-download-directly".getBytes(StandardCharsets.UTF_8)));
        AppProperties properties = configuredProperties("grok-key", "jimeng-key");
        AppProperties.MediaProxy mediaProxy = properties.getSuiXiangVideo().getMediaProxy();
        mediaProxy.setEnabled(true);
        mediaProxy.setType("http");
        mediaProxy.setHost("127.0.0.1");
        mediaProxy.setPort(mediaProxyServer.getAddress().getPort());
        mediaProxy.setHosts(List.of("127.0.0.1"));
        VideoModelCatalog catalog = new VideoModelCatalog(properties);
        OpenAiCompatibleVideoService service = service(properties, catalog, Duration.ofSeconds(2));
        Path output = tempDir.resolve("media-proxy.mp4");

        assertThrows(Exception.class, () -> service.generateVideo(
                catalog.require("grok-imagine-video"), "test", List.of(), "16:9", 4, output.toString()));

        assertEquals(1, submissionCount.get());
        assertEquals(3, mediaProxyRequestCount.get());
        assertFalse(Files.exists(output));
    }

    @Test
    void cancellingTaskStopsMediaRetryAndRemovesPartialOutput() throws Exception {
        CountDownLatch firstMediaAttempt = new CountDownLatch(1);
        AtomicInteger mediaAttemptCount = new AtomicInteger();
        startServer();
        server.createContext("/v1/videos/generations", exchange ->
                respondJson(exchange, "{\"video_url\":\"" + serverUrl("/cancel-retry.mp4") + "\"}"));
        server.createContext("/cancel-retry.mp4", exchange -> {
            mediaAttemptCount.incrementAndGet();
            firstMediaAttempt.countDown();
            respondJson(exchange, 503, "{\"error\":\"retry later\"}");
        });
        AppProperties properties = configuredProperties("grok-key", "jimeng-key");
        VideoModelCatalog catalog = new VideoModelCatalog(properties);
        OpenAiCompatibleVideoService service = new OpenAiCompatibleVideoService(
                properties, catalog, 10L, Duration.ofSeconds(2), 10_000L);
        Path output = tempDir.resolve("cancel-media-retry.mp4");
        Path partial = output.resolveSibling(output.getFileName() + ".part");
        callerExecutor = Executors.newSingleThreadExecutor();

        Future<String> future = callerExecutor.submit(() -> GenerationCancellationContext.withTask(
                "video-cancel-task",
                () -> {
                    try {
                        return service.generateVideo(
                                catalog.require("grok-imagine-video"), "test", List.of(), "16:9", 4, output.toString());
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                }));

        assertTrue(firstMediaAttempt.await(2, TimeUnit.SECONDS));
        GenerationCancellationContext.cancelTask("video-cancel-task");
        assertTimeoutPreemptively(Duration.ofSeconds(2), () -> assertThrows(Exception.class, future::get));
        assertEquals(1, mediaAttemptCount.get());
        assertFalse(Files.exists(partial));
        assertFalse(Files.exists(output));
    }

    @Test
    void timesOutAProviderTaskThatNeverCompletes() throws Exception {
        startServer();
        server.createContext("/v1/videos/generations", exchange ->
                respondJson(exchange, "{\"request_id\":\"slow-task\"}"));
        server.createContext("/v1/videos/slow-task", exchange ->
                respondJson(exchange, "{\"status\":\"processing\"}"));
        AppProperties properties = configuredProperties("grok-key", "jimeng-key");
        VideoModelCatalog catalog = new VideoModelCatalog(properties);
        OpenAiCompatibleVideoService service = service(properties, catalog, Duration.ofMillis(80));

        IllegalStateException error = assertThrows(IllegalStateException.class, () -> service.generateVideo(
                catalog.require("grok-imagine-video"), "test", List.of(), "16:9", 4,
                tempDir.resolve("timeout.mp4").toString()));

        assertTrue(error.getMessage().contains("超时"));
    }

    @Test
    void cancellingTaskDisconnectsPendingCreateRequestAndRemovesPartialOutput() throws Exception {
        CountDownLatch requestReceived = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        startServer();
        server.createContext("/v1/videos/generations", exchange -> {
            requestReceived.countDown();
            try {
                release.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        });
        AppProperties properties = configuredProperties("grok-key", "jimeng-key");
        VideoModelCatalog catalog = new VideoModelCatalog(properties);
        OpenAiCompatibleVideoService service = service(properties, catalog, Duration.ofSeconds(2));
        Path output = tempDir.resolve("cancelled.mp4");
        callerExecutor = Executors.newSingleThreadExecutor();

        Future<String> future = callerExecutor.submit(() -> GenerationCancellationContext.withTask(
                "video-cancel-task",
                () -> {
                    try {
                        return service.generateVideo(catalog.require("grok-imagine-video"), "test", List.of(), "16:9", 4, output.toString());
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                }));

        assertTrue(requestReceived.await(2, TimeUnit.SECONDS));
        GenerationCancellationContext.cancelTask("video-cancel-task");
        assertTimeoutPreemptively(Duration.ofSeconds(2), () -> assertThrows(Exception.class, future::get));
        assertFalse(Files.exists(output));
        release.countDown();
    }

    @Test
    void rejectsMissingProviderKeyBeforeCallingUpstream() {
        AppProperties properties = new AppProperties();
        VideoModelCatalog catalog = new VideoModelCatalog(properties);
        OpenAiCompatibleVideoService service = new OpenAiCompatibleVideoService(properties, catalog);

        IllegalStateException error = assertThrows(IllegalStateException.class, () -> service.generateVideo(
                catalog.require("video-ds-2.0"), "test", List.of(), "16:9", 4,
                tempDir.resolve("missing.mp4").toString()));

        assertTrue(error.getMessage().contains("SUIXIANG_JIMENG_VIDEO_API_KEY"));
    }

    @Test
    void springSelectsTheProductionConstructorWhenTestConstructorAlsoExists() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.registerBean(AppProperties.class);
            context.register(VideoModelCatalog.class, OpenAiCompatibleVideoService.class);
            context.refresh();

            assertTrue(context.containsBean("openAiCompatibleVideoService"));
        }
    }

    private OpenAiCompatibleVideoService service(
            AppProperties properties,
            VideoModelCatalog catalog,
            Duration timeout) {
        return new OpenAiCompatibleVideoService(properties, catalog, 10L, timeout);
    }

    private AppProperties configuredProperties(String grokKey, String jimengKey) {
        AppProperties properties = new AppProperties();
        properties.getSuiXiangVideo().setBaseUrl(serverUrl("/v1"));
        properties.getSuiXiangVideo().getGrok().setApiKey(grokKey);
        properties.getSuiXiangVideo().getJimeng().setApiKey(jimengKey);
        return properties;
    }

    private void startServer() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        serverExecutor = Executors.newCachedThreadPool();
        server.setExecutor(serverExecutor);
        server.start();
    }

    private void startMediaProxyServer(AtomicInteger requestCount) throws Exception {
        mediaProxyServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        mediaProxyExecutor = Executors.newCachedThreadPool();
        mediaProxyServer.setExecutor(mediaProxyExecutor);
        mediaProxyServer.createContext("/", exchange -> {
            requestCount.incrementAndGet();
            respondJson(exchange, 502, "{\"error\":\"proxy rejected media request\"}");
        });
        mediaProxyServer.start();
    }

    private String serverUrl(String path) {
        return "http://127.0.0.1:" + server.getAddress().getPort() + path;
    }

    private void assertMultipartField(String body, String name, String value) {
        assertTrue(body.contains("name=\"" + name + "\""), "缺少 multipart 字段: " + name);
        assertTrue(body.contains("\r\n\r\n" + value + "\r\n"), "multipart 字段值不正确: " + name);
    }

    private void respondJson(HttpExchange exchange, String body) throws java.io.IOException {
        respondJson(exchange, 200, body);
    }

    private void respondJson(HttpExchange exchange, int status, String body) throws java.io.IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private void respondBytes(HttpExchange exchange, byte[] bytes) throws java.io.IOException {
        exchange.getResponseHeaders().set("Content-Type", "video/mp4");
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
