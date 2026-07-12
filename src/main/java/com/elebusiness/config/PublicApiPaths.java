package com.elebusiness.config;

final class PublicApiPaths {

    private PublicApiPaths() {
    }

    static boolean isPublic(String uri) {
        if (uri == null) {
            return false;
        }
        return uri.startsWith("/api/auth/")
                || uri.equals("/api/prompts")
                || uri.startsWith("/api/prompts/")
                || uri.equals("/api/categories/index")
                || uri.equals("/api/config/status")
                || uri.equals("/api/agents")
                // 支付平台不会携带站内用户会话，由回调服务校验专用 Token。
                || uri.startsWith("/api/billing/payment-callbacks/");
    }
}
