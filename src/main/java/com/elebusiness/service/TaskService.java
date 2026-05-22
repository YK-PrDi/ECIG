package com.elebusiness.service;

import com.elebusiness.model.GenerationTask;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

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

    @PostConstruct
    void start() {
        // 每 5 分钟扫一次过期任务
        cleaner.scheduleAtFixedRate(this::evictExpired, 5, 5, TimeUnit.MINUTES);
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
}

