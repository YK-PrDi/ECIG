package com.elebusiness.service.agent;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LiblibOpenApiSignerTest {

    @Test
    void createsUrlSafeHmacSha1SignatureWithoutPadding() {
        String signature = LiblibOpenApiSigner.sign(
                "/api/genImg",
                "1725458584000",
                "random1232",
                "test-secret-key"
        );

        assertEquals("5fawu4cx5w2guq0fa26htrO4xmE", signature);
        assertFalse(signature.contains("="));
    }

    @Test
    void appendsRequiredAuthQueryParameters() {
        String signedUrl = LiblibOpenApiSigner.signedUrl(
                "https://openapi.liblibai.cloud",
                "/api/generate/webui/status",
                "access-key",
                "secret-key",
                "1725458584000",
                "random1232"
        );

        assertTrue(signedUrl.startsWith("https://openapi.liblibai.cloud/api/generate/webui/status?"));
        assertTrue(signedUrl.contains("AccessKey=access-key"));
        assertTrue(signedUrl.contains("Timestamp=1725458584000"));
        assertTrue(signedUrl.contains("SignatureNonce=random1232"));
        assertTrue(signedUrl.contains("Signature="));
    }
}
