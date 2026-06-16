package com.betterclouddrive.web.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Base64;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
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
}
