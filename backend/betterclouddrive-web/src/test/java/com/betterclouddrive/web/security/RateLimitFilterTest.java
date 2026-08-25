package com.betterclouddrive.web.security;

import com.betterclouddrive.web.config.ClientIpProperties;
import com.betterclouddrive.web.config.RateLimitProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class RateLimitFilterTest {
    private StringRedisTemplate redis;
    private RateLimitProperties properties;
    private ClientIpProperties clientIpProperties;
    private SimpleMeterRegistry registry;
    private RateLimitFilter filter;

    @BeforeEach
    void setUp() {
        redis = mock(StringRedisTemplate.class);
        properties = new RateLimitProperties();
        properties.setDefaultPermits(2);
        properties.setAuthPermits(3);
        properties.setUploadPermits(4);
        properties.setUploadChunkPermits(5);
        clientIpProperties = new ClientIpProperties();
        registry = new SimpleMeterRegistry();
        filter = new RateLimitFilter(
                redis,
                properties,
                new ClientIpResolver(clientIpProperties),
                registry,
                new ObjectMapper());
        allowRequest();
    }

    @AfterEach
    void clearSecurityContext() {
        org.springframework.security.core.context.SecurityContextHolder.clearContext();
        registry.close();
    }

    @Test
    void skipsOptionsNonApiAndGrafanaAuthenticationRequests() throws Exception {
        filter.doFilter(new MockHttpServletRequest("OPTIONS", "/api/v1/files"),
                new MockHttpServletResponse(), (req, res) -> { });
        filter.doFilter(new MockHttpServletRequest("GET", "/actuator/health"),
                new MockHttpServletResponse(), (req, res) -> { });
        filter.doFilter(new MockHttpServletRequest("GET", "/api/v1/grafana/auth"),
                new MockHttpServletResponse(), (req, res) -> { });

        verifyNoInteractions(redis);
    }

    @Test
    void separatesAuthenticationControlChunkAndDefaultBuckets() throws Exception {
        assertKey(new MockHttpServletRequest("POST", "/api/v1/auth/forgot-password"), ":auth");
        assertKey(new MockHttpServletRequest("POST", "/api/v1/upload/init"), ":upload-control");
        assertKey(new MockHttpServletRequest("POST", "/api/v1/upload/session/chunk"), ":upload-chunk");
        assertKey(new MockHttpServletRequest("POST", "/api/v1/teams/7/upload/session/chunk"), ":upload-chunk");
        assertKey(new MockHttpServletRequest("GET", "/api/v1/files"), ":default");
    }

    @Test
    void returnsSemantic429RetryAfterAndLowCardinalityMetric() throws Exception {
        doReturn(List.of(0L, 17L)).when(redis)
                .execute(any(RedisScript.class), anyList(), anyString(), anyString(), anyString(), anyString());
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/upload/init");
        request.setRemoteAddr("192.0.2.10");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean called = new AtomicBoolean();

        filter.doFilter(request, response, (req, res) -> called.set(true));

        assertThat(called).isFalse();
        assertThat(response.getStatus()).isEqualTo(429);
        assertThat(response.getHeader("Retry-After")).isEqualTo("17");
        assertThat(response.getContentAsString())
                .contains("error.rateLimited")
                .doesNotContain("192.0.2.10");
        assertThat(registry.get("drive.rate_limit.rejected")
                .tags("bucket", "upload-control", "subject_type", "ip")
                .counter().count()).isEqualTo(1);
    }

    @Test
    void usesAuthenticatedUserInsteadOfForwardedAddress() throws Exception {
        var principal = new UserPrincipal(42L, "alice", "jti", "ROLE_USER");
        org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null,
                        List.of(new SimpleGrantedAuthority("ROLE_USER"))));
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/files");
        request.setRemoteAddr("203.0.113.5");
        request.addHeader("X-Forwarded-For", "198.51.100.7");

        assertKey(request, "ratelimit:v2:user:42:default");
    }

    @Test
    void failsOpenAndRecordsMetricWhenRedisIsUnavailable() throws Exception {
        doReturn(null).when(redis)
                .execute(any(RedisScript.class), anyList(), anyString(), anyString(), anyString(), anyString());
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/files");
        AtomicBoolean called = new AtomicBoolean();

        filter.doFilter(request, new MockHttpServletResponse(), (req, res) -> called.set(true));

        assertThat(called).isTrue();
        assertThat(registry.get("drive.rate_limit.redis_fail_open")
                .tag("bucket", "default").counter().count()).isEqualTo(1);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private void assertKey(MockHttpServletRequest request, String expectedSuffix) throws Exception {
        if (request.getRemoteAddr() == null) {
            request.setRemoteAddr("127.0.0.1");
        }
        filter.doFilter(request, new MockHttpServletResponse(), (req, res) -> { });
        var keys = org.mockito.ArgumentCaptor.forClass(List.class);
        verify(redis).execute(
                any(RedisScript.class), keys.capture(), anyString(), anyString(), anyString(), anyString());
        assertThat((String) keys.getValue().getFirst()).endsWith(expectedSuffix);
        org.mockito.Mockito.clearInvocations(redis);
        allowRequest();
    }

    private void allowRequest() {
        doReturn(List.of(1L, 0L)).when(redis)
                .execute(any(RedisScript.class), anyList(), anyString(), anyString(), anyString(), anyString());
    }
}
