package com.elebusiness.service.billing;

import com.elebusiness.model.entity.PaymentOrder;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
public class PaymentCallbackService {

    private final List<PaymentCallbackVerifier> verifiers;
    private final PaymentOrderService orderService;

    public PaymentCallbackService(List<PaymentCallbackVerifier> verifiers,
                                  PaymentOrderService orderService) {
        this.verifiers = verifiers == null ? List.of() : List.copyOf(verifiers);
        this.orderService = orderService;
    }

    public PaymentOrder handleCallback(String provider, Map<String, Object> body, Map<String, String> headers) {
        PaymentCallbackVerifier verifier = resolveVerifier(provider);
        verifier.verify(provider, body, headers);

        String callbackStatus = stringValue(body, "status", "PAID").toUpperCase(Locale.ROOT);
        if (!isPaidStatus(callbackStatus)) {
            throw new IllegalArgumentException("暂不处理非支付成功回调: " + callbackStatus);
        }
        String orderNo = stringValue(body, "orderNo", "");
        String providerOrderNo = stringValue(body, "providerOrderNo", "");
        long paidAmountCents = longValue(body, "paidAmountCents", 0);
        if (orderNo.isBlank()) {
            throw new IllegalArgumentException("orderNo 不能为空");
        }
        if (paidAmountCents <= 0) {
            throw new IllegalArgumentException("paidAmountCents 必须大于 0");
        }
        return orderService.markPaid(orderNo, providerOrderNo, paidAmountCents);
    }

    private PaymentCallbackVerifier resolveVerifier(String provider) {
        return verifiers.stream()
                .filter(verifier -> verifier.supports(provider))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("未支持的支付回调渠道: " + (provider == null ? "" : provider)));
    }

    private boolean isPaidStatus(String status) {
        return "PAID".equals(status)
                || "SUCCESS".equals(status)
                || "TRADE_SUCCESS".equals(status);
    }

    private long longValue(Map<String, Object> body, String key, long fallback) {
        if (body == null || body.get(key) == null) return fallback;
        Object value = body.get(key);
        if (value instanceof Number number) return number.longValue();
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private String stringValue(Map<String, Object> body, String key, String fallback) {
        if (body == null || body.get(key) == null) return fallback;
        return String.valueOf(body.get(key)).trim();
    }
}
