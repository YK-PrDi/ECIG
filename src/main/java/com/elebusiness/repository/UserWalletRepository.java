package com.elebusiness.repository;

import com.elebusiness.model.entity.UserWallet;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface UserWalletRepository extends JpaRepository<UserWallet, Long> {
    Optional<UserWallet> findByUserId(Long userId);

    @Query("""
            select w.userId from UserWallet w
            where (:afterUserId is null or w.userId > :afterUserId)
            order by w.userId asc
            """)
    List<Long> findUserIdsAfter(@Param("afterUserId") Long afterUserId, Pageable pageable);

    @Query("""
            select
              coalesce(sum(w.balancePoints), 0),
              coalesce(sum(w.frozenPoints), 0),
              count(w)
            from UserWallet w
            where (:userId is null or w.userId = :userId)
            """)
    Object[] summarizeWallets(@Param("userId") Long userId);
}
