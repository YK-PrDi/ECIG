package com.elebusiness.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SessionStoreConfigurationTest {

    @Test
    void defaultConfigurationKeepsSpringSessionAutoConfigurationEnabled() throws Exception {
        YamlPropertySourceLoader loader = new YamlPropertySourceLoader();
        List<PropertySource<?>> sources = loader.load("application", new ClassPathResource("application.yml"));

        Object excludedAutoConfiguration = sources.stream()
                .map(source -> source.getProperty("spring.autoconfigure.exclude"))
                .filter(value -> value != null)
                .findFirst()
                .orElse(null);

        assertEquals("${SPRING_AUTOCONFIGURE_EXCLUDE:}", excludedAutoConfiguration);
        Object sessionTimeout = sources.stream()
                .map(source -> source.getProperty("server.servlet.session.timeout"))
                .filter(value -> value != null)
                .findFirst()
                .orElse(null);
        assertEquals("${SERVER_SERVLET_SESSION_TIMEOUT:12h}", sessionTimeout);
    }
}
