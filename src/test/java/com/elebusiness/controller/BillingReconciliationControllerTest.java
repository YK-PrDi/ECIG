package com.elebusiness.controller;

import com.elebusiness.service.auth.AuthService;
import com.elebusiness.service.auth.CurrentUserService;
import com.elebusiness.service.billing.BillingReconciliationService;
import com.elebusiness.service.billing.BillingReconciliationRunService;
import com.elebusiness.model.entity.BillingReconciliationRun;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BillingReconciliationControllerTest {

    @Test
    void onlyAdminCanRunReconciliation() {
        BillingReconciliationService service = mock(BillingReconciliationService.class);
        BillingReconciliationController controller = new BillingReconciliationController(
                service, mock(BillingReconciliationRunService.class), new CurrentUserService());

        assertThrows(ResponseStatusException.class,
                () -> controller.adminReconciliation(userSession(1001L, "USER"), null));

        BillingReconciliationService.Report report = new BillingReconciliationService.Report(
                2002L,
                true,
                List.of(new BillingReconciliationService.Check("WALLET_BALANCE", "钱包余额", 100L, 100L, 0L, true))
        );
        when(service.reconcile(2002L)).thenReturn(report);

        BillingReconciliationService.Report body = controller.adminReconciliation(userSession(1L, "ADMIN"), 2002L);

        assertEquals(2002L, body.userId());
        assertEquals(true, body.healthy());
        verify(service).reconcile(2002L);
    }

    @Test
    void onlyAdminCanListReconciliationAnomalyDetails() {
        BillingReconciliationService service = mock(BillingReconciliationService.class);
        BillingReconciliationController controller = new BillingReconciliationController(
                service, mock(BillingReconciliationRunService.class), new CurrentUserService());

        assertThrows(ResponseStatusException.class,
                () -> controller.adminReconciliationAnomalies(
                        userSession(1001L, "USER"), "PAID_ORDER_LEDGER", 2002L, 120L, 20));

        BillingReconciliationService.AnomalyPage page = new BillingReconciliationService.AnomalyPage(
                "PAID_ORDER_LEDGER",
                2002L,
                List.of(new BillingReconciliationService.AnomalyItem(
                        88L, 2002L, "R202607100001", "PAID", 100L, 19900L, "manual", null
                )),
                20,
                false,
                88L
        );
        when(service.anomalyDetails("PAID_ORDER_LEDGER", 2002L, 120L, 20)).thenReturn(page);

        BillingReconciliationService.AnomalyPage body = controller.adminReconciliationAnomalies(
                userSession(1L, "ADMIN"), "PAID_ORDER_LEDGER", 2002L, 120L, 20);

        assertEquals("PAID_ORDER_LEDGER", body.type());
        assertEquals(1, body.items().size());
        assertEquals(88L, body.nextCursor());
        verify(service).anomalyDetails("PAID_ORDER_LEDGER", 2002L, 120L, 20);
    }

    @Test
    void onlyAdminCanCreateAndStoreReconciliationRun() {
        BillingReconciliationService service = mock(BillingReconciliationService.class);
        BillingReconciliationRunService runService = mock(BillingReconciliationRunService.class);
        BillingReconciliationController controller = new BillingReconciliationController(
                service, runService, new CurrentUserService());

        assertThrows(ResponseStatusException.class,
                () -> controller.adminCreateReconciliationRun(userSession(1001L, "USER"), 2002L, "manual"));

        BillingReconciliationRun run = new BillingReconciliationRun();
        run.setId(88L);
        run.setRunId("run-88");
        run.setScopeUserId(2002L);
        run.setStatus("SUCCESS");
        BillingReconciliationService.Report report = new BillingReconciliationService.Report(
                2002L,
                true,
                List.of(new BillingReconciliationService.Check("WALLET_BALANCE", "钱包余额", 100L, 100L, 0L, true))
        );
        when(runService.runAndRecord(2002L, "manual", 1L))
                .thenReturn(new BillingReconciliationRunService.RunResult(run, report));

        BillingReconciliationRunService.RunResult body = controller.adminCreateReconciliationRun(
                userSession(1L, "ADMIN"), 2002L, "manual");

        assertEquals("run-88", body.run().getRunId());
        assertEquals(true, body.report().healthy());
        verify(runService).runAndRecord(2002L, "manual", 1L);
    }

    @Test
    void onlyAdminCanListReconciliationRuns() {
        BillingReconciliationService service = mock(BillingReconciliationService.class);
        BillingReconciliationRunService runService = mock(BillingReconciliationRunService.class);
        BillingReconciliationController controller = new BillingReconciliationController(
                service, runService, new CurrentUserService());

        assertThrows(ResponseStatusException.class,
                () -> controller.adminReconciliationRuns(
                        userSession(1001L, "USER"), 2002L, "SUCCESS", "MANUAL", null, null, 120L, 20));

        BillingReconciliationRun run = new BillingReconciliationRun();
        run.setId(66L);
        run.setRunId("run-66");
        run.setScopeUserId(2002L);
        run.setStatus("SUCCESS");
        BillingReconciliationRunService.RunPage page = new BillingReconciliationRunService.RunPage(
                List.of(run), 20, true, 66L);
        when(runService.listRuns(2002L, "SUCCESS", "MANUAL", null, null, 120L, 20)).thenReturn(page);

        BillingReconciliationRunService.RunPage body = controller.adminReconciliationRuns(
                userSession(1L, "ADMIN"), 2002L, "SUCCESS", "MANUAL", null, null, 120L, 20);

        assertEquals(1, body.items().size());
        assertEquals(66L, body.nextCursor());
        verify(runService).listRuns(2002L, "SUCCESS", "MANUAL", null, null, 120L, 20);
    }

    private MockHttpSession userSession(long userId, String role) {
        CurrentUserService currentUserService = new CurrentUserService();
        MockHttpSession session = new MockHttpSession();
        currentUserService.bind(session, new AuthService.AuthUser(userId, "user" + userId, "User " + userId, role));
        return session;
    }
}
