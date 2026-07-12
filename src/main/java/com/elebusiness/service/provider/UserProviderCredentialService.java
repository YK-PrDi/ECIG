package com.elebusiness.service.provider;

import com.elebusiness.config.AppProperties;
import com.elebusiness.model.entity.UserProviderCredential;
import com.elebusiness.repository.UserProviderCredentialRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
public class UserProviderCredentialService {

    private static final String DEFAULT_CREDENTIAL_NAME = "default";
    private static final int GCM_TAG_BITS = 128;
    private static final int GCM_IV_BYTES = 12;

    private final UserProviderCredentialRepository credentialRepository;
    private final AppProperties appProperties;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final SecureRandom secureRandom = new SecureRandom();

    public UserProviderCredentialService(UserProviderCredentialRepository credentialRepository,
                                         AppProperties appProperties) {
        this.credentialRepository = credentialRepository;
        this.appProperties = appProperties;
    }

    @Transactional
    public CredentialSummary upsertCredential(long userId, String provider, String credentialName,
                                              Map<String, Object> payload, boolean enabled) {
        if (userId <= 0) {
            throw new IllegalArgumentException("userId 必须大于 0");
        }
        String safeProvider = normalizeProvider(provider);
        String safeCredentialName = normalizeCredentialName(credentialName);
        Map<String, Object> safePayload = normalizePayload(payload);

        UserProviderCredential credential = credentialRepository
                .findByUserIdAndProviderAndCredentialName(userId, safeProvider, safeCredentialName)
                .orElseGet(UserProviderCredential::new);
        credential.setUserId(userId);
        credential.setProvider(safeProvider);
        credential.setCredentialName(safeCredentialName);
        credential.setEncryptedPayload(encryptPayload(safePayload));
        credential.setEnabled(enabled);
        UserProviderCredential saved = credentialRepository.save(credential);
        return summaryOf(saved, safePayload);
    }

    @Transactional(readOnly = true)
    public List<CredentialSummary> listSummaries(long userId) {
        return credentialRepository.findByUserIdOrderByProviderAscCredentialNameAsc(userId).stream()
                .map(this::summaryOf)
                .toList();
    }

    @Transactional(readOnly = true)
    public Optional<ResolvedCredential> resolveCredential(long userId, String provider, String credentialName) {
        String safeProvider = normalizeProvider(provider);
        String safeCredentialName = normalizeCredentialName(credentialName);
        return credentialRepository.findByUserIdAndProviderAndCredentialName(userId, safeProvider, safeCredentialName)
                .filter(UserProviderCredential::isEnabled)
                .map(credential -> new ResolvedCredential(
                        credential.getUserId(),
                        credential.getProvider(),
                        credential.getCredentialName(),
                        decryptPayload(credential.getEncryptedPayload())
                ));
    }

    private CredentialSummary summaryOf(UserProviderCredential credential) {
        return summaryOf(credential, decryptPayload(credential.getEncryptedPayload()));
    }

    private CredentialSummary summaryOf(UserProviderCredential credential, Map<String, Object> payload) {
        return new CredentialSummary(
                credential.getId(),
                credential.getUserId(),
                credential.getProvider(),
                credential.getCredentialName(),
                credential.isEnabled(),
                credential.getCreatedAt(),
                credential.getUpdatedAt(),
                payloadKeys(payload)
        );
    }

    private String encryptPayload(Map<String, Object> payload) {
        try {
            byte[] iv = new byte[GCM_IV_BYTES];
            secureRandom.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, encryptionKey(), new GCMParameterSpec(GCM_TAG_BITS, iv));
            byte[] json = objectMapper.writeValueAsBytes(payload);
            byte[] encrypted = cipher.doFinal(json);
            return "v1."
                    + Base64.getUrlEncoder().withoutPadding().encodeToString(iv)
                    + "."
                    + Base64.getUrlEncoder().withoutPadding().encodeToString(encrypted);
        } catch (Exception e) {
            throw new IllegalStateException("用户供应商凭据加密失败", e);
        }
    }

    private Map<String, Object> decryptPayload(String encryptedPayload) {
        try {
            if (encryptedPayload == null || !encryptedPayload.startsWith("v1.")) {
                throw new IllegalArgumentException("凭据密文格式不正确");
            }
            String[] parts = encryptedPayload.split("\\.", 3);
            if (parts.length != 3) {
                throw new IllegalArgumentException("凭据密文格式不正确");
            }
            byte[] iv = Base64.getUrlDecoder().decode(parts[1]);
            byte[] encrypted = Base64.getUrlDecoder().decode(parts[2]);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, encryptionKey(), new GCMParameterSpec(GCM_TAG_BITS, iv));
            byte[] json = cipher.doFinal(encrypted);
            return objectMapper.readValue(json, new TypeReference<LinkedHashMap<String, Object>>() {});
        } catch (GeneralSecurityException e) {
            throw new SecurityException("用户供应商凭据解密失败", e);
        } catch (Exception e) {
            throw new IllegalStateException("用户供应商凭据解析失败", e);
        }
    }

    private SecretKeySpec encryptionKey() {
        String secret = credentialSecret();
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(secret.getBytes(StandardCharsets.UTF_8));
            return new SecretKeySpec(digest, "AES");
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("用户供应商凭据加密密钥不可用", e);
        }
    }

    private String credentialSecret() {
        AppProperties.Billing billing = appProperties.getBilling();
        String secret = billing == null ? "" : billing.getCredentialSecret();
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException("用户供应商凭据加密密钥未配置");
        }
        return secret;
    }

    private String normalizeProvider(String provider) {
        String safe = provider == null ? "" : provider.trim().toLowerCase();
        if (safe.isBlank()) {
            throw new IllegalArgumentException("provider 不能为空");
        }
        return safe;
    }

    private String normalizeCredentialName(String credentialName) {
        String safe = credentialName == null ? "" : credentialName.trim();
        return safe.isBlank() ? DEFAULT_CREDENTIAL_NAME : safe;
    }

    private Map<String, Object> normalizePayload(Map<String, Object> payload) {
        if (payload == null || payload.isEmpty()) {
            throw new IllegalArgumentException("payload 不能为空");
        }
        Map<String, Object> safe = new LinkedHashMap<>();
        payload.forEach((key, value) -> {
            if (key != null && !key.isBlank() && value != null) {
                safe.put(key.trim(), value);
            }
        });
        if (safe.isEmpty()) {
            throw new IllegalArgumentException("payload 不能为空");
        }
        return safe;
    }

    private List<String> payloadKeys(Map<String, Object> payload) {
        List<String> keys = new ArrayList<>(payload.keySet());
        keys.sort(String::compareTo);
        return keys;
    }

    public record CredentialSummary(
            Long id,
            Long userId,
            String provider,
            String credentialName,
            boolean enabled,
            LocalDateTime createdAt,
            LocalDateTime updatedAt,
            List<String> payloadKeys
    ) {
    }

    public record ResolvedCredential(
            Long userId,
            String provider,
            String credentialName,
            Map<String, Object> payload
    ) {
    }
}
