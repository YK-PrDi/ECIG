package com.elebusiness.controller;

import com.elebusiness.service.auth.CurrentUserService;
import com.elebusiness.service.billing.BillingReconciliationService;
import com.elebusiness.service.billing.BillingReconciliationRunService;
import jakarta.servlet.http.HttpSession;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;

@RestController
public class BillingReconciliationController {

    private final BillingReconciliationService reconciliationService;
    private final BillingReconciliationRunService runService;
    private final CurrentUserService currentUserService;

    public BillingReconciliationController(BillingReconciliationService reconciliationService,
                                           BillingReconciliationRunService runService,
                                           CurrentUserService currentUserService) {
        this.reconciliationService = reconciliationService;
        this.runService = runService;
        this.currentUserService = currentUserService;
    }

    @GetMapping("/api/billing/admin/reconciliation")
    public BillingReconciliationService.Report adminReconciliation(HttpSession session,
                                                                   @RequestParam(required = false) Long userId) {
        currentUserService.requireAdmin(session);
        return reconciliationService.reconcile(userId);
    }

    @GetMapping("/api/billing/admin/reconciliation/anomalies")
    public BillingReconciliationService.AnomalyPage adminReconciliationAnomalies(
            HttpSession session,
            @RequestParam String type,
            @RequestParam(required = false) Long userId,
            @RequestParam(required = false) Long cursor,
            @RequestParam(defaultValue = "50") int limit) {
        currentUserService.requireAdmin(session);
        return reconciliationService.anomalyDetails(type, userId, cursor, limit);
    }

    @PostMapping("/api/billing/admin/reconciliation/runs")
    public BillingReconciliationRunService.RunResult adminCreateReconciliationRun(
            HttpSession session,
            @RequestParam(required = false) Long userId,
            @RequestParam(defaultValue = "MANUAL") String triggerType) {
        var admin = currentUserService.requireAdmin(session);
        return runService.runAndRecord(userId, triggerType, admin.id());
    }

    @GetMapping("/api/billing/admin/reconciliation/runs")
    public BillingReconciliationRunService.RunPage adminReconciliationRuns(
            HttpSession session,
            @RequestParam(required = false) Long userId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String triggerType,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(required = false) Long cursor,
            @RequestParam(defaultValue = "50") int limit) {
        currentUserService.requireAdmin(session);
        return runService.listRuns(userId, status, triggerType, parseTime(from), parseTime(to), cursor, limit);
    }

    @GetMapping("/api/billing/admin/reconciliation/runs/{runId}")
    public com.elebusiness.model.entity.BillingReconciliationRun adminReconciliationRun(
            HttpSession session,
            @PathVariable String runId) {
        currentUserService.requireAdmin(session);
        return runService.getByRunId(runId);
    }

    private LocalDateTime parseTime(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return LocalDateTime.parse(value.trim());
    }
}
