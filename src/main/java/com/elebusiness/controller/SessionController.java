package com.elebusiness.controller;

import com.elebusiness.service.SessionService;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Session 管理 API。
 *
 * 将 SESSION_ID 从浏览器 localStorage 迁移到服务端管理，
 * 实现跨浏览器会话的历史记录共享。
 */
@RestController
@RequestMapping("/api/session")
public class SessionController {

    private final SessionService sessionService;

    public SessionController(SessionService sessionService) {
        this.sessionService = sessionService;
    }

    /**
     * 获取当前 session ID（前端首次加载时调用）
     */
    @GetMapping("/current")
    public Map<String, Object> getCurrentSession() {
        Map<String, Object> result = new HashMap<>();
        result.put("sessionId", sessionService.getCurrentSessionId());
        result.put("sessions", sessionService.listSessionsWithId());
        return result;
    }

    /**
     * 切换 session
     */
    @PostMapping("/switch")
    public Map<String, Object> switchSession(@RequestBody Map<String, String> body) {
        String sessionId = body.get("sessionId");
        if (sessionId == null || sessionId.isBlank()) {
            return Map.of("success", false, "error", "缺少 sessionId");
        }
        sessionService.setCurrentSessionId(sessionId);
        return Map.of("success", true, "sessionId", sessionId);
    }

    /**
     * 创建新 session
     */
    @PostMapping("/create")
    public Map<String, Object> createSession(@RequestBody(required = false) Map<String, String> body) {
        String name = body != null ? body.getOrDefault("name", "新会话") : "新会话";
        String id = sessionService.createSession(name);
        return Map.of("success", true, "sessionId", id);
    }

    /**
     * 删除 session
     */
    @DeleteMapping("/{sessionId}")
    public Map<String, Object> deleteSession(@PathVariable String sessionId) {
        boolean success = sessionService.deleteSession(sessionId);
        return Map.of("success", success);
    }

    /**
     * 重命名 session
     */
    @PutMapping("/{sessionId}/rename")
    public Map<String, Object> renameSession(
            @PathVariable String sessionId,
            @RequestBody Map<String, String> body) {
        String newName = body.get("name");
        if (newName == null || newName.isBlank()) {
            return Map.of("success", false, "error", "缺少新名称");
        }
        boolean success = sessionService.renameSession(sessionId, newName);
        return Map.of("success", success);
    }

    /**
     * 列出所有 session
     */
    @GetMapping("/list")
    public Map<String, Object> listSessions() {
        List<Map<String, Object>> sessions = sessionService.listSessionsWithId();
        return Map.of("sessions", sessions, "total", sessions.size());
    }
}
