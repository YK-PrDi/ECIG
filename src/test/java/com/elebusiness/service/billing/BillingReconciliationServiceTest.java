package com.elebusiness.service.billing;

import com.elebusiness.repository.GenerationUsageLogRepository;
import com.elebusiness.repository.PaymentOrderRepository;
import com.elebusiness.repository.UserWalletRepository;
import com.elebusiness.repository.WalletLedgerRepository;
import com.elebusiness.repository.BillingReconciliationAnomalyActionRepository;
import com.elebusiness.model.entity.BillingReconciliationAnomalyAction;
import com.elebusiness.model.entity.GenerationUsageLog;
import com.elebusiness.model.entity.PaymentOrder;
import com.elebusiness.model.entity.WalletLedger;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.SliceImpl;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class BillingReconciliationServiceTest {

    @Test
    void reconcileReportsHealthyWhenWalletLedgerOrdersAndUsageMatch() {
        UserWalletRepository walletRepository = mock(UserWalletRepository.class);
        WalletLedgerRepository ledgerRepository = mock(WalletLedgerRepository.class);
        GenerationUsageLogRepository usageRepository = mock(GenerationUsageLogRepository.class);
        PaymentOrderRepository orderRepository = mock(PaymentOrderRepository.class);
        BillingReconciliationService service = new BillingReconciliationService(
                walletRepository, ledgerRepository, usageRepository, orderRepository);

        when(walletRepository.summarizeWallets(1001L)).thenReturn(new Object[]{100L, 30L, 1L});
        when(ledgerRepository.sumPostedBalancePoints(1001L)).thenReturn(100L);
        when(usageRepository.sumOpenEstimatedPoints(1001L)).thenReturn(30L);
        when(orderRepository.countPaidOrders(1001L)).thenReturn(2L);
        when(ledgerRepository.countPaymentRechargeLedgers(1001L)).thenReturn(2L);
        when(usageRepository.countChargeableSucceededUsages(1001L)).thenReturn(5L);
        when(ledgerRepository.countGenerationChargeLedgers(1001L)).thenReturn(5L);

        BillingReconciliationService.Report report = service.reconcile(1001L);

        assertTrue(report.healthy());
        assertEquals(4, report.checks().size());
        assertTrue(report.checks().stream().allMatch(BillingReconciliationService.Check::ok));
    }

    @Test
    void reconcileReportsDifferencesWithoutMutatingData() {
        UserWalletRepository walletRepository = mock(UserWalletRepository.class);
        WalletLedgerRepository ledgerRepository = mock(WalletLedgerRepository.class);
        GenerationUsageLogRepository usageRepository = mock(GenerationUsageLogRepository.class);
        PaymentOrderRepository orderRepository = mock(PaymentOrderRepository.class);
        BillingReconciliationService service = new BillingReconciliationService(
                walletRepository, ledgerRepository, usageRepository, orderRepository);

        when(walletRepository.summarizeWallets(null)).thenReturn(new Object[]{120L, 50L, 3L});
        when(ledgerRepository.sumPostedBalancePoints(null)).thenReturn(100L);
        when(usageRepository.sumOpenEstimatedPoints(null)).thenReturn(30L);
        when(orderRepository.countPaidOrders(null)).thenReturn(4L);
        when(ledgerRepository.countPaymentRechargeLedgers(null)).thenReturn(3L);
        when(usageRepository.countChargeableSucceededUsages(null)).thenReturn(8L);
        when(ledgerRepository.countGenerationChargeLedgers(null)).thenReturn(6L);

        BillingReconciliationService.Report report = service.reconcile(null);

        assertFalse(report.healthy());
        assertEquals(20L, check(report, "WALLET_BALANCE").difference());
        assertEquals(20L, check(report, "WALLET_FROZEN").difference());
        assertEquals(1L, check(report, "PAID_ORDER_LEDGER").difference());
        assertEquals(2L, check(report, "USAGE_CHARGE_LEDGER").difference());
    }

    @Test
    void anomalyDetailsListPaidOrdersMissingRechargeLedgerWithCursorPagination() {
        UserWalletRepository walletRepository = mock(UserWalletRepository.class);
        WalletLedgerRepository ledgerRepository = mock(WalletLedgerRepository.class);
        GenerationUsageLogRepository usageRepository = mock(GenerationUsageLogRepository.class);
        PaymentOrderRepository orderRepository = mock(PaymentOrderRepository.class);
        BillingReconciliationService service = new BillingReconciliationService(
                walletRepository, ledgerRepository, usageRepository, orderRepository);
        PaymentOrder order = new PaymentOrder();
        order.setId(88L);
        order.setOrderNo("R202607100001");
        order.setUserId(2002L);
        order.setProvider("manual");
        order.setStatus("PAID");
        order.setPoints(100L);
        order.setAmountCents(19900L);
        when(orderRepository.findPaidOrdersMissingRechargeLedger(2002L, 120L, PageRequest.of(0, 20)))
                .thenReturn(new SliceImpl<>(List.of(order), PageRequest.of(0, 20), true));

        BillingReconciliationService.AnomalyPage page = service.anomalyDetails(
                "PAID_ORDER_LEDGER", 2002L, 120L, 20);

        assertEquals("PAID_ORDER_LEDGER", page.type());
        assertEquals(true, page.hasMore());
        assertEquals(88L, page.nextCursor());
        assertEquals(1, page.items().size());
        assertEquals("R202607100001", page.items().get(0).referenceNo());
        assertEquals(100L, page.items().get(0).points());
        assertEquals(19900L, page.items().get(0).amountCents());
        verify(orderRepository).findPaidOrdersMissingRechargeLedger(2002L, 120L, PageRequest.of(0, 20));
    }

    @Test
    void anomalyDetailsAttachActionSummaryByStableAnomalyKey() {
        UserWalletRepository walletRepository = mock(UserWalletRepository.class);
        WalletLedgerRepository ledgerRepository = mock(WalletLedgerRepository.class);
        GenerationUsageLogRepository usageRepository = mock(GenerationUsageLogRepository.class);
        PaymentOrderRepository orderRepository = mock(PaymentOrderRepository.class);
        BillingReconciliationAnomalyActionRepository actionRepository =
                mock(BillingReconciliationAnomalyActionRepository.class);
        BillingReconciliationService service = new BillingReconciliationService(
                walletRepository, ledgerRepository, usageRepository, orderRepository, actionRepository);
        PaymentOrder order = new PaymentOrder();
        order.setId(88L);
        order.setOrderNo("R202607100001");
        order.setUserId(2002L);
        order.setProvider("manual");
        order.setStatus("PAID");
        order.setPoints(100L);
        order.setAmountCents(19900L);
        BillingReconciliationAnomalyAction action = new BillingReconciliationAnomalyAction();
        action.setAnomalyKey("PAID_ORDER_LEDGER:88");
        action.setStatus("IGNORED");
        action.setNote("历史导入订单，人工确认忽略");
        action.setUpdatedAt(LocalDateTime.of(2026, 7, 10, 8, 10));
        when(orderRepository.findPaidOrdersMissingRechargeLedger(2002L, 120L, PageRequest.of(0, 20)))
                .thenReturn(new SliceImpl<>(List.of(order), PageRequest.of(0, 20), false));
        when(actionRepository.findByAnomalyKeyIn(List.of("PAID_ORDER_LEDGER:88"))).thenReturn(List.of(action));

        BillingReconciliationService.AnomalyPage page = service.anomalyDetails(
                "PAID_ORDER_LEDGER", 2002L, 120L, 20);

        BillingReconciliationService.AnomalyItem item = page.items().get(0);
        assertEquals("PAID_ORDER_LEDGER:88", item.anomalyKey());
        assertEquals("IGNORED", item.actionStatus());
        assertEquals("历史导入订单，人工确认忽略", item.actionNote());
        assertEquals(LocalDateTime.of(2026, 7, 10, 8, 10), item.actionUpdatedAt());
        verify(actionRepository).findByAnomalyKeyIn(List.of("PAID_ORDER_LEDGER:88"));
    }

    @Test
    void anomalyDetailsListSucceededUsagesMissingChargeLedgerWithCursorPagination() {
        UserWalletRepository walletRepository = mock(UserWalletRepository.class);
        WalletLedgerRepository ledgerRepository = mock(WalletLedgerRepository.class);
        GenerationUsageLogRepository usageRepository = mock(GenerationUsageLogRepository.class);
        PaymentOrderRepository orderRepository = mock(PaymentOrderRepository.class);
        BillingReconciliationService service = new BillingReconciliationService(
                walletRepository, ledgerRepository, usageRepository, orderRepository);
        GenerationUsageLog usage = new GenerationUsageLog();
        usage.setId(77L);
        usage.setTaskId("task-77");
        usage.setUserId(2002L);
        usage.setProvider("liblib");
        usage.setStatus("SUCCEEDED");
        usage.setActualPoints(12);
        when(usageRepository.findChargeableSucceededUsagesMissingChargeLedger(2002L, 120L, PageRequest.of(0, 20)))
                .thenReturn(new SliceImpl<>(List.of(usage), PageRequest.of(0, 20), false));

        BillingReconciliationService.AnomalyPage page = service.anomalyDetails(
                "USAGE_CHARGE_LEDGER", 2002L, 120L, 20);

        assertEquals("USAGE_CHARGE_LEDGER", page.type());
        assertEquals(false, page.hasMore());
        assertEquals(77L, page.nextCursor());
        assertEquals("task-77", page.items().get(0).referenceNo());
        assertEquals(12L, page.items().get(0).points());
        assertEquals("liblib", page.items().get(0).provider());
        verify(usageRepository).findChargeableSucceededUsagesMissingChargeLedger(2002L, 120L, PageRequest.of(0, 20));
    }

    @Test
    void anomalyDetailsListPaymentLedgersMissingPaidOrderWithCursorPagination() {
        UserWalletRepository walletRepository = mock(UserWalletRepository.class);
        WalletLedgerRepository ledgerRepository = mock(WalletLedgerRepository.class);
        GenerationUsageLogRepository usageRepository = mock(GenerationUsageLogRepository.class);
        PaymentOrderRepository orderRepository = mock(PaymentOrderRepository.class);
        BillingReconciliationService service = new BillingReconciliationService(
                walletRepository, ledgerRepository, usageRepository, orderRepository);
        WalletLedger ledger = new WalletLedger();
        ledger.setId(66L);
        ledger.setUserId(2002L);
        ledger.setType("PAYMENT_RECHARGE");
        ledger.setStatus("POSTED");
        ledger.setPointsDelta(100L);
        ledger.setIdempotencyKey("payment:R202607100002");
        ledger.setCreatedAt(LocalDateTime.of(2026, 7, 10, 12, 30));
        when(ledgerRepository.findPaymentRechargeLedgersMissingPaidOrder(2002L, 120L, PageRequest.of(0, 20)))
                .thenReturn(new SliceImpl<>(List.of(ledger), PageRequest.of(0, 20), false));

        BillingReconciliationService.AnomalyPage page = service.anomalyDetails(
                "PAYMENT_LEDGER_ORDER", 2002L, 120L, 20);

        assertEquals("PAYMENT_LEDGER_ORDER", page.type());
        assertEquals(false, page.hasMore());
        assertEquals(66L, page.nextCursor());
        assertEquals("R202607100002", page.items().get(0).referenceNo());
        assertEquals(100L, page.items().get(0).points());
        assertEquals(0L, page.items().get(0).amountCents());
        verify(ledgerRepository).findPaymentRechargeLedgersMissingPaidOrder(2002L, 120L, PageRequest.of(0, 20));
    }

    @Test
    void anomalyDetailsListChargeLedgersMissingSucceededUsageWithCursorPagination() {
        UserWalletRepository walletRepository = mock(UserWalletRepository.class);
        WalletLedgerRepository ledgerRepository = mock(WalletLedgerRepository.class);
        GenerationUsageLogRepository usageRepository = mock(GenerationUsageLogRepository.class);
        PaymentOrderRepository orderRepository = mock(PaymentOrderRepository.class);
        BillingReconciliationService service = new BillingReconciliationService(
                walletRepository, ledgerRepository, usageRepository, orderRepository);
        WalletLedger ledger = new WalletLedger();
        ledger.setId(55L);
        ledger.setUserId(2002L);
        ledger.setUsageLogId(77L);
        ledger.setType("GENERATION_CHARGE");
        ledger.setStatus("POSTED");
        ledger.setPointsDelta(-12L);
        ledger.setIdempotencyKey("usage:77:charge");
        ledger.setCreatedAt(LocalDateTime.of(2026, 7, 10, 13, 30));
        when(ledgerRepository.findGenerationChargeLedgersMissingSucceededUsage(2002L, 120L, PageRequest.of(0, 20)))
                .thenReturn(new SliceImpl<>(List.of(ledger), PageRequest.of(0, 20), true));

        BillingReconciliationService.AnomalyPage page = service.anomalyDetails(
                "CHARGE_LEDGER_USAGE", 2002L, 120L, 20);

        assertEquals("CHARGE_LEDGER_USAGE", page.type());
        assertEquals(true, page.hasMore());
        assertEquals(55L, page.nextCursor());
        assertEquals("77", page.items().get(0).referenceNo());
        assertEquals(12L, page.items().get(0).points());
        assertEquals("GENERATION_CHARGE", page.items().get(0).provider());
        verify(ledgerRepository).findGenerationChargeLedgersMissingSucceededUsage(2002L, 120L, PageRequest.of(0, 20));
    }

    private BillingReconciliationService.Check check(BillingReconciliationService.Report report, String code) {
        return report.checks().stream()
                .filter(item -> code.equals(item.code()))
                .findFirst()
                .orElseThrow();
    }
}
