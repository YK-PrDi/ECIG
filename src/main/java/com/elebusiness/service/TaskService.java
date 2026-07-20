package com.elebusiness.service;

import com.elebusiness.config.AppProperties;
import com.elebusiness.model.GenerationTask;
import com.elebusiness.service.agent.GenerationCancellationContext;
import com.elebusiness.service.agent.GenerationProviderCostContext;
import com.elebusiness.service.billing.BillingService;
import com.elebusiness.service.workspace.UserStorageService;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Autowired;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@Service
public class TaskService {

    private static final Logger log = LoggerFactory.getLogger(TaskService.class);

    // 任务完成后的保留时长：前端轮询拉完最终状态需要时间，60 分钟足以覆盖
    // 之后清理掉，避免 tasks Map 无限累积导致 OOM（B 阶段审查发现）
    private static final long DONE_TASK_TTL_MS = 60 * 60 * 1000L;

    // Phase 1：临时归档目录子文件夹的保留时长 = 2 小时，过期自动删
    // 用户在前端点 💾 才 copy 到永久 outputDir；不点就当成"看完即扔"
    private static final long TEMP_OUTPUT_TTL_MS = 2 * 60 * 60 * 1000L;

    private final AppProperties appProperties;
    private final BillingService billingService;
    private final UserStorageService userStorageService;

