package com.elebusiness.service.video;

import com.elebusiness.config.AppProperties;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.Proxy;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VideoMediaProxyPolicyTest {

    @Test
    void staysDirectWhenMediaProxyIsDisabled() {
        AppProperties properties = properties(false, "socks5", List.of("vidgen.x.ai"));

        assertTrue(new VideoMediaProxyPolicy(properties)
                .proxyFor("https://vidgen.x.ai/video.mp4")
                .isEmpty());
    }

    @Test
    void usesSocksProxyOnlyForAllowlistedMediaHostsAndSubdomains() {
        AppProperties properties = properties(true, "socks5", List.of("vidgen.x.ai"));
        VideoMediaProxyPolicy policy = new VideoMediaProxyPolicy(properties);

        Proxy exact = policy.proxyFor("https://vidgen.x.ai/video.mp4").orElseThrow();
        Proxy subdomain = policy.proxyFor("https://cdn.vidgen.x.ai/video.mp4").orElseThrow();

        assertEquals(Proxy.Type.SOCKS, exact.type());
        assertEquals(Proxy.Type.SOCKS, subdomain.type());
        assertEquals(new InetSocketAddress("127.0.0.1", 40000), exact.address());
        assertTrue(policy.proxyFor("https://sui-xiang.com/v1/videos/task").isEmpty());
        assertTrue(policy.proxyFor("https://example.com/video.mp4").isEmpty());
    }

    @Test
    void supportsHttpProxyType() {
        AppProperties properties = properties(true, "http", List.of("media.example.com"));

        Proxy proxy = new VideoMediaProxyPolicy(properties)
                .proxyFor("https://media.example.com/video.mp4")
                .orElseThrow();

        assertEquals(Proxy.Type.HTTP, proxy.type());
    }

    @Test
    void ignoresInvalidUrlsAndIncompleteProxyConfiguration() {
        AppProperties properties = properties(true, "socks5", List.of("vidgen.x.ai"));
        properties.getSuiXiangVideo().getMediaProxy().setHost("");
        VideoMediaProxyPolicy policy = new VideoMediaProxyPolicy(properties);

        assertTrue(policy.proxyFor("not-a-url").isEmpty());
        assertTrue(policy.proxyFor("https://vidgen.x.ai/video.mp4").isEmpty());
    }

    private AppProperties properties(boolean enabled, String type, List<String> hosts) {
        AppProperties properties = new AppProperties();
        AppProperties.MediaProxy proxy = properties.getSuiXiangVideo().getMediaProxy();
        proxy.setEnabled(enabled);
        proxy.setType(type);
        proxy.setHost("127.0.0.1");
        proxy.setPort(40000);
        proxy.setHosts(hosts);
        return properties;
    }
}
