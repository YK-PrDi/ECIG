package com.elebusiness.controller;

import com.elebusiness.service.auth.CurrentUserService;
import com.elebusiness.service.billing.BillingExportAuditService;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
public class BillingExportAuditController {

    private static final MediaType CSV_UTF8 = MediaType.parseMediaType("text/csv; charset=UTF-8");

    private final BillingExportAuditService service;
    private final CurrentUserService currentUserService;

    public BillingExportAuditController(BillingExportAuditService service,
                                        CurrentUserService currentUserService) {
        this.service = service;
        this.currentUserService = currentUserService;
    }

    @GetMapping("/api/billing/admin/export-audits")
    public BillingExportAuditService.ExportAuditPage listLogs(
            HttpSession session,
            @RequestParam(required = false) Long operatorUserId,
            @RequestParam(required = false) String exportType,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Long cursor,
            @RequestParam(defaultValue = "50") int limit) {
        currentUserService.requireAdmin(session);
        return service.listLogs(operatorUserId, exportType, status, cursor, limit);
    }

    @GetMapping("/api/billing/admin/export-audits/export")
    public ResponseEntity<String> exportLogs(
            HttpSession session,
            @RequestParam(required = false) Long operatorUserId,
            @RequestParam(required = false) String exportType,
            @RequestParam(required = false) String status) {
        var admin = currentUserService.requireAdmin(session);
        String safeExportType = optionalText(exportType);
        String safeStatus = optionalText(status);
        Map<String, Object> filters = filterMap(
                "scope", "ADMIN", "operatorUserId", operatorUserId,
                "exportType", safeExportType, "status", safeStatus);
        String csv;
        try {
            csv = service.exportLogsForAdmin(operatorUserId, safeExportType, safeStatus);
        } catch (RuntimeException e) {
            recordFailureSafely(admin.id(), filters, e);
            throw e;
        }
        service.recordSuccess(admin.id(), null, "EXPORT_AUDIT", filters, csv);
        return ResponseEntity.ok()
                .contentType(CSV_UTF8)
                .header("Content-Disposition", "attachment; filename=\"billing-export-audits.csv\"")
                .body(csv);
    }

    private Map<String, Object> filterMap(Object... pairs) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            map.put(String.valueOf(pairs[i]), pairs[i + 1]);
        }
        return map;
    }

    private String optionalText(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    private void recordFailureSafely(Long operatorUserId, Map<String, Object> filters, RuntimeException failure) {
        try {
            service.recordFailure(operatorUserId, null, "EXPORT_AUDIT", filters, failure);
        } catch (RuntimeException auditFailure) {
            if (auditFailure != failure) {
                failure.addSuppressed(auditFailure);
            }
        }
    }
}
