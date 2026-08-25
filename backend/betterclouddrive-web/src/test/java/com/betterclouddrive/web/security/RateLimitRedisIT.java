package com.betterclouddrive.web.security;

import com.betterclouddrive.web.config.ClientIpProperties;
import com.betterclouddrive.web.config.RateLimitProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class RateLimitRedisIT {

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:8.8.1-alpine"))
            .withExposedPorts(6379);

    @Test
    void admitsExactlyTheConfiguredNumberUnderConcurrency() throws Exception {
        LettuceConnectionFactory connectionFactory = new LettuceConnectionFactory(
                REDIS.getHost(), REDIS.getMappedPort(6379));
        connectionFactory.afterPropertiesSet();
        connectionFactory.start();
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        try {
            StringRedisTemplate redis = new StringRedisTemplate(connectionFactory);
            redis.afterPropertiesSet();
            RateLimitProperties properties = new RateLimitProperties();
            properties.setDefaultPermits(5);
            properties.setDefaultPeriodSeconds(60);
            RateLimitFilter filter = new RateLimitFilter(
                    redis,
                    properties,
                    new ClientIpResolver(new ClientIpProperties()),
                    registry,
                    new ObjectMapper());

            int requests = 20;
            CountDownLatch ready = new CountDownLatch(requests);
            CountDownLatch start = new CountDownLatch(1);
            var responses = java.util.Collections.synchronizedList(new ArrayList<MockHttpServletResponse>());
            try (var executor = Executors.newFixedThreadPool(requests)) {
                List<java.util.concurrent.Future<?>> futures = new ArrayList<>();
                for (int index = 0; index < requests; index++) {
                    futures.add(executor.submit(() -> {
                        ready.countDown();
                        start.await();
                        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/files");
                        request.setRemoteAddr("192.0.2.25");
                        MockHttpServletResponse response = new MockHttpServletResponse();
                        filter.doFilter(request, response, (req, res) -> { });
                        responses.add(response);
                        return null;
                    }));
                }
                assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
                start.countDown();
                for (var future : futures) {
                    future.get(10, TimeUnit.SECONDS);
                }
            }

            assertThat(responses).hasSize(requests);
            assertThat(responses.stream().filter(response -> response.getStatus() == 200)).hasSize(5);
            assertThat(responses.stream().filter(response -> response.getStatus() == 429)).hasSize(15);
            assertThat(responses.stream()
                    .filter(response -> response.getStatus() == 429)
                    .allMatch(response -> response.getHeader("Retry-After") != null)).isTrue();
        } finally {
            registry.close();
            connectionFactory.destroy();
        }
    }
}
