package com.elebusiness.controller;

import com.elebusiness.model.entity.BillingReconciliationAnomalyAction;
import com.elebusiness.service.auth.AuthService;
import com.elebusiness.service.auth.CurrentUserService;
import com.elebusiness.service.billing.BillingReconciliationAnomalyActionService;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BillingReconciliationAnomalyActionControllerTest {

    @Test
    void onlyAdminCanRecordAnomalyAction() {
        BillingReconciliationAnomalyActionService service = mock(BillingReconciliationAnomalyActionService.class);
        BillingReconciliationAnomalyActionController controller = new BillingReconciliationAnomalyActionController(
                service, new CurrentUserService());
        BillingReconciliationAnomalyActionService.ActionRequest request =
                new BillingReconciliationAnomalyActionService.ActionRequest(
                        "PAID_ORDER_LEDGER", 88L, 2002L, "R202607100001", "ACKNOWLEDGED", "人工核对中");

        assertThrows(ResponseStatusException.class,
                () -> controller.recordAction(userSession(1001L, "USER"), request));

        BillingReconciliationAnomalyAction action = new BillingReconciliationAnomalyAction();
        action.setId(9L);
        action.setAnomalyKey("PAID_ORDER_LEDGER:88");
        action.setStatus("ACKNOWLEDGED");
        when(service.recordAction(request, 1L)).thenReturn(action);

        BillingReconciliationAnomalyAction body = controller.recordAction(userSession(1L, "ADMIN"), request);

        assertEquals("PAID_ORDER_LEDGER:88", body.getAnomalyKey());
        assertEquals("ACKNOWLEDGED", body.getStatus());
        verify(service).recordAction(request, 1L);
    }

    @Test
    void onlyAdminCanListAnomalyActions() {
        BillingReconciliationAnomalyActionService service = mock(BillingReconciliationAnomalyActionService.class);
        BillingReconciliationAnomalyActionController controller = new BillingReconciliationAnomalyActionController(
                service, new CurrentUserService());

        assertThrows(ResponseStatusException.class,
                () -> controller.listActions(userSession(1001L, "USER"),
                        "PAID_ORDER_LEDGER", 2002L, "IGNORED", 120L, 50));

        BillingReconciliationAnomalyActionService.ActionPage page =
                new BillingReconciliationAnomalyActionService.ActionPage(java.util.List.of(), 50, false, null);
        when(service.listActions("PAID_ORDER_LEDGER", 2002L, "IGNORED", 120L, 50)).thenReturn(page);

        BillingReconciliationAnomalyActionService.ActionPage body = controller.listActions(userSession(1L, "ADMIN"),
                "PAID_ORDER_LEDGER", 2002L, "IGNORED", 120L, 50);

        assertEquals(50, body.limit());
        verify(service).listActions("PAID_ORDER_LEDGER", 2002L, "IGNORED", 120L, 50);
    }

    private MockHttpSession userSession(long userId, String role) {
        CurrentUserService currentUserService = new CurrentUserService();
        MockHttpSession session = new MockHttpSession();
        currentUserService.bind(session, new AuthService.AuthUser(userId, "user" + userId, "User " + userId, role, 1L));
        return session;
    }
}
