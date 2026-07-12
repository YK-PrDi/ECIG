package com.elebusiness.service.agent;

import com.elebusiness.config.LiblibConfig;
import com.elebusiness.service.CosService;
import com.elebusiness.service.billing.BillingService;
import com.elebusiness.service.provider.UserProviderCredentialService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class LiblibLoraAgentCredentialTest {

    @AfterEach
    void tearDown() {
        GenerationProviderCostContext.clear();
    }

    @Test
    void requestCredentialPrefersCurrentUsersEnabledLiblibCredential() {
        LiblibConfig config = new LiblibConfig();
        UserProviderCredentialService credentialService = mock(UserProviderCredentialService.class);
        LiblibLoraAgent agent = new LiblibLoraAgent(config, mock(CosService.class), credentialService);
        when(credentialService.resolveCredential(1001L, "liblib", "default"))
                .thenReturn(Optional.of(new UserProviderCredentialService.ResolvedCredential(
                        1001L,
                        "liblib",
                        "default",
                        Map.of("accessKey", "user-ak", "secretKey", "user-sk")
                )));

        LiblibLoraAgent.RequestCredential credential = GenerationInvocationContext.withUserId(
                1001L,
                agent::resolveRequestCredential
        );

        assertEquals("user", credential.source());
        assertEquals("user-ak", credential.accessKey());
        assertEquals("user-sk", credential.secretKey());
    }

    @Test
    void requestCredentialFallsBackToPlatformConfigWhenUserCredentialIsMissing() {
        LiblibConfig config = new LiblibConfig();
        config.setAccessKey("platform-ak");
        config.setSecretKey("platform-sk");
        LiblibLoraAgent agent = new LiblibLoraAgent(config, mock(CosService.class), mock(UserProviderCredentialService.class));

        LiblibLoraAgent.RequestCredential credential = agent.resolveRequestCredential();

        assertEquals("platform", credential.source());
        assertEquals("platform-ak", credential.accessKey());
        assertEquals("platform-sk", credential.secretKey());
    }

    @Test
    void recordsSubmittedGenerateUuidForBillingAuditContext() {
        LiblibLoraAgent agent = new LiblibLoraAgent(
                new LiblibConfig(), mock(CosService.class), mock(UserProviderCredentialService.class));

        agent.recordSubmittedGenerateUuid("generate-uuid-001");

        BillingService.ProviderCost cost = GenerationProviderCostContext.snapshotAndClear();
        assertEquals("generate-uuid-001", cost.providerTaskId());
        assertEquals("PROVIDER_TASK_REPORTED", cost.costSource());
    }
}
