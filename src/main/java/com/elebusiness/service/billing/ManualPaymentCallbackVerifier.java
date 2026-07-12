package com.elebusiness.service.billing;

import com.elebusiness.config.AppProperties;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public class ManualPaymentCallbackVerifier implements PaymentCallbackVerifier {

    static final String CALLBACK_TOKEN_HEADER = "X-Billing-Callback-Token";

    private final AppProperties appProperties;

    public ManualPaymentCallbackVerifier(AppProperties appProperties) {
        this.appProperties = appProperties;
    }

    @Override
    public boolean supports(String provider) {
        return provider != null && "manual".equalsIgnoreCase(provider.trim());
    }

    @Override
    public void verify(String provider, Map<String, Object> body, Map<String, String> headers) {
        String configuredToken = configuredCallbackToken();
        if (configuredToken.isBlank()) {
            throw new IllegalStateException("支付回调 token 未配置，回调入口未启用");
        }
        String callbackToken = headerValue(headers, CALLBACK_TOKEN_HEADER);
        if (callbackToken == null || !configuredToken.equals(callbackToken.trim())) {
            throw new SecurityException("支付回调 token 校验失败");
        }
    }

    private String configuredCallbackToken() {
        AppProperties.Billing billing = appProperties.getBilling();
        if (billing == null || billing.getCallbackToken() == null) {
            return "";
        }
        return billing.getCallbackToken().trim();
    }

    private String headerValue(Map<String, String> headers, String name) {
        if (headers == null || headers.isEmpty()) {
            return null;
        }
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            if (entry.getKey() != null && entry.getKey().equalsIgnoreCase(name)) {
                return entry.getValue();
            }
        }
        return null;
    }
}
