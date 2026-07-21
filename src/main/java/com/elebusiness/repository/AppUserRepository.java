package com.elebusiness.repository;

import com.elebusiness.model.entity.AppUser;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface AppUserRepository extends JpaRepository<AppUser, Long> {
    Optional<AppUser> findByUsername(String username);
    boolean existsByUsername(String username);
    List<AppUser> findByEnterpriseId(Long enterpriseId);
    long countByEnterpriseId(Long enterpriseId);
}
