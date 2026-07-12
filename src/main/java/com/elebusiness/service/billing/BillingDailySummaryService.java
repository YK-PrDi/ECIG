package com.elebusiness.service.billing;

import com.elebusiness.model.entity.BillingDailySummary;
import com.elebusiness.repository.BillingDailySummaryRepository;
import com.elebusiness.repository.GenerationUsageLogRepository;
import com.elebusiness.repository.PaymentOrderRepository;
import com.elebusiness.repository.WalletLedgerRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Service
public class BillingDailySummaryService {

    public static final long GLOBAL_SCOPE_USER_ID = 0L;
    public static final int MAX_REFRESH_RANGE_DAYS = 31;

    private final BillingDailySummaryRepository summaryRepository;
    private final WalletLedgerRepository ledgerRepository;
    private final GenerationUsageLogRepository usageRepository;
    private final PaymentOrderRepository orderRepository;

    public BillingDailySummaryService(BillingDailySummaryRepository summaryRepository,
                                      WalletLedgerRepository ledgerRepository,
                                      GenerationUsageLogRepository usageRepository,
                                      PaymentOrderRepository orderRepository) {
        this.summaryRepository = summaryRepository;
        this.ledgerRepository = ledgerRepository;
        this.usageRepository = usageRepository;
        this.orderRepository = orderRepository;
    }

    @Transactional
    public BillingDailySummary refreshDailySummary(LocalDate date, Long userId) {
        if (date == null) {
            throw new IllegalArgumentException("summary date must not be null");
        }
        Long scopeUserId = scopeUserId(userId);
        Long aggregateUserId = aggregateUserId(scopeUserId);
        LocalDateTime fromTime = date.atStartOfDay();
        LocalDateTime toTime = date.plusDays(1).atStartOfDay();

        Object[] ledgerSummary = flattenAggregate(ledgerRepository.summarizeLedger(aggregateUserId, fromTime, toTime));
        Object[] usageSummary = flattenAggregate(usageRepository.summarizeUsage(aggregateUserId, fromTime, toTime));
        Object[] orderSummary = flattenAggregate(orderRepository.summarizeOrders(aggregateUserId, fromTime, toTime));

        BillingDailySummary summary = summaryRepository.findByScopeUserIdAndSummaryDate(scopeUserId, date)
                .orElseGet(() -> {
                    BillingDailySummary created = new BillingDailySummary();
                    created.setScopeUserId(scopeUserId);
                    created.setSummaryDate(date);
                    return created;
                });
        applyLedgerSummary(summary, ledgerSummary);
        applyUsageSummary(summary, usageSummary);
        applyOrderSummary(summary, orderSummary);
        summary.setRefreshedAt(LocalDateTime.now());
        return summaryRepository.save(summary);
    }

    public RefreshRangeResult refreshDailySummaryRange(LocalDate fromDate, LocalDate toDate, Long userId) {
        if (fromDate == null || toDate == null) {
            throw new IllegalArgumentException("summary range must not be null");
        }
        if (toDate.isBefore(fromDate)) {
            throw new IllegalArgumentException("summary range end date must not be before start date");
        }
        long requestedDays = ChronoUnit.DAYS.between(fromDate, toDate) + 1;
        if (requestedDays > MAX_REFRESH_RANGE_DAYS) {
            throw new IllegalArgumentException("summary range must not exceed " + MAX_REFRESH_RANGE_DAYS + " days");
        }

        Long requestScopeUserId = scopeUserId(userId);
        List<RefreshDayResult> days = new ArrayList<>();
        long successDays = 0;
        long failedDays = 0;
        for (int offset = 0; offset < requestedDays; offset++) {
            LocalDate date = fromDate.plusDays(offset);
            try {
                BillingDailySummary summary = refreshDailySummary(date, userId);
                days.add(new RefreshDayResult(date, "SUCCESS", null, summary.getScopeUserId()));
                successDays++;
            } catch (RuntimeException e) {
                days.add(new RefreshDayResult(date, "FAILED", e.getMessage(), requestScopeUserId));
                failedDays++;
            }
        }
        return new RefreshRangeResult(requestedDays, successDays, failedDays, List.copyOf(days));
    }

    @Transactional(readOnly = true)
    public Optional<PeriodSummary> findCompletePeriodSummary(Long userId, LocalDateTime fromTime, LocalDateTime toTime) {
        if (!isWholeDayRange(fromTime, toTime)) {
            return Optional.empty();
        }
        LocalDate fromDate = fromTime.toLocalDate();
        LocalDate toDate = toTime.toLocalDate();
        long expectedDays = ChronoUnit.DAYS.between(fromDate, toDate);
        if (expectedDays <= 0 || expectedDays > Integer.MAX_VALUE) {
            return Optional.empty();
        }

        List<BillingDailySummary> rows = summaryRepository
                .findByScopeUserIdAndSummaryDateGreaterThanEqualAndSummaryDateLessThanOrderBySummaryDateAsc(
                        scopeUserId(userId), fromDate, toDate);
        if (rows.size() != expectedDays || !datesAreContinuous(rows, fromDate)) {
            return Optional.empty();
        }
        return Optional.of(sumPeriod(rows));
    }