    // 增加任务线程池大小以支持更多并发用户
    private final ExecutorService executor = Executors.newFixedThreadPool(20);
    private final Map<String, GenerationTask> tasks = new ConcurrentHashMap<>();
    private final Map<Long, Set<String>> activeTaskIdsByUser = new ConcurrentHashMap<>();
    private final Map<String, Thread> runningThreads = new ConcurrentHashMap<>();
    // 完成时间戳：仅 done/error/stopped 状态的任务才进入这个 map，按 TTL 过期
    private final Map<String, Long> completedAt = new ConcurrentHashMap<>();
    private final ScheduledExecutorService cleaner =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "task-cleaner");
                t.setDaemon(true);
                return t;
            });

    public TaskService(AppProperties appProperties) {
        this(appProperties, null, new UserStorageService(appProperties));
    }

    public TaskService(AppProperties appProperties, BillingService billingService) {
        this(appProperties, billingService, new UserStorageService(appProperties));
    }

    @Autowired
    public TaskService(AppProperties appProperties, BillingService billingService, UserStorageService userStorageService) {
        this.appProperties = appProperties;
        this.billingService = billingService;
        this.userStorageService = userStorageService;
    }

    @PostConstruct
    void start() {
        // 每 5 分钟扫一次过期任务
        cleaner.scheduleAtFixedRate(this::evictExpired, 5, 5, TimeUnit.MINUTES);
        // 每 30 分钟扫一次临时归档目录，删超过 2h 的子文件夹
        cleaner.scheduleAtFixedRate(this::evictExpiredTempOutput, 1, 30, TimeUnit.MINUTES);
    }

    public GenerationTask createTask(int total) {
        return createTask(0L, total);
    }

    public GenerationTask createTask(long ownerUserId, int total) {
        return createTaskWithLimit(ownerUserId, total, 1);
    }

    public GenerationTask createTask(long ownerUserId, int total, String role) {
        int limit = "ADMIN".equalsIgnoreCase(role)
                ? Math.max(1, appProperties.getApi().getAdminMaxConcurrentTasks())
                : Math.max(1, appProperties.getApi().getUserMaxConcurrentTasks());
        return createTaskWithLimit(ownerUserId, total, limit);
    }

    private GenerationTask createTaskWithLimit(long ownerUserId, int total, int limit) {
        String id = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        GenerationTask task = new GenerationTask(id, ownerUserId, total);
        if (ownerUserId > 0) {
            activeTaskIdsByUser.compute(ownerUserId, (userId, activeTaskIds) -> {
                Set<String> ids = activeTaskIds == null ? ConcurrentHashMap.newKeySet() : activeTaskIds;
                if (ids.size() >= limit) {
                    throw new ActiveGenerationTaskException(limit);
                }
                ids.add(id);
                return ids;
            });
        }
        tasks.put(id, task);
        return task;
    }

    public void submit(GenerationTask task, Runnable work) {
        executor.submit(() -> {
            runningThreads.put(task.getId(), Thread.currentThread());
            synchronized (task) {
                if (task.isCancelled()) {
                    finishCancelledLocked(task);
                    return;
                }
                task.setStatus("running");
            }
            GenerationProviderCostContext.clear();
            try {
                GenerationCancellationContext.withTask(task.getId(), work);
            } catch (Exception e) {
                finishFailed(task, e);
                return;
            } finally {
                runningThreads.remove(task.getId(), Thread.currentThread());
            }
            finishSucceeded(task);
        });
    }

    public Optional<GenerationTask> getTask(String taskId) {
        return Optional.ofNullable(tasks.get(taskId));
    }

    public Optional<GenerationTask> getTask(long ownerUserId, String taskId) {
        return Optional.ofNullable(tasks.get(taskId))
                .filter(task -> task.getOwnerUserId() == ownerUserId);
    }

    public boolean cancel(String taskId) {
        GenerationTask task = tasks.get(taskId);
        if (task == null) return false;
        return cancelTask(task);
    }

    public boolean cancel(long ownerUserId, String taskId) {
        GenerationTask task = tasks.get(taskId);
        if (task == null || task.getOwnerUserId() != ownerUserId) return false;
        return cancelTask(task);
    }

    private boolean cancelTask(GenerationTask task) {
        boolean interruptRunningTask = false;
        synchronized (task) {
            if ("stopped".equals(task.getStatus())) return true;
            if (isTerminal(task.getStatus())) return false;
            boolean newlyCancelled = task.requestCancel();
            if (!newlyCancelled) return true;
            if ("pending".equals(task.getStatus())) {
                finishCancelledLocked(task);
            } else if ("running".equals(task.getStatus())) {
                task.setStatus("stopping");
                interruptRunningTask = true;
            }
        }
        if (interruptRunningTask) {
            GenerationCancellationContext.cancelTask(task.getId());
            Thread runningThread = runningThreads.get(task.getId());
            if (runningThread != null) runningThread.interrupt();
        }
        return true;
    }

    private void finishCancelled(GenerationTask task) {
        synchronized (task) {
            finishCancelledLocked(task);
        }
    }

    private void finishCancelledLocked(GenerationTask task) {
        if (isTerminal(task.getStatus())) return;
        task.setStatus("stopped");
        markUsageCancelled(task);
        GenerationProviderCostContext.clear();
        completedAt.put(task.getId(), System.currentTimeMillis());
        cleanupTaskExecution(task);
        releaseUserSlot(task);
    }

    private void finishSucceeded(GenerationTask task) {
        synchronized (task) {
            if (task.isCancelled()) {
                finishCancelledLocked(task);
                return;
            }
            task.setStatus("done");
            try {
                markUsageSucceeded(task);
            } finally {
                GenerationProviderCostContext.clear();
            }
            completedAt.put(task.getId(), System.currentTimeMillis());
            cleanupTaskExecution(task);
            releaseUserSlot(task);
        }
    }

    private void finishFailed(GenerationTask task, Exception exception) {
        synchronized (task) {
            if (task.isCancelled()) {
                finishCancelledLocked(task);
                return;
            }
            log.error("任务 {} 执行异常: {}", task.getId(), exception.getMessage(), exception);
            task.setStatus("error");
            markUsageFailed(task, exception.getMessage());
            GenerationProviderCostContext.clear();
            completedAt.put(task.getId(), System.currentTimeMillis());
            cleanupTaskExecution(task);
            releaseUserSlot(task);
        }
    }

    private void cleanupTaskExecution(GenerationTask task) {
        runningThreads.remove(task.getId());
        if (!task.isCancelled()) {
            GenerationCancellationContext.clearTask(task.getId());
        }
    }

    private boolean isTerminal(String status) {
        return "done".equals(status) || "stopped".equals(status) || "error".equals(status);
    }

    private void releaseUserSlot(GenerationTask task) {
        if (task.getOwnerUserId() > 0) {
            activeTaskIdsByUser.computeIfPresent(task.getOwnerUserId(), (userId, activeTaskIds) -> {
                activeTaskIds.remove(task.getId());
                return activeTaskIds.isEmpty() ? null : activeTaskIds;
            });
        }
    }

    /** 扫表：把超过 TTL 的"已完成任务"从主表里挪走，让 GC 回收 task results / thoughts 占的内存。 */
    private void evictExpired() {
        long now = System.currentTimeMillis();
        int removed = 0;
        Iterator<Map.Entry<String, Long>> it = completedAt.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, Long> e = it.next();
            if (now - e.getValue() > DONE_TASK_TTL_MS) {
                tasks.remove(e.getKey());
                GenerationCancellationContext.clearTask(e.getKey());
                it.remove();
                removed++;
            }
        }
        if (removed > 0) log.debug("TaskService 清理过期任务: {} 条", removed);
    }

    /**
     * Phase 1 临时归档清理：扫 .temp-output/，删 mtime > 2h 的子文件夹。
     * 用户在前端点 💾 时会把图 copy 到永久 outputDir；没点的就自然过期被删。
     * 仅删第一层子目录（如 自定义模式生成/{timestamp}、{category}/{product}_N、视频）的内层。
     * 顶层目录本身（自定义模式生成 / 视频 等）保留，避免反复创建。
     */
    private void evictExpiredTempOutput() {
        try {
            long cutoff = System.currentTimeMillis() - TEMP_OUTPUT_TTL_MS;
            int removed = 0;

            // 兼容旧版本：升级前的临时结果仍可能在全局 .temp-output。
            removed += evictExpiredTempOutputRoot(new File(appProperties.getPaths().getTempOutputDir()), cutoff);

            Path usersRoot = userStorageService.usersRoot();
            if (Files.isDirectory(usersRoot)) {
                try (var users = Files.newDirectoryStream(usersRoot)) {
                    for (Path userRoot : users) {
                        if (!Files.isDirectory(userRoot)) continue;
                        File tempRoot = userRoot.resolve("files").resolve("temp-output").toFile();
                        removed += evictExpiredTempOutputRoot(tempRoot, cutoff);
                    }
                }
            }

            if (removed > 0) log.info("临时归档清理: 删了 {} 个过期目录/文件", removed);
        } catch (Exception e) {
            log.warn("临时归档清理失败: {}", e.getMessage());
        }
    }

    private int evictExpiredTempOutputRoot(File root, long cutoff) {
        if (root == null || !root.isDirectory()) return 0;
        int removed = 0;
        File[] topLevel = root.listFiles(File::isDirectory);
        if (topLevel == null) return 0;
        for (File top : topLevel) {
            File[] subDirs = top.listFiles(File::isDirectory);
            if (subDirs != null) {
                for (File sub : subDirs) {
                    if (sub.lastModified() < cutoff && deleteRecursively(sub)) {
                        removed++;
                    }
                }
            }
            // 视频模式可能直接落在一级分类目录下，单独处理过期文件。
            File[] files = top.listFiles(File::isFile);
            if (files != null) {
                for (File f : files) {
                    if (f.lastModified() < cutoff && f.delete()) {
                        removed++;
                    }
                }
            }
        }
        return removed;
    }

    /** 递归删除文件或目录，返回是否成功（局部失败也尽力而为）。 */
    private boolean deleteRecursively(File file) {
        if (file == null || !file.exists()) return false;
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) for (File c : children) deleteRecursively(c);
        }
        return file.delete();
    }

    private void markUsageSucceeded(GenerationTask task) {
        if (billingService != null && task.getUsageLogId() != null) {
            billingService.markGenerationSucceeded(task.getUsageLogId(), GenerationProviderCostContext.snapshotAndClear());
        }
    }

    private void markUsageFailed(GenerationTask task, String message) {
        if (billingService != null && task.getUsageLogId() != null) {
            billingService.markGenerationFailed(task.getUsageLogId(), message);
        }
    }

    private void markUsageCancelled(GenerationTask task) {
        if (billingService != null && task.getUsageLogId() != null) {
            billingService.markGenerationCancelled(task.getUsageLogId());
        }
    }
}

