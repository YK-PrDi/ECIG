package com.elebusiness.service.auth;

import com.elebusiness.config.AppProperties;
import com.elebusiness.model.entity.AppUser;
import com.elebusiness.repository.AppUserRepository;
import com.elebusiness.repository.CompanyAssetRepository;
import com.elebusiness.repository.EnterpriseRepository;
import com.elebusiness.service.billing.BillingService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @Mock
    AppUserRepository userRepository;

    @Mock
    BillingService billingService;

    @Test
    void blankUsernameFallsBackToAdminForLegacyLogin() {
        PasswordHasher hasher = new PasswordHasher();
        AppProperties props = new AppProperties();
        props.getAuth().setUsername("admin");

        AppUser admin = new AppUser();
        admin.setId(1L);
        admin.setUsername("admin");
        admin.setDisplayName("Admin 用户");
        admin.setPasswordHash(hasher.hash("123456"));
        admin.setEnabled(true);
        admin.setRole("ADMIN");

        when(userRepository.findByUsername("admin")).thenReturn(Optional.of(admin));

        AuthService service = new AuthService(userRepository,
                mock(EnterpriseRepository.class), mock(CompanyAssetRepository.class),
                billingService, props, hasher);

        Optional<AuthService.AuthUser> result = service.authenticate("", "123456");

        assertTrue(result.isPresent());
        assertEquals(1L, result.get().id());
        assertEquals("admin", result.get().username());
        verify(billingService).ensureWallet(1L);
    }
}
