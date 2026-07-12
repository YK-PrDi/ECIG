package com.elebusiness.controller;

import com.elebusiness.service.auth.CurrentUserService;
import com.elebusiness.service.billing.BillingExportAuditService;
import com.elebusiness.service.billing.BillingCsvExportService;
import jakarta.servlet.http.HttpSession;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;

@RestController
public class BillingExportController {

    private static final MediaType CSV_UTF8 = MediaType.parseMediaType("text/csv; charset=UTF-8");

    private final BillingCsvExportService exportService;
    private final CurrentUserService currentUserService;
    private final BillingExportAuditService auditService;

    @Autowired
    public BillingExportController(BillingCsvExportService exportService,
                                   CurrentUserService currentUserService,
                                   BillingExportAuditService auditService) {
        this.exportService = exportService;
        this.currentUserService = currentUserService;
        this.auditService = auditService;
    }

    public BillingExportController(BillingCsvExportService exportService, CurrentUserService currentUserService) {
        this(exportService, currentUserService, null);
    }

    @GetMapping("/api/billing/ledger/export")
    public ResponseEntity<String> exportLedger(HttpSession session,
                                               @RequestParam(required = false) String type,
                                               @RequestParam(required = false) String direction,
                                               @RequestParam(required = false) String from,
                                               @RequestParam(required = false) String to) {
        long userId = currentUserService.requireUserId(session);
        String safeType = optionalText(type);
        String safeDirection = optionalText(direction);
        LocalDateTime fromTime = parseTime(from);
        LocalDateTime toTime = parseTime(to);
        return exportWithAudit(userId, userId, "LEDGER", filterMap(
                        "scope", "USER", "type", safeType, "direction", safeDirection,
                        "from", optionalText(from), "to", optionalText(to)),
                "billing-ledger.csv",
                () -> exportService.exportLedgerForUser(userId, safeType, safeDirection, fromTime, toTime));
    }

    @GetMapping("/api/billing/usage/export")
    public ResponseEntity<String> exportUsage(HttpSession session,
                                              @RequestParam(required = false) String status,
                                              @RequestParam(required = false) String provider,
                                              @RequestParam(required = false) String mode,
                                              @RequestParam(required = false) String from,
                                              @RequestParam(required = false) String to) {
        long userId = currentUserService.requireUserId(session);
        String safeStatus = optionalText(status);
        String safeProvider = optionalText(provider);
        String safeMode = optionalText(mode);
        LocalDateTime fromTime = parseTime(from);
        LocalDateTime toTime = parseTime(to);
        return exportWithAudit(userId, userId, "USAGE", filterMap(
                        "scope", "USER", "status", safeStatus, "provider", safeProvider, "mode", safeMode,
                        "from", optionalText(from), "to", optionalText(to)),
                "billing-usage.csv",
                () -> exportService.exportUsageForUser(userId, safeStatus, safeProvider, safeMode, fromTime, toTime));
    }

    @GetMapping("/api/billing/payment-orders/export")
    public ResponseEntity<String> exportPaymentOrders(HttpSession session,
                                                      @RequestParam(required = false) String status,
                                                      @RequestParam(required = false) String provider,
                                                      @RequestParam(required = false) String from,
                                                      @RequestParam(required = false) String to) {
        long userId = currentUserService.requireUserId(session);
        String safeStatus = optionalText(status);
        String safeProvider = optionalText(provider);
        LocalDateTime fromTime = parseTime(from);
        LocalDateTime toTime = parseTime(to);
        return exportWithAudit(userId, userId, "PAYMENT_ORDER", filterMap(
                        "scope", "USER", "status", safeStatus, "provider", safeProvider,
                        "from", optionalText(from), "to", optionalText(to)),
                "billing-payment-orders.csv",
                () -> exportService.exportPaymentOrdersForUser(userId, safeStatus, safeProvider, fromTime, toTime));
    }

    @GetMapping("/api/billing/admin/ledger/export")
    public ResponseEntity<String> exportAdminLedger(HttpSession session,
                                                    @RequestParam(required = false) Long userId,
                                                    @RequestParam(required = false) String type,
                                                    @RequestParam(required = false) String direction,
                                                    @RequestParam(required = false) String from,
                                                    @RequestParam(required = false) String to) {
        var admin = currentUserService.requireAdmin(session);
        String safeType = optionalText(type);
        String safeDirection = optionalText(direction);
        LocalDateTime fromTime = parseTime(from);
        LocalDateTime toTime = parseTime(to);
        return exportWithAudit(admin.id(), userId, "LEDGER", filterMap(
                        "scope", "ADMIN", "type", safeType, "direction", safeDirection,
                        "from", optionalText(from), "to", optionalText(to)),
                "billing-ledger.csv",
                () -> exportService.exportLedgerForAdmin(userId, safeType, safeDirection, fromTime, toTime));
    }

