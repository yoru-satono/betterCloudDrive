package com.betterclouddrive.web.service;

import com.betterclouddrive.common.exception.BusinessException;
import com.betterclouddrive.dal.entity.UserEntity;
import com.betterclouddrive.dal.repository.UserRepository;
import com.betterclouddrive.web.observability.ObservabilityProperties;
import com.betterclouddrive.web.security.UserPrincipal;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;

@Service
@RequiredArgsConstructor
public class GrafanaAuthService {

    public static final String HEADER_USER = "X-WEBAUTH-USER";
    public static final String HEADER_NAME = "X-WEBAUTH-NAME";
    public static final String HEADER_EMAIL = "X-WEBAUTH-EMAIL";
    public static final String HEADER_ROLE = "X-WEBAUTH-ROLE";

    private static final String ROLE_ADMIN = "ROLE_ADMIN";
    private static final String GRAFANA_ROLE_ADMIN = "Admin";
    private static final String HMAC_ALGORITHM = "HmacSHA256";

    private final ObservabilityProperties properties;
    private final UserRepository userRepository;

    public void issueSession(Authentication authentication, HttpServletResponse response) {
        UserEntity user = authenticatedAdmin(authentication);
        long expiresAt = Instant.now().plusSeconds(properties.getGrafanaAuth().getTtlSeconds()).getEpochSecond();
        String payload = user.getId() + "|" + expiresAt;
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(payload.getBytes(StandardCharsets.UTF_8))
                + "." + sign(payload);

        ResponseCookie cookie = ResponseCookie.from(cookieName(), token)
                .httpOnly(true)
                .secure(false)
                .sameSite("Lax")
                .path("/")
                .maxAge(Duration.ofSeconds(properties.getGrafanaAuth().getTtlSeconds()))
                .build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    }

    public void authenticateProxyRequest(HttpServletRequest request, HttpServletResponse response) {
        UserEntity user = validateCookie(request);
        response.setHeader(HEADER_USER, safe(user.getUsername()));
        response.setHeader(HEADER_NAME, StringUtils.hasText(user.getNickname()) ? user.getNickname() : safe(user.getUsername()));
        if (StringUtils.hasText(user.getEmail())) {
            response.setHeader(HEADER_EMAIL, user.getEmail());
        }
        response.setHeader(HEADER_ROLE, GRAFANA_ROLE_ADMIN);
        response.setStatus(HttpServletResponse.SC_NO_CONTENT);
    }

    private UserEntity authenticatedAdmin(Authentication authentication) {
        if (authentication == null || !(authentication.getPrincipal() instanceof UserPrincipal principal)) {
            throw new AccessDeniedException("Grafana access requires administrator authentication");
        }
        UserEntity user = userRepository.findById(principal.getUserId())
                .orElseThrow(() -> new AccessDeniedException("User not found"));
        if (!isActiveAdmin(user)) {
            throw new AccessDeniedException("Grafana access requires administrator role");
        }
        return user;
    }

    private UserEntity validateCookie(HttpServletRequest request) {
        String token = extractCookie(request);
        if (!StringUtils.hasText(token)) {
            throw new AccessDeniedException("Grafana session is missing");
        }

        int separator = token.lastIndexOf('.');
        if (separator <= 0 || separator == token.length() - 1) {
            throw new AccessDeniedException("Grafana session is invalid");
        }

        String encodedPayload = token.substring(0, separator);
        String signature = token.substring(separator + 1);
        String payload;
        try {
            payload = new String(Base64.getUrlDecoder().decode(encodedPayload), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            throw new AccessDeniedException("Grafana session payload is invalid");
        }

        if (!constantTimeEquals(sign(payload), signature)) {
            throw new AccessDeniedException("Grafana session signature is invalid");
        }

        String[] parts = payload.split("\\|", -1);
        if (parts.length != 2) {
            throw new AccessDeniedException("Grafana session payload is malformed");
        }

        long userId;
        long expiresAt;
        try {
            userId = Long.parseLong(parts[0]);
            expiresAt = Long.parseLong(parts[1]);
        } catch (NumberFormatException e) {
            throw new AccessDeniedException("Grafana session identifiers are invalid");
        }
        if (Instant.now().getEpochSecond() > expiresAt) {
            throw new AccessDeniedException("Grafana session is expired");
        }

        UserEntity user = userRepository.findById(userId)
                .orElseThrow(() -> new AccessDeniedException("User not found"));
        if (!isActiveAdmin(user)) {
            throw new AccessDeniedException("Grafana access requires administrator role");
        }
        return user;
    }

    private String extractCookie(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return null;
        }
        for (Cookie cookie : cookies) {
            if (cookieName().equals(cookie.getName())) {
                return cookie.getValue();
            }
        }
        return null;
    }

    private boolean isActiveAdmin(UserEntity user) {
        return user != null
                && user.getDeletedAt() == null
                && Integer.valueOf(1).equals(user.getStatus())
                && ROLE_ADMIN.equals(user.getRole());
    }

    private String sign(String payload) {
        String secret = properties.getGrafanaAuth().getSecret();
        if (!StringUtils.hasText(secret)) {
            throw new BusinessException(500, "Grafana auth proxy secret is not configured");
        }
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), HMAC_ALGORITHM));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new BusinessException(500, "Failed to sign Grafana session");
        }
    }

    private String cookieName() {
        return properties.getGrafanaAuth().getCookieName();
    }

    private boolean constantTimeEquals(String expected, String actual) {
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), actual.getBytes(StandardCharsets.UTF_8));
    }

    private String safe(String value) {
        return value != null ? value : "";
    }
}
