package com.elebusiness.repository;

import com.elebusiness.model.entity.BillingSchedulerState;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface BillingSchedulerStateRepository extends JpaRepository<BillingSchedulerState, Long> {
    Optional<BillingSchedulerState> findByStateKey(String stateKey);
}
