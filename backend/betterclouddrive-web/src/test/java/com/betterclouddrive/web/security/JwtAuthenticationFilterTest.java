package com.betterclouddrive.web.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Base64;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

class JwtAuthenticationFilterTest {

    @AfterEach
    void tearDown() {
        org.springframework.security.core.context.SecurityContextHolder.clearContext();
    }

    @Test
    void expiredTokenReturnsRefreshableApiError() throws Exception {
        JwtTokenProvider tokenProvider = new JwtTokenProvider();
        String accessKey = Base64.getEncoder().encodeToString(
                "this-is-a-32-byte-secret-key-for-acc!".getBytes());
        ReflectionTestUtils.setField(tokenProvider, "accessSecret", accessKey);
        ReflectionTestUtils.setField(tokenProvider, "accessExpirationMs", -1000L);

        String expiredToken = tokenProvider.createAccessToken(1L, "alice", "ROLE_USER");
        JwtAuthenticationFilter filter = new JwtAuthenticationFilter(tokenProvider, null, null);
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/upload/session-1/complete");
        request.addHeader("Authorization", "Bearer " + expiredToken);
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean chainCalled = new AtomicBoolean(false);

        filter.doFilter(request, response, (req, res) -> chainCalled.set(true));

        assertThat(chainCalled).isFalse();
        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentType()).isEqualTo("application/json");
        assertThat(response.getContentAsString()).contains("\"code\":401001");
        assertThat(response.getContentAsString()).contains("\"message\":\"token expired\"");
    }

    @Test
    void onlyOfficeContentEndpointSkipsJwtHeaderValidation() throws Exception {
        JwtAuthenticationFilter filter = new JwtAuthenticationFilter(new JwtTokenProvider(), null, null);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/office/files/506/content");
        request.addHeader("Authorization", "Bearer not-a-drive-access-token");
        request.addParameter("token", "office-content-token");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean chainCalled = new AtomicBoolean(false);

        filter.doFilter(request, response, (req, res) -> chainCalled.set(true));

        assertThat(chainCalled).isTrue();
        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    void onlyOfficeTeamCallbackEndpointSkipsJwtHeaderValidation() throws Exception {
        JwtAuthenticationFilter filter = new JwtAuthenticationFilter(new JwtTokenProvider(), null, null);
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/teams/10/office/files/506/callback");
        request.addHeader("Authorization", "Bearer onlyoffice-document-server-token");
        request.addParameter("token", "office-callback-token");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean chainCalled = new AtomicBoolean(false);

        filter.doFilter(request, response, (req, res) -> chainCalled.set(true));

        assertThat(chainCalled).isTrue();
        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    void notificationStreamAcceptsAccessTokenQueryParameter() throws Exception {
        JwtTokenProvider tokenProvider = new JwtTokenProvider();
        String accessKey = Base64.getEncoder().encodeToString(
                "this-is-a-32-byte-secret-key-for-acc!".getBytes());
        ReflectionTestUtils.setField(tokenProvider, "accessSecret", accessKey);
        ReflectionTestUtils.setField(tokenProvider, "accessExpirationMs", 60000L);
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        when(redisTemplate.hasKey(anyString())).thenReturn(false);

        String token = tokenProvider.createAccessToken(9L, "chain", "ROLE_USER");
        JwtAuthenticationFilter filter = new JwtAuthenticationFilter(tokenProvider, redisTemplate, null);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/notifications/stream");
        request.addParameter("accessToken", token);
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean chainCalled = new AtomicBoolean(false);

        filter.doFilter(request, response, (req, res) -> chainCalled.set(true));

        assertThat(chainCalled).isTrue();
        assertThat(response.getStatus()).isEqualTo(200);
        UserPrincipal principal = (UserPrincipal) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        assertThat(principal.getUserId()).isEqualTo(9L);
        assertThat(principal.getUsername()).isEqualTo("chain");
    }
}
