package com.elebusiness.service.agent;

import java.util.Optional;
import java.util.function.Supplier;

public final class GenerationInvocationContext {

    private static final ThreadLocal<Long> CURRENT_USER_ID = new ThreadLocal<>();

    private GenerationInvocationContext() {
    }

    public static Optional<Long> currentUserId() {
        Long userId = CURRENT_USER_ID.get();
        return userId == null || userId <= 0 ? Optional.empty() : Optional.of(userId);
    }

    public static <T> T withUserId(long userId, Supplier<T> supplier) {
        Long previous = CURRENT_USER_ID.get();
        if (userId > 0) {
            CURRENT_USER_ID.set(userId);
        } else {
            CURRENT_USER_ID.remove();
        }
        try {
            return supplier.get();
        } finally {
            if (previous == null) {
                CURRENT_USER_ID.remove();
            } else {
                CURRENT_USER_ID.set(previous);
            }
        }
    }
}
