package com.betterclouddrive.web.service;

import com.betterclouddrive.dal.entity.UserEntity;
import com.betterclouddrive.dal.repository.UserRepository;
import com.betterclouddrive.web.observability.ObservabilityProperties;
import com.betterclouddrive.web.security.UserPrincipal;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GrafanaAuthServiceTest {

    private ObservabilityProperties properties;
    private UserRepository userRepository;
    private GrafanaAuthService service;

    @BeforeEach
    void setUp() {
        properties = new ObservabilityProperties();
        properties.getGrafanaAuth().setSecret("test-grafana-auth-secret");
        properties.getGrafanaAuth().setTtlSeconds(300);
        userRepository = mock(UserRepository.class);
        service = new GrafanaAuthService(properties, userRepository);
    }

    @Test
    void adminCanIssueSessionAndAuthenticateProxyRequest() {
        UserEntity admin = user(1L, "admin", "admin@test.local", "ROLE_ADMIN", 1);
        when(userRepository.findById(1L)).thenReturn(Optional.of(admin));
        MockHttpServletResponse issueResponse = new MockHttpServletResponse();

        service.issueSession(authentication(1L, "admin", "ROLE_ADMIN"), issueResponse);

        String cookieHeader = issueResponse.getHeader(HttpHeaders.SET_COOKIE);
        assertThat(cookieHeader).contains("BCD_GRAFANA_SESSION=").contains("HttpOnly").contains("SameSite=Lax");

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/grafana/auth");
        request.setCookies(new Cookie("BCD_GRAFANA_SESSION", cookieValue(cookieHeader)));
        MockHttpServletResponse authResponse = new MockHttpServletResponse();

        service.authenticateProxyRequest(request, authResponse);

        assertThat(authResponse.getStatus()).isEqualTo(204);
        assertThat(authResponse.getHeader(GrafanaAuthService.HEADER_USER)).isEqualTo("admin");
        assertThat(authResponse.getHeader(GrafanaAuthService.HEADER_EMAIL)).isEqualTo("admin@test.local");
        assertThat(authResponse.getHeader(GrafanaAuthService.HEADER_ROLE)).isEqualTo("Admin");
    }

    @Test
    void nonAdminCannotIssueGrafanaSession() {
        UserEntity user = user(2L, "alice", "alice@test.local", "ROLE_USER", 1);
        when(userRepository.findById(2L)).thenReturn(Optional.of(user));

        assertThatThrownBy(() -> service.issueSession(authentication(2L, "alice", "ROLE_USER"), new MockHttpServletResponse()))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void revokedAdminCannotUseExistingCookie() {
        UserEntity admin = user(1L, "admin", "admin@test.local", "ROLE_ADMIN", 1);
        UserEntity revoked = user(1L, "admin", "admin@test.local", "ROLE_USER", 1);
        when(userRepository.findById(1L)).thenReturn(Optional.of(admin), Optional.of(revoked));
        MockHttpServletResponse issueResponse = new MockHttpServletResponse();
        service.issueSession(authentication(1L, "admin", "ROLE_ADMIN"), issueResponse);

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/grafana/auth");
        request.setCookies(new Cookie("BCD_GRAFANA_SESSION", cookieValue(issueResponse.getHeader(HttpHeaders.SET_COOKIE))));

        assertThatThrownBy(() -> service.authenticateProxyRequest(request, new MockHttpServletResponse()))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void tamperedCookieIsRejected() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/grafana/auth");
        request.setCookies(new Cookie("BCD_GRAFANA_SESSION", "tampered.value"));

        assertThatThrownBy(() -> service.authenticateProxyRequest(request, new MockHttpServletResponse()))
                .isInstanceOf(AccessDeniedException.class);
    }

    private UsernamePasswordAuthenticationToken authentication(Long userId, String username, String role) {
        return new UsernamePasswordAuthenticationToken(
                new UserPrincipal(userId, username, "jti", role),
                null,
                List.of(new SimpleGrantedAuthority(role)));
    }

    private UserEntity user(Long id, String username, String email, String role, Integer status) {
        return UserEntity.builder()
                .id(id)
                .username(username)
                .email(email)
                .role(role)
                .status(status)
                .build();
    }

    private String cookieValue(String setCookieHeader) {
        String prefix = "BCD_GRAFANA_SESSION=";
        int start = setCookieHeader.indexOf(prefix) + prefix.length();
        int end = setCookieHeader.indexOf(';', start);
        return setCookieHeader.substring(start, end);
    }
}
