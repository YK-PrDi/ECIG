package com.elebusiness.controller;

import com.elebusiness.model.entity.GenerationProviderTaskRef;
import com.elebusiness.model.entity.GenerationUsageLog;
import com.elebusiness.repository.GenerationProviderTaskRefRepository;
import com.elebusiness.repository.GenerationUsageLogRepository;
import com.elebusiness.service.auth.CurrentUserService;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
public class BillingProviderTaskRefController {

    private final GenerationProviderTaskRefRepository refRepository;
    private final GenerationUsageLogRepository usageRepository;
    private final CurrentUserService currentUserService;

    public BillingProviderTaskRefController(GenerationProviderTaskRefRepository refRepository,
                                            GenerationUsageLogRepository usageRepository,
                                            CurrentUserService currentUserService) {
        this.refRepository = refRepository;
        this.usageRepository = usageRepository;
        this.currentUserService = currentUserService;
    }

    @GetMapping("/api/billing/admin/provider-task-ref")
    public Map<String, Object> lookup(HttpSession session,
                                      @RequestParam String provider,
                                      @RequestParam String providerTaskId) {
        currentUserService.requireAdmin(session);
        String safeProvider = requireText(provider, "provider");
        String safeProviderTaskId = requireText(providerTaskId, "providerTaskId");

        return refRepository.findByProviderAndProviderTaskId(safeProvider, safeProviderTaskId)
                .map(ref -> {
                    Map<String, Object> body = new LinkedHashMap<>();
                    body.put("found", true);
                    body.put("ref", refMap(ref));
                    body.put("usage", usageRepository.findById(ref.getUsageLogId())
                            .map(this::usageMap)
                            .orElse(null));
                    return body;
                })
                .orElseGet(() -> Map.of("found", false));
    }

    private String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, name + " 不能为空");
        }
        return value.trim();
    }

    private Map<String, Object> refMap(GenerationProviderTaskRef ref) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", ref.getId());
        map.put("usageLogId", ref.getUsageLogId());
        map.put("userId", ref.getUserId());
        map.put("provider", ref.getProvider());
        map.put("providerTaskId", ref.getProviderTaskId());
        map.put("costSource", ref.getCostSource());
        map.put("createdAt", ref.getCreatedAt());
        return map;
    }

    private Map<String, Object> usageMap(GenerationUsageLog usage) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", usage.getId());
        map.put("userId", usage.getUserId());
        map.put("taskId", usage.getTaskId());
        map.put("mode", usage.getMode());
        map.put("agentId", usage.getAgentId());
        map.put("provider", usage.getProvider());
        map.put("estimatedPoints", usage.getEstimatedPoints());
        map.put("actualPoints", usage.getActualPoints());
        map.put("providerTaskId", usage.getProviderTaskId());
        map.put("providerRawCost", usage.getProviderRawCost());
        map.put("providerRawUnit", usage.getProviderRawUnit());
        map.put("costSource", usage.getCostSource());
        map.put("exchangeRate", usage.getExchangeRate());
        map.put("status", usage.getStatus());
        map.put("errorMessage", usage.getErrorMessage());
        map.put("startedAt", usage.getStartedAt());
        map.put("finishedAt", usage.getFinishedAt());
        return map;
    }
}