    private void applyLedgerSummary(BillingDailySummary summary, Object[] values) {
        summary.setInPoints(aggregateLong(values, 0));
        summary.setOutPoints(aggregateLong(values, 1));
        summary.setFrozenPointsDelta(aggregateLong(values, 2));
        summary.setReleasedPoints(aggregateLong(values, 3));
        summary.setLedgerCount(aggregateLong(values, 4));
    }

    private void applyUsageSummary(BillingDailySummary summary, Object[] values) {
        summary.setUsageCount(aggregateLong(values, 0));
        summary.setSucceededUsageCount(aggregateLong(values, 1));
        summary.setFailedUsageCount(aggregateLong(values, 2));
        summary.setRunningUsageCount(aggregateLong(values, 3));
        summary.setActualPoints(aggregateLong(values, 4));
        summary.setEstimatedPoints(aggregateLong(values, 5));
    }

    private void applyOrderSummary(BillingDailySummary summary, Object[] values) {
        summary.setOrderCount(aggregateLong(values, 0));
        summary.setPaidOrderCount(aggregateLong(values, 1));
        summary.setPendingOrderCount(aggregateLong(values, 2));
        summary.setPaidAmountCents(aggregateLong(values, 3));
        summary.setPendingAmountCents(aggregateLong(values, 4));
    }

    private PeriodSummary sumPeriod(List<BillingDailySummary> rows) {
        long summaryDays = rows.size();
        long inPoints = 0;
        long outPoints = 0;
        long frozenPointsDelta = 0;
        long releasedPoints = 0;
        long ledgerCount = 0;
        long usageCount = 0;
        long succeededUsageCount = 0;
        long failedUsageCount = 0;
        long runningUsageCount = 0;
        long actualPoints = 0;
        long estimatedPoints = 0;
        long orderCount = 0;
        long paidOrderCount = 0;
        long pendingOrderCount = 0;
        long paidAmountCents = 0;
        long pendingAmountCents = 0;

        for (BillingDailySummary row : rows) {
            inPoints += row.getInPoints();
            outPoints += row.getOutPoints();
            frozenPointsDelta += row.getFrozenPointsDelta();
            releasedPoints += row.getReleasedPoints();
            ledgerCount += row.getLedgerCount();
            usageCount += row.getUsageCount();
            succeededUsageCount += row.getSucceededUsageCount();
            failedUsageCount += row.getFailedUsageCount();
            runningUsageCount += row.getRunningUsageCount();
            actualPoints += row.getActualPoints();
            estimatedPoints += row.getEstimatedPoints();
            orderCount += row.getOrderCount();
            paidOrderCount += row.getPaidOrderCount();
            pendingOrderCount += row.getPendingOrderCount();
            paidAmountCents += row.getPaidAmountCents();
            pendingAmountCents += row.getPendingAmountCents();
        }

        return new PeriodSummary(summaryDays, inPoints, outPoints, frozenPointsDelta, releasedPoints, ledgerCount,
                usageCount, succeededUsageCount, failedUsageCount, runningUsageCount, actualPoints, estimatedPoints,
                orderCount, paidOrderCount, pendingOrderCount, paidAmountCents, pendingAmountCents);
    }

    private boolean datesAreContinuous(List<BillingDailySummary> rows, LocalDate fromDate) {
        for (int index = 0; index < rows.size(); index++) {
            if (!fromDate.plusDays(index).equals(rows.get(index).getSummaryDate())) {
                return false;
            }
        }
        return true;
    }

    private boolean isWholeDayRange(LocalDateTime fromTime, LocalDateTime toTime) {
        return fromTime != null
                && toTime != null
                && LocalTime.MIDNIGHT.equals(fromTime.toLocalTime())
                && LocalTime.MIDNIGHT.equals(toTime.toLocalTime())
                && toTime.isAfter(fromTime);
    }

    private Long scopeUserId(Long userId) {
        return userId == null || userId <= 0 ? GLOBAL_SCOPE_USER_ID : userId;
    }

    private Long aggregateUserId(Long scopeUserId) {
        return GLOBAL_SCOPE_USER_ID == scopeUserId ? null : scopeUserId;
    }

    private Object[] flattenAggregate(Object aggregate) {
        if (aggregate == null) {
            return new Object[0];
        }
        if (aggregate instanceof Object[] values) {
            if (values.length == 1 && values[0] instanceof Object[] nested) {
                return nested;
            }
            return values;
        }
        return new Object[]{aggregate};
    }

    private long aggregateLong(Object[] values, int index) {
        if (values == null || index < 0 || index >= values.length || values[index] == null) {
            return 0L;
        }
        Object value = values[index];
        if (value instanceof Number number) {
            return number.longValue();
        }
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    public record PeriodSummary(
            long summaryDays,
            long inPoints,
            long outPoints,
            long frozenPointsDelta,
            long releasedPoints,
            long ledgerCount,
            long usageCount,
            long succeededUsageCount,
            long failedUsageCount,
            long runningUsageCount,
            long actualPoints,
            long estimatedPoints,
            long orderCount,
            long paidOrderCount,
            long pendingOrderCount,
            long paidAmountCents,
            long pendingAmountCents
    ) {
    }

    public record RefreshRangeResult(
            long requestedDays,
            long successDays,
            long failedDays,
            List<RefreshDayResult> days
    ) {
    }

    public record RefreshDayResult(
            LocalDate date,
            String status,
            String message,
            Long scopeUserId
    ) {
    }
}
