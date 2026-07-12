package com.elebusiness.service.agent;

import com.elebusiness.config.AppProperties;
import com.elebusiness.service.provider.UserProviderCredentialService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GptImageAgentCredentialTest {

    @Test
    void requestCredentialPrefersCurrentUsersEnabledGptImageCredential() {
        AppProperties properties = new AppProperties();
        properties.getGptImage().setApiKeys(List.of("platform-key"));
        UserProviderCredentialService credentialService = mock(UserProviderCredentialService.class);
        GptImageAgent agent = new GptImageAgent(properties, credentialService);
        when(credentialService.resolveCredential(1001L, "gpt-image", "default"))
                .thenReturn(Optional.of(new UserProviderCredentialService.ResolvedCredential(
                        1001L,
                        "gpt-image",
                        "default",
                        Map.of("apiKey", "user-key", "baseUrl", "https://user.example/v1/")
                )));

        List<GptImageAgent.RequestCredential> credentials = GenerationInvocationContext.withUserId(
                1001L,
                agent::resolveRequestCredentials
        );

        assertEquals(1, credentials.size());
        assertEquals("user", credentials.get(0).source());
        assertEquals("user-key", credentials.get(0).apiKey());
        assertEquals("https://user.example/v1", credentials.get(0).baseUrl());
    }

    @Test
    void requestCredentialFallsBackToPlatformConfigWhenUserCredentialIsMissing() {
        AppProperties properties = new AppProperties();
        properties.getGptImage().setApiKeys(List.of("platform-a", "platform-b"));
        properties.getGptImage().setBaseUrl("https://platform.example/");
        properties.getGptImage().setKeyBaseUrls(Map.of("platform-b", "https://special.example/"));
        GptImageAgent agent = new GptImageAgent(properties, mock(UserProviderCredentialService.class));

        List<GptImageAgent.RequestCredential> credentials = agent.resolveRequestCredentials();

        assertEquals(2, credentials.size());
        assertEquals("platform", credentials.get(0).source());
        assertEquals("platform-a", credentials.get(0).apiKey());
        assertEquals("https://platform.example", credentials.get(0).baseUrl());
        assertEquals("platform-b", credentials.get(1).apiKey());
        assertEquals("https://special.example", credentials.get(1).baseUrl());
    }
}
