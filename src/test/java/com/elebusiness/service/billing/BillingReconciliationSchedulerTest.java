package com.elebusiness.service.billing;

import com.elebusiness.config.AppProperties;
import com.elebusiness.model.entity.BillingSchedulerState;
import com.elebusiness.repository.BillingSchedulerStateRepository;
import com.elebusiness.repository.UserWalletRepository;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageRequest;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class BillingReconciliationSchedulerTest {

    @Test
    void scheduledReconciliationDoesNothingWhenDisabled() {
        BillingReconciliationRunService runService = mock(BillingReconciliationRunService.class);
        AppProperties properties = new AppProperties();
        properties.getBilling().setReconciliationScheduleEnabled(false);
        BillingReconciliationScheduler scheduler = new BillingReconciliationScheduler(runService, properties);

        scheduler.runScheduledReconciliation();

        verifyNoInteractions(runService);
    }

    @Test
    void scheduledReconciliationRecordsGlobalRunWhenEnabled() {
        BillingReconciliationRunService runService = mock(BillingReconciliationRunService.class);
        AppProperties properties = new AppProperties();
        properties.getBilling().setReconciliationScheduleEnabled(true);
        BillingReconciliationScheduler scheduler = new BillingReconciliationScheduler(runService, properties);

        scheduler.runScheduledReconciliation();

        verify(runService).runAndRecord(null, "SCHEDULED", null);
    }

    @Test
    void scheduledReconciliationRunsOneWalletUserBatchAndStoresNextCursor() {
        BillingReconciliationRunService runService = mock(BillingReconciliationRunService.class);
        UserWalletRepository walletRepository = mock(UserWalletRepository.class);
        BillingSchedulerStateRepository stateRepository = mock(BillingSchedulerStateRepository.class);
        AppProperties properties = new AppProperties();
        properties.getBilling().setReconciliationScheduleEnabled(true);
        properties.getBilling().setReconciliationScheduleMode("USER_BATCH");
        properties.getBilling().setReconciliationUserBatchSize(2);
        List<BillingSchedulerState> savedSnapshots = captureSavedStates(stateRepository);
        when(walletRepository.findUserIdsAfter(null, PageRequest.of(0, 2)))
                .thenReturn(List.of(1001L, 1002L));
        BillingReconciliationScheduler scheduler = new BillingReconciliationScheduler(
                runService, walletRepository, stateRepository, properties);

        scheduler.runScheduledReconciliation();

        verify(walletRepository).findUserIdsAfter(null, PageRequest.of(0, 2));
        verify(runService).runAndRecord(1001L, "SCHEDULED_USER_BATCH", null);
        verify(runService).runAndRecord(1002L, "SCHEDULED_USER_BATCH", null);
        assertTrue(savedSnapshots.stream().anyMatch(state ->
                "BILLING_RECONCILIATION_USER_BATCH".equals(state.getStateKey())
                        && Long.valueOf(1002L).equals(state.getLastUserId())
                        && state.getLeaseOwner() == null
                        && state.getLeaseUntil() == null));
        assertTrue(savedSnapshots.stream().anyMatch(state ->
                "BILLING_RECONCILIATION_USER_BATCH".equals(state.getStateKey())
                        && state.getLeaseOwner() != null
                        && state.getLeaseUntil() != null));
        verify(runService, never()).runAndRecord(null, "SCHEDULED", null);
    }

    @Test
    void scheduledUserBatchSkipsWhenAnotherInstanceLeaseIsStillActive() {
        BillingReconciliationRunService runService = mock(BillingReconciliationRunService.class);
        UserWalletRepository walletRepository = mock(UserWalletRepository.class);
        BillingSchedulerStateRepository stateRepository = mock(BillingSchedulerStateRepository.class);
        BillingSchedulerState state = new BillingSchedulerState();
        state.setStateKey("BILLING_RECONCILIATION_USER_BATCH");
        state.setLeaseOwner("node-a");
        state.setLeaseUntil(LocalDateTime.now().plusMinutes(5));
        AppProperties properties = new AppProperties();
        properties.getBilling().setReconciliationScheduleEnabled(true);
        properties.getBilling().setReconciliationScheduleMode("USER_BATCH");
        when(stateRepository.findByStateKey("BILLING_RECONCILIATION_USER_BATCH"))
                .thenReturn(java.util.Optional.of(state));
        BillingReconciliationScheduler scheduler = new BillingReconciliationScheduler(
                runService, walletRepository, stateRepository, properties);

        scheduler.runScheduledReconciliation();

        verifyNoInteractions(walletRepository);
        verifyNoInteractions(runService);
    }

    @Test
    void scheduledUserBatchContinuesWhenOneUserFails() {
        BillingReconciliationRunService runService = mock(BillingReconciliationRunService.class);
        UserWalletRepository walletRepository = mock(UserWalletRepository.class);
        BillingSchedulerStateRepository stateRepository = mock(BillingSchedulerStateRepository.class);
        AppProperties properties = new AppProperties();
        properties.getBilling().setReconciliationScheduleEnabled(true);
        properties.getBilling().setReconciliationScheduleMode("USER_BATCH");
        properties.getBilling().setReconciliationUserBatchSize(50);
        when(walletRepository.findUserIdsAfter(null, PageRequest.of(0, 50)))
                .thenReturn(List.of(1001L, 1002L));
        when(runService.runAndRecord(1001L, "SCHEDULED_USER_BATCH", null))
                .thenThrow(new IllegalStateException("user 1001 unavailable"));
        BillingReconciliationScheduler scheduler = new BillingReconciliationScheduler(
                runService, walletRepository, stateRepository, properties);

        assertDoesNotThrow(scheduler::runScheduledReconciliation);

        verify(runService).runAndRecord(1001L, "SCHEDULED_USER_BATCH", null);
        verify(runService).runAndRecord(1002L, "SCHEDULED_USER_BATCH", null);
    }

    @Test
    void scheduledUserBatchStartsAfterPersistedCursorAndResetsWhenShortPageMeansCycleEnd() {
        BillingReconciliationRunService runService = mock(BillingReconciliationRunService.class);
        UserWalletRepository walletRepository = mock(UserWalletRepository.class);
        BillingSchedulerStateRepository stateRepository = mock(BillingSchedulerStateRepository.class);
        BillingSchedulerState state = new BillingSchedulerState();
        state.setStateKey("BILLING_RECONCILIATION_USER_BATCH");
        state.setLastUserId(1002L);
        AppProperties properties = new AppProperties();
        properties.getBilling().setReconciliationScheduleEnabled(true);
        properties.getBilling().setReconciliationScheduleMode("USER_BATCH");
        properties.getBilling().setReconciliationUserBatchSize(2);
        List<BillingSchedulerState> savedSnapshots = captureSavedStates(stateRepository);
        when(stateRepository.findByStateKey("BILLING_RECONCILIATION_USER_BATCH"))
                .thenReturn(java.util.Optional.of(state));
        when(walletRepository.findUserIdsAfter(1002L, PageRequest.of(0, 2)))
                .thenReturn(List.of(1005L));
        BillingReconciliationScheduler scheduler = new BillingReconciliationScheduler(
                runService, walletRepository, stateRepository, properties);

        scheduler.runScheduledReconciliation();

        verify(walletRepository).findUserIdsAfter(1002L, PageRequest.of(0, 2));
        verify(runService).runAndRecord(1005L, "SCHEDULED_USER_BATCH", null);
        assertTrue(savedSnapshots.stream().anyMatch(saved ->
                "BILLING_RECONCILIATION_USER_BATCH".equals(saved.getStateKey())
                        && saved.getLastUserId() == null
                        && saved.getLeaseOwner() == null
                        && saved.getLeaseUntil() == null));
    }

    @Test
    void scheduledReconciliationSwallowsRecordedFailureSoFutureSchedulesContinue() {
        BillingReconciliationRunService runService = mock(BillingReconciliationRunService.class);
        when(runService.runAndRecord(null, "SCHEDULED", null))
                .thenThrow(new IllegalStateException("database unavailable"));
        AppProperties properties = new AppProperties();
        properties.getBilling().setReconciliationScheduleEnabled(true);
        BillingReconciliationScheduler scheduler = new BillingReconciliationScheduler(runService, properties);

        assertDoesNotThrow(scheduler::runScheduledReconciliation);

        verify(runService).runAndRecord(null, "SCHEDULED", null);
    }

    private List<BillingSchedulerState> captureSavedStates(BillingSchedulerStateRepository repository) {
        List<BillingSchedulerState> snapshots = new ArrayList<>();
        when(repository.save(any(BillingSchedulerState.class))).thenAnswer(invocation -> {
            BillingSchedulerState source = invocation.getArgument(0);
            BillingSchedulerState snapshot = new BillingSchedulerState();
            snapshot.setStateKey(source.getStateKey());
            snapshot.setLastUserId(source.getLastUserId());
            snapshot.setLeaseOwner(source.getLeaseOwner());
            snapshot.setLeaseUntil(source.getLeaseUntil());
            snapshots.add(snapshot);
            return source;
        });
        return snapshots;
    }
}
