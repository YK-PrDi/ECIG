package com.elebusiness.repository;

import com.elebusiness.model.entity.BillingReconciliationRun;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Optional;

public interface BillingReconciliationRunRepository extends JpaRepository<BillingReconciliationRun, Long> {
    Optional<BillingReconciliationRun> findByRunId(String runId);

    @Query("""
            select r from BillingReconciliationRun r
            where (:scopeUserId is null or r.scopeUserId = :scopeUserId)
              and (:status is null or r.status = :status)
              and (:triggerType is null or r.triggerType = :triggerType)
              and (:fromTime is null or r.startedAt >= :fromTime)
              and (:toTime is null or r.startedAt < :toTime)
              and (:cursor is null or r.id < :cursor)
            order by r.id desc
            """)
    Slice<BillingReconciliationRun> searchBeforeId(@Param("scopeUserId") Long scopeUserId,
                                                   @Param("status") String status,
                                                   @Param("triggerType") String triggerType,
                                                   @Param("fromTime") LocalDateTime fromTime,
                                                   @Param("toTime") LocalDateTime toTime,
                                                   @Param("cursor") Long cursor,
                                                   Pageable pageable);
}
