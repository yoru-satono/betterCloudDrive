package com.betterclouddrive.web.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.util.Base64;
import java.util.Date;
import java.util.UUID;

@Component
public class JwtTokenProvider {

    @Value("${jwt.access-token.secret}")
    private String accessSecret;

    @Value("${jwt.access-token.expiration-ms}")
    private long accessExpirationMs;

    @Value("${jwt.refresh-token.secret}")
    private String refreshSecret;

    @Value("${jwt.refresh-token.expiration-ms}")
    private long refreshExpirationMs;

    public String createAccessToken(Long userId, String username, String role) {
        Date now = new Date();
        return Jwts.builder()
                .subject(String.valueOf(userId))
                .id(UUID.randomUUID().toString())
                .issuedAt(now)
                .expiration(new Date(now.getTime() + accessExpirationMs))
                .claim("username", username)
                .claim("role", role != null ? role : "ROLE_USER")
                .signWith(getSigningKey(accessSecret))
                .compact();
    }

    public String createRefreshToken(Long userId) {
        Date now = new Date();
        return Jwts.builder()
                .subject(String.valueOf(userId))
                .id(UUID.randomUUID().toString())
                .issuedAt(now)
                .expiration(new Date(now.getTime() + refreshExpirationMs))
                .claim("type", "refresh")
                .signWith(getSigningKey(refreshSecret))
                .compact();
    }

    public Claims validateAccessToken(String token) {
        return Jwts.parser()
                .verifyWith(getSigningKey(accessSecret))
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    public Claims validateRefreshToken(String token) {
        Claims claims = Jwts.parser()
                .verifyWith(getSigningKey(refreshSecret))
                .build()
                .parseSignedClaims(token)
                .getPayload();
        if (!"refresh".equals(claims.get("type"))) {
            throw new RuntimeException("Invalid token type");
        }
        return claims;
    }

    public Long getUserIdFromToken(String token) {
        return Long.parseLong(validateAccessToken(token).getSubject());
    }

    public String getJtiFromToken(String token) {
        return validateAccessToken(token).getId();
    }

    public long getAccessExpirationMs() {
        return accessExpirationMs;
    }

    private SecretKey getSigningKey(String secret) {
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException(
                    "JWT secret is not configured. Set JWT_ACCESS_SECRET and JWT_REFRESH_SECRET environment variables.");
        }
        byte[] keyBytes = Base64.getDecoder().decode(secret);
        return Keys.hmacShaKeyFor(keyBytes);
    }
}
