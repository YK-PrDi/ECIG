package com.elebusiness.service.agent;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GenerationInvocationContextTest {

    @Test
    void scopedUserIdIsAvailableOnlyInsideInvocation() {
        assertTrue(GenerationInvocationContext.currentUserId().isEmpty());

        String result = GenerationInvocationContext.withUserId(1001L, () -> {
            assertEquals(1001L, GenerationInvocationContext.currentUserId().orElseThrow());
            return "ok";
        });

        assertEquals("ok", result);
        assertTrue(GenerationInvocationContext.currentUserId().isEmpty());
    }
}
