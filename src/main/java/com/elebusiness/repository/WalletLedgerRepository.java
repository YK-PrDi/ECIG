package com.elebusiness.repository;

import com.elebusiness.model.entity.WalletLedger;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface WalletLedgerRepository extends JpaRepository<WalletLedger, Long> {
    List<WalletLedger> findByUserIdOrderByCreatedAtDesc(Long userId);
    Slice<WalletLedger> findByUserIdOrderByCreatedAtDesc(Long userId, Pageable pageable);
    Optional<WalletLedger> findByIdempotencyKey(String idempotencyKey);

    @Query("""
            select l from WalletLedger l
            where l.userId = :userId
              and (:type is null or l.type = :type)
              and (:direction is null or l.direction = :direction)
              and (:fromTime is null or l.createdAt >= :fromTime)
              and (:toTime is null or l.createdAt < :toTime)
            order by l.createdAt desc
            """)
    Slice<WalletLedger> searchByUser(@Param("userId") Long userId,
                                     @Param("type") String type,
                                     @Param("direction") String direction,
                                     @Param("fromTime") LocalDateTime fromTime,
                                     @Param("toTime") LocalDateTime toTime,
                                     Pageable pageable);

    @Query("""
            select l from WalletLedger l
            where l.userId = :userId
              and l.id < :beforeId
              and (:type is null or l.type = :type)
              and (:direction is null or l.direction = :direction)
              and (:fromTime is null or l.createdAt >= :fromTime)
              and (:toTime is null or l.createdAt < :toTime)
            order by l.id desc
            """)
    Slice<WalletLedger> searchByUserBeforeId(@Param("userId") Long userId,
                                             @Param("beforeId") Long beforeId,
                                             @Param("type") String type,
                                             @Param("direction") String direction,
                                             @Param("fromTime") LocalDateTime fromTime,
                                             @Param("toTime") LocalDateTime toTime,
                                             Pageable pageable);

    @Query("""
            select l from WalletLedger l
            where (:userId is null or l.userId = :userId)
              and (:type is null or l.type = :type)
              and (:direction is null or l.direction = :direction)
              and (:fromTime is null or l.createdAt >= :fromTime)
              and (:toTime is null or l.createdAt < :toTime)
            order by l.createdAt desc
            """)
    Slice<WalletLedger> searchAdmin(@Param("userId") Long userId,
                                    @Param("type") String type,
                                    @Param("direction") String direction,
                                    @Param("fromTime") LocalDateTime fromTime,
                                    @Param("toTime") LocalDateTime toTime,
                                    Pageable pageable);

    @Query("""
            select l from WalletLedger l
            where (:userId is null or l.userId = :userId)
              and l.id < :beforeId
              and (:type is null or l.type = :type)
              and (:direction is null or l.direction = :direction)
              and (:fromTime is null or l.createdAt >= :fromTime)
              and (:toTime is null or l.createdAt < :toTime)
            order by l.id desc
            """)
    Slice<WalletLedger> searchAdminBeforeId(@Param("userId") Long userId,
                                            @Param("beforeId") Long beforeId,
                                            @Param("type") String type,
                                            @Param("direction") String direction,
                                            @Param("fromTime") LocalDateTime fromTime,
                                            @Param("toTime") LocalDateTime toTime,
                                            Pageable pageable);

    @Query("""
            select
              coalesce(sum(case when l.direction = 'IN' then l.pointsDelta else 0 end), 0),
              coalesce(sum(case when l.direction = 'OUT' then -l.pointsDelta else 0 end), 0),
              coalesce(sum(case when l.direction = 'FREEZE' then l.pointsDelta else 0 end), 0),
              coalesce(sum(case when l.direction = 'RELEASE' then l.pointsDelta else 0 end), 0),
              count(l)
            from WalletLedger l
            where (:userId is null or l.userId = :userId)
              and (:fromTime is null or l.createdAt >= :fromTime)
              and (:toTime is null or l.createdAt < :toTime)
            """)
    Object[] summarizeLedger(@Param("userId") Long userId,
                             @Param("fromTime") LocalDateTime fromTime,
                             @Param("toTime") LocalDateTime toTime);

    @Query("""
            select coalesce(sum(case when l.direction in ('IN', 'OUT') then l.pointsDelta else 0 end), 0)
            from WalletLedger l
            where (:userId is null or l.userId = :userId)
              and l.status = 'POSTED'
            """)
    Long sumPostedBalancePoints(@Param("userId") Long userId);

    @Query("""
            select count(l)
            from WalletLedger l
            where (:userId is null or l.userId = :userId)
              and l.type = 'PAYMENT_RECHARGE'
              and l.status = 'POSTED'
            """)
    long countPaymentRechargeLedgers(@Param("userId") Long userId);

    @Query("""
            select count(l)
            from WalletLedger l
            where (:userId is null or l.userId = :userId)
              and l.type = 'GENERATION_CHARGE'
              and l.status = 'POSTED'
            """)
    long countGenerationChargeLedgers(@Param("userId") Long userId);

    @Query("""
            select l from WalletLedger l
            where (:userId is null or l.userId = :userId)
              and (:beforeId is null or l.id < :beforeId)
              and l.type = 'PAYMENT_RECHARGE'
              and l.status = 'POSTED'
              and not exists (
                  select o.id from PaymentOrder o
                  where o.userId = l.userId
                    and o.status = 'PAID'
                    and l.idempotencyKey = concat('payment:', o.orderNo)
              )
            order by l.id desc
            """)
    Slice<WalletLedger> findPaymentRechargeLedgersMissingPaidOrder(@Param("userId") Long userId,
                                                                   @Param("beforeId") Long beforeId,
                                                                   Pageable pageable);

    @Query("""
            select l from WalletLedger l
            where (:userId is null or l.userId = :userId)
              and (:beforeId is null or l.id < :beforeId)
              and l.type = 'GENERATION_CHARGE'
              and l.status = 'POSTED'
              and not exists (
                  select u.id from GenerationUsageLog u
                  where u.userId = l.userId
                    and u.id = l.usageLogId
                    and u.status = 'SUCCEEDED'
                    and u.actualPoints > 0
              )
            order by l.id desc
            """)
    Slice<WalletLedger> findGenerationChargeLedgersMissingSucceededUsage(@Param("userId") Long userId,
                                                                         @Param("beforeId") Long beforeId,
                                                                         Pageable pageable);
}
