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
    private final com.elebusiness.config.AppProperties appProperties;

    public AuthController(AuthService authService, CurrentUserService currentUserService,
                          com.elebusiness.config.AppProperties appProperties) {
        this.authService = authService;
        this.currentUserService = currentUserService;
        this.appProperties = appProperties;
    }

    /**
     * 自助注册（模块化开关：app.auth.registration-enabled / REGISTRATION_ENABLED）。
     * 默认关闭 —— 只保留接口，开关联通后即可恢复，无需改代码。
     */
    @PostMapping("/register")
    public ResponseEntity<Map<String, Object>> register(@RequestBody Map<String, String> body,
                                                        HttpSession session) {
        if (!appProperties.getAuth().isRegistrationEnabled()) {
            return ResponseEntity.status(403).body(Map.of(
                    "success", false, "error", "注册暂未开放，请联系管理员开通账号"));
        }
        try {
            AuthService.AuthUser user = authService.register(
                    body == null ? null : body.get("username"),
                    body == null ? null : body.get("password"),
                    body == null ? null : body.get("displayName"),
                    body == null ? null : body.get("enterpriseName"));
            currentUserService.bind(session, user);
            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "user", currentUserService.toMap(user)));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "error", e.getMessage()));
        }
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
