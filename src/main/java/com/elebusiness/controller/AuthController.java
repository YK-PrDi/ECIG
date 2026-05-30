package com.elebusiness.controller;

import com.elebusiness.config.AppProperties;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private static final String SESSION_KEY = "authenticated";
    private final AppProperties appProperties;

    public AuthController(AppProperties appProperties) {
        this.appProperties = appProperties;
    }

    @PostMapping("/login")
    public ResponseEntity<Map<String, Object>> login(
            @RequestBody Map<String, String> body, HttpSession session) {
        String password = body == null ? "" : body.getOrDefault("password", "");
        String expected = appProperties.getAuth().getPassword();
        if (expected != null && expected.equals(password)) {
            session.setAttribute(SESSION_KEY, true);
            return ResponseEntity.ok(Map.of("success", true));
        }
        return ResponseEntity.ok(Map.of("success", false, "error", "密码错误"));
    }

    @GetMapping("/check")
    public ResponseEntity<Map<String, Object>> check(HttpSession session) {
        boolean ok = Boolean.TRUE.equals(session.getAttribute(SESSION_KEY));
        return ResponseEntity.ok(Map.of("authenticated", ok));
    }

    @PostMapping("/logout")
    public ResponseEntity<Map<String, Object>> logout(HttpSession session) {
        session.invalidate();
        return ResponseEntity.ok(Map.of("success", true));
    }
}
