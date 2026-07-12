package com.elebusiness.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

class RedisSessionDependencyTest {

    @Test
    void redisSessionDependenciesAreAvailableOnClasspath() {
        assertDoesNotThrow(() -> Class.forName(
                "org.springframework.session.data.redis.config.annotation.web.http.RedisHttpSessionConfiguration"));
        assertDoesNotThrow(() -> Class.forName(
                "org.springframework.data.redis.connection.RedisConnectionFactory"));
    }
}
