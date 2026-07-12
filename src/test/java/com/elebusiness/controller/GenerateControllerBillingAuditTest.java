package com.elebusiness.controller;

import com.elebusiness.config.AppProperties;
import com.elebusiness.model.entity.GenerationUsageLog;
import com.elebusiness.service.auth.AuthService;
import com.elebusiness.service.auth.CurrentUserService;
import com.elebusiness.service.CosService;
import com.elebusiness.service.agent.GenerationInvocationContext;
import com.elebusiness.service.agent.GptImageAgent;
import com.elebusiness.service.billing.BillingService;
import com.elebusiness.service.billing.GenerationPricingService;
import com.elebusiness.service.provider.UserProviderCredentialService;
import com.elebusiness.service.workspace.UserStorageService;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockMultipartFile;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GenerateControllerBillingAuditTest {

    @Test
    void directGptImageGenerationWritesUsageAuditForCurrentUser() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/images/generations", exchange -> {
            byte[] body = "{\"data\":[{\"url\":\"http://example.test/1.png\"}]}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            AppProperties props = new AppProperties();
            props.getGptImage().setApiKeys(new ArrayList<>(List.of("test-key")));
            props.getGptImage().setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
            props.getBilling().setChargeEnabled(true);
            props.getBilling().setDefaultImagePoints(8);
            GenerationPricingService pricingService = new GenerationPricingService(props);
            UserProviderCredentialService credentialService = mock(UserProviderCredentialService.class);
            when(credentialService.resolveCredential(1001L, "gpt-image", "default")).thenReturn(Optional.empty());
            GptImageAgent gptImageAgent = new GptImageAgent(props, credentialService);

            CurrentUserService currentUserService = new CurrentUserService();
            MockHttpSession session = new MockHttpSession();
            currentUserService.bind(session, new AuthService.AuthUser(1001L, "alice", "Alice", "USER"));

            BillingService billingService = mock(BillingService.class);
            GenerationUsageLog usageLog = new GenerationUsageLog();
            usageLog.setId(77L);
            when(billingService.recordGenerationStarted(
                    1001L, "", "direct-gpt-image", "gpt-image-2", 8)).thenReturn(usageLog);

            GenerateController controller = new GenerateController(
                    null, null, props, null, gptImageAgent, null, null, null,
                    List.of(), currentUserService, new UserStorageService(props), billingService, pricingService);

            ResponseEntity<Map<String, Object>> response = controller.gptImageGenerate(
                    Map.<String, Object>of("prompt", "A product photo"), session);

            assertEquals(200, response.getStatusCode().value());
            assertNotNull(response.getBody());
            assertEquals(true, response.getBody().get("success"));
            verify(billingService).recordGenerationStarted(
                    1001L, "", "direct-gpt-image", "gpt-image-2", 8);
            verify(billingService).markGenerationSucceeded(77L, 0);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void directGptImageGenerationUsesCurrentUsersCredentialWhenConfigured() throws Exception {
        AtomicReference<String> authorization = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/images/generations", exchange -> {
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            byte[] body = "{\"data\":[{\"url\":\"http://example.test/1.png\"}]}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            AppProperties props = new AppProperties();
            props.getGptImage().setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
            props.getBilling().setChargeEnabled(true);
            props.getBilling().setDefaultImagePoints(8);
            GenerationPricingService pricingService = new GenerationPricingService(props);
            UserProviderCredentialService credentialService = mock(UserProviderCredentialService.class);
            when(credentialService.resolveCredential(1001L, "gpt-image", "default"))
                    .thenReturn(Optional.of(new UserProviderCredentialService.ResolvedCredential(
                            1001L,
                            "gpt-image",
                            "default",
                            Map.of("apiKey", "user-direct-key", "baseUrl",
                                    "http://127.0.0.1:" + server.getAddress().getPort())
                    )));

            CurrentUserService currentUserService = new CurrentUserService();
            MockHttpSession session = new MockHttpSession();
            currentUserService.bind(session, new AuthService.AuthUser(1001L, "alice", "Alice", "USER"));

            BillingService billingService = mock(BillingService.class);
            GenerationUsageLog usageLog = new GenerationUsageLog();
            usageLog.setId(78L);
            when(billingService.recordGenerationStarted(
                    1001L, "", "direct-gpt-image", "gpt-image-2", 8)).thenReturn(usageLog);

            GptImageAgent gptImageAgent = new GptImageAgent(props, credentialService);
            GenerateController controller = new GenerateController(
                    null, null, props, null, gptImageAgent, null, null, null,
                    List.of(), currentUserService, new UserStorageService(props), billingService, pricingService);

            ResponseEntity<Map<String, Object>> response = controller.gptImageGenerate(
                    Map.<String, Object>of("prompt", "A product photo"), session);

            assertEquals(200, response.getStatusCode().value());
            assertEquals(true, response.getBody().get("success"));
            assertEquals("Bearer user-direct-key", authorization.get());
            verify(billingService).markGenerationSucceeded(78L, 0);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void inpaintPassesCurrentUserContextToGptImageAgent() throws Exception {
        AppProperties props = new AppProperties();
        props.getPaths().setUserDataDir(Files.createTempDirectory("inpaint-user-data").toString());
        CurrentUserService currentUserService = new CurrentUserService();
        MockHttpSession session = new MockHttpSession();
        currentUserService.bind(session, new AuthService.AuthUser(1001L, "alice", "Alice", "USER"));
        AtomicReference<Long> capturedUserId = new AtomicReference<>(0L);
        GptImageAgent gptImageAgent = new GptImageAgent(props, mock(UserProviderCredentialService.class)) {
            @Override
            public boolean generateWithMask(String prompt, File imageFile, File maskFile, String outputPath, String aspect) {
                capturedUserId.set(GenerationInvocationContext.currentUserId().orElse(0L));
                return true;
            }
        };
        CosService cosService = mock(CosService.class);
        MockMultipartFile image = new MockMultipartFile("image", "image.png", "image/png", pngBytes(4, 3));
        MockMultipartFile mask = new MockMultipartFile("mask", "mask.png", "image/png", pngBytes(4, 3));
        GenerateController controller = new GenerateController(
                null, null, props, null, gptImageAgent, null, cosService, null,
                List.of(), currentUserService, new UserStorageService(props),
                mock(BillingService.class), new GenerationPricingService(props));

        ResponseEntity<Map<String, Object>> response = controller.inpaint(image, mask, "remove background", "s1", session);

        assertEquals(200, response.getStatusCode().value());
        assertEquals(1001L, capturedUserId.get());
    }

    private byte[] pngBytes(int width, int height) throws Exception {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return out.toByteArray();
    }
}
