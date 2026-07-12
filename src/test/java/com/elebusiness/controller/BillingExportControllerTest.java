package com.elebusiness.controller;

import com.elebusiness.service.auth.AuthService;
import com.elebusiness.service.auth.CurrentUserService;
import com.elebusiness.service.billing.BillingExportAuditService;
import com.elebusiness.service.billing.BillingCsvExportService;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BillingExportControllerTest {

    @Test
    void userLedgerExportUsesCurrentUserAndReturnsCsvAttachment() {
        BillingCsvExportService exportService = mock(BillingCsvExportService.class);
        BillingExportAuditService auditService = mock(BillingExportAuditService.class);
        BillingExportController controller = new BillingExportController(exportService, new CurrentUserService(), auditService);
        LocalDateTime from = LocalDateTime.of(2026, 7, 10, 0, 0);
        LocalDateTime to = LocalDateTime.of(2026, 7, 11, 0, 0);
        when(exportService.exportLedgerForUser(1001L, "GENERATION_CHARGE", "OUT", from, to))
                .thenReturn("\uFEFFid,userId\n1,1001\n");

        ResponseEntity<String> response = controller.exportLedger(
                userSession(1001L, "USER"),
                "GENERATION_CHARGE",
                "OUT",
                "2026-07-10T00:00:00",
                "2026-07-11T00:00:00");

        assertEquals(200, response.getStatusCode().value());
        assertTrue(response.getHeaders().getFirst("Content-Disposition").contains("billing-ledger"));
        assertTrue(response.getBody().contains("1001"));
        verify(exportService).exportLedgerForUser(1001L, "GENERATION_CHARGE", "OUT", from, to);
        verify(auditService).recordSuccess(eq(1001L), eq(1001L), eq("LEDGER"), eq(Map.of(
                "scope", "USER",
                "type", "GENERATION_CHARGE",
                "direction", "OUT",
                "from", "2026-07-10T00:00:00",
                "to", "2026-07-11T00:00:00"
        )), eq("\uFEFFid,userId\n1,1001\n"));
    }

    @Test
    void adminPaymentExportRequiresAdminAndAllowsGlobalScope() {
        BillingCsvExportService exportService = mock(BillingCsvExportService.class);
        BillingExportController controller = new BillingExportController(exportService, new CurrentUserService());

        assertThrows(ResponseStatusException.class, () -> controller.exportAdminPaymentOrders(
                userSession(1001L, "USER"), null, "PAID", "manual", null, null));

        when(exportService.exportPaymentOrdersForAdmin(null, "PAID", "manual", null, null))
                .thenReturn("\uFEFForderNo,userId\nR1,1001\n");
        ResponseEntity<String> response = controller.exportAdminPaymentOrders(
                userSession(1L, "ADMIN"), null, "PAID", "manual", null, null);

        assertEquals(200, response.getStatusCode().value());
        assertTrue(response.getHeaders().getFirst("Content-Disposition").contains("billing-payment-orders"));
        verify(exportService).exportPaymentOrdersForAdmin(null, "PAID", "manual", null, null);
    }

    @Test
    void adminReconciliationAnomalyExportRequiresAdminAndReturnsCsvAttachment() {
        BillingCsvExportService exportService = mock(BillingCsvExportService.class);
        BillingExportAuditService auditService = mock(BillingExportAuditService.class);
        BillingExportController controller = new BillingExportController(exportService, new CurrentUserService(), auditService);

        assertThrows(ResponseStatusException.class, () -> controller.exportAdminReconciliationAnomalies(
                userSession(1001L, "USER"), "PAID_ORDER_LEDGER", 2002L));

        when(exportService.exportReconciliationAnomaliesForAdmin("PAID_ORDER_LEDGER", 2002L))
                .thenReturn("\uFEFFtype,id\nPAID_ORDER_LEDGER,88\n");
        ResponseEntity<String> response = controller.exportAdminReconciliationAnomalies(
                userSession(1L, "ADMIN"), "PAID_ORDER_LEDGER", 2002L);

        assertEquals(200, response.getStatusCode().value());
        assertTrue(response.getHeaders().getFirst("Content-Disposition").contains("billing-reconciliation-anomalies"));
        assertTrue(response.getBody().contains("PAID_ORDER_LEDGER"));
        verify(exportService).exportReconciliationAnomaliesForAdmin("PAID_ORDER_LEDGER", 2002L);
        verify(auditService).recordSuccess(eq(1L), eq(2002L), eq("RECONCILIATION_ANOMALY"), eq(Map.of(
                "scope", "ADMIN",
                "type", "PAID_ORDER_LEDGER"
        )), eq("\uFEFFtype,id\nPAID_ORDER_LEDGER,88\n"));
    }

    @Test
    void userUsageExportFailureRecordsFailureAuditAndKeepsOriginalException() {
        BillingCsvExportService exportService = mock(BillingCsvExportService.class);
        BillingExportAuditService auditService = mock(BillingExportAuditService.class);
        BillingExportController controller = new BillingExportController(exportService, new CurrentUserService(), auditService);
        IllegalStateException failure = new IllegalStateException("database unavailable");
        Map<String, Object> expectedFilters = nullableMap(
                "scope", "USER",
                "status", "SUCCEEDED",
                "provider", "liblib",
                "mode", "custom",
                "from", null,
                "to", null
        );
        when(exportService.exportUsageForUser(1001L, "SUCCEEDED", "liblib", "custom", null, null))
                .thenThrow(failure);
        when(auditService.recordFailure(eq(1001L), eq(1001L), eq("USAGE"), anyMap(), eq(failure)))
                .thenThrow(new IllegalStateException("audit unavailable"));

        IllegalStateException thrown = assertThrows(IllegalStateException.class, () -> controller.exportUsage(
                userSession(1001L, "USER"), "SUCCEEDED", "liblib", "custom", null, null));

        assertSame(failure, thrown);
        verify(auditService).recordFailure(eq(1001L), eq(1001L), eq("USAGE"), eq(expectedFilters), eq(failure));
    }

    private MockHttpSession userSession(long userId, String role) {
        CurrentUserService currentUserService = new CurrentUserService();
        MockHttpSession session = new MockHttpSession();
        currentUserService.bind(session, new AuthService.AuthUser(userId, "user" + userId, "User " + userId, role));
        return session;
    }

    private Map<String, Object> nullableMap(Object... pairs) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            map.put(String.valueOf(pairs[i]), pairs[i + 1]);
        }
        return map;
    }
}
