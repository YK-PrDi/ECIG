package com.elebusiness.controller;

import com.elebusiness.config.AppProperties;
import com.elebusiness.service.DingTalkService;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ResourceControllerProductsTest {

    @Test
    void returnsConfigurationStatusInsteadOfServerErrorWhenDingTalkIsMissing() {
        DingTalkService dingTalkService = mock(DingTalkService.class);
        when(dingTalkService.isConfigured()).thenReturn(false);
        ResourceController controller = controller(dingTalkService);

        ResponseEntity<Map<String, Object>> response = controller.getProducts();

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, response.getStatusCode());
        assertEquals("DINGTALK_NOT_CONFIGURED", response.getBody().get("code"));
    }

    @Test
    void returnsGatewayStatusWhenConfiguredDingTalkCallFails() throws Exception {
        DingTalkService dingTalkService = mock(DingTalkService.class);
        when(dingTalkService.isConfigured()).thenReturn(true);
        when(dingTalkService.getAllRecords()).thenThrow(new RuntimeException("upstream unavailable"));
        ResourceController controller = controller(dingTalkService);

        ResponseEntity<Map<String, Object>> response = controller.getProducts();

        assertEquals(HttpStatus.BAD_GATEWAY, response.getStatusCode());
        assertEquals("DINGTALK_PRODUCTS_UNAVAILABLE", response.getBody().get("code"));
    }

    private ResourceController controller(DingTalkService dingTalkService) {
        return new ResourceController(dingTalkService, new AppProperties(), null, null, null, null);
    }
}
