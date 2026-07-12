package com.elebusiness.controller;

import com.elebusiness.service.auth.AuthService;
import com.elebusiness.service.auth.CurrentUserService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;
    private final CurrentUserService currentUserService;

    public AuthController(AuthService authService, CurrentUserService currentUserService) {
        this.authService = authService;
        this.currentUserService = currentUserService;
    }

    @PostMapping("/login")
    public ResponseEntity<Map<String, Object>> login(
            @RequestBody Map<String, String> body, HttpSession session) {
        String username = body == null ? "" : body.getOrDefault("username", "");
        String password = body == null ? "" : body.getOrDefault("password", "");
        Optional<AuthService.AuthUser> user = authService.authenticate(username, password);
        if (user.isPresent()) {
            currentUserService.bind(session, user.get());
            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "user", currentUserService.toMap(user.get())
            ));
        }
        return ResponseEntity.ok(Map.of("success", false, "error", "密码错误"));
    }

    @GetMapping("/check")
    public ResponseEntity<Map<String, Object>> check(HttpSession session) {
        Optional<AuthService.AuthUser> user = currentUserService.optional(session);
        if (user.isEmpty()) {
            return ResponseEntity.ok(Map.of("authenticated", false));
        }
        return ResponseEntity.ok(Map.of(
                "authenticated", true,
                "user", currentUserService.toMap(user.get())
        ));
    }

    @GetMapping("/heartbeat")
    public ResponseEntity<Map<String, Object>> heartbeat(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        Optional<AuthService.AuthUser> user = currentUserService.optional(session);
        if (user.isEmpty()) {
            return ResponseEntity.ok(Map.of(
                    "authenticated", false,
                    "serverTime", System.currentTimeMillis()
            ));
        }
        return ResponseEntity.ok(Map.of(
                "authenticated", true,
                "user", currentUserService.toMap(user.get()),
                "serverTime", System.currentTimeMillis()
        ));
    }

    @PostMapping("/logout")
    public ResponseEntity<Map<String, Object>> logout(HttpSession session) {
        session.invalidate();
        return ResponseEntity.ok(Map.of("success", true));
    }
}