    @GetMapping("/api/billing/admin/usage/export")
    public ResponseEntity<String> exportAdminUsage(HttpSession session,
                                                   @RequestParam(required = false) Long userId,
                                                   @RequestParam(required = false) String status,
                                                   @RequestParam(required = false) String provider,
                                                   @RequestParam(required = false) String mode,
                                                   @RequestParam(required = false) String from,
                                                   @RequestParam(required = false) String to) {
        var admin = currentUserService.requireAdmin(session);
        String safeStatus = optionalText(status);
        String safeProvider = optionalText(provider);
        String safeMode = optionalText(mode);
        LocalDateTime fromTime = parseTime(from);
        LocalDateTime toTime = parseTime(to);
        return exportWithAudit(admin.id(), userId, "USAGE", filterMap(
                        "scope", "ADMIN", "status", safeStatus, "provider", safeProvider, "mode", safeMode,
                        "from", optionalText(from), "to", optionalText(to)),
                "billing-usage.csv",
                () -> exportService.exportUsageForAdmin(userId, safeStatus, safeProvider, safeMode, fromTime, toTime));
    }

    @GetMapping("/api/billing/admin/payment-orders/export")
    public ResponseEntity<String> exportAdminPaymentOrders(HttpSession session,
                                                           @RequestParam(required = false) Long userId,
                                                           @RequestParam(required = false) String status,
                                                           @RequestParam(required = false) String provider,
                                                           @RequestParam(required = false) String from,
                                                           @RequestParam(required = false) String to) {
        var admin = currentUserService.requireAdmin(session);
        String safeStatus = optionalText(status);
        String safeProvider = optionalText(provider);
        LocalDateTime fromTime = parseTime(from);
        LocalDateTime toTime = parseTime(to);
        return exportWithAudit(admin.id(), userId, "PAYMENT_ORDER", filterMap(
                        "scope", "ADMIN", "status", safeStatus, "provider", safeProvider,
                        "from", optionalText(from), "to", optionalText(to)),
                "billing-payment-orders.csv",
                () -> exportService.exportPaymentOrdersForAdmin(userId, safeStatus, safeProvider, fromTime, toTime));
    }

    @GetMapping("/api/billing/admin/reconciliation/anomalies/export")
    public ResponseEntity<String> exportAdminReconciliationAnomalies(HttpSession session,
                                                                     @RequestParam String type,
                                                                     @RequestParam(required = false) Long userId) {
        var admin = currentUserService.requireAdmin(session);
        String safeType = optionalText(type);
        return exportWithAudit(admin.id(), userId, "RECONCILIATION_ANOMALY", filterMap(
                        "scope", "ADMIN", "type", safeType),
                "billing-reconciliation-anomalies.csv",
                () -> exportService.exportReconciliationAnomaliesForAdmin(safeType, userId));
    }

    private ResponseEntity<String> exportWithAudit(Long operatorUserId,
                                                   Long scopeUserId,
                                                   String exportType,
                                                   Map<String, Object> filters,
                                                   String filename,
                                                   Supplier<String> exporter) {
        String csv;
        try {
            csv = exporter.get();
        } catch (RuntimeException e) {
            recordFailureSafely(operatorUserId, scopeUserId, exportType, filters, e);
            throw e;
        }
        recordSuccess(operatorUserId, scopeUserId, exportType, filters, csv);
        return csvResponse(filename, csv);
    }

    private void recordSuccess(Long operatorUserId, Long scopeUserId, String exportType,
                               Map<String, Object> filters, String csv) {
        if (auditService != null) {
            auditService.recordSuccess(operatorUserId, scopeUserId, exportType, filters, csv);
        }
    }

    private void recordFailureSafely(Long operatorUserId, Long scopeUserId, String exportType,
                                     Map<String, Object> filters, RuntimeException failure) {
        if (auditService == null) {
            return;
        }
        try {
            auditService.recordFailure(operatorUserId, scopeUserId, exportType, filters, failure);
        } catch (RuntimeException auditFailure) {
            if (auditFailure != failure) {
                failure.addSuppressed(auditFailure);
            }
        }
    }

    private Map<String, Object> filterMap(Object... pairs) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            map.put(String.valueOf(pairs[i]), pairs[i + 1]);
        }
        return map;
    }

    private ResponseEntity<String> csvResponse(String filename, String csv) {
        return ResponseEntity.ok()
                .contentType(CSV_UTF8)
                .header("Content-Disposition", "attachment; filename=\"" + filename + "\"")
                .body(csv);
    }

    private String optionalText(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    private LocalDateTime parseTime(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.length() == 10) {
            return LocalDate.parse(trimmed).atStartOfDay();
        }
        return LocalDateTime.parse(trimmed);
    }
}
