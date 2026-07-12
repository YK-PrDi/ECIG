package com.elebusiness.service.billing;

import com.elebusiness.model.entity.GenerationUsageLog;
import com.elebusiness.model.entity.GenerationProviderTaskRef;
import com.elebusiness.model.entity.UserWallet;
import com.elebusiness.model.entity.WalletLedger;
import com.elebusiness.repository.GenerationProviderTaskRefRepository;
import com.elebusiness.repository.GenerationUsageLogRepository;
import com.elebusiness.repository.UserWalletRepository;
import com.elebusiness.repository.WalletLedgerRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BillingServiceTest {

    @Mock
    UserWalletRepository walletRepository;

    @Mock
    WalletLedgerRepository ledgerRepository;

    @Mock
    GenerationUsageLogRepository usageLogRepository;

    @Mock
    GenerationProviderTaskRefRepository providerTaskRefRepository;

    @InjectMocks
    BillingService billingService;

    @Test
    void startingGenerationEnsuresWalletAndWritesUsageLogWithoutCharging() {
        when(walletRepository.findByUserId(7L)).thenReturn(Optional.empty());
        when(walletRepository.save(any(UserWallet.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(usageLogRepository.save(any(GenerationUsageLog.class))).thenAnswer(invocation -> invocation.getArgument(0));

        GenerationUsageLog log = billingService.recordGenerationStarted(
                7L, "task-123", "custom", "liblib-lora", 0);

        ArgumentCaptor<UserWallet> walletCaptor = ArgumentCaptor.forClass(UserWallet.class);
        verify(walletRepository).save(walletCaptor.capture());
        assertEquals(7L, walletCaptor.getValue().getUserId());
        assertEquals(0L, walletCaptor.getValue().getBalancePoints());
        assertEquals(0L, walletCaptor.getValue().getFrozenPoints());

        assertEquals(7L, log.getUserId());
        assertEquals("task-123", log.getTaskId());
        assertEquals("custom", log.getMode());
        assertEquals("liblib-lora", log.getAgentId());
        assertEquals("STARTED", log.getStatus());
        assertEquals(0, log.getEstimatedPoints());
        assertEquals(0, log.getActualPoints());
        assertNotNull(log.getStartedAt());
    }

    @Test
    void creditPointsIncreasesWalletBalanceAndWritesLedgerSnapshot() {
        UserWallet wallet = wallet(7L, 10, 0);
        when(walletRepository.findByUserId(7L)).thenReturn(Optional.of(wallet));
        when(walletRepository.save(any(UserWallet.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(ledgerRepository.save(any(WalletLedger.class))).thenAnswer(invocation -> invocation.getArgument(0));

        billingService.creditPoints(7L, 90, "ADMIN_RECHARGE", "initial recharge", "recharge-7-1");

        assertEquals(100, wallet.getBalancePoints());
        assertEquals(0, wallet.getFrozenPoints());

        ArgumentCaptor<WalletLedger> ledgerCaptor = ArgumentCaptor.forClass(WalletLedger.class);
        verify(ledgerRepository).save(ledgerCaptor.capture());
        WalletLedger ledger = ledgerCaptor.getValue();
        assertEquals(7L, ledger.getUserId());
        assertEquals("ADMIN_RECHARGE", ledger.getType());
        assertEquals("IN", ledger.getDirection());
        assertEquals(90, ledger.getPointsDelta());
        assertEquals(10, ledger.getBalanceBefore());
        assertEquals(100, ledger.getBalanceAfter());
        assertEquals(0, ledger.getFrozenBefore());
        assertEquals(0, ledger.getFrozenAfter());
        assertEquals("recharge-7-1", ledger.getIdempotencyKey());
    }

    @Test
    void startingGenerationWithEstimatedPointsFreezesAvailableBalanceAndWritesLedger() {
        UserWallet wallet = wallet(7L, 100, 10);
        when(walletRepository.findByUserId(7L)).thenReturn(Optional.of(wallet));
        when(walletRepository.save(any(UserWallet.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(usageLogRepository.save(any(GenerationUsageLog.class))).thenAnswer(invocation -> {
            GenerationUsageLog log = invocation.getArgument(0);
            log.setId(55L);
            return log;
        });
        when(ledgerRepository.save(any(WalletLedger.class))).thenAnswer(invocation -> invocation.getArgument(0));

        GenerationUsageLog log = billingService.recordGenerationStarted(
                7L, "task-123", "custom", "liblib-lora", 30);

        assertEquals(100, wallet.getBalancePoints());
        assertEquals(40, wallet.getFrozenPoints());
        assertEquals(30, log.getEstimatedPoints());

        ArgumentCaptor<WalletLedger> ledgerCaptor = ArgumentCaptor.forClass(WalletLedger.class);
        verify(ledgerRepository).save(ledgerCaptor.capture());
        WalletLedger ledger = ledgerCaptor.getValue();
        assertEquals("GENERATION_FREEZE", ledger.getType());
        assertEquals("FREEZE", ledger.getDirection());
        assertEquals(30, ledger.getPointsDelta());
        assertEquals(100, ledger.getBalanceBefore());
        assertEquals(100, ledger.getBalanceAfter());
        assertEquals(10, ledger.getFrozenBefore());
        assertEquals(40, ledger.getFrozenAfter());
        assertEquals("usage:55:freeze", ledger.getIdempotencyKey());
    }

    @Test
    void succeededGenerationChargesActualPointsAndReleasesFrozenBalance() {
        GenerationUsageLog log = startedLog(55L, 7L, 30);
        UserWallet wallet = wallet(7L, 100, 30);
        when(usageLogRepository.findById(55L)).thenReturn(Optional.of(log));
        when(walletRepository.findByUserId(7L)).thenReturn(Optional.of(wallet));
        when(walletRepository.save(any(UserWallet.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(ledgerRepository.save(any(WalletLedger.class))).thenAnswer(invocation -> invocation.getArgument(0));

        billingService.markGenerationSucceeded(55L, 25);

        assertEquals("SUCCEEDED", log.getStatus());
        assertEquals(25, log.getActualPoints());
        assertNotNull(log.getFinishedAt());
        assertEquals(75, wallet.getBalancePoints());
        assertEquals(0, wallet.getFrozenPoints());

        ArgumentCaptor<WalletLedger> ledgerCaptor = ArgumentCaptor.forClass(WalletLedger.class);
        verify(ledgerRepository).save(ledgerCaptor.capture());
        WalletLedger ledger = ledgerCaptor.getValue();
        assertEquals("GENERATION_CHARGE", ledger.getType());
        assertEquals("OUT", ledger.getDirection());
        assertEquals(-25, ledger.getPointsDelta());
        assertEquals(100, ledger.getBalanceBefore());
        assertEquals(75, ledger.getBalanceAfter());
        assertEquals(30, ledger.getFrozenBefore());
        assertEquals(0, ledger.getFrozenAfter());
        assertEquals("usage:55:charge", ledger.getIdempotencyKey());
    }

    @Test
    void succeededGenerationWithoutActualPointsChargesEstimatedPoints() {
        GenerationUsageLog log = startedLog(55L, 7L, 30);
        UserWallet wallet = wallet(7L, 100, 30);
        when(usageLogRepository.findById(55L)).thenReturn(Optional.of(log));
        when(walletRepository.findByUserId(7L)).thenReturn(Optional.of(wallet));
        when(walletRepository.save(any(UserWallet.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(ledgerRepository.save(any(WalletLedger.class))).thenAnswer(invocation -> invocation.getArgument(0));

        billingService.markGenerationSucceeded(55L, 0);

        assertEquals("SUCCEEDED", log.getStatus());
        assertEquals(30, log.getActualPoints());
        assertEquals(70, wallet.getBalancePoints());
        assertEquals(0, wallet.getFrozenPoints());

        ArgumentCaptor<WalletLedger> ledgerCaptor = ArgumentCaptor.forClass(WalletLedger.class);
        verify(ledgerRepository).save(ledgerCaptor.capture());
        WalletLedger ledger = ledgerCaptor.getValue();
        assertEquals("GENERATION_CHARGE", ledger.getType());
        assertEquals(-30, ledger.getPointsDelta());
        assertEquals(100, ledger.getBalanceBefore());
        assertEquals(70, ledger.getBalanceAfter());
    }

    @Test
    void succeededGenerationStoresProviderCostMetadataAndChargesConvertedPoints() {
        GenerationUsageLog log = startedLog(55L, 7L, 30);
        UserWallet wallet = wallet(7L, 100, 30);
        when(usageLogRepository.findById(55L)).thenReturn(Optional.of(log));
        when(walletRepository.findByUserId(7L)).thenReturn(Optional.of(wallet));
        when(walletRepository.save(any(UserWallet.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(ledgerRepository.save(any(WalletLedger.class))).thenAnswer(invocation -> invocation.getArgument(0));

        billingService.markGenerationSucceeded(55L, new BillingService.ProviderCost(
                18,
                "liblib-generate-001",
                new BigDecimal("12.500000"),
                "LIBLIB_POINTS",
                "PROVIDER_REPORTED",
                new BigDecimal("1.440000")
        ));

        assertEquals("SUCCEEDED", log.getStatus());
        assertEquals(18, log.getActualPoints());
        assertEquals("liblib-generate-001", log.getProviderTaskId());
        assertEquals(new BigDecimal("12.500000"), log.getProviderRawCost());
        assertEquals("LIBLIB_POINTS", log.getProviderRawUnit());
        assertEquals("PROVIDER_REPORTED", log.getCostSource());
        assertEquals(new BigDecimal("1.440000"), log.getExchangeRate());
        assertEquals(82, wallet.getBalancePoints());
        assertEquals(0, wallet.getFrozenPoints());

        ArgumentCaptor<WalletLedger> ledgerCaptor = ArgumentCaptor.forClass(WalletLedger.class);
        verify(ledgerRepository).save(ledgerCaptor.capture());
        assertEquals(-18, ledgerCaptor.getValue().getPointsDelta());
    }

    @Test
    void succeededGenerationWritesOneProviderTaskRefPerProviderTaskIdForExactLookup() {
        GenerationUsageLog log = startedLog(55L, 7L, 30);
        UserWallet wallet = wallet(7L, 100, 30);
        when(usageLogRepository.findById(55L)).thenReturn(Optional.of(log));
        when(walletRepository.findByUserId(7L)).thenReturn(Optional.of(wallet));
        when(walletRepository.save(any(UserWallet.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(ledgerRepository.save(any(WalletLedger.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(providerTaskRefRepository.findByProviderAndProviderTaskId("liblib", "uuid-001")).thenReturn(Optional.empty());
        when(providerTaskRefRepository.findByProviderAndProviderTaskId("liblib", "uuid-002")).thenReturn(Optional.empty());
        when(providerTaskRefRepository.save(any(GenerationProviderTaskRef.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        billingService.markGenerationSucceeded(55L, new BillingService.ProviderCost(
                0,
                "uuid-001,uuid-002,uuid-001",
                null,
                null,
                "PROVIDER_TASK_REPORTED",
                null
        ));

        ArgumentCaptor<GenerationProviderTaskRef> refCaptor = ArgumentCaptor.forClass(GenerationProviderTaskRef.class);
        verify(providerTaskRefRepository, org.mockito.Mockito.times(2)).save(refCaptor.capture());
        assertEquals("uuid-001", refCaptor.getAllValues().get(0).getProviderTaskId());
        assertEquals("uuid-002", refCaptor.getAllValues().get(1).getProviderTaskId());
        assertEquals("liblib", refCaptor.getAllValues().get(0).getProvider());
        assertEquals(55L, refCaptor.getAllValues().get(0).getUsageLogId());
        assertEquals(7L, refCaptor.getAllValues().get(0).getUserId());
    }

    @Test
    void succeededGenerationCannotSpendPointsFrozenByOtherTasks() {
        GenerationUsageLog log = startedLog(55L, 7L, 30);
        UserWallet wallet = wallet(7L, 100, 90);
        when(usageLogRepository.findById(55L)).thenReturn(Optional.of(log));
        when(walletRepository.findByUserId(7L)).thenReturn(Optional.of(wallet));

        assertThrows(BillingService.InsufficientBalanceException.class,
                () -> billingService.markGenerationSucceeded(55L, 70));

        assertEquals("STARTED", log.getStatus());
        assertEquals(100, wallet.getBalancePoints());
        assertEquals(90, wallet.getFrozenPoints());
        verify(walletRepository, never()).save(any(UserWallet.class));
        verify(ledgerRepository, never()).save(any(WalletLedger.class));
    }

    @Test
    void failedGenerationReleasesFrozenBalanceAndWritesLedger() {
        GenerationUsageLog log = startedLog(55L, 7L, 30);
        UserWallet wallet = wallet(7L, 100, 30);
        when(usageLogRepository.findById(55L)).thenReturn(Optional.of(log));
        when(walletRepository.findByUserId(7L)).thenReturn(Optional.of(wallet));
        when(walletRepository.save(any(UserWallet.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(ledgerRepository.save(any(WalletLedger.class))).thenAnswer(invocation -> invocation.getArgument(0));

        billingService.markGenerationFailed(55L, "provider failed");

        assertEquals("FAILED", log.getStatus());
        assertEquals("provider failed", log.getErrorMessage());
        assertEquals(100, wallet.getBalancePoints());
        assertEquals(0, wallet.getFrozenPoints());

        ArgumentCaptor<WalletLedger> ledgerCaptor = ArgumentCaptor.forClass(WalletLedger.class);
        verify(ledgerRepository).save(ledgerCaptor.capture());
        WalletLedger ledger = ledgerCaptor.getValue();
        assertEquals("GENERATION_RELEASE", ledger.getType());
        assertEquals("RELEASE", ledger.getDirection());
        assertEquals(30, ledger.getPointsDelta());
        assertEquals(100, ledger.getBalanceBefore());
        assertEquals(100, ledger.getBalanceAfter());
        assertEquals(30, ledger.getFrozenBefore());
        assertEquals(0, ledger.getFrozenAfter());
        assertEquals("usage:55:release", ledger.getIdempotencyKey());
    }

    @Test
    void terminalUsageLogIsNotChargedTwice() {
        GenerationUsageLog log = startedLog(55L, 7L, 30);
        log.setStatus("SUCCEEDED");
        when(usageLogRepository.findById(55L)).thenReturn(Optional.of(log));

        billingService.markGenerationSucceeded(55L, 25);

        verify(walletRepository, never()).save(any(UserWallet.class));
        verify(ledgerRepository, never()).save(any(WalletLedger.class));
    }

    private UserWallet wallet(long userId, long balance, long frozen) {
        UserWallet wallet = new UserWallet();
        wallet.setUserId(userId);
        wallet.setBalancePoints(balance);
        wallet.setFrozenPoints(frozen);
        return wallet;
    }

    private GenerationUsageLog startedLog(long id, long userId, int estimatedPoints) {
        GenerationUsageLog log = new GenerationUsageLog();
        log.setId(id);
        log.setUserId(userId);
        log.setTaskId("task-123");
        log.setMode("custom");
        log.setAgentId("liblib-lora");
        log.setProvider("liblib");
        log.setEstimatedPoints(estimatedPoints);
        log.setActualPoints(0);
        log.setStatus("STARTED");
        return log;
    }
}
