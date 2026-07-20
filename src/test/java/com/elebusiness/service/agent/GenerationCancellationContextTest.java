package com.elebusiness.service.agent;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GenerationCancellationContextTest {

    @AfterEach
    void tearDown() {
        GenerationCancellationContext.clearTask("task-1");
        GenerationCancellationContext.clearTask("task-2");
    }

    @Test
    void cancellingTaskInvokesOnlyItsRegisteredOperationsOnce() {
        AtomicInteger firstCancelled = new AtomicInteger();
        AtomicInteger secondCancelled = new AtomicInteger();

        GenerationCancellationContext.withTask("task-1", () -> {
            GenerationCancellationContext.register(firstCancelled::incrementAndGet);
            return null;
        });
        GenerationCancellationContext.withTask("task-2", () -> {
            GenerationCancellationContext.register(secondCancelled::incrementAndGet);
            return null;
        });

        assertTrue(GenerationCancellationContext.cancelTask("task-1"));
        assertTrue(GenerationCancellationContext.cancelTask("task-1"));
        assertEquals(1, firstCancelled.get());
        assertEquals(0, secondCancelled.get());
    }

    @Test
    void operationRegisteredAfterCancellationIsCancelledImmediately() {
        AtomicInteger cancelled = new AtomicInteger();
        GenerationCancellationContext.cancelTask("task-1");

        GenerationCancellationContext.withTask("task-1", () -> {
            GenerationCancellationContext.register(cancelled::incrementAndGet);
            return null;
        });

        assertEquals(1, cancelled.get());
    }

    @Test
    void currentTaskIdIsScopedAndRestored() {
        assertTrue(GenerationCancellationContext.currentTaskId().isEmpty());

        GenerationCancellationContext.withTask("task-1", () -> {
            assertEquals("task-1", GenerationCancellationContext.currentTaskId().orElseThrow());
            GenerationCancellationContext.withTask("task-2", () ->
                    assertEquals("task-2", GenerationCancellationContext.currentTaskId().orElseThrow()));
            assertEquals("task-1", GenerationCancellationContext.currentTaskId().orElseThrow());
        });

        assertTrue(GenerationCancellationContext.currentTaskId().isEmpty());
    }
}
