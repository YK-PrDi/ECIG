package com.elebusiness.service.billing;

import com.elebusiness.model.entity.BillingReconciliationRun;
import com.elebusiness.repository.BillingReconciliationRunRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.SliceImpl;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BillingReconciliationRunServiceTest {
    @Test
    void springCanCreateRunServiceUsingRepositoryConstructor() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            BillingReconciliationService reconciliationService = mock(BillingReconciliationService.class);
            BillingReconciliationRunRepository runRepository = mock(BillingReconciliationRunRepository.class);
            context.registerBean(BillingReconciliationService.class, () -> reconciliationService);
            context.registerBean(BillingReconciliationRunRepository.class, () -> runRepository);
            context.register(BillingReconciliationRunService.class);

            context.refresh();

            assertNotNull(context.getBean(BillingReconciliationRunService.class));
        }
    }
    @Test
    void runAndRecordDoesNotUseRollbackingOuterTransactionSoFailedRunsCanPersist() throws Exception {
        Transactional transactional = BillingReconciliationRunService.class
                .getMethod("runAndRecord", Long.class, String.class, Long.class)
                .getAnnotation(Transactional.class);

        assertNull(transactional);
    }

    @Test
    void runAndRecordSavesSuccessfulRunWithScopedUserAndAnomalyTotals() {
        BillingReconciliationService reconciliationService = mock(BillingReconciliationService.class);
        BillingReconciliationRunRepository runRepository = mock(BillingReconciliationRunRepository.class);
        BillingReconciliationRunService service = new BillingReconciliationRunService(reconciliationService, runRepository);
        BillingReconciliationService.Report report = new BillingReconciliationService.Report(
                2002L,
                false,
                List.of(
                        new BillingReconciliationService.Check("WALLET_BALANCE", "钱包余额", 100L, 120L, 20L, false),
                        new BillingReconciliationService.Check("PAID_ORDER_LEDGER", "支付流水", 3L, 2L, 1L, false)
                )
        );
        when(reconciliationService.reconcile(2002L)).thenReturn(report);
        when(runRepository.save(any(BillingReconciliationRun.class))).thenAnswer(invocation -> {
            BillingReconciliationRun run = invocation.getArgument(0);
            run.setId(88L);
            return run;
        });

        BillingReconciliationRunService.RunResult result = service.runAndRecord(2002L, "manual", 1L);

        ArgumentCaptor<BillingReconciliationRun> captor = ArgumentCaptor.forClass(BillingReconciliationRun.class);
        verify(runRepository).save(captor.capture());
        BillingReconciliationRun saved = captor.getValue();
        assertEquals(88L, result.run().getId());
        assertEquals(report, result.report());
        assertEquals(2002L, saved.getScopeUserId());
        assertEquals(1L, saved.getTriggeredByUserId());
        assertEquals("MANUAL", saved.getTriggerType());
        assertEquals("SUCCESS", saved.getStatus());
        assertFalse(saved.isHealthy());
        assertEquals(2L, saved.getCheckCount());
        assertEquals(21L, saved.getAnomalyCount());
        assertNotNull(saved.getRunId());
        assertNotNull(saved.getStartedAt());
        assertNotNull(saved.getFinishedAt());
        assertTrue(saved.getDurationMillis() >= 0);
        assertTrue(saved.getReportJson().contains("WALLET_BALANCE"));
    }

    @Test
    void runAndRecordSavesFailedGlobalRunBeforeRethrowing() {
        BillingReconciliationService reconciliationService = mock(BillingReconciliationService.class);
        BillingReconciliationRunRepository runRepository = mock(BillingReconciliationRunRepository.class);
        BillingReconciliationRunService service = new BillingReconciliationRunService(reconciliationService, runRepository);
        when(reconciliationService.reconcile(null)).thenThrow(new IllegalStateException("database unavailable"));
        when(runRepository.save(any(BillingReconciliationRun.class))).thenAnswer(invocation -> invocation.getArgument(0));

        IllegalStateException error = org.junit.jupiter.api.Assertions.assertThrows(
                IllegalStateException.class,
                () -> service.runAndRecord(null, "scheduled", 1L)
        );

        ArgumentCaptor<BillingReconciliationRun> captor = ArgumentCaptor.forClass(BillingReconciliationRun.class);
        verify(runRepository).save(captor.capture());
        BillingReconciliationRun saved = captor.getValue();
        assertEquals("database unavailable", error.getMessage());
        assertEquals(BillingReconciliationRunService.GLOBAL_SCOPE_USER_ID, saved.getScopeUserId());
        assertEquals("SCHEDULED", saved.getTriggerType());
        assertEquals("FAILED", saved.getStatus());
        assertFalse(saved.isHealthy());
        assertEquals(0L, saved.getCheckCount());
        assertEquals(0L, saved.getAnomalyCount());
        assertTrue(saved.getErrorMessage().contains("database unavailable"));
    }

    @Test
    void listRunsUsesKeysetPaginationAndOptionalFilters() {
        BillingReconciliationService reconciliationService = mock(BillingReconciliationService.class);
        BillingReconciliationRunRepository runRepository = mock(BillingReconciliationRunRepository.class);
        BillingReconciliationRunService service = new BillingReconciliationRunService(reconciliationService, runRepository);
        BillingReconciliationRun run = new BillingReconciliationRun();
        run.setId(66L);
        run.setScopeUserId(2002L);
        run.setStatus("SUCCESS");
        run.setTriggerType("MANUAL");
        LocalDateTime from = LocalDateTime.of(2026, 7, 10, 0, 0);
        LocalDateTime to = LocalDateTime.of(2026, 7, 11, 0, 0);
        when(runRepository.searchBeforeId(2002L, "SUCCESS", "MANUAL", from, to, 120L, PageRequest.of(0, 20)))
                .thenReturn(new SliceImpl<>(List.of(run), PageRequest.of(0, 20), true));

        BillingReconciliationRunService.RunPage page = service.listRuns(
                2002L, "success", "manual", from, to, 120L, 20);

        assertEquals(1, page.items().size());
        assertEquals(66L, page.nextCursor());
        assertTrue(page.hasMore());
        verify(runRepository).searchBeforeId(2002L, "SUCCESS", "MANUAL", from, to, 120L, PageRequest.of(0, 20));
    }
}
