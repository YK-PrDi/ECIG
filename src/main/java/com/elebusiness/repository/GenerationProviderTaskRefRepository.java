package com.elebusiness.repository;

import com.elebusiness.model.entity.GenerationProviderTaskRef;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface GenerationProviderTaskRefRepository extends JpaRepository<GenerationProviderTaskRef, Long> {
    Optional<GenerationProviderTaskRef> findByProviderAndProviderTaskId(String provider, String providerTaskId);

    List<GenerationProviderTaskRef> findByUsageLogIdOrderByIdAsc(Long usageLogId);
}
