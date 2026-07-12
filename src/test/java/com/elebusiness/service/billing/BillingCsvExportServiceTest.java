package com.elebusiness.service.billing;

import com.elebusiness.config.AppProperties;
import com.elebusiness.model.entity.GenerationUsageLog;
import com.elebusiness.model.entity.PaymentOrder;
import com.elebusiness.model.entity.WalletLedger;
import com.elebusiness.repository.GenerationUsageLogRepository;
import com.elebusiness.repository.PaymentOrderRepository;
import com.elebusiness.repository.WalletLedgerRepository;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.SliceImpl;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BillingCsvExportServiceTest {

    @Test
    void userLedgerExportUsesCurrentUserScopeAndEscapesCsv() {
        WalletLedgerRepository ledgerRepository = mock(WalletLedgerRepository.class);
        BillingCsvExportService service = service(ledgerRepository,
                mock(GenerationUsageLogRepository.class), mock(PaymentOrderRepository.class));
        LocalDateTime from = LocalDateTime.of(2026, 7, 10, 0, 0);
        LocalDateTime to = LocalDateTime.of(2026, 7, 11, 0, 0);
        WalletLedger ledger = new WalletLedger();
        ledger.setId(9L);
        ledger.setUserId(1001L);
        ledger.setUsageLogId(55L);
        ledger.setType("PAYMENT_RECHARGE");
        ledger.setDirection("IN");
        ledger.setPointsDelta(100L);
        ledger.setBalanceBefore(0L);
        ledger.setBalanceAfter(100L);
        ledger.setFrozenBefore(0L);
        ledger.setFrozenAfter(0L);
        ledger.setStatus("POSTED");
        ledger.setRemark("充值,测试\"A\"");
        ledger.setCreatedAt(from.plusHours(1));
        when(ledgerRepository.searchByUserBeforeId(eq(1001L), eq(Long.MAX_VALUE), eq("PAYMENT_RECHARGE"), eq("IN"),
                eq(from), eq(to), eq(PageRequest.of(0, 500))))
                .thenReturn(new SliceImpl<>(List.of(ledger), PageRequest.of(0, 500), false));

        String csv = service.exportLedgerForUser(1001L, "PAYMENT_RECHARGE", "IN", from, to);

        assertTrue(csv.startsWith("\uFEFFid,userId,usageLogId,type,direction"));
        assertTrue(csv.contains("\"充值,测试\"\"A\"\"\""));
        verify(ledgerRepository).searchByUserBeforeId(1001L, Long.MAX_VALUE, "PAYMENT_RECHARGE", "IN", from, to, PageRequest.of(0, 500));
        verify(ledgerRepository, never()).searchByUser(eq(1001L), eq("PAYMENT_RECHARGE"), eq("IN"), eq(from), eq(to), eq(PageRequest.of(0, 500)));
        verify(ledgerRepository, never()).searchAdmin(eq(1001L), eq("PAYMENT_RECHARGE"), eq("IN"), eq(from), eq(to), eq(PageRequest.of(0, 500)));
    }

    @Test
    void adminUsageExportReadsNextBatchByCursorWithoutLargeOffset() {
        GenerationUsageLogRepository usageRepository = mock(GenerationUsageLogRepository.class);
        BillingCsvExportService service = service(mock(WalletLedgerRepository.class),
                usageRepository, mock(PaymentOrderRepository.class));
        GenerationUsageLog first = usage(200L, 2002L, "SUCCEEDED");
        GenerationUsageLog second = usage(120L, 2002L, "SUCCEEDED");
        when(usageRepository.searchAdminBeforeId(eq(2002L), eq(Long.MAX_VALUE), eq("SUCCEEDED"), eq("liblib"), eq("custom"),
                eq(null), eq(null), eq(PageRequest.of(0, 500))))
                .thenReturn(new SliceImpl<>(List.of(first), PageRequest.of(0, 500), true));
        when(usageRepository.searchAdminBeforeId(eq(2002L), eq(200L), eq("SUCCEEDED"), eq("liblib"), eq("custom"),
                eq(null), eq(null), eq(PageRequest.of(0, 500))))
                .thenReturn(new SliceImpl<>(List.of(second), PageRequest.of(0, 500), false));

        String csv = service.exportUsageForAdmin(2002L, "SUCCEEDED", "liblib", "custom", null, null);

        assertTrue(csv.contains("task-200"));
        assertTrue(csv.contains("task-120"));
        assertTrue(csv.contains("providerTaskId,providerRawCost,providerRawUnit,costSource,exchangeRate"));
        assertTrue(csv.contains("liblib-task-200,12.500000,LIBLIB_POINTS,PROVIDER_REPORTED,1.440000"));
        verify(usageRepository).searchAdminBeforeId(2002L, Long.MAX_VALUE, "SUCCEEDED", "liblib", "custom", null, null, PageRequest.of(0, 500));
        verify(usageRepository).searchAdminBeforeId(2002L, 200L, "SUCCEEDED", "liblib", "custom", null, null, PageRequest.of(0, 500));
        verify(usageRepository, never()).searchAdmin(eq(2002L), eq("SUCCEEDED"), eq("liblib"), eq("custom"),
                eq(null), eq(null), eq(PageRequest.of(0, 500)));
    }

    @Test
    void paymentOrderExportIncludesPaymentFields() {
        PaymentOrderRepository orderRepository = mock(PaymentOrderRepository.class);
        BillingCsvExportService service = service(mock(WalletLedgerRepository.class),
                mock(GenerationUsageLogRepository.class), orderRepository);
        PaymentOrder order = new PaymentOrder();
        order.setId(3L);
        order.setOrderNo("R202607100001");
        order.setUserId(1001L);
        order.setPoints(100L);
        order.setAmountCents(19900L);
        order.setCurrency("CNY");
        order.setProvider("manual");
        order.setProviderOrderNo("wx-123");
        order.setStatus("PAID");
        order.setCreatedAt(LocalDateTime.of(2026, 7, 10, 1, 0));
        when(orderRepository.searchByUserBeforeId(eq(1001L), eq(Long.MAX_VALUE), eq("PAID"), eq("manual"),
                eq(null), eq(null), eq(PageRequest.of(0, 500))))
                .thenReturn(new SliceImpl<>(List.of(order), PageRequest.of(0, 500), false));

        String csv = service.exportPaymentOrdersForUser(1001L, "PAID", "manual", null, null);

        assertTrue(csv.contains("orderNo,userId,points,amountCents,currency,provider,providerOrderNo,status"));
        assertTrue(csv.contains("R202607100001,1001,100,19900,CNY,manual,wx-123,PAID"));
    }

    @Test
    void adminReconciliationAnomalyExportUsesCursorPagesAndIncludesActionSummary() {
        BillingReconciliationService reconciliationService = mock(BillingReconciliationService.class);
        BillingCsvExportService service = new BillingCsvExportService(
                new AppProperties(),
                mock(WalletLedgerRepository.class),
                mock(GenerationUsageLogRepository.class),
                mock(PaymentOrderRepository.class),
                reconciliationService);
        BillingReconciliationService.AnomalyItem first = new BillingReconciliationService.AnomalyItem(
                88L, 2002L, "R202607100001", "PAID", 100L, 19900L, "manual",
                LocalDateTime.of(2026, 7, 10, 8, 0),
                "PAID_ORDER_LEDGER:88", "IGNORED", "历史导入,人工确认", LocalDateTime.of(2026, 7, 10, 9, 0)
        );
        BillingReconciliationService.AnomalyItem second = new BillingReconciliationService.AnomalyItem(
                66L, 2002L, "R202607100002", "PAID", 50L, 9900L, "manual",
                LocalDateTime.of(2026, 7, 10, 7, 0),
                "PAID_ORDER_LEDGER:66", null, null, null
        );
        when(reconciliationService.anomalyDetails("PAID_ORDER_LEDGER", 2002L, null, 200))
                .thenReturn(new BillingReconciliationService.AnomalyPage(
                        "PAID_ORDER_LEDGER", 2002L, List.of(first), 200, true, 88L));
        when(reconciliationService.anomalyDetails("PAID_ORDER_LEDGER", 2002L, 88L, 200))
                .thenReturn(new BillingReconciliationService.AnomalyPage(
                        "PAID_ORDER_LEDGER", 2002L, List.of(second), 200, false, 66L));

        String csv = service.exportReconciliationAnomaliesForAdmin("PAID_ORDER_LEDGER", 2002L);

        assertTrue(csv.startsWith("\uFEFFtype,id,userId,referenceNo,status,points,amountCents,provider,occurredAt,anomalyKey,actionStatus,actionNote,actionUpdatedAt"));
        assertTrue(csv.contains("PAID_ORDER_LEDGER,88,2002,R202607100001,PAID,100,19900,manual,2026-07-10T08:00,PAID_ORDER_LEDGER:88,IGNORED,\"历史导入,人工确认\",2026-07-10T09:00"));
        assertTrue(csv.contains("PAID_ORDER_LEDGER,66,2002,R202607100002,PAID,50,9900,manual,2026-07-10T07:00,PAID_ORDER_LEDGER:66,,,"));
        verify(reconciliationService).anomalyDetails("PAID_ORDER_LEDGER", 2002L, null, 200);
        verify(reconciliationService).anomalyDetails("PAID_ORDER_LEDGER", 2002L, 88L, 200);
    }

    private BillingCsvExportService service(WalletLedgerRepository ledgerRepository,
                                            GenerationUsageLogRepository usageRepository,
                                            PaymentOrderRepository orderRepository) {
        return new BillingCsvExportService(new AppProperties(), ledgerRepository, usageRepository, orderRepository);
    }

    private GenerationUsageLog usage(long id, long userId, String status) {
        GenerationUsageLog usage = new GenerationUsageLog();
        usage.setId(id);
        usage.setUserId(userId);
        usage.setTaskId("task-" + id);
        usage.setMode("custom");
        usage.setAgentId("liblib-lora");
        usage.setProvider("liblib");
        usage.setEstimatedPoints(10);
        usage.setActualPoints(8);
        usage.setProviderTaskId("liblib-task-" + id);
        usage.setProviderRawCost(new BigDecimal("12.500000"));
        usage.setProviderRawUnit("LIBLIB_POINTS");
        usage.setCostSource("PROVIDER_REPORTED");
        usage.setExchangeRate(new BigDecimal("1.440000"));
        usage.setStatus(status);
        usage.setStartedAt(LocalDateTime.of(2026, 7, 10, 1, 0).plusMinutes(id));
        return usage;
    }
}
