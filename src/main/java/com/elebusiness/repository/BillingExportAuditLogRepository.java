package com.elebusiness.repository;

import com.elebusiness.model.entity.BillingExportAuditLog;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface BillingExportAuditLogRepository extends JpaRepository<BillingExportAuditLog, Long> {
    Optional<BillingExportAuditLog> findByExportId(String exportId);

    @Query("""
            select l from BillingExportAuditLog l
            where (:operatorUserId is null or l.operatorUserId = :operatorUserId)
              and (:exportType is null or l.exportType = :exportType)
              and (:status is null or l.status = :status)
              and (:cursor is null or l.id < :cursor)
            order by l.id desc
            """)
    Slice<BillingExportAuditLog> searchLogs(@Param("operatorUserId") Long operatorUserId,
                                            @Param("exportType") String exportType,
                                            @Param("status") String status,
                                            @Param("cursor") Long cursor,
                                            Pageable pageable);
}
