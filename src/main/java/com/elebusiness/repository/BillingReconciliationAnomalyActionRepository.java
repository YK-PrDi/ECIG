package com.elebusiness.repository;

import com.elebusiness.model.entity.BillingReconciliationAnomalyAction;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface BillingReconciliationAnomalyActionRepository
        extends JpaRepository<BillingReconciliationAnomalyAction, Long> {

    Optional<BillingReconciliationAnomalyAction> findByAnomalyKey(String anomalyKey);

    List<BillingReconciliationAnomalyAction> findByAnomalyKeyIn(Collection<String> anomalyKeys);

    @Query("""
            select a from BillingReconciliationAnomalyAction a
            where (:type is null or a.type = :type)
              and (:userId is null or a.userId = :userId)
              and (:status is null or a.status = :status)
              and (:cursor is null or a.id < :cursor)
            order by a.id desc
            """)
    Slice<BillingReconciliationAnomalyAction> searchActions(@Param("type") String type,
                                                            @Param("userId") Long userId,
                                                            @Param("status") String status,
                                                            @Param("cursor") Long cursor,
                                                            Pageable pageable);
}
