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
import java.util.Objects;

/**
 * 员工账号管理。
 * 企业负责人（ADMIN）：只能看/管本企业成员，新建账号自动挂到本企业。
 * 平台中控（SUPERADMIN）：可看全部（用于指定企业负责人），但不能新建中控账号。
 */
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
        AuthService.AuthUser operator = currentUserService.requireAdmin(session);
        List<AppUser> users = operator.isSuperadmin()
                ? userRepository.findAll()
                : userRepository.findByEnterpriseId(operator.enterpriseId());
        List<Map<String, Object>> items = users.stream().map(this::toMap).toList();
        return Map.of("items", items, "total", items.size());
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> createUser(@RequestBody Map<String, String> body,
                                                          HttpSession session) {
        AuthService.AuthUser operator = currentUserService.requireAdmin(session);
        try {
            // 企业负责人建的账号只能进本企业；中控可指定 enterpriseId
            Long enterpriseId = operator.isSuperadmin()
                    ? parseLong(body == null ? null : body.get("enterpriseId"))
                    : operator.enterpriseId();
            if (!operator.isSuperadmin() && enterpriseId == null) {
                return ResponseEntity.badRequest()
                        .body(Map.of("success", false, "error", "你的账号未归属企业，无法拉人"));
            }
            AuthService.AuthUser user = authService.createUserInEnterprise(
                    body == null ? "" : body.get("username"),
                    body == null ? "" : body.get("password"),
                    body == null ? "" : body.get("displayName"),
                    body == null ? "USER" : body.getOrDefault("role", "USER"),
                    enterpriseId);
            return ResponseEntity.ok(Map.of("success", true, "user", currentUserService.toMap(user)));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "error", e.getMessage()));
        }
    }

    @PutMapping("/{id}/password")
    public ResponseEntity<Map<String, Object>> resetPassword(@PathVariable long id,
                                                             @RequestBody Map<String, String> body,
                                                             HttpSession session) {
        AuthService.AuthUser operator = currentUserService.requireAdmin(session);
        if (operator.id() == id) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "error", "不能在此重置自己的密码"));
        }
        ResponseEntity<Map<String, Object>> denied = checkSameEnterprise(operator, id);
        if (denied != null) return denied;
        try {
            authService.resetPassword(id, body == null ? null : body.get("password"));
            return ResponseEntity.ok(Map.of("success", true));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "error", e.getMessage()));
        }
    }

    @PutMapping("/{id}/enabled")
    public ResponseEntity<Map<String, Object>> setEnabled(@PathVariable long id,
                                                          @RequestBody Map<String, Object> body,
                                                          HttpSession session) {
        AuthService.AuthUser operator = currentUserService.requireAdmin(session);
        if (operator.id() == id) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "error", "不能停用自己的账号"));
        }
        ResponseEntity<Map<String, Object>> denied = checkSameEnterprise(operator, id);
        if (denied != null) return denied;
        boolean enabled = body != null && Boolean.parseBoolean(String.valueOf(body.getOrDefault("enabled", "true")));
        try {
            authService.setEnabled(id, enabled);
            return ResponseEntity.ok(Map.of("success", true));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "error", e.getMessage()));
        }
    }

    /** 企业负责人只能操作本企业成员；中控不受限。 */
    private ResponseEntity<Map<String, Object>> checkSameEnterprise(AuthService.AuthUser operator, long targetUserId) {
        if (operator.isSuperadmin()) return null;
        AppUser target = userRepository.findById(targetUserId).orElse(null);
        if (target == null) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "error", "用户不存在"));
        }
        if (!Objects.equals(operator.enterpriseId(), target.getEnterpriseId())) {
            return ResponseEntity.status(403).body(Map.of("success", false, "error", "只能管理本企业内的账号"));
        }
        return null;
    }

    private Long parseLong(String value) {
        if (value == null || value.isBlank()) return null;
        try { return Long.parseLong(value.trim()); } catch (NumberFormatException e) { return null; }
    }

    private Map<String, Object> toMap(AppUser user) {
        return Map.of(
                "id", user.getId(),
                "username", user.getUsername(),
                "displayName", user.getDisplayName(),
                "role", user.getRole(),
                "enabled", user.isEnabled(),
                "enterpriseId", user.getEnterpriseId() == null ? 0 : user.getEnterpriseId(),
                "createdAt", user.getCreatedAt()
        );
    }
}
