package com.elebusiness.service.billing;

import com.elebusiness.config.AppProperties;
import com.elebusiness.model.entity.PaymentOrder;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class PaymentCallbackServiceTest {

    @Test
    void unknownProviderIsRejectedBeforeTouchingOrder() {
        PaymentCallbackVerifier verifier = mock(PaymentCallbackVerifier.class);
        PaymentOrderService orderService = mock(PaymentOrderService.class);
        PaymentCallbackService service = new PaymentCallbackService(List.of(verifier), orderService);

        assertThrows(IllegalArgumentException.class, () -> service.handleCallback("wechat",
                Map.<String, Object>of("orderNo", "R202607100001", "paidAmountCents", 19900),
                Map.of("X-Billing-Callback-Token", "token")));

        verifyNoInteractions(orderService);
    }

    @Test
    void verifiedPaidCallbackUsesProviderVerifierThenMarksOrderPaidThroughOrderService() {
        PaymentCallbackVerifier verifier = mock(PaymentCallbackVerifier.class);
        PaymentOrderService orderService = mock(PaymentOrderService.class);
        PaymentCallbackService service = new PaymentCallbackService(List.of(verifier), orderService);
        Map<String, Object> body = Map.of(
                "orderNo", "R202607100001",
                "providerOrderNo", "wx-123",
                "paidAmountCents", 19900,
                "status", "PAID"
        );
        Map<String, String> headers = Map.of("X-Billing-Callback-Token", "server-token");
        PaymentOrder order = new PaymentOrder();
        order.setOrderNo("R202607100001");
        order.setStatus("PAID");
        when(verifier.supports("manual")).thenReturn(true);
        when(orderService.markPaid("R202607100001", "wx-123", 19900L)).thenReturn(order);

        PaymentOrder paid = service.handleCallback("manual", body, headers);

        assertEquals("PAID", paid.getStatus());
        verify(verifier).verify("manual", body, headers);
        verify(orderService).markPaid("R202607100001", "wx-123", 19900L);
    }

    @Test
    void manualVerifierIsDisabledUntilTokenIsConfigured() {
        ManualPaymentCallbackVerifier verifier = new ManualPaymentCallbackVerifier(new AppProperties());

        assertThrows(IllegalStateException.class, () -> verifier.verify("manual",
                Map.<String, Object>of("orderNo", "R202607100001", "paidAmountCents", 19900),
                Map.of("X-Billing-Callback-Token", "token")));
    }

    @Test
    void manualVerifierRejectsInvalidTokenAndAcceptsConfiguredToken() {
        AppProperties properties = new AppProperties();
        properties.getBilling().setCallbackToken("server-token");
        ManualPaymentCallbackVerifier verifier = new ManualPaymentCallbackVerifier(properties);

        assertThrows(SecurityException.class, () -> verifier.verify("manual",
                Map.<String, Object>of("orderNo", "R202607100001", "paidAmountCents", 19900),
                Map.of("X-Billing-Callback-Token", "wrong-token")));

        assertDoesNotThrow(() -> verifier.verify("manual",
                Map.<String, Object>of("orderNo", "R202607100001", "paidAmountCents", 19900),
                Map.of("X-Billing-Callback-Token", "server-token")));
    }
}
