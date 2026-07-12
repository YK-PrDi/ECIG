package com.elebusiness.controller;

import com.elebusiness.model.entity.AppUser;
import com.elebusiness.repository.AppUserRepository;
import com.elebusiness.service.auth.AuthService;
import com.elebusiness.service.auth.CurrentUserService;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/users")
public class UserController {

    private final AppUserRepository userRepository;
    private final AuthService authService;
    private final CurrentUserService currentUserService;

    public UserController(AppUserRepository userRepository,
                          AuthService authService,
                          CurrentUserService currentUserService) {
        this.userRepository = userRepository;
        this.authService = authService;
        this.currentUserService = currentUserService;
    }

    @GetMapping
    public Map<String, Object> listUsers(HttpSession session) {
        currentUserService.requireAdmin(session);
        List<Map<String, Object>> users = userRepository.findAll().stream()
                .map(this::toMap)
                .toList();
        return Map.of("items", users, "total", users.size());
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> createUser(@RequestBody Map<String, String> body,
                                                          HttpSession session) {
        currentUserService.requireAdmin(session);
        try {
            AuthService.AuthUser user = authService.createUser(
                    body == null ? "" : body.get("username"),
                    body == null ? "" : body.get("password"),
                    body == null ? "" : body.get("displayName"),
                    body == null ? "USER" : body.getOrDefault("role", "USER")
            );
            return ResponseEntity.ok(Map.of("success", true, "user", currentUserService.toMap(user)));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "error", e.getMessage()));
        }
    }

    private Map<String, Object> toMap(AppUser user) {
        return Map.of(
                "id", user.getId(),
                "username", user.getUsername(),
                "displayName", user.getDisplayName(),
                "role", user.getRole(),
                "enabled", user.isEnabled(),
                "createdAt", user.getCreatedAt()
        );
    }
}
