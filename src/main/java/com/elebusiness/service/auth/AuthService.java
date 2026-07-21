package com.elebusiness.service.auth;

import com.elebusiness.config.AppProperties;
import com.elebusiness.model.entity.AppUser;
import com.elebusiness.model.entity.Enterprise;
import com.elebusiness.repository.AppUserRepository;
import com.elebusiness.repository.CompanyAssetRepository;
import com.elebusiness.repository.EnterpriseRepository;
import com.elebusiness.service.billing.BillingService;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/**
 * 认证与账号体系。
 * 角色三级：SUPERADMIN（平台中控，无企业）/ ADMIN（企业负责人）/ USER（企业成员）。
 */
@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);
    public static final String ROLE_SUPERADMIN = "SUPERADMIN";
    public static final String ROLE_ADMIN = "ADMIN";
    public static final String ROLE_USER = "USER";
    private static final String DEFAULT_ENTERPRISE_NAME = "羽刃科技（默认企业）";

    private final AppUserRepository userRepository;
    private final EnterpriseRepository enterpriseRepository;
    private final CompanyAssetRepository assetRepository;
    private final BillingService billingService;
    private final AppProperties appProperties;
    private final PasswordHasher passwordHasher;

    public AuthService(AppUserRepository userRepository,
                       EnterpriseRepository enterpriseRepository,
                       CompanyAssetRepository assetRepository,
                       BillingService billingService,
                       AppProperties appProperties,
                       PasswordHasher passwordHasher) {
        this.userRepository = userRepository;
        this.enterpriseRepository = enterpriseRepository;
        this.assetRepository = assetRepository;
        this.billingService = billingService;
        this.appProperties = appProperties;
        this.passwordHasher = passwordHasher;
    }

    /**
     * 启动引导（顺序固定）：
     * 1. 没有任何账号时创建默认管理员
     * 2. 存量账号/资产归入默认企业（多企业改造的数据迁移）
     * 3. 按环境变量创建平台中控账号（SUPERADMIN_USERNAME / SUPERADMIN_PASSWORD）
     */
    @PostConstruct
    @Transactional
    public void bootstrap() {
        bootstrapDefaultAdmin();
        migrateToDefaultEnterprise();
        bootstrapSuperAdmin();
    }

    private void bootstrapDefaultAdmin() {
        String username = defaultUsername();
        if (userRepository.existsByUsername(username)) {
            return;
        }
        AppUser admin = new AppUser();
        admin.setUsername(username);
        admin.setDisplayName(defaultDisplayName());
        admin.setPasswordHash(passwordHasher.hash(defaultPassword()));
        admin.setRole(ROLE_ADMIN);
        admin.setEnabled(true);
        AppUser saved = userRepository.save(admin);
        if (saved.getId() != null) {
            billingService.ensureWallet(saved.getId());
        }
    }

    /** 存量 ADMIN/USER 账号（enterpriseId 为空）归入默认企业；遗留资产一并归入。 */
    private void migrateToDefaultEnterprise() {
        List<AppUser> orphans = userRepository.findAll().stream()
                .filter(u -> u.getEnterpriseId() == null)
                .filter(u -> !ROLE_SUPERADMIN.equalsIgnoreCase(u.getRole()))
                .toList();
        boolean hasOrphanAssets = !assetRepository.findByEnterpriseIdIsNull().isEmpty();
        if (orphans.isEmpty() && !hasOrphanAssets) {
            return;
        }
        Enterprise enterprise = enterpriseRepository.findAllByOrderByCreatedAtAsc().stream()
                .findFirst()
                .orElseGet(() -> {
                    Enterprise e = new Enterprise();
                    e.setName(DEFAULT_ENTERPRISE_NAME);
                    return enterpriseRepository.save(e);
                });
        for (AppUser user : orphans) {
            user.setEnterpriseId(enterprise.getId());
            userRepository.save(user);
        }
        // 默认企业的负责人：第一个 ADMIN
        if (enterprise.getOwnerId() == null) {
            orphans.stream()
                    .filter(u -> ROLE_ADMIN.equalsIgnoreCase(u.getRole()))
                    .findFirst()
                    .ifPresent(owner -> {
                        enterprise.setOwnerId(owner.getId());
                        enterprise.setOwnerName(owner.getDisplayName());
                        enterpriseRepository.save(enterprise);
                    });
        }
        assetRepository.findByEnterpriseIdIsNull().forEach(asset -> {
            asset.setEnterpriseId(enterprise.getId());
            assetRepository.save(asset);
        });
        log.info("多企业迁移完成：{} 个账号归入企业 [{}]", orphans.size(), enterprise.getName());
    }

    /** 平台中控账号：仅通过环境变量创建，不在代码里留默认凭据。 */
    private void bootstrapSuperAdmin() {
        String username = System.getenv("SUPERADMIN_USERNAME");
        String password = System.getenv("SUPERADMIN_PASSWORD");
        if (username == null || username.isBlank() || password == null || password.isBlank()) {
            return;
        }
        username = username.trim();
        if (userRepository.existsByUsername(username)) {
            return;
        }
        AppUser sa = new AppUser();
        sa.setUsername(username);
        sa.setDisplayName("平台中控");
        sa.setPasswordHash(passwordHasher.hash(password));
        sa.setRole(ROLE_SUPERADMIN);
        sa.setEnabled(true);
        sa.setEnterpriseId(null);
        AppUser saved = userRepository.save(sa);
        if (saved.getId() != null) {
            billingService.ensureWallet(saved.getId());
        }
        log.info("平台中控账号已创建: {}", username);
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
                user.getRole(),
                user.getEnterpriseId()
        );
    }

    @Transactional
    public AuthUser createUser(String username, String password, String displayName, String role) {
        return createUserInEnterprise(username, password, displayName, role, null);
    }

    @Transactional
    public AuthUser createUserInEnterprise(String username, String password, String displayName,
                                           String role, Long enterpriseId) {
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
        String safeRole = role == null || role.isBlank() ? ROLE_USER : role.trim().toUpperCase();
        // 平台中控账号只能由环境变量引导创建
        if (ROLE_SUPERADMIN.equals(safeRole)) {
            throw new IllegalArgumentException("不能通过接口创建平台中控账号");
        }
        AppUser user = new AppUser();
        user.setUsername(safeUsername);
        user.setDisplayName(displayName == null || displayName.isBlank() ? safeUsername : displayName.trim());
        user.setPasswordHash(passwordHasher.hash(password));
        user.setRole(safeRole);
        user.setEnterpriseId(enterpriseId);
        user.setEnabled(true);
        AppUser saved = userRepository.save(user);
        if (saved.getId() != null) {
            billingService.ensureWallet(saved.getId());
        }
        return toAuthUser(saved);
    }

    /**
     * 自助注册：创建普通账号；若填写企业名则同时创建企业，注册人成为该企业负责人。
     */
    @Transactional
    public AuthUser register(String username, String password, String displayName, String enterpriseName) {
        boolean withEnterprise = enterpriseName != null && !enterpriseName.isBlank();
        AuthUser user = createUserInEnterprise(username, password, displayName,
                withEnterprise ? ROLE_ADMIN : ROLE_USER, null);
        if (withEnterprise) {
            Enterprise enterprise = createEnterprise(enterpriseName.trim(), user);
            AppUser entity = userRepository.findById(user.id()).orElseThrow();
            entity.setEnterpriseId(enterprise.getId());
            userRepository.save(entity);
            return toAuthUser(entity);
        }
        return user;
    }

    /** 建企业并指定负责人。 */
    @Transactional
    public Enterprise createEnterprise(String name, AuthUser owner) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("企业名称不能为空");
        }
        Enterprise enterprise = new Enterprise();
        enterprise.setName(name.trim());
        if (owner != null && owner.id() > 0) {
            enterprise.setOwnerId(owner.id());
            enterprise.setOwnerName(owner.displayName());
        }
        return enterpriseRepository.save(enterprise);
    }

    /** 中控指定/更换企业负责人：用户绑定到该企业并升级为 ADMIN。 */
    @Transactional
    public void assignOwner(long enterpriseId, long userId) {
        Enterprise enterprise = enterpriseRepository.findById(enterpriseId)
                .orElseThrow(() -> new IllegalArgumentException("企业不存在"));
        AppUser user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("用户不存在"));
        if (ROLE_SUPERADMIN.equalsIgnoreCase(user.getRole())) {
            throw new IllegalArgumentException("平台中控账号不能兼任企业负责人");
        }
        user.setEnterpriseId(enterpriseId);
        user.setRole(ROLE_ADMIN);
        userRepository.save(user);
        enterprise.setOwnerId(user.getId());
        enterprise.setOwnerName(user.getDisplayName());
        enterpriseRepository.save(enterprise);
    }

    /** 管理员重置员工密码。 */
    @Transactional
    public void resetPassword(long userId, String newPassword) {
        if (newPassword == null || newPassword.isBlank()) {
            throw new IllegalArgumentException("新密码不能为空");
        }
        AppUser user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("用户不存在"));
        user.setPasswordHash(passwordHasher.hash(newPassword));
        userRepository.save(user);
    }

    /** 管理员启用/停用员工账号（停用后立即无法登录，由 authenticate 的 enabled 过滤保证）。 */
    @Transactional
    public void setEnabled(long userId, boolean enabled) {
        AppUser user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("用户不存在"));
        user.setEnabled(enabled);
        userRepository.save(user);
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
        return value == null || value.isBlank() ? "Admin 用户" : value;
    }

    public record AuthUser(long id, String username, String displayName, String role, Long enterpriseId) {
        public boolean isSuperadmin() { return ROLE_SUPERADMIN.equalsIgnoreCase(role); }
        public boolean isAdmin() { return ROLE_ADMIN.equalsIgnoreCase(role) || isSuperadmin(); }
    }
}
