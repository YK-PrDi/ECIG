package com.elebusiness.service;

import com.elebusiness.config.AppProperties;
import com.elebusiness.service.agent.GenerationInvocationContext;
import com.elebusiness.service.agent.ImageGeneratorAgent;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class ImageGenerationServiceUserContextTest {

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
