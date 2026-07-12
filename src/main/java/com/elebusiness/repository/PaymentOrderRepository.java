package com.elebusiness.repository;

import com.elebusiness.model.entity.PaymentOrder;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface PaymentOrderRepository extends JpaRepository<PaymentOrder, Long> {
    Optional<PaymentOrder> findByOrderNo(String orderNo);
    Optional<PaymentOrder> findByIdempotencyKey(String idempotencyKey);
    List<PaymentOrder> findByUserIdOrderByCreatedAtDesc(Long userId);
    Slice<PaymentOrder> findByUserIdOrderByCreatedAtDesc(Long userId, Pageable pageable);

    @Query("""
            select o from PaymentOrder o
            where o.userId = :userId
              and (:status is null or o.status = :status)
              and (:provider is null or o.provider = :provider)
              and (:fromTime is null or o.createdAt >= :fromTime)
              and (:toTime is null or o.createdAt < :toTime)
            order by o.createdAt desc
            """)
    Slice<PaymentOrder> searchByUser(@Param("userId") Long userId,
                                     @Param("status") String status,
                                     @Param("provider") String provider,
                                     @Param("fromTime") LocalDateTime fromTime,
                                     @Param("toTime") LocalDateTime toTime,
                                     Pageable pageable);

    @Query("""
            select o from PaymentOrder o
            where o.userId = :userId
              and o.id < :beforeId
              and (:status is null or o.status = :status)
              and (:provider is null or o.provider = :provider)
              and (:fromTime is null or o.createdAt >= :fromTime)
              and (:toTime is null or o.createdAt < :toTime)
            order by o.id desc
            """)
    Slice<PaymentOrder> searchByUserBeforeId(@Param("userId") Long userId,
                                             @Param("beforeId") Long beforeId,
                                             @Param("status") String status,
                                             @Param("provider") String provider,
                                             @Param("fromTime") LocalDateTime fromTime,
                                             @Param("toTime") LocalDateTime toTime,
                                             Pageable pageable);

    @Query("""
            select o from PaymentOrder o
            where (:userId is null or o.userId = :userId)
              and (:status is null or o.status = :status)
              and (:provider is null or o.provider = :provider)
              and (:fromTime is null or o.createdAt >= :fromTime)
              and (:toTime is null or o.createdAt < :toTime)
            order by o.createdAt desc
            """)
    Slice<PaymentOrder> searchAdmin(@Param("userId") Long userId,
                                    @Param("status") String status,
                                    @Param("provider") String provider,
                                    @Param("fromTime") LocalDateTime fromTime,
                                    @Param("toTime") LocalDateTime toTime,
                                    Pageable pageable);

    @Query("""
            select o from PaymentOrder o
            where (:userId is null or o.userId = :userId)
              and o.id < :beforeId
              and (:status is null or o.status = :status)
              and (:provider is null or o.provider = :provider)
              and (:fromTime is null or o.createdAt >= :fromTime)
              and (:toTime is null or o.createdAt < :toTime)
            order by o.id desc
            """)
    Slice<PaymentOrder> searchAdminBeforeId(@Param("userId") Long userId,
                                            @Param("beforeId") Long beforeId,
                                            @Param("status") String status,
                                            @Param("provider") String provider,
                                            @Param("fromTime") LocalDateTime fromTime,
                                            @Param("toTime") LocalDateTime toTime,
                                            Pageable pageable);

    @Query("""
            select
              count(o),
              coalesce(sum(case when o.status = 'PAID' then 1 else 0 end), 0),
              coalesce(sum(case when o.status = 'PENDING' then 1 else 0 end), 0),
              coalesce(sum(case when o.status = 'PAID' then o.amountCents else 0 end), 0),
              coalesce(sum(case when o.status = 'PENDING' then o.amountCents else 0 end), 0)
            from PaymentOrder o
            where (:userId is null or o.userId = :userId)
              and (:fromTime is null or o.createdAt >= :fromTime)
              and (:toTime is null or o.createdAt < :toTime)
            """)
    Object[] summarizeOrders(@Param("userId") Long userId,
                             @Param("fromTime") LocalDateTime fromTime,
                             @Param("toTime") LocalDateTime toTime);

    @Query("""
            select count(o)
            from PaymentOrder o
            where (:userId is null or o.userId = :userId)
              and o.status = 'PAID'
            """)
    long countPaidOrders(@Param("userId") Long userId);

    @Query("""
            select o from PaymentOrder o
            where (:userId is null or o.userId = :userId)
              and (:beforeId is null or o.id < :beforeId)
              and o.status = 'PAID'
              and not exists (
                  select l.id from WalletLedger l
                  where l.userId = o.userId
                    and l.type = 'PAYMENT_RECHARGE'
                    and l.status = 'POSTED'
                    and l.idempotencyKey = concat('payment:', o.orderNo)
              )
            order by o.id desc
            """)
    Slice<PaymentOrder> findPaidOrdersMissingRechargeLedger(@Param("userId") Long userId,
                                                            @Param("beforeId") Long beforeId,
                                                            Pageable pageable);
}
