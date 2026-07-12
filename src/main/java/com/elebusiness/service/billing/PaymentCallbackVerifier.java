package com.elebusiness.service.billing;

import java.util.Map;

public interface PaymentCallbackVerifier {
    boolean supports(String provider);

    void verify(String provider, Map<String, Object> body, Map<String, String> headers);
}
