package com.elebusiness.controller;

import com.elebusiness.model.entity.BillingReconciliationAnomalyAction;
import com.elebusiness.service.auth.CurrentUserService;
import com.elebusiness.service.billing.BillingReconciliationAnomalyActionService;
import jakarta.servlet.http.HttpSession;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class BillingReconciliationAnomalyActionController {

    private final BillingReconciliationAnomalyActionService service;
    private final CurrentUserService currentUserService;

    public BillingReconciliationAnomalyActionController(BillingReconciliationAnomalyActionService service,
                                                        CurrentUserService currentUserService) {
        this.service = service;
        this.currentUserService = currentUserService;
    }

    @PutMapping("/api/billing/admin/reconciliation/anomaly-actions")
    public BillingReconciliationAnomalyAction recordAction(
            HttpSession session,
            @RequestBody BillingReconciliationAnomalyActionService.ActionRequest request) {
        var admin = currentUserService.requireAdmin(session);
        return service.recordAction(request, admin.id());
    }

    @GetMapping("/api/billing/admin/reconciliation/anomaly-actions")
    public BillingReconciliationAnomalyActionService.ActionPage listActions(
            HttpSession session,
            @RequestParam(required = false) String type,
            @RequestParam(required = false) Long userId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Long cursor,
            @RequestParam(defaultValue = "50") int limit) {
        currentUserService.requireAdmin(session);
        return service.listActions(type, userId, status, cursor, limit);
    }
}
