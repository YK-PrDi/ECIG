package com.elebusiness.controller;

import com.elebusiness.model.entity.PaymentOrder;
import com.elebusiness.service.billing.PaymentCallbackService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
public class PaymentCallbackController {

    private final PaymentCallbackService callbackService;

    public PaymentCallbackController(PaymentCallbackService callbackService) {
        this.callbackService = callbackService;
    }

    @PostMapping("/api/billing/payment-callbacks/{provider}")
    public ResponseEntity<Map<String, Object>> paymentCallback(
            @PathVariable String provider,
            @RequestHeader Map<String, String> headers,
            @RequestBody Map<String, Object> body) {
        try {
            PaymentOrder order = callbackService.handleCallback(provider, body, headers);
            return ResponseEntity.ok(Map.of("success", true, "order", orderMap(order)));
        } catch (SecurityException e) {
            return error(HttpStatus.FORBIDDEN, e.getMessage());
        } catch (IllegalStateException e) {
            return error(HttpStatus.SERVICE_UNAVAILABLE, e.getMessage());
        } catch (IllegalArgumentException e) {
            return error(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }

    private ResponseEntity<Map<String, Object>> error(HttpStatus status, String message) {
        return ResponseEntity.status(status).body(Map.of("success", false, "error", message == null ? "" : message));
    }

    private Map<String, Object> orderMap(PaymentOrder order) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("orderNo", order.getOrderNo());
        map.put("userId", order.getUserId());
        map.put("points", order.getPoints());
        map.put("amountCents", order.getAmountCents());
        map.put("currency", order.getCurrency());
        map.put("provider", order.getProvider());
        map.put("providerOrderNo", order.getProviderOrderNo());
        map.put("status", order.getStatus());
        map.put("paidAt", order.getPaidAt());
        return map;
    }
}
