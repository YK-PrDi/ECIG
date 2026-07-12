package com.elebusiness.service.billing;

import com.elebusiness.model.entity.BillingReconciliationAnomalyAction;
import com.elebusiness.repository.BillingReconciliationAnomalyActionRepository;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.SliceImpl;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BillingReconciliationAnomalyActionServiceTest {

    @Test
    void recordActionCreatesStableAnomalyKeyAndUpsertsExistingRecord() {
        BillingReconciliationAnomalyActionRepository repository = mock(BillingReconciliationAnomalyActionRepository.class);
        BillingReconciliationAnomalyActionService service = new BillingReconciliationAnomalyActionService(repository);
        BillingReconciliationAnomalyAction existing = new BillingReconciliationAnomalyAction();
        existing.setId(9L);
        existing.setAnomalyKey("USAGE_CHARGE_LEDGER:77");
        existing.setType("USAGE_CHARGE_LEDGER");
        existing.setSourceId(77L);
        existing.setStatus("ACKNOWLEDGED");
        when(repository.findByAnomalyKey("USAGE_CHARGE_LEDGER:77")).thenReturn(Optional.of(existing));
        when(repository.save(any(BillingReconciliationAnomalyAction.class))).thenAnswer(invocation -> invocation.getArgument(0));

        BillingReconciliationAnomalyAction action = service.recordAction(new BillingReconciliationAnomalyActionService.ActionRequest(
                "usage_charge_ledger", 77L, 2002L, "task-77", "resolved", "已补齐扣费流水"
        ), 1L);

        assertEquals(9L, action.getId());
        assertEquals("USAGE_CHARGE_LEDGER:77", action.getAnomalyKey());
        assertEquals("RESOLVED", action.getStatus());
        assertEquals("已补齐扣费流水", action.getNote());
        assertEquals(1L, action.getOperatorUserId());
        verify(repository).findByAnomalyKey("USAGE_CHARGE_LEDGER:77");
        verify(repository).save(existing);
    }

    @Test
    void recordActionRejectsUnknownStatus() {
        BillingReconciliationAnomalyActionService service = new BillingReconciliationAnomalyActionService(
                mock(BillingReconciliationAnomalyActionRepository.class));

        assertThrows(ResponseStatusException.class, () -> service.recordAction(
                new BillingReconciliationAnomalyActionService.ActionRequest(
                        "PAID_ORDER_LEDGER", 88L, 2002L, "R202607100001", "DONE", "invalid"),
                1L));
    }

    @Test
    void listActionsUsesCursorPaginationAndCapsLimit() {
        BillingReconciliationAnomalyActionRepository repository = mock(BillingReconciliationAnomalyActionRepository.class);
        BillingReconciliationAnomalyActionService service = new BillingReconciliationAnomalyActionService(repository);
        BillingReconciliationAnomalyAction action = new BillingReconciliationAnomalyAction();
        action.setId(66L);
        action.setType("PAID_ORDER_LEDGER");
        action.setSourceId(88L);
        action.setUserId(2002L);
        action.setStatus("IGNORED");
        action.setReferenceNo("R202607100001");
        when(repository.searchActions("PAID_ORDER_LEDGER", 2002L, "IGNORED", 120L, PageRequest.of(0, 200)))
                .thenReturn(new SliceImpl<>(List.of(action), PageRequest.of(0, 200), true));

        BillingReconciliationAnomalyActionService.ActionPage page = service.listActions(
                "paid_order_ledger", 2002L, "ignored", 120L, 500);

        assertEquals(1, page.items().size());
        assertEquals(true, page.hasMore());
        assertEquals(66L, page.nextCursor());
        assertEquals(200, page.limit());
        verify(repository).searchActions("PAID_ORDER_LEDGER", 2002L, "IGNORED", 120L, PageRequest.of(0, 200));
    }
}
