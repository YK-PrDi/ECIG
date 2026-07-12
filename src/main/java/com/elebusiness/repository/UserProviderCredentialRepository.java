package com.elebusiness.repository;

import com.elebusiness.model.entity.UserProviderCredential;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface UserProviderCredentialRepository extends JpaRepository<UserProviderCredential, Long> {
    List<UserProviderCredential> findByUserIdOrderByProviderAscCredentialNameAsc(Long userId);
    Optional<UserProviderCredential> findByUserIdAndProviderAndCredentialName(Long userId, String provider, String credentialName);
}
