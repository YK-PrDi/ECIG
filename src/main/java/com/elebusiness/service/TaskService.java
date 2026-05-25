package com.elebusiness.service;

import com.elebusiness.config.AppProperties;
import com.elebusiness.model.GenerationTask;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.File;
import java.util.Iterator;
import java.util.Map;
import java.util.Optional;
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

    // 最多同时跑 10 个生成任务
    private final ExecutorService executor = Executors.newFixedThreadPool(10);
    private final Map<String, GenerationTask> tasks = new ConcurrentHashMap<>();
    // 完成时间戳：仅 done/error/stopped 状态的任务才进入这个 map，按 TTL 过期
    private final Map<String, Long> completedAt = new ConcurrentHashMap<>();
    private final ScheduledExecutorService cleaner =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "task-cleaner");
                t.setDaemon(true);
                return t;
            });

    public TaskService(AppProperties appProperties) {
        this.appProperties = appProperties;
    }

    @PostConstruct
    void start() {
        // 每 5 分钟扫一次过期任务
        cleaner.scheduleAtFixedRate(this::evictExpired, 5, 5, TimeUnit.MINUTES);
        // 每 30 分钟扫一次临时归档目录，删超过 2h 的子文件夹
        cleaner.scheduleAtFixedRate(this::evictExpiredTempOutput, 1, 30, TimeUnit.MINUTES);
    }

    public GenerationTask createTask(int total) {
        String id = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        GenerationTask task = new GenerationTask(id, total);
        tasks.put(id, task);
        return task;
    }

    public void submit(GenerationTask task, Runnable work) {
        executor.submit(() -> {
            task.setStatus("running");
            try {
                work.run();
            } catch (Exception e) {
                log.error("任务 {} 执行异常: {}", task.getId(), e.getMessage(), e);
                task.setStatus("error");
                completedAt.put(task.getId(), System.currentTimeMillis());
                return;
            }
            task.setStatus(task.isCancelled() ? "stopped" : "done");
            completedAt.put(task.getId(), System.currentTimeMillis());
        });
    }

    public Optional<GenerationTask> getTask(String taskId) {
        return Optional.ofNullable(tasks.get(taskId));
    }

    public boolean cancel(String taskId) {
        GenerationTask task = tasks.get(taskId);
        if (task == null) return false;
        task.cancel();
        return true;
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
            File root = new File(appProperties.getPaths().getTempOutputDir());
            if (!root.isDirectory()) return;
            long cutoff = System.currentTimeMillis() - TEMP_OUTPUT_TTL_MS;
            int removed = 0;
            File[] topLevel = root.listFiles(File::isDirectory);
            if (topLevel == null) return;
            for (File top : topLevel) {
                File[] subDirs = top.listFiles(File::isDirectory);
                if (subDirs == null) continue;
                for (File sub : subDirs) {
                    if (sub.lastModified() < cutoff) {
                        if (deleteRecursively(sub)) removed++;
                    }
                }
                // 视频模式直接落在 视频/ 下，没有 timestamp 子目录 — 单独处理 .mp4 文件
                File[] files = top.listFiles(File::isFile);
                if (files != null) {
                    for (File f : files) {
                        if (f.lastModified() < cutoff && f.delete()) removed++;
                    }
                }
            }
            if (removed > 0) log.info("临时归档清理: 删了 {} 个过期目录/文件", removed);
        } catch (Exception e) {
            log.warn("临时归档清理失败: {}", e.getMessage());
        }
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
}

