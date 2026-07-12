package com.elebusiness.controller;

import com.elebusiness.service.auth.CurrentUserService;
import com.elebusiness.service.workspace.WorkspaceSessionService;
import jakarta.servlet.http.HttpSession;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/session")
public class SessionController {

    private final WorkspaceSessionService sessionService;
    private final CurrentUserService currentUserService;

    public SessionController(WorkspaceSessionService sessionService, CurrentUserService currentUserService) {
        this.sessionService = sessionService;
        this.currentUserService = currentUserService;
    }

    @GetMapping("/current")
    public Map<String, Object> getCurrentSession(HttpSession httpSession) {
        long userId = currentUserService.requireUserId(httpSession);
        WorkspaceSessionService.SessionDto current = sessionService.currentSession(userId);
        return Map.of(
                "sessionId", current.id(),
                "sessions", listSessionMaps(userId)
        );
    }

    @PostMapping("/switch")
    public Map<String, Object> switchSession(@RequestBody Map<String, String> body, HttpSession httpSession) {
        long userId = currentUserService.requireUserId(httpSession);
        String sessionId = body == null ? "" : body.get("sessionId");
        if (sessionId == null || sessionId.isBlank()) {
            return Map.of("success", false, "error", "缺少 sessionId");
        }
        boolean success = sessionService.switchSession(userId, sessionId);
        return success
                ? Map.of("success", true, "sessionId", sessionId)
                : Map.of("success", false, "error", "会话不存在");
    }

    @PostMapping("/create")
    public Map<String, Object> createSession(@RequestBody(required = false) Map<String, String> body,
                                             HttpSession httpSession) {
        long userId = currentUserService.requireUserId(httpSession);
        String name = body != null ? body.getOrDefault("name", "新会话") : "新会话";
        String id = sessionService.createSession(userId, name);
        return Map.of("success", true, "sessionId", id);
    }

    @DeleteMapping("/{sessionId}")
    public Map<String, Object> deleteSession(@PathVariable String sessionId, HttpSession httpSession) {
        long userId = currentUserService.requireUserId(httpSession);
        boolean success = sessionService.deleteSession(userId, sessionId);
        return Map.of("success", success);
    }

    @PutMapping("/{sessionId}/rename")
    public Map<String, Object> renameSession(@PathVariable String sessionId,
                                             @RequestBody Map<String, String> body,
                                             HttpSession httpSession) {
        long userId = currentUserService.requireUserId(httpSession);
        String newName = body == null ? "" : body.get("name");
        if (newName == null || newName.isBlank()) {
            return Map.of("success", false, "error", "缺少新名称");
        }
        boolean success = sessionService.renameSession(userId, sessionId, newName);
        return Map.of("success", success);
    }

    @GetMapping("/list")
    public Map<String, Object> listSessions(HttpSession httpSession) {
        long userId = currentUserService.requireUserId(httpSession);
        List<Map<String, Object>> sessions = listSessionMaps(userId);
        return Map.of("sessions", sessions, "total", sessions.size());
    }

    private List<Map<String, Object>> listSessionMaps(long userId) {
        return sessionService.listSessions(userId).stream()
                .map(this::toMap)
                .toList();
    }

    private Map<String, Object> toMap(WorkspaceSessionService.SessionDto dto) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", dto.id());
        map.put("name", dto.name());
        map.put("createdAt", dto.createdAt());
        map.put("lastActiveAt", dto.lastActiveAt());
        map.put("isCurrent", dto.current());
        return map;
    }
}
