package com.elebusiness.service.provider;

import com.elebusiness.config.AppProperties;
import com.elebusiness.model.entity.UserProviderCredential;
import com.elebusiness.repository.UserProviderCredentialRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class UserProviderCredentialServiceTest {

    @Test
    void upsertRequiresCredentialSecretBeforeSavingSensitivePayload() {
        UserProviderCredentialService service = new UserProviderCredentialService(
                mock(UserProviderCredentialRepository.class), new AppProperties());

        assertThrows(IllegalStateException.class, () -> service.upsertCredential(
                1001L, "liblib", "default",
                Map.of("accessKey", "ak-1", "secretKey", "sk-1"),
                true));
    }

    @Test
    void upsertEncryptsPayloadAndResolveDecryptsOnlyEnabledCredentialForSameUser() {
        UserProviderCredentialRepository repository = mock(UserProviderCredentialRepository.class);
        AppProperties properties = new AppProperties();
        properties.getBilling().setCredentialSecret("local-test-secret");
        UserProviderCredentialService service = new UserProviderCredentialService(repository, properties);

        when(repository.findByUserIdAndProviderAndCredentialName(1001L, "liblib", "default"))
                .thenReturn(Optional.empty());
        when(repository.save(any(UserProviderCredential.class))).thenAnswer(invocation -> invocation.getArgument(0));

        UserProviderCredentialService.CredentialSummary summary = service.upsertCredential(
                1001L, " liblib ", " ",
                Map.of("accessKey", "ak-1", "secretKey", "sk-1"),
                true);

        ArgumentCaptor<UserProviderCredential> credentialCaptor = ArgumentCaptor.forClass(UserProviderCredential.class);
        verify(repository).save(credentialCaptor.capture());
        UserProviderCredential saved = credentialCaptor.getValue();
        assertEquals(1001L, summary.userId());
        assertEquals("liblib", summary.provider());
        assertEquals("default", summary.credentialName());
        assertEquals(List.of("accessKey", "secretKey"), summary.payloadKeys());
        assertEquals(1001L, saved.getUserId());
        assertEquals("liblib", saved.getProvider());
        assertEquals("default", saved.getCredentialName());
        assertFalse(saved.getEncryptedPayload().contains("ak-1"));
        assertFalse(saved.getEncryptedPayload().contains("sk-1"));

        when(repository.findByUserIdAndProviderAndCredentialName(1001L, "liblib", "default"))
                .thenReturn(Optional.of(saved));
        Optional<UserProviderCredentialService.ResolvedCredential> resolved = service.resolveCredential(
                1001L, "liblib", "default");

        assertTrue(resolved.isPresent());
        assertEquals("ak-1", resolved.get().payload().get("accessKey"));
        assertEquals("sk-1", resolved.get().payload().get("secretKey"));

        saved.setEnabled(false);
        assertTrue(service.resolveCredential(1001L, "liblib", "default").isEmpty());
    }
}
