package com.betterclouddrive.web.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import javax.crypto.SecretKey;
import java.util.Base64;
import java.util.Date;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

class JwtTokenProviderTest {

    private JwtTokenProvider provider;

    @BeforeEach
    void setUp() {
        provider = new JwtTokenProvider();
        // Generate valid Base64-encoded 256-bit keys
        String accessKey = Base64.getEncoder().encodeToString(
            "this-is-a-32-byte-secret-key-for-acc!".getBytes());
        String refreshKey = Base64.getEncoder().encodeToString(
            "this-is-a-32-byte-refresh-secret-key!!".getBytes());

        ReflectionTestUtils.setField(provider, "accessSecret", accessKey);
        ReflectionTestUtils.setField(provider, "accessExpirationMs", 1800000L);
        ReflectionTestUtils.setField(provider, "refreshSecret", refreshKey);
        ReflectionTestUtils.setField(provider, "refreshExpirationMs", 2592000000L);
    }

    @Test
    void shouldCreateAccessToken() {
        String token = provider.createAccessToken(1L, "testuser", "ROLE_USER");
        assertThat(token).isNotNull().isNotEmpty();
        // Token should have 3 parts (header.payload.signature)
        assertThat(token.split("\\.")).hasSize(3);
    }

    @Test
    void shouldValidateAccessToken() {
        String token = provider.createAccessToken(42L, "alice", "ROLE_USER");
        Claims claims = provider.validateAccessToken(token);
        assertThat(claims.getSubject()).isEqualTo("42");
        assertThat(claims.get("username", String.class)).isEqualTo("alice");
        assertThat(claims.get("role", String.class)).isEqualTo("ROLE_USER");
        assertThat(claims.getId()).isNotNull();
    }

    @Test
    void shouldCreateRefreshToken() {
        String token = provider.createRefreshToken(1L);
        assertThat(token).isNotNull();
        Claims claims = provider.validateRefreshToken(token);
        assertThat(claims.get("type", String.class)).isEqualTo("refresh");
    }

    @Test
    void shouldRejectAccessTokenAsRefreshToken() {
        // Build a token with the refresh secret but WITHOUT type=refresh claim
        // This simulates an access token trying to pass as refresh token
        String refreshSecret = (String) ReflectionTestUtils.getField(provider, "refreshSecret");
        SecretKey refreshKey = Keys.hmacShaKeyFor(Base64.getDecoder().decode(refreshSecret));
        String badToken = Jwts.builder()
                .subject("1")
                .id(UUID.randomUUID().toString())
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + 3600000))
                // Note: no .claim("type", "refresh")
                .signWith(refreshKey)
                .compact();
        assertThatThrownBy(() -> provider.validateRefreshToken(badToken))
            .isInstanceOf(RuntimeException.class)
            .hasMessageContaining("Invalid token type");
    }

    @Test
    void shouldRejectExpiredAccessToken() throws InterruptedException {
        // Set very short expiry
        ReflectionTestUtils.setField(provider, "accessExpirationMs", 1L);
        String token = provider.createAccessToken(1L, "user", "ROLE_USER");
        Thread.sleep(10); // Wait for token to expire
        assertThatThrownBy(() -> provider.validateAccessToken(token))
            .isInstanceOf(ExpiredJwtException.class);
    }

    @Test
    void shouldExtractUserIdFromToken() {
        String token = provider.createAccessToken(100L, "bob", "ROLE_USER");
        Long userId = provider.getUserIdFromToken(token);
        assertThat(userId).isEqualTo(100L);
    }

    @Test
    void shouldExtractJtiFromToken() {
        String token = provider.createAccessToken(1L, "user", "ROLE_USER");
        String jti = provider.getJtiFromToken(token);
        assertThat(jti).isNotNull().isNotEmpty();
    }

    @Test
    void shouldReturnAccessExpirationMs() {
        assertThat(provider.getAccessExpirationMs()).isEqualTo(1800000L);
    }
}
