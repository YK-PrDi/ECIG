package com.elebusiness.repository;

import com.elebusiness.model.entity.GenerationUsageLog;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface GenerationUsageLogRepository extends JpaRepository<GenerationUsageLog, Long> {
    Optional<GenerationUsageLog> findFirstByTaskIdOrderByStartedAtDesc(String taskId);
    List<GenerationUsageLog> findByUserIdOrderByStartedAtDesc(Long userId);
    Slice<GenerationUsageLog> findByUserIdOrderByStartedAtDesc(Long userId, Pageable pageable);

    @Query("""
            select u from GenerationUsageLog u
            where u.userId = :userId
              and (:status is null or u.status = :status)
              and (:provider is null or u.provider = :provider)
              and (:mode is null or u.mode = :mode)
              and (:fromTime is null or u.startedAt >= :fromTime)
              and (:toTime is null or u.startedAt < :toTime)
            order by u.startedAt desc
            """)
    Slice<GenerationUsageLog> searchByUser(@Param("userId") Long userId,
                                           @Param("status") String status,
                                           @Param("provider") String provider,
                                           @Param("mode") String mode,
                                           @Param("fromTime") LocalDateTime fromTime,
                                           @Param("toTime") LocalDateTime toTime,
                                           Pageable pageable);

    @Query("""
            select u from GenerationUsageLog u
            where u.userId = :userId
              and u.id < :beforeId
              and (:status is null or u.status = :status)
              and (:provider is null or u.provider = :provider)
              and (:mode is null or u.mode = :mode)
              and (:fromTime is null or u.startedAt >= :fromTime)
              and (:toTime is null or u.startedAt < :toTime)
            order by u.id desc
            """)
    Slice<GenerationUsageLog> searchByUserBeforeId(@Param("userId") Long userId,
                                                   @Param("beforeId") Long beforeId,
                                                   @Param("status") String status,
                                                   @Param("provider") String provider,
                                                   @Param("mode") String mode,
                                                   @Param("fromTime") LocalDateTime fromTime,
                                                   @Param("toTime") LocalDateTime toTime,
                                                   Pageable pageable);

    @Query("""
            select u from GenerationUsageLog u
            where (:userId is null or u.userId = :userId)
              and (:status is null or u.status = :status)
              and (:provider is null or u.provider = :provider)
              and (:mode is null or u.mode = :mode)
              and (:fromTime is null or u.startedAt >= :fromTime)
              and (:toTime is null or u.startedAt < :toTime)
            order by u.startedAt desc
            """)
    Slice<GenerationUsageLog> searchAdmin(@Param("userId") Long userId,
                                          @Param("status") String status,
                                          @Param("provider") String provider,
                                          @Param("mode") String mode,
                                          @Param("fromTime") LocalDateTime fromTime,
                                          @Param("toTime") LocalDateTime toTime,
                                          Pageable pageable);

    @Query("""
            select u from GenerationUsageLog u
            where (:userId is null or u.userId = :userId)
              and u.id < :beforeId
              and (:status is null or u.status = :status)
              and (:provider is null or u.provider = :provider)
              and (:mode is null or u.mode = :mode)
              and (:fromTime is null or u.startedAt >= :fromTime)
              and (:toTime is null or u.startedAt < :toTime)
            order by u.id desc
            """)
    Slice<GenerationUsageLog> searchAdminBeforeId(@Param("userId") Long userId,
                                                  @Param("beforeId") Long beforeId,
                                                  @Param("status") String status,
                                                  @Param("provider") String provider,
                                                  @Param("mode") String mode,
                                                  @Param("fromTime") LocalDateTime fromTime,
                                                  @Param("toTime") LocalDateTime toTime,
                                                  Pageable pageable);

    @Query("""
            select
              count(u),
              coalesce(sum(case when u.status = 'SUCCEEDED' then 1 else 0 end), 0),
              coalesce(sum(case when u.status = 'FAILED' then 1 else 0 end), 0),
              coalesce(sum(case when u.status = 'STARTED' then 1 else 0 end), 0),
              coalesce(sum(u.actualPoints), 0),
              coalesce(sum(u.estimatedPoints), 0)
            from GenerationUsageLog u
            where (:userId is null or u.userId = :userId)
              and (:fromTime is null or u.startedAt >= :fromTime)
              and (:toTime is null or u.startedAt < :toTime)
            """)
    Object[] summarizeUsage(@Param("userId") Long userId,
                            @Param("fromTime") LocalDateTime fromTime,
                            @Param("toTime") LocalDateTime toTime);

    @Query("""
            select coalesce(sum(u.estimatedPoints), 0)
            from GenerationUsageLog u
            where (:userId is null or u.userId = :userId)
              and u.status = 'STARTED'
            """)
    Long sumOpenEstimatedPoints(@Param("userId") Long userId);

    @Query("""
            select count(u)
            from GenerationUsageLog u
            where (:userId is null or u.userId = :userId)
              and u.status = 'SUCCEEDED'
              and u.actualPoints > 0
            """)
    long countChargeableSucceededUsages(@Param("userId") Long userId);

    @Query("""
            select u from GenerationUsageLog u
            where (:userId is null or u.userId = :userId)
              and (:beforeId is null or u.id < :beforeId)
              and u.status = 'SUCCEEDED'
              and u.actualPoints > 0
              and not exists (
                  select l.id from WalletLedger l
                  where l.userId = u.userId
                    and l.usageLogId = u.id
                    and l.type = 'GENERATION_CHARGE'
                    and l.status = 'POSTED'
              )
            order by u.id desc
            """)
    Slice<GenerationUsageLog> findChargeableSucceededUsagesMissingChargeLedger(@Param("userId") Long userId,
                                                                               @Param("beforeId") Long beforeId,
                                                                               Pageable pageable);
}
