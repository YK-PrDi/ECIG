package com.elebusiness.service.billing;

import com.elebusiness.model.entity.BillingDailySummary;
import com.elebusiness.repository.BillingDailySummaryRepository;
import com.elebusiness.repository.GenerationUsageLogRepository;
import com.elebusiness.repository.PaymentOrderRepository;
import com.elebusiness.repository.WalletLedgerRepository;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BillingDailySummaryServiceTest {

    @Test
    void refreshDailySummaryUpsertsScopedDailySnapshotFromSourceTables() {
        BillingDailySummaryRepository summaryRepository = mock(BillingDailySummaryRepository.class);
        WalletLedgerRepository ledgerRepository = mock(WalletLedgerRepository.class);
        GenerationUsageLogRepository usageRepository = mock(GenerationUsageLogRepository.class);
        PaymentOrderRepository orderRepository = mock(PaymentOrderRepository.class);
        BillingDailySummaryService service = new BillingDailySummaryService(
                summaryRepository, ledgerRepository, usageRepository, orderRepository);
        LocalDate date = LocalDate.of(2026, 7, 10);
        LocalDateTime from = date.atStartOfDay();
        LocalDateTime to = date.plusDays(1).atStartOfDay();
        BillingDailySummary existing = new BillingDailySummary();
        existing.setScopeUserId(2002L);
        existing.setSummaryDate(date);

        when(summaryRepository.findByScopeUserIdAndSummaryDate(2002L, date)).thenReturn(Optional.of(existing));
        when(ledgerRepository.summarizeLedger(2002L, from, to))
                .thenReturn(new Object[]{300L, 80L, 40L, 20L, 6L});
        when(usageRepository.summarizeUsage(2002L, from, to))
                .thenReturn(new Object[]{10L, 7L, 2L, 1L, 90L, 110L});
        when(orderRepository.summarizeOrders(2002L, from, to))
                .thenReturn(new Object[]{4L, 3L, 1L, 39900L, 19900L});
        when(summaryRepository.save(existing)).thenReturn(existing);

        BillingDailySummary summary = service.refreshDailySummary(date, 2002L);

        assertSame(existing, summary);
        assertEquals(2002L, summary.getScopeUserId());
        assertEquals(date, summary.getSummaryDate());
        assertEquals(300L, summary.getInPoints());
        assertEquals(80L, summary.getOutPoints());
        assertEquals(6L, summary.getLedgerCount());
        assertEquals(10L, summary.getUsageCount());
        assertEquals(7L, summary.getSucceededUsageCount());
        assertEquals(3L, summary.getPaidOrderCount());
        assertEquals(39900L, summary.getPaidAmountCents());
        verify(summaryRepository).save(existing);
    }

    @Test
    void refreshDailySummaryRangeRefreshesInclusiveDaysAndReportsCounts() {
        BillingDailySummaryRepository summaryRepository = mock(BillingDailySummaryRepository.class);
        WalletLedgerRepository ledgerRepository = mock(WalletLedgerRepository.class);
        GenerationUsageLogRepository usageRepository = mock(GenerationUsageLogRepository.class);
        PaymentOrderRepository orderRepository = mock(PaymentOrderRepository.class);
        BillingDailySummaryService service = new BillingDailySummaryService(
                summaryRepository, ledgerRepository, usageRepository, orderRepository);
        LocalDate fromDate = LocalDate.of(2026, 7, 10);
        BillingDailySummary dayOne = summary(2002L, fromDate, 0, 0, 0);
        BillingDailySummary dayTwo = summary(2002L, fromDate.plusDays(1), 0, 0, 0);
        BillingDailySummary dayThree = summary(2002L, fromDate.plusDays(2), 0, 0, 0);

        when(summaryRepository.findByScopeUserIdAndSummaryDate(2002L, fromDate)).thenReturn(Optional.of(dayOne));
        when(summaryRepository.findByScopeUserIdAndSummaryDate(2002L, fromDate.plusDays(1))).thenReturn(Optional.of(dayTwo));
        when(summaryRepository.findByScopeUserIdAndSummaryDate(2002L, fromDate.plusDays(2))).thenReturn(Optional.of(dayThree));
        when(summaryRepository.save(any(BillingDailySummary.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(ledgerRepository.summarizeLedger(eq(2002L), any(LocalDateTime.class), any(LocalDateTime.class)))
                .thenReturn(new Object[]{100L, 30L, 0L, 0L, 2L});
        when(usageRepository.summarizeUsage(eq(2002L), any(LocalDateTime.class), any(LocalDateTime.class)))
                .thenReturn(new Object[]{1L, 1L, 0L, 0L, 30L, 30L});
        when(orderRepository.summarizeOrders(eq(2002L), any(LocalDateTime.class), any(LocalDateTime.class)))
                .thenReturn(new Object[]{1L, 1L, 0L, 9900L, 0L});

        BillingDailySummaryService.RefreshRangeResult result = service.refreshDailySummaryRange(
                fromDate, fromDate.plusDays(2), 2002L);

        assertEquals(3L, result.requestedDays());
        assertEquals(3L, result.successDays());
        assertEquals(0L, result.failedDays());
        assertEquals(3, result.days().size());
        assertEquals(fromDate, result.days().get(0).date());
        assertEquals("SUCCESS", result.days().get(0).status());
        verify(summaryRepository, times(3)).save(any(BillingDailySummary.class));
    }

    @Test
    void refreshDailySummaryRangeRejectsTooLargeRanges() {
        BillingDailySummaryService service = new BillingDailySummaryService(
                mock(BillingDailySummaryRepository.class), mock(WalletLedgerRepository.class),
                mock(GenerationUsageLogRepository.class), mock(PaymentOrderRepository.class));
        LocalDate fromDate = LocalDate.of(2026, 7, 1);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.refreshDailySummaryRange(fromDate, fromDate.plusDays(31), 2002L));

        assertTrue(ex.getMessage().contains("31"));
    }

    @Test
    void findCompletePeriodSummaryOnlyUsesSnapshotsForWholeDayRangesWithAllRowsPresent() {
        BillingDailySummaryRepository summaryRepository = mock(BillingDailySummaryRepository.class);
        BillingDailySummaryService service = new BillingDailySummaryService(
                summaryRepository, mock(WalletLedgerRepository.class),
                mock(GenerationUsageLogRepository.class), mock(PaymentOrderRepository.class));
        LocalDate fromDate = LocalDate.of(2026, 7, 10);
        LocalDate toDate = LocalDate.of(2026, 7, 12);
        BillingDailySummary dayOne = summary(2002L, fromDate, 100, 30, 2);
        BillingDailySummary dayTwo = summary(2002L, fromDate.plusDays(1), 200, 50, 4);

        when(summaryRepository.findByScopeUserIdAndSummaryDateGreaterThanEqualAndSummaryDateLessThanOrderBySummaryDateAsc(
                2002L, fromDate, toDate)).thenReturn(List.of(dayOne, dayTwo));

        Optional<BillingDailySummaryService.PeriodSummary> found = service.findCompletePeriodSummary(
                2002L, fromDate.atStartOfDay(), toDate.atStartOfDay());
        Optional<BillingDailySummaryService.PeriodSummary> partialTime = service.findCompletePeriodSummary(
                2002L, fromDate.atTime(1, 0), toDate.atStartOfDay());

        assertTrue(found.isPresent());
        assertEquals(2L, found.get().summaryDays());
        assertEquals(300L, found.get().inPoints());
        assertEquals(80L, found.get().outPoints());
        assertEquals(6L, found.get().ledgerCount());
        assertFalse(partialTime.isPresent());
    }

    @Test
    void findCompletePeriodSummaryFallsBackWhenAnyDailySnapshotIsMissing() {
        BillingDailySummaryRepository summaryRepository = mock(BillingDailySummaryRepository.class);
        BillingDailySummaryService service = new BillingDailySummaryService(
                summaryRepository, mock(WalletLedgerRepository.class),
                mock(GenerationUsageLogRepository.class), mock(PaymentOrderRepository.class));
        LocalDate fromDate = LocalDate.of(2026, 7, 10);
        LocalDate toDate = LocalDate.of(2026, 7, 12);

        when(summaryRepository.findByScopeUserIdAndSummaryDateGreaterThanEqualAndSummaryDateLessThanOrderBySummaryDateAsc(
                2002L, fromDate, toDate)).thenReturn(List.of(summary(2002L, fromDate, 100, 30, 2)));

        Optional<BillingDailySummaryService.PeriodSummary> found = service.findCompletePeriodSummary(
                2002L, fromDate.atStartOfDay(), toDate.atStartOfDay());

        assertFalse(found.isPresent());
    }

    private BillingDailySummary summary(long scopeUserId, LocalDate date, long inPoints, long outPoints, long ledgerCount) {
        BillingDailySummary summary = new BillingDailySummary();
        summary.setScopeUserId(scopeUserId);
        summary.setSummaryDate(date);
        summary.setInPoints(inPoints);
        summary.setOutPoints(outPoints);
        summary.setLedgerCount(ledgerCount);
        return summary;
    }
}
