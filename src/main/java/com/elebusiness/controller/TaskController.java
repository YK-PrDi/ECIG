package com.elebusiness.controller;

import com.elebusiness.service.TaskService;
import com.elebusiness.service.auth.CurrentUserService;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 异步任务进度查询与停止。
 * 从 ApiController 拆出（A.1 重构），业务逻辑零变动。
 */
@RestController
public class TaskController {

    private final TaskService taskService;
    private final CurrentUserService currentUserService;

    public TaskController(TaskService taskService, CurrentUserService currentUserService) {
        this.taskService = taskService;
        this.currentUserService = currentUserService;
    }

    /** 查询任务状态 */
    @GetMapping("/api/task/{taskId}")
    public ResponseEntity<Map<String, Object>> getTaskStatus(@PathVariable String taskId, HttpSession httpSession) {
        long userId = currentUserService.requireUserId(httpSession);
        return taskService.getTask(userId, taskId)
                .map(task -> {
                    Map<String, Object> resp = new LinkedHashMap<>();
                    resp.put("taskId", task.getId());
                    resp.put("status", task.getStatus());
                    resp.put("progress", task.getProgress());
                    resp.put("total", task.getTotal());
                    resp.put("currentProduct", task.getCurrentProduct());
                    resp.put("results", task.getResults());
                    return ResponseEntity.ok(resp);
                })
                .orElse(ResponseEntity.notFound().build());
    }

    /** 停止任务 */
    @PostMapping("/api/task/{taskId}/stop")
    public ResponseEntity<Map<String, Object>> stopTask(@PathVariable String taskId, HttpSession httpSession) {
        long userId = currentUserService.requireUserId(httpSession);
        boolean ok = taskService.cancel(userId, taskId);
        if (!ok) {
            return ResponseEntity.notFound().build();
        }
        String status = taskService.getTask(userId, taskId)
                .map(task -> task.getStatus())
                .orElse("stopped");
        return ResponseEntity.ok(Map.of("success", true, "status", status));
    }
}
