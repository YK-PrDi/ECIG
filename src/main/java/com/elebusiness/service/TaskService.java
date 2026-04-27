package com.elebusiness.service;

import com.elebusiness.model.GenerationTask;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Service
public class TaskService {

    // 最多同时跑 2 个生成任务
    private final ExecutorService executor = Executors.newFixedThreadPool(2);
    private final Map<String, GenerationTask> tasks = new ConcurrentHashMap<>();

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
                task.setStatus("error");
                return;
            }
            task.setStatus(task.isCancelled() ? "stopped" : "done");
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
}
