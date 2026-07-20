package com.elebusiness.config;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProductionConfigSecretSafetyTest {

    private static final Pattern LIKELY_SECRET = Pattern.compile(
            "(?i)(sk-[a-z0-9_-]{12,}|AKID[a-z0-9]{12,}|AIza[a-z0-9_-]{20,})");

    @Test
    void productionConfigUsesEnvironmentVariablesInsteadOfEmbeddedSecrets() throws Exception {
        ClassPathResource resource = new ClassPathResource("application-prod.yml");
        String yaml = new String(resource.getInputStream().readAllBytes(), StandardCharsets.UTF_8);

        assertFalse(LIKELY_SECRET.matcher(yaml).find(), "生产配置不能内嵌真实密钥");
        assertTrue(yaml.contains("${GEMINI_API_KEY:}"));
        assertTrue(yaml.contains("${GPT_IMAGE_KEY_1:}"));
        assertTrue(yaml.contains("${LIBLIB_ACCESS_KEY:}"));
        assertTrue(yaml.contains("${LIBLIB_SECRET_KEY:}"));
        assertTrue(yaml.contains("${COS_SECRET_ID:}"));
        assertTrue(yaml.contains("${COS_SECRET_KEY:}"));
        assertTrue(yaml.contains("${SUIXIANG_GROK_VIDEO_API_KEY:}"));
        assertTrue(yaml.contains("${SUIXIANG_JIMENG_VIDEO_API_KEY:}"));
    }

    @Test
    void localConfigCanResolveIgnoredLocalVideoCredentials() throws Exception {
        ClassPathResource resource = new ClassPathResource("application.yml");
        String yaml = new String(resource.getInputStream().readAllBytes(), StandardCharsets.UTF_8);

        assertTrue(yaml.contains("${SUIXIANG_GROK_VIDEO_API_KEY:}"));
        assertTrue(yaml.contains("${SUIXIANG_JIMENG_VIDEO_API_KEY:}"));
    }
}
