package com.elebusiness.controller;

import com.elebusiness.model.entity.GenerationProviderTaskRef;
import com.elebusiness.model.entity.GenerationUsageLog;
import com.elebusiness.repository.GenerationProviderTaskRefRepository;
import com.elebusiness.repository.GenerationUsageLogRepository;
import com.elebusiness.service.auth.AuthService;
import com.elebusiness.service.auth.CurrentUserService;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BillingProviderTaskRefControllerTest {

    @Test
    void onlyAdminCanLookupUsageByProviderTaskId() {
        GenerationProviderTaskRefRepository refRepository = mock(GenerationProviderTaskRefRepository.class);
        GenerationUsageLogRepository usageRepository = mock(GenerationUsageLogRepository.class);
        BillingProviderTaskRefController controller = new BillingProviderTaskRefController(
                refRepository, usageRepository, new CurrentUserService());

        assertThrows(ResponseStatusException.class, () -> controller.lookup(
                userSession(1001L, "USER"), "liblib", "uuid-001"));

        GenerationProviderTaskRef ref = new GenerationProviderTaskRef();
        ref.setId(9L);
        ref.setUserId(2002L);
        ref.setUsageLogId(77L);
        ref.setProvider("liblib");
        ref.setProviderTaskId("uuid-001");
        ref.setCostSource("PROVIDER_TASK_REPORTED");
        GenerationUsageLog usage = new GenerationUsageLog();
        usage.setId(77L);
        usage.setUserId(2002L);
        usage.setTaskId("task-77");
        usage.setMode("custom");
        usage.setAgentId("liblib-lora");
        usage.setProvider("liblib");
        usage.setStatus("SUCCEEDED");
        usage.setEstimatedPoints(30);
        usage.setActualPoints(18);
        when(refRepository.findByProviderAndProviderTaskId("liblib", "uuid-001")).thenReturn(Optional.of(ref));
        when(usageRepository.findById(77L)).thenReturn(Optional.of(usage));

        Map<String, Object> body = controller.lookup(userSession(1L, "ADMIN"), "liblib", "uuid-001");

        assertEquals(true, body.get("found"));
        Map<?, ?> refBody = (Map<?, ?>) body.get("ref");
        Map<?, ?> usageBody = (Map<?, ?>) body.get("usage");
        assertEquals(77L, refBody.get("usageLogId"));
        assertEquals("uuid-001", refBody.get("providerTaskId"));
        assertEquals("task-77", usageBody.get("taskId"));
        assertEquals("SUCCEEDED", usageBody.get("status"));
        verify(refRepository).findByProviderAndProviderTaskId("liblib", "uuid-001");
        verify(usageRepository).findById(77L);
    }

    private MockHttpSession userSession(long userId, String role) {
        CurrentUserService currentUserService = new CurrentUserService();
        MockHttpSession session = new MockHttpSession();
        currentUserService.bind(session, new AuthService.AuthUser(userId, "user" + userId, "User " + userId, role, 1L));
        return session;
    }
}
