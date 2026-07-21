package com.elebusiness.controller;

import com.elebusiness.model.entity.AppUser;
import com.elebusiness.model.entity.Enterprise;
import com.elebusiness.repository.AppUserRepository;
import com.elebusiness.repository.EnterpriseRepository;
import com.elebusiness.repository.UserWalletRepository;
import com.elebusiness.service.auth.AuthService;
import com.elebusiness.service.auth.CurrentUserService;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 企业管理（多租户）。
 * SUPERADMIN（平台中控）：建企业、指定负责人、监视各企业（人数/点数）。
 * 普通登录用户：没有企业时可自助创建企业并成为负责人。
 * 注意：中控按设计看不到任何企业的生成内容与资产库（在 AssetController 隔离）。
 */
@RestController
@RequestMapping("/api/enterprises")
public class EnterpriseController {

    private final EnterpriseRepository enterpriseRepository;
    private final AppUserRepository userRepository;
    private final UserWalletRepository walletRepository;
    private final AuthService authService;
    private final CurrentUserService currentUserService;

    public EnterpriseController(EnterpriseRepository enterpriseRepository,
                                AppUserRepository userRepository,
                                UserWalletRepository walletRepository,
                                AuthService authService,
                                CurrentUserService currentUserService) {
        this.enterpriseRepository = enterpriseRepository;
        this.userRepository = userRepository;
        this.walletRepository = walletRepository;
        this.authService = authService;
        this.currentUserService = currentUserService;
    }

    /** 企业列表：中控看全部（含监视数据）；负责人只看本企业。 */
    @GetMapping
    public ResponseEntity<Map<String, Object>> list(HttpSession session) {
        AuthService.AuthUser user = currentUserService.require(session);
        if (user.isSuperadmin()) {
            List<Map<String, Object>> items = enterpriseRepository.findAllByOrderByCreatedAtAsc().stream()
                    .map(this::toMapWithStats)
                    .toList();
            return ResponseEntity.ok(Map.of("items", items, "total", items.size()));
        }
        if (user.enterpriseId() == null) {
            return ResponseEntity.ok(Map.of("items", List.of(), "total", 0));
        }
        Enterprise own = enterpriseRepository.findById(user.enterpriseId()).orElse(null);
        List<Map<String, Object>> items = own == null ? List.of() : List.of(toMapWithStats(own));
        return ResponseEntity.ok(Map.of("items", items, "total", items.size()));
    }

    /**
     * 建企业。
     * 中控：{name, ownerUsername?, ownerPassword?, ownerDisplayName?} —— 可同时创建负责人账号；
     * 普通用户（无企业）：{name} —— 自助建企业，自己成为负责人。
     */
    @PostMapping
    public ResponseEntity<Map<String, Object>> create(@RequestBody Map<String, String> body,
                                                      HttpSession session) {
        AuthService.AuthUser user = currentUserService.require(session);
        String name = body == null ? null : body.get("name");
        try {
            if (user.isSuperadmin()) {
                Enterprise enterprise = authService.createEnterprise(name, null);
                String ownerUsername = body == null ? null : body.get("ownerUsername");
                if (ownerUsername != null && !ownerUsername.isBlank()) {
                    AuthService.AuthUser owner = userRepository.findByUsername(ownerUsername.trim())
                            .map(authService::toAuthUser)
                            .orElseGet(() -> authService.createUserInEnterprise(
                                    ownerUsername.trim(),
                                    body.get("ownerPassword"),
                                    body.get("ownerDisplayName"),
                                    AuthService.ROLE_ADMIN, null));
                    authService.assignOwner(enterprise.getId(), owner.id());
                }
                Enterprise saved = enterpriseRepository.findById(enterprise.getId()).orElseThrow();
                return ResponseEntity.ok(Map.of("success", true, "item", toMapWithStats(saved)));
            }
            // 自助建企业：仅限尚未归属任何企业的用户
            if (user.enterpriseId() != null) {
                return ResponseEntity.badRequest()
                        .body(Map.of("success", false, "error", "你已归属企业，不能重复创建"));
            }
            Enterprise enterprise = authService.createEnterprise(name, user);
            authService.assignOwner(enterprise.getId(), user.id());
            Enterprise saved = enterpriseRepository.findById(enterprise.getId()).orElseThrow();
            return ResponseEntity.ok(Map.of("success", true, "item", toMapWithStats(saved)));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "error", e.getMessage()));
        }
    }

    /** 中控：指定/更换企业负责人。 */
    @PutMapping("/{id}/owner")
    public ResponseEntity<Map<String, Object>> assignOwner(@PathVariable long id,
                                                           @RequestBody Map<String, Object> body,
                                                           HttpSession session) {
        currentUserService.requireSuperadmin(session);
        long userId = body == null ? 0 : Long.parseLong(String.valueOf(body.getOrDefault("userId", "0")));
        try {
            authService.assignOwner(id, userId);
            Enterprise enterprise = enterpriseRepository.findById(id).orElseThrow();
            return ResponseEntity.ok(Map.of("success", true, "item", toMapWithStats(enterprise)));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "error", e.getMessage()));
        }
    }

    private Map<String, Object> toMapWithStats(Enterprise enterprise) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", enterprise.getId());
        map.put("name", enterprise.getName());
        map.put("ownerId", enterprise.getOwnerId());
        map.put("ownerName", enterprise.getOwnerName());
        map.put("createdAt", enterprise.getCreatedAt() == null ? null : enterprise.getCreatedAt().toString());
        List<AppUser> members = userRepository.findByEnterpriseId(enterprise.getId());
        map.put("memberCount", members.size());
        long balance = 0;
        long frozen = 0;
        for (AppUser member : members) {
            var wallet = walletRepository.findByUserId(member.getId());
            if (wallet.isPresent()) {
                balance += wallet.get().getBalancePoints();
                frozen += wallet.get().getFrozenPoints();
            }
        }
        map.put("totalBalancePoints", balance);
        map.put("totalFrozenPoints", frozen);
        return map;
    }
}
