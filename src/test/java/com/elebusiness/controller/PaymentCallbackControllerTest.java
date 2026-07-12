package com.elebusiness.controller;

import com.elebusiness.model.entity.PaymentOrder;
import com.elebusiness.service.billing.PaymentCallbackService;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PaymentCallbackControllerTest {

    @Test
    void callbackDelegatesToVerifierAndReturnsOrderSnapshot() {
        PaymentCallbackService callbackService = mock(PaymentCallbackService.class);
        PaymentCallbackController controller = new PaymentCallbackController(callbackService);
        Map<String, Object> body = Map.of("orderNo", "R202607100001", "paidAmountCents", 19900);
        Map<String, String> headers = Map.of("X-Billing-Callback-Token", "server-token");
        PaymentOrder order = new PaymentOrder();
        order.setOrderNo("R202607100001");
        order.setUserId(1001L);
        order.setStatus("PAID");
        when(callbackService.handleCallback("manual", body, headers)).thenReturn(order);

        ResponseEntity<Map<String, Object>> response = controller.paymentCallback("manual", headers, body);

        assertEquals(200, response.getStatusCode().value());
        assertEquals(true, response.getBody().get("success"));
        Map<?, ?> orderBody = (Map<?, ?>) response.getBody().get("order");
        assertEquals("R202607100001", orderBody.get("orderNo"));
        assertEquals(1001L, orderBody.get("userId"));
        assertEquals("PAID", orderBody.get("status"));
        verify(callbackService).handleCallback("manual", body, headers);
    }
}
