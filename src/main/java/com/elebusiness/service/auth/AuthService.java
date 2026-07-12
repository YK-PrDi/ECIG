package com.elebusiness.service.auth;

import com.elebusiness.config.AppProperties;
import com.elebusiness.model.entity.AppUser;
import com.elebusiness.repository.AppUserRepository;
import com.elebusiness.service.billing.BillingService;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

@Service
public class AuthService {

    private final AppUserRepository userRepository;
    private final BillingService billingService;
    private final AppProperties appProperties;
    private final PasswordHasher passwordHasher;

    public AuthService(AppUserRepository userRepository,
                       BillingService billingService,
                       AppProperties appProperties,
                       PasswordHasher passwordHasher) {
        this.userRepository = userRepository;
        this.billingService = billingService;
        this.appProperties = appProperties;
        this.passwordHasher = passwordHasher;
    }

    @PostConstruct
    @Transactional
    public void bootstrapDefaultAdmin() {
        String username = defaultUsername();
        if (userRepository.existsByUsername(username)) {
            return;
        }
        AppUser admin = new AppUser();
        admin.setUsername(username);
        admin.setDisplayName(defaultDisplayName());
        admin.setPasswordHash(passwordHasher.hash(defaultPassword()));
        admin.setRole("ADMIN");
        admin.setEnabled(true);
        AppUser saved = userRepository.save(admin);
        if (saved.getId() != null) {
            billingService.ensureWallet(saved.getId());
        }
    }

    @Transactional
    public Optional<AuthUser> authenticate(String username, String password) {
        String loginName = normalizeUsername(username);
        return userRepository.findByUsername(loginName)
                .filter(AppUser::isEnabled)
                .filter(user -> passwordHasher.verify(password, user.getPasswordHash()))
                .map(user -> {
                    if (user.getId() != null) {
                        billingService.ensureWallet(user.getId());
                    }
                    return toAuthUser(user);
                });
    }

    public AuthUser toAuthUser(AppUser user) {
        return new AuthUser(
                user.getId() == null ? 0L : user.getId(),
                user.getUsername(),
                user.getDisplayName(),
                user.getRole()
        );
    }

    @Transactional
    public AuthUser createUser(String username, String password, String displayName, String role) {
        String safeUsername = username == null ? "" : username.trim();
        if (safeUsername.isBlank()) {
            throw new IllegalArgumentException("账号不能为空");
        }
        if (password == null || password.isBlank()) {
            throw new IllegalArgumentException("密码不能为空");
        }
        if (userRepository.existsByUsername(safeUsername)) {
            throw new IllegalArgumentException("账号已存在");
        }
        AppUser user = new AppUser();
        user.setUsername(safeUsername);
        user.setDisplayName(displayName == null || displayName.isBlank() ? safeUsername : displayName.trim());
        user.setPasswordHash(passwordHasher.hash(password));
        user.setRole(role == null || role.isBlank() ? "USER" : role.trim().toUpperCase());
        user.setEnabled(true);
        AppUser saved = userRepository.save(user);
        if (saved.getId() != null) {
            billingService.ensureWallet(saved.getId());
        }
        return toAuthUser(saved);
    }

    private String normalizeUsername(String username) {
        if (username == null || username.isBlank()) {
            return defaultUsername();
        }
        return username.trim();
    }

    private String defaultUsername() {
        String value = appProperties.getAuth().getUsername();
        return value == null || value.isBlank() ? "admin" : value.trim();
    }

    private String defaultPassword() {
        String value = appProperties.getAuth().getPassword();
        return value == null || value.isBlank() ? "123456" : value;
    }

    private String defaultDisplayName() {
        String value = appProperties.getAuth().getDisplayName();
        return value == null || value.isBlank() ? "Admin 用户" : value.trim();
    }

    public record AuthUser(long id, String username, String displayName, String role) {
    }
}
