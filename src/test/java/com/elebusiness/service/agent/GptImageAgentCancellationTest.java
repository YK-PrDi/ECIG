package com.elebusiness.service.agent;

import com.elebusiness.config.AppProperties;
import com.elebusiness.service.provider.UserProviderCredentialService;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class GptImageAgentCancellationTest {

    private static final String TASK_ID = "task-cancel";

    private HttpServer server;
    private ExecutorService serverExecutor;
    private ExecutorService callerExecutor;
    private final CountDownLatch releaseResponse = new CountDownLatch(1);

    @TempDir
    Path tempDir;

    @AfterEach
    void tearDown() {
        releaseResponse.countDown();
        GenerationCancellationContext.clearTask(TASK_ID);
        if (server != null) server.stop(0);
        if (serverExecutor != null) serverExecutor.shutdownNow();
        if (callerExecutor != null) callerExecutor.shutdownNow();
    }

    @Test
    void cancellingTaskDisconnectsPendingGenerationRequest() throws Exception {
        CountDownLatch requestReceived = new CountDownLatch(1);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/images/generations", exchange -> {
            requestReceived.countDown();
            try {
                releaseResponse.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        });
        serverExecutor = Executors.newSingleThreadExecutor();
        server.setExecutor(serverExecutor);
        server.start();

        AppProperties properties = new AppProperties();
        properties.getGptImage().setApiKeys(List.of("test-key"));
        properties.getGptImage().setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
        GptImageAgent agent = new GptImageAgent(properties, mock(UserProviderCredentialService.class));

        callerExecutor = Executors.newSingleThreadExecutor();
        Path outputPath = tempDir.resolve("result.jpg");
        Future<Boolean> result = callerExecutor.submit(() -> GenerationCancellationContext.withTask(
                TASK_ID,
                () -> agent.generateMulti("test", List.of(), null,
                        outputPath.toString(), "1:1")
        ));

        assertTrue(requestReceived.await(2, TimeUnit.SECONDS));
        GenerationCancellationContext.cancelTask(TASK_ID);

        assertTimeoutPreemptively(Duration.ofSeconds(2), () -> assertFalse(result.get()));
        assertFalse(Files.exists(outputPath));
    }
}
