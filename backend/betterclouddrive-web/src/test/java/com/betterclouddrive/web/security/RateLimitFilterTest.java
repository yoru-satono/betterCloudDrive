package com.betterclouddrive.web.security;

import com.betterclouddrive.web.config.RateLimitProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.List;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RateLimitFilterTest {
    private StringRedisTemplate redis;
    private ZSetOperations<String, String> zset;
    private RateLimitProperties properties;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redis = mock(StringRedisTemplate.class);
        zset = mock(ZSetOperations.class);
        when(redis.opsForZSet()).thenReturn(zset);
        properties = new RateLimitProperties();
        properties.setDefaultPermits(2);
        properties.setUploadPermits(1);
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void skipsNonApiAndGrafanaAuthenticationRequests() throws Exception {
        assertPasses(new MockHttpServletRequest("GET", "/actuator/health"));
        assertPasses(new MockHttpServletRequest("GET", "/api/v1/grafana/auth"));
        verifyNoInteractions(redis);
    }

    @Test
    void returnsSemantic429WhenUploadWindowIsFull() throws Exception {
        when(zset.zCard("ratelimit:ip:127.0.0.1:upload")).thenReturn(1L);
        var request = new MockHttpServletRequest("POST", "/api/v1/upload/init");
        request.setRemoteAddr("127.0.0.1");
        var response = new MockHttpServletResponse();
        var called = new AtomicBoolean();

        new RateLimitFilter(redis, properties).doFilter(request, response, (req, res) -> called.set(true));

        assertThat(called).isFalse();
        assertThat(response.getStatus()).isEqualTo(429);
        assertThat(response.getContentAsString()).contains("error.rateLimited").doesNotContain("\"message\"");
    }

    @Test
    void usesAuthenticatedUserInsteadOfForwardedAddress() throws Exception {
        var principal = new UserPrincipal(42L, "alice", "jti", "ROLE_USER");
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null,
                        List.of(new SimpleGrantedAuthority("ROLE_USER"))));
        when(zset.zCard("ratelimit:user:42:default")).thenReturn(0L);
        var request = new MockHttpServletRequest("GET", "/api/v1/files");
        request.addHeader("X-Forwarded-For", "203.0.113.10");

        assertPasses(request);

        verify(zset).add(eq("ratelimit:user:42:default"), anyString(), anyDouble());
    }

    @Test
    void failsOpenWhenRedisIsUnavailable() throws Exception {
        when(zset.removeRangeByScore(anyString(), anyDouble(), anyDouble()))
                .thenThrow(new IllegalStateException("redis unavailable"));
        assertPasses(new MockHttpServletRequest("GET", "/api/v1/files"));
    }

    private void assertPasses(MockHttpServletRequest request) throws Exception {
        var response = new MockHttpServletResponse();
        var called = new AtomicBoolean();
        new RateLimitFilter(redis, properties).doFilter(request, response, (req, res) -> called.set(true));
        assertThat(called).isTrue();
        assertThat(response.getStatus()).isEqualTo(200);
    }
}
