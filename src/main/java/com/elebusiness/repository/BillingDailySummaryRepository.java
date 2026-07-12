package com.elebusiness.repository;

import com.elebusiness.model.entity.BillingDailySummary;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface BillingDailySummaryRepository extends JpaRepository<BillingDailySummary, Long> {
    Optional<BillingDailySummary> findByScopeUserIdAndSummaryDate(Long scopeUserId, LocalDate summaryDate);

    List<BillingDailySummary> findByScopeUserIdAndSummaryDateGreaterThanEqualAndSummaryDateLessThanOrderBySummaryDateAsc(
            Long scopeUserId,
            LocalDate fromDate,
            LocalDate toDate
    );
}
