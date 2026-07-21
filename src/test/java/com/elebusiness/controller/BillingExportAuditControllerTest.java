package com.elebusiness.controller;

import com.elebusiness.model.entity.BillingExportAuditLog;
import com.elebusiness.service.auth.AuthService;
import com.elebusiness.service.auth.CurrentUserService;
import com.elebusiness.service.billing.BillingExportAuditService;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BillingExportAuditControllerTest {

    @Test
    void onlyAdminCanListExportAuditLogs() {
        BillingExportAuditService service = mock(BillingExportAuditService.class);
        BillingExportAuditController controller = new BillingExportAuditController(service, new CurrentUserService());

        assertThrows(ResponseStatusException.class,
                () -> controller.listLogs(userSession(1001L, "USER"), 1L, "USAGE", "SUCCESS", 120L, 50));

        BillingExportAuditLog log = new BillingExportAuditLog();
        log.setId(66L);
        log.setExportId("export-66");
        log.setOperatorUserId(1L);
        log.setExportType("USAGE");
        log.setStatus("SUCCESS");
        BillingExportAuditService.ExportAuditPage page =
                new BillingExportAuditService.ExportAuditPage(List.of(log), 50, true, 66L);
        when(service.listLogs(1L, "USAGE", "SUCCESS", 120L, 50)).thenReturn(page);

        BillingExportAuditService.ExportAuditPage body =
                controller.listLogs(userSession(1L, "ADMIN"), 1L, "USAGE", "SUCCESS", 120L, 50);

        assertEquals(1, body.items().size());
        assertEquals(66L, body.nextCursor());
        verify(service).listLogs(1L, "USAGE", "SUCCESS", 120L, 50);
    }

    @Test
    void onlyAdminCanExportExportAuditLogsAndRecordsAudit() {
        BillingExportAuditService service = mock(BillingExportAuditService.class);
        BillingExportAuditController controller = new BillingExportAuditController(service, new CurrentUserService());
        String csv = "\uFEFFid,exportId\n66,export-66\n";

        assertThrows(ResponseStatusException.class,
                () -> controller.exportLogs(userSession(1001L, "USER"), 1L, "USAGE", "SUCCESS"));
        when(service.exportLogsForAdmin(1L, "USAGE", "SUCCESS")).thenReturn(csv);

        var response = controller.exportLogs(userSession(1L, "ADMIN"), 1L, "USAGE", "SUCCESS");

        assertEquals(csv, response.getBody());
        assertEquals("attachment; filename=\"billing-export-audits.csv\"",
                response.getHeaders().getFirst("Content-Disposition"));
        verify(service).exportLogsForAdmin(1L, "USAGE", "SUCCESS");
        verify(service).recordSuccess(eq(1L), eq(null), eq("EXPORT_AUDIT"), anyMap(), eq(csv));
    }

    @Test
    void exportAuditLogFailureRecordsFailureAuditAndKeepsOriginalException() {
        BillingExportAuditService service = mock(BillingExportAuditService.class);
        BillingExportAuditController controller = new BillingExportAuditController(service, new CurrentUserService());
        IllegalStateException failure = new IllegalStateException("database unavailable");
        when(service.exportLogsForAdmin(1L, "USAGE", "SUCCESS")).thenThrow(failure);
        when(service.recordFailure(eq(1L), eq(null), eq("EXPORT_AUDIT"), anyMap(), eq(failure)))
                .thenThrow(new IllegalStateException("audit unavailable"));

        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> controller.exportLogs(userSession(1L, "ADMIN"), 1L, "USAGE", "SUCCESS"));

        assertSame(failure, thrown);
        verify(service).recordFailure(eq(1L), eq(null), eq("EXPORT_AUDIT"), anyMap(), eq(failure));
    }

    private MockHttpSession userSession(long userId, String role) {
        CurrentUserService currentUserService = new CurrentUserService();
        MockHttpSession session = new MockHttpSession();
        currentUserService.bind(session, new AuthService.AuthUser(userId, "user" + userId, "User " + userId, role, 1L));
        return session;
    }
}
