package com.elebusiness.controller;

import com.elebusiness.model.entity.BillingDailySummary;
import com.elebusiness.service.auth.CurrentUserService;
import com.elebusiness.service.billing.BillingDailySummaryService;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;

@RestController
public class BillingDailySummaryController {

    private final BillingDailySummaryService summaryService;
    private final CurrentUserService currentUserService;

    public BillingDailySummaryController(BillingDailySummaryService summaryService,
                                         CurrentUserService currentUserService) {
        this.summaryService = summaryService;
        this.currentUserService = currentUserService;
    }

    @PostMapping("/api/billing/admin/daily-summary/refresh")
    public ResponseEntity<Map<String, Object>> refreshDailySummary(HttpSession session,
                                                                   @RequestParam String date,
                                                                   @RequestParam(required = false) Long userId) {
        currentUserService.requireAdmin(session);
        BillingDailySummary summary = summaryService.refreshDailySummary(LocalDate.parse(date), userId);
        return ResponseEntity.ok(Map.of("success", true, "summary", summaryMap(summary)));
    }

    @PostMapping("/api/billing/admin/daily-summary/refresh-range")
    public ResponseEntity<Map<String, Object>> refreshDailySummaryRange(HttpSession session,
                                                                        @RequestParam String from,
                                                                        @RequestParam String to,
                                                                        @RequestParam(required = false) Long userId) {
        currentUserService.requireAdmin(session);
        BillingDailySummaryService.RefreshRangeResult result = summaryService.refreshDailySummaryRange(
                LocalDate.parse(from), LocalDate.parse(to), userId);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", true);
        body.put("requestedDays", result.requestedDays());
        body.put("successDays", result.successDays());
        body.put("failedDays", result.failedDays());
        body.put("allSucceeded", result.failedDays() == 0);
        body.put("days", result.days().stream().map(this::dayResultMap).toList());
        return ResponseEntity.ok(body);
    }

    private Map<String, Object> summaryMap(BillingDailySummary summary) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", summary.getId());
        map.put("scopeUserId", summary.getScopeUserId());
        map.put("userId", summary.getScopeUserId() == BillingDailySummaryService.GLOBAL_SCOPE_USER_ID
                ? null
                : summary.getScopeUserId());
        map.put("summaryDate", String.valueOf(summary.getSummaryDate()));
        map.put("inPoints", summary.getInPoints());
        map.put("outPoints", summary.getOutPoints());
        map.put("frozenPointsDelta", summary.getFrozenPointsDelta());
        map.put("releasedPoints", summary.getReleasedPoints());
        map.put("ledgerCount", summary.getLedgerCount());
        map.put("usageCount", summary.getUsageCount());
        map.put("succeededUsageCount", summary.getSucceededUsageCount());
        map.put("failedUsageCount", summary.getFailedUsageCount());
        map.put("runningUsageCount", summary.getRunningUsageCount());
        map.put("actualPoints", summary.getActualPoints());
        map.put("estimatedPoints", summary.getEstimatedPoints());
        map.put("orderCount", summary.getOrderCount());
        map.put("paidOrderCount", summary.getPaidOrderCount());
        map.put("pendingOrderCount", summary.getPendingOrderCount());
        map.put("paidAmountCents", summary.getPaidAmountCents());
        map.put("pendingAmountCents", summary.getPendingAmountCents());
        map.put("refreshedAt", summary.getRefreshedAt());
        return map;
    }

    private Map<String, Object> dayResultMap(BillingDailySummaryService.RefreshDayResult day) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("date", String.valueOf(day.date()));
        map.put("status", day.status());
        map.put("message", day.message());
        map.put("scopeUserId", day.scopeUserId());
        map.put("userId", day.scopeUserId() == BillingDailySummaryService.GLOBAL_SCOPE_USER_ID
                ? null
                : day.scopeUserId());
        return map;
    }
}
