package com.elebusiness.controller;

import com.elebusiness.model.entity.BillingSchedulerState;
import com.elebusiness.repository.BillingSchedulerStateRepository;
import com.elebusiness.service.auth.AuthService;
import com.elebusiness.service.auth.CurrentUserService;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BillingSchedulerStateControllerTest {

    @Test
    void onlyAdminCanListSchedulerStatesWithLeaseStatus() {
        BillingSchedulerStateRepository repository = mock(BillingSchedulerStateRepository.class);
        BillingSchedulerStateController controller = new BillingSchedulerStateController(
                repository, new CurrentUserService());

        assertThrows(ResponseStatusException.class,
                () -> controller.listStates(userSession(1001L, "USER")));

        BillingSchedulerState state = new BillingSchedulerState();
        state.setId(9L);
        state.setStateKey("BILLING_RECONCILIATION_USER_BATCH");
        state.setLastUserId(1002L);
        state.setLeaseOwner("node-a");
        state.setLeaseUntil(LocalDateTime.now().plusMinutes(5));
        state.setUpdatedAt(LocalDateTime.of(2026, 7, 10, 7, 30));
        when(repository.findAll()).thenReturn(List.of(state));

        Map<String, Object> body = controller.listStates(userSession(1L, "ADMIN"));

        List<?> items = (List<?>) body.get("items");
        Map<?, ?> item = (Map<?, ?>) items.get(0);
        assertEquals(1, items.size());
        assertEquals("BILLING_RECONCILIATION_USER_BATCH", item.get("stateKey"));
        assertEquals(1002L, item.get("lastUserId"));
        assertEquals("node-a", item.get("leaseOwner"));
        assertEquals(true, item.get("leaseActive"));
        verify(repository).findAll();
    }

    private MockHttpSession userSession(long userId, String role) {
        CurrentUserService currentUserService = new CurrentUserService();
        MockHttpSession session = new MockHttpSession();
        currentUserService.bind(session, new AuthService.AuthUser(userId, "user" + userId, "User " + userId, role, 1L));
        return session;
    }
}
