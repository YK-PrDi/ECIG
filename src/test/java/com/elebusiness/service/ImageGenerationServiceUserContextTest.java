package com.elebusiness.service;

import com.elebusiness.config.AppProperties;
import com.elebusiness.service.agent.GenerationInvocationContext;
import com.elebusiness.service.agent.ImageGeneratorAgent;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ImageGenerationServiceUserContextTest {

    @TempDir
    Path tempDir;

    @Test
    void userScopedGenerateMultiExposesUserIdOnlyDuringAgentCall() {
        CapturingAgent agent = new CapturingAgent();
        ImageGenerationService service = new ImageGenerationService(
                new AppProperties(),
                List.of(agent),
                mock(PromptTemplateLoader.class)
        );

        boolean ok = service.generateImageMulti(
                1001L,
                "prompt",
                List.of("ref.png"),
                null,
                "out.png",
                "capture",
                "3:4"
        );

        assertTrue(ok);
        assertEquals(1001L, agent.capturedUserId);
        assertTrue(GenerationInvocationContext.currentUserId().isEmpty());
    }

    @Test
    void customAnalysisUsesOneReferenceAndOneUnitPerTargetBeforeIntegrating() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        List<String> unitRequests = new CopyOnWriteArrayList<>();
        List<String> integrationRequests = new CopyOnWriteArrayList<>();
        List<Integer> unitImageCounts = new CopyOnWriteArrayList<>();
        List<Integer> integrationImageCounts = new CopyOnWriteArrayList<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            JsonNode content = mapper.readTree(body).path("messages").path(1).path("content");
            String requestText = content.path(0).path("text").asText();
            int imageCount = 0;
            for (JsonNode part : content) {
                if ("image_url".equals(part.path("type").asText())) imageCount += 1;
            }
            String reply;
            if (imageCount > 0) {
                unitRequests.add(requestText);
                unitImageCounts.add(imageCount);
                reply = "基于这一张参考图提取指定单元的可见结构、位置、材质和限制。";
            } else {
                integrationRequests.add(requestText);
                integrationImageCounts.add(imageCount);
                String target = requestText.replaceAll("(?s).*【本张图编号】第\\s*(\\d+)/\\d+.*", "$1");
                reply = "【第 " + target + " 张方案】\n【本图卖点】单元整合\n【产品一致性】保持参考图结构\n【场景构图】商业棚拍";
            }
            ObjectNode responseRoot = mapper.createObjectNode();
            responseRoot.putArray("choices").addObject().putObject("message").put("content", reply);
            byte[] response = responseRoot.toString().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();

        AppProperties properties = new AppProperties();
        properties.getGemini().setApiKey("test-key");
        properties.getGemini().setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
        properties.getApi().setMaxConcurrent(4);
        PromptTemplateLoader loader = mock(PromptTemplateLoader.class);
        when(loader.load(anyString(), anyString())).thenAnswer(invocation -> invocation.getArgument(1));
        ImageGenerationService service = new ImageGenerationService(properties, List.of(), loader);

        List<File> references = new ArrayList<>();
        for (int index = 0; index < 3; index++) {
            Path image = tempDir.resolve("reference-" + index + ".png");
            Files.write(image, new byte[]{1, 2, 3});
            references.add(image.toFile());
        }
        try {
            String result = service.analyzeCustomImagePrompts("突出产品功能", references, 3, true);

            assertEquals(9, unitRequests.size());
            assertEquals(3, integrationRequests.size());
            assertTrue(unitImageCounts.stream().allMatch(imageCount -> imageCount == 1));
            assertTrue(integrationImageCounts.stream().allMatch(imageCount -> imageCount == 0));
            assertTrue(unitRequests.stream().allMatch(text -> text.contains("【本次唯一分析单元】")));
            assertTrue(unitRequests.stream().allMatch(text -> text.contains("100-180")));
            assertTrue(result.startsWith("【总分析】"));
            assertTrue(result.contains("【第 1 张方案】"));
            assertTrue(result.contains("【第 2 张方案】"));
            assertTrue(result.contains("【第 3 张方案】"));
        } finally {
            service.getExecutor().shutdownNow();
            server.stop(0);
        }
    }

    private static class CapturingAgent implements ImageGeneratorAgent {
        private long capturedUserId = 0L;

        @Override
        public String getId() {
            return "capture";
        }

        @Override
        public String getDisplayName() {
            return "Capture";
        }

        @Override
        public boolean generate(String prompt, String refImagePath, String whiteBgPath, String outputPath) {
            return true;
        }

        @Override
        public boolean generateMulti(String prompt, List<String> refImagePaths,
                                     String whiteBgPath, String outputPath, String aspect) {
            capturedUserId = GenerationInvocationContext.currentUserId().orElse(0L);
            return true;
        }
    }
}
