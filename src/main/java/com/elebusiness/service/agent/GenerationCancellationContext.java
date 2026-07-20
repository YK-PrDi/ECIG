package com.elebusiness.service.agent;

import java.util.Set;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

public final class GenerationCancellationContext {

    private static final ThreadLocal<String> CURRENT_TASK_ID = new ThreadLocal<>();
    private static final ConcurrentHashMap<String, Set<Operation>> OPERATIONS = new ConcurrentHashMap<>();
    private static final Set<String> CANCELLED_TASKS = ConcurrentHashMap.newKeySet();
    private static final Registration NOOP_REGISTRATION = () -> { };

    private GenerationCancellationContext() {
    }

    public static <T> T withTask(String taskId, Supplier<T> supplier) {
        String previous = CURRENT_TASK_ID.get();
        if (taskId == null || taskId.isBlank()) {
            CURRENT_TASK_ID.remove();
        } else {
            CURRENT_TASK_ID.set(taskId);
        }
        try {
            return supplier.get();
        } finally {
            if (previous == null) {
                CURRENT_TASK_ID.remove();
            } else {
                CURRENT_TASK_ID.set(previous);
            }
        }
    }

    public static void withTask(String taskId, Runnable runnable) {
        withTask(taskId, () -> {
            runnable.run();
            return null;
        });
    }

    public static Registration register(Runnable cancelAction) {
        String taskId = CURRENT_TASK_ID.get();
        if (taskId == null || taskId.isBlank() || cancelAction == null) {
            return NOOP_REGISTRATION;
        }
        Operation operation = new Operation(taskId, cancelAction);
        OPERATIONS.computeIfAbsent(taskId, ignored -> ConcurrentHashMap.newKeySet()).add(operation);
        if (CANCELLED_TASKS.contains(taskId)) {
            operation.cancel();
        }
        return operation;
    }

    public static boolean cancelTask(String taskId) {
        if (taskId == null || taskId.isBlank()) return false;
        CANCELLED_TASKS.add(taskId);
        Set<Operation> operations = OPERATIONS.get(taskId);
        if (operations != null) {
            operations.forEach(Operation::cancel);
        }
        return true;
    }

    public static boolean isCancellationRequested() {
        String taskId = CURRENT_TASK_ID.get();
        return taskId != null && CANCELLED_TASKS.contains(taskId);
    }

    public static Optional<String> currentTaskId() {
        return Optional.ofNullable(CURRENT_TASK_ID.get());
    }

    public static void clearTask(String taskId) {
        if (taskId == null || taskId.isBlank()) return;
        Set<Operation> operations = OPERATIONS.remove(taskId);
        if (operations != null) {
            operations.forEach(Operation::close);
        }
        CANCELLED_TASKS.remove(taskId);
    }

    public interface Registration extends AutoCloseable {
        @Override
        void close();
    }

    private static final class Operation implements Registration {
        private final String taskId;
        private final Runnable cancelAction;
        private final AtomicBoolean cancelled = new AtomicBoolean(false);
        private final AtomicBoolean closed = new AtomicBoolean(false);

        private Operation(String taskId, Runnable cancelAction) {
            this.taskId = taskId;
            this.cancelAction = cancelAction;
        }

        private void cancel() {
            if (closed.get() || !cancelled.compareAndSet(false, true)) return;
            try {
                cancelAction.run();
            } catch (RuntimeException ignored) {
            }
        }

        @Override
        public void close() {
            if (!closed.compareAndSet(false, true)) return;
            Set<Operation> operations = OPERATIONS.get(taskId);
            if (operations != null) {
                operations.remove(this);
                if (operations.isEmpty()) {
                    OPERATIONS.remove(taskId, operations);
                }
            }
        }
    }
}
