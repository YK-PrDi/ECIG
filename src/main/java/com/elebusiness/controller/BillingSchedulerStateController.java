package com.elebusiness.controller;

import com.elebusiness.model.entity.BillingSchedulerState;
import com.elebusiness.repository.BillingSchedulerStateRepository;
import com.elebusiness.service.auth.CurrentUserService;
import jakarta.servlet.http.HttpSession;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

@RestController
public class BillingSchedulerStateController {

    private final BillingSchedulerStateRepository repository;
    private final CurrentUserService currentUserService;

    public BillingSchedulerStateController(BillingSchedulerStateRepository repository,
                                           CurrentUserService currentUserService) {
        this.repository = repository;
        this.currentUserService = currentUserService;
    }

    @GetMapping("/api/billing/admin/scheduler-states")
    public Map<String, Object> listStates(HttpSession session) {
        currentUserService.requireAdmin(session);
        LocalDateTime now = LocalDateTime.now();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("items", repository.findAll().stream()
                .map(state -> stateMap(state, now))
                .toList());
        return body;
    }

    private Map<String, Object> stateMap(BillingSchedulerState state, LocalDateTime now) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", state.getId());
        map.put("stateKey", state.getStateKey());
        map.put("lastUserId", state.getLastUserId());
        map.put("leaseOwner", state.getLeaseOwner());
        map.put("leaseUntil", state.getLeaseUntil());
        map.put("leaseActive", state.getLeaseUntil() != null && state.getLeaseUntil().isAfter(now));
        map.put("createdAt", state.getCreatedAt());
        map.put("updatedAt", state.getUpdatedAt());
        return map;
    }
}
