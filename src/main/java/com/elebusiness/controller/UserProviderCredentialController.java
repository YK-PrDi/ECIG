package com.elebusiness.controller;

import com.elebusiness.service.auth.CurrentUserService;
import com.elebusiness.service.provider.UserProviderCredentialService;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/provider-credentials")
public class UserProviderCredentialController {

    private final UserProviderCredentialService credentialService;
    private final CurrentUserService currentUserService;

    public UserProviderCredentialController(UserProviderCredentialService credentialService,
                                            CurrentUserService currentUserService) {
        this.credentialService = credentialService;
        this.currentUserService = currentUserService;
    }

    @GetMapping
    public Map<String, Object> listCredentials(HttpSession session) {
        long userId = currentUserService.requireUserId(session);
        List<Map<String, Object>> items = credentialService.listSummaries(userId).stream()
                .map(this::summaryMap)
                .toList();
        return Map.of("items", items, "total", items.size());
    }

    @PutMapping("/{provider}/{credentialName}")
    public ResponseEntity<Map<String, Object>> upsertCredential(HttpSession session,
                                                                @PathVariable String provider,
                                                                @PathVariable String credentialName,
                                                                @RequestBody Map<String, Object> body) {
        long userId = currentUserService.requireUserId(session);
        UserProviderCredentialService.CredentialSummary summary = credentialService.upsertCredential(
                userId,
                provider,
                credentialName,
                payload(body),
                booleanValue(body, "enabled", true)
        );
        return ResponseEntity.ok(Map.of("success", true, "credential", summaryMap(summary)));
    }

    private Map<String, Object> summaryMap(UserProviderCredentialService.CredentialSummary summary) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", summary.id());
        map.put("userId", summary.userId());
        map.put("provider", summary.provider());
        map.put("credentialName", summary.credentialName());
        map.put("enabled", summary.enabled());
        map.put("createdAt", summary.createdAt());
        map.put("updatedAt", summary.updatedAt());
        map.put("payloadKeys", summary.payloadKeys());
        return map;
    }

    private Map<String, Object> payload(Map<String, Object> body) {
        Object value = body == null ? null : body.get("payload");
        if (!(value instanceof Map<?, ?> raw)) {
            throw new IllegalArgumentException("payload 不能为空");
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        raw.forEach((key, item) -> {
            if (key != null) {
                payload.put(String.valueOf(key), item);
            }
        });
        return payload;
    }

    private boolean booleanValue(Map<String, Object> body, String key, boolean fallback) {
        if (body == null || body.get(key) == null) return fallback;
        Object value = body.get(key);
        if (value instanceof Boolean bool) return bool;
        return Boolean.parseBoolean(String.valueOf(value));
    }
}
