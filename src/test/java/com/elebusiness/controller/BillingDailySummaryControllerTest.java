package com.elebusiness.controller;

import com.elebusiness.model.entity.BillingDailySummary;
import com.elebusiness.service.auth.AuthService;
import com.elebusiness.service.auth.CurrentUserService;
import com.elebusiness.service.billing.BillingDailySummaryService;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BillingDailySummaryControllerTest {

    @Test
    void onlyAdminCanRefreshDailySummarySnapshot() {
        BillingDailySummaryService service = mock(BillingDailySummaryService.class);
        BillingDailySummaryController controller = new BillingDailySummaryController(service, new CurrentUserService());

        assertThrows(ResponseStatusException.class,
                () -> controller.refreshDailySummary(userSession(1001L, "USER"), "2026-07-10", 2002L));

        BillingDailySummary summary = new BillingDailySummary();
        summary.setScopeUserId(2002L);
        summary.setSummaryDate(LocalDate.of(2026, 7, 10));
        summary.setInPoints(300L);
        summary.setOutPoints(80L);
        summary.setLedgerCount(6L);
        when(service.refreshDailySummary(LocalDate.of(2026, 7, 10), 2002L)).thenReturn(summary);

        ResponseEntity<Map<String, Object>> response = controller.refreshDailySummary(
                userSession(1L, "ADMIN"), "2026-07-10", 2002L);

        assertEquals(200, response.getStatusCode().value());
        assertEquals(true, response.getBody().get("success"));
        Map<?, ?> body = (Map<?, ?>) response.getBody().get("summary");
        assertEquals(2002L, body.get("userId"));
        assertEquals("2026-07-10", body.get("summaryDate"));
        assertEquals(300L, body.get("inPoints"));
        verify(service).refreshDailySummary(LocalDate.of(2026, 7, 10), 2002L);
    }

    @Test
    void onlyAdminCanRefreshDailySummarySnapshotRange() {
        BillingDailySummaryService service = mock(BillingDailySummaryService.class);
        BillingDailySummaryController controller = new BillingDailySummaryController(service, new CurrentUserService());

        assertThrows(ResponseStatusException.class,
                () -> controller.refreshDailySummaryRange(
                        userSession(1001L, "USER"), "2026-07-10", "2026-07-11", 2002L));

        BillingDailySummaryService.RefreshRangeResult result = new BillingDailySummaryService.RefreshRangeResult(
                2,
                2,
                0,
                List.of(
                        new BillingDailySummaryService.RefreshDayResult(LocalDate.of(2026, 7, 10), "SUCCESS", null, 2002L),
                        new BillingDailySummaryService.RefreshDayResult(LocalDate.of(2026, 7, 11), "SUCCESS", null, 2002L)
                )
        );
        when(service.refreshDailySummaryRange(LocalDate.of(2026, 7, 10), LocalDate.of(2026, 7, 11), 2002L))
                .thenReturn(result);

        ResponseEntity<Map<String, Object>> response = controller.refreshDailySummaryRange(
                userSession(1L, "ADMIN"), "2026-07-10", "2026-07-11", 2002L);

        assertEquals(200, response.getStatusCode().value());
        assertEquals(true, response.getBody().get("success"));
        assertEquals(2L, response.getBody().get("requestedDays"));
        assertEquals(2L, response.getBody().get("successDays"));
        List<?> days = (List<?>) response.getBody().get("days");
        Map<?, ?> firstDay = (Map<?, ?>) days.get(0);
        assertEquals("2026-07-10", firstDay.get("date"));
        assertEquals("SUCCESS", firstDay.get("status"));
        assertEquals(2002L, firstDay.get("userId"));
        verify(service).refreshDailySummaryRange(LocalDate.of(2026, 7, 10), LocalDate.of(2026, 7, 11), 2002L);
    }

    private MockHttpSession userSession(long userId, String role) {
        CurrentUserService currentUserService = new CurrentUserService();
        MockHttpSession session = new MockHttpSession();
        currentUserService.bind(session, new AuthService.AuthUser(userId, "user" + userId, "User " + userId, role));
        return session;
    }
}
