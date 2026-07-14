package com.elebusiness.service;

import com.elebusiness.config.AppProperties;
import com.elebusiness.model.GenerationTask;
import com.elebusiness.service.agent.GenerationProviderCostContext;
import com.elebusiness.service.billing.BillingService;
import com.elebusiness.service.workspace.UserStorageService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CountDownLatch;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.ArgumentMatchers.eq;

class TaskServiceIsolationTest {

    @TempDir
    Path tempDir;

    @AfterEach
    void tearDown() {
        GenerationProviderCostContext.clear();
    }

    @Test
    void taskLookupIsScopedByOwnerUserId() {
        TaskService taskService = new TaskService(new AppProperties());

        GenerationTask task = taskService.createTask(1001L, 1);

        assertTrue(taskService.getTask(1001L, task.getId()).isPresent());
        assertFalse(taskService.getTask(2002L, task.getId()).isPresent());
    }

    @Test
    void taskCancellationIsScopedByOwnerUserId() {
        TaskService taskService = new TaskService(new AppProperties());

        GenerationTask task = taskService.createTask(1001L, 1);

        assertFalse(taskService.cancel(2002L, task.getId()));
        assertTrue(taskService.cancel(1001L, task.getId()));
    }

    @Test
    void sameUserCannotCreateAnotherActiveGenerationTask() {
        TaskService taskService = new TaskService(new AppProperties());

        taskService.createTask(1001L, 1);

        assertThrows(IllegalStateException.class, () -> taskService.createTask(1001L, 1));
        assertDoesNotThrow(() -> taskService.createTask(2002L, 1));
    }

    @Test
    void cancellingPendingTaskReleasesUserSlotAndCancelsUsageWithoutCharge() {
        BillingService billingService = mock(BillingService.class);
        TaskService taskService = new TaskService(new AppProperties(), billingService);
        GenerationTask task = taskService.createTask(1001L, 1);
        task.setUsageLogId(88L);

        assertTrue(taskService.cancel(1001L, task.getId()));
        assertEquals("stopped", task.getStatus());
        assertTrue(taskService.cancel(1001L, task.getId()), "重复停止应保持幂等");
        verify(billingService).markGenerationCancelled(88L);
        assertDoesNotThrow(() -> taskService.createTask(1001L, 1));
    }

    @Test
    void cancellingRunningTaskMarksUsageCancelledInsteadOfCharging() throws Exception {
        BillingService billingService = mock(BillingService.class);
        TaskService taskService = new TaskService(new AppProperties(), billingService);
        GenerationTask task = taskService.createTask(1001L, 1);
        task.setUsageLogId(99L);
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);

        taskService.submit(task, () -> {
            started.countDown();
            try {
                release.await(3, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        assertTrue(started.await(3, TimeUnit.SECONDS));

        assertTrue(taskService.cancel(1001L, task.getId()));
        assertEquals("stopping", task.getStatus());
        release.countDown();

        verify(billingService, timeout(3000)).markGenerationCancelled(99L);
        verify(billingService, never()).markGenerationSucceeded(
                eq(99L), org.mockito.ArgumentMatchers.any(BillingService.ProviderCost.class));
    }

    @Test
    void successfulTaskPassesProviderTaskIdsIntoBillingProviderCost() {
        BillingService billingService = mock(BillingService.class);
        TaskService taskService = new TaskService(new AppProperties(), billingService);
        GenerationTask task = taskService.createTask(1001L, 1);
        task.setUsageLogId(77L);

        taskService.submit(task, () ->
                GenerationProviderCostContext.recordProviderTaskId("liblib", "liblib-generate-001"));

        ArgumentCaptor<BillingService.ProviderCost> costCaptor = ArgumentCaptor.forClass(BillingService.ProviderCost.class);
        verify(billingService, timeout(3000)).markGenerationSucceeded(eq(77L), costCaptor.capture());
        assertEquals("liblib-generate-001", costCaptor.getValue().providerTaskId());
        assertEquals("PROVIDER_TASK_REPORTED", costCaptor.getValue().costSource());
    }

    @Test
    void expiredTempOutputCleanupScansEveryUsersWorkspace() throws Exception {
        AppProperties props = new AppProperties();
        props.getPaths().setUserDataDir(tempDir.toString());
        UserStorageService storageService = new UserStorageService(props);

        Path oldBatch = storageService.tempOutputRoot(1001L).resolve("custom").resolve("old_batch");
        Files.createDirectories(oldBatch);
        Files.writeString(oldBatch.resolve("1.jpg"), "old");
        long oldTime = System.currentTimeMillis() - TimeUnit.HOURS.toMillis(3);
        Files.setLastModifiedTime(oldBatch.resolve("1.jpg"), FileTime.fromMillis(oldTime));
        Files.setLastModifiedTime(oldBatch, FileTime.fromMillis(oldTime));

        Path freshBatch = storageService.tempOutputRoot(2002L).resolve("custom").resolve("fresh_batch");
        Files.createDirectories(freshBatch);
        Files.writeString(freshBatch.resolve("1.jpg"), "fresh");

        TaskService taskService = new TaskService(props);
        Method cleanup = TaskService.class.getDeclaredMethod("evictExpiredTempOutput");
        cleanup.setAccessible(true);
        cleanup.invoke(taskService);

        assertFalse(Files.exists(oldBatch), "过期批次必须从对应用户的临时目录清掉");
        assertTrue(Files.exists(freshBatch), "未过期批次不能被误删");
    }
}
