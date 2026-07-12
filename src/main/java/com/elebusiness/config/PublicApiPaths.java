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
                || uri.equals("/api/agents");
    }
}
