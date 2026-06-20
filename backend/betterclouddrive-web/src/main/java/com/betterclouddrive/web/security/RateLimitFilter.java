package com.betterclouddrive.web.security;

import com.betterclouddrive.common.constant.ApiCode;
import com.betterclouddrive.common.dto.ApiResponse;
import com.betterclouddrive.web.config.RateLimitProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Slf4j
public class RateLimitFilter extends OncePerRequestFilter {

    private final StringRedisTemplate redisTemplate;
    private final RateLimitProperties properties;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private static final String RATELIMIT_KEY_PREFIX = "ratelimit:";

    public RateLimitFilter(StringRedisTemplate redisTemplate, RateLimitProperties properties) {
        this.redisTemplate = redisTemplate;
        this.properties = properties;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {

        String path = request.getRequestURI();
        if (!path.startsWith("/api/") || "/api/v1/grafana/auth".equals(path)) {
            filterChain.doFilter(request, response);
            return;
        }

        String subject = resolveSubject(request);
        String action = resolveAction(path);
        int permits = getPermits(action);
        int periodSeconds = getPeriodSeconds(action);

        String key = RATELIMIT_KEY_PREFIX + subject + ":" + action;
        long now = System.currentTimeMillis();
        long windowStart = now - (periodSeconds * 1000L);

        try {
            redisTemplate.opsForZSet().removeRangeByScore(key, 0, windowStart);
            Long count = redisTemplate.opsForZSet().zCard(key);

            if (count != null && count >= permits) {
                response.setStatus(429);
                response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                ApiResponse<Void> error = ApiResponse.error(
                        ApiCode.RATE_LIMITED.getCode(), ApiCode.RATE_LIMITED.getMessage());
                response.getWriter().write(objectMapper.writeValueAsString(error));
                return;
            }

            redisTemplate.opsForZSet().add(key, UUID.randomUUID().toString(), now);
            redisTemplate.expire(key, periodSeconds + 10, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.warn("Rate limit check failed, allowing request: {}", e.getMessage());
        }

        filterChain.doFilter(request, response);
    }

    private String resolveSubject(HttpServletRequest request) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof UserPrincipal principal) {
            return "user:" + principal.getUserId();
        }
        String ip = request.getHeader("X-Forwarded-For");
        if (ip == null || ip.isBlank()) {
            ip = request.getRemoteAddr();
        }
        return "ip:" + (ip != null ? ip : "unknown");
    }

    private String resolveAction(String path) {
        if (path.contains("/upload")) return "upload";
        if (path.contains("/auth/login") || path.contains("/auth/register") || path.contains("/auth/refresh")) return "auth";
        return "default";
    }

    private int getPermits(String action) {
        return "upload".equals(action) ? properties.getUploadPermits() : properties.getDefaultPermits();
    }

    private int getPeriodSeconds(String action) {
        return "upload".equals(action) ? properties.getUploadPeriodSeconds() : properties.getDefaultPeriodSeconds();
    }
}
