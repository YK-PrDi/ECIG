package com.elebusiness.service.video;

import com.elebusiness.config.AppProperties;

import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.URI;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

public final class VideoMediaProxyPolicy {

    private final AppProperties properties;

    public VideoMediaProxyPolicy(AppProperties properties) {
        this.properties = properties;
    }

    public Optional<Proxy> proxyFor(String mediaUrl) {
        AppProperties.MediaProxy config = properties.getSuiXiangVideo().getMediaProxy();
        if (!config.isEnabled() || config.getHost().isBlank() || !validPort(config.getPort())) {
            return Optional.empty();
        }

        String mediaHost = hostOf(mediaUrl);
        if (mediaHost.isBlank() || !matchesAllowlist(mediaHost, config.getHosts())) {
            return Optional.empty();
        }

        Proxy.Type type = "http".equalsIgnoreCase(config.getType())
                ? Proxy.Type.HTTP
                : Proxy.Type.SOCKS;
        return Optional.of(new Proxy(type, new InetSocketAddress(config.getHost(), config.getPort())));
    }

    private boolean matchesAllowlist(String mediaHost, List<String> allowlist) {
        if (allowlist == null || allowlist.isEmpty()) return false;
        String normalizedHost = mediaHost.toLowerCase(Locale.ROOT);
        return allowlist.stream()
                .filter(value -> value != null && !value.isBlank())
                .map(value -> value.trim().toLowerCase(Locale.ROOT))
                .anyMatch(allowed -> normalizedHost.equals(allowed)
                        || normalizedHost.endsWith("." + allowed));
    }

    private String hostOf(String mediaUrl) {
        try {
            String host = URI.create(mediaUrl).getHost();
            return host == null ? "" : host;
        } catch (IllegalArgumentException e) {
            return "";
        }
    }

    private boolean validPort(int port) {
        return port > 0 && port <= 65_535;
    }
}
