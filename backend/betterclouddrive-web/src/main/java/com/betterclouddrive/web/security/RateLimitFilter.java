package com.betterclouddrive.web.security;

import com.betterclouddrive.common.constant.ApiCode;
import com.betterclouddrive.common.dto.ApiResponse;
import com.betterclouddrive.web.config.RateLimitProperties;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

@Slf4j
public class RateLimitFilter extends OncePerRequestFilter {

    private static final String RATELIMIT_KEY_PREFIX = "ratelimit:v2:";
    private static final Pattern PERSONAL_UPLOAD = Pattern.compile("^/api/v1/upload(?:/.*)?$");
    private static final Pattern TEAM_UPLOAD = Pattern.compile("^/api/v1/teams/[^/]+/upload(?:/.*)?$");
    private static final Pattern PERSONAL_CHUNK = Pattern.compile("^/api/v1/upload/[^/]+/chunk/?$");
    private static final Pattern TEAM_CHUNK = Pattern.compile("^/api/v1/teams/[^/]+/upload/[^/]+/chunk/?$");
    private static final Set<String> AUTH_PATHS = Set.of(
            "/api/v1/auth/login",
            "/api/v1/auth/register",
            "/api/v1/auth/register-code/send",
            "/api/v1/auth/refresh",
            "/api/v1/auth/forgot-password",
            "/api/v1/auth/reset-password"
    );
    private static final RedisScript<List> RATE_LIMIT_SCRIPT = new DefaultRedisScript<>("""
            local key = KEYS[1]
            local now = tonumber(ARGV[1])
            local window = tonumber(ARGV[2])
            local permits = tonumber(ARGV[3])
            local member = ARGV[4]
            redis.call('ZREMRANGEBYSCORE', key, '-inf', now - window)
            local count = redis.call('ZCARD', key)
            if count >= permits then
                local oldest = redis.call('ZRANGE', key, 0, 0, 'WITHSCORES')
                local retry = 1
                if oldest[2] then
                    retry = math.max(1, math.ceil((tonumber(oldest[2]) + window - now) / 1000))
                end
                return {0, retry}
            end
            redis.call('ZADD', key, now, member)
            redis.call('PEXPIRE', key, window + 10000)
            return {1, 0}
            """, List.class);

    private final StringRedisTemplate redisTemplate;
    private final RateLimitProperties properties;
    private final ClientIpResolver clientIpResolver;
    private final MeterRegistry meterRegistry;
    private final ObjectMapper objectMapper;

    public RateLimitFilter(StringRedisTemplate redisTemplate,
                           RateLimitProperties properties,
                           ClientIpResolver clientIpResolver,
                           MeterRegistry meterRegistry,
                           ObjectMapper objectMapper) {
        this.redisTemplate = redisTemplate;
        this.properties = properties;
        this.clientIpResolver = clientIpResolver;
        this.meterRegistry = meterRegistry;
        this.objectMapper = objectMapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return "OPTIONS".equalsIgnoreCase(request.getMethod())
                || !path.startsWith("/api/")
                || "/api/v1/grafana/auth".equals(path);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        Bucket bucket = resolveBucket(request);
        Subject subject = resolveSubject(request);
        int permits = Math.max(1, permits(bucket));
        int periodSeconds = Math.max(1, periodSeconds(bucket));
        long now = System.currentTimeMillis();
        String key = RATELIMIT_KEY_PREFIX + subject.key() + ":" + bucket.tag;

        List<?> result;
        try {
            result = redisTemplate.execute(
                    RATE_LIMIT_SCRIPT,
                    List.of(key),
                    Long.toString(now),
                    Long.toString(periodSeconds * 1000L),
                    Integer.toString(permits),
                    UUID.randomUUID().toString());
            if (result == null || result.size() < 2) {
                throw new IllegalStateException("rate limit script returned no result");
            }
        } catch (Exception error) {
            meterRegistry.counter("drive.rate_limit.redis_fail_open", "bucket", bucket.tag).increment();
            log.warn("Rate limit check failed open for bucket {}: {}", bucket.tag, error.getMessage());
            filterChain.doFilter(request, response);
            return;
        }

        if (number(result.get(0)) == 0) {
            reject(response, bucket, subject.type(), Math.max(1, number(result.get(1))));
            return;
        }

        filterChain.doFilter(request, response);
    }

    private void reject(HttpServletResponse response, Bucket bucket, String subjectType, long retryAfter)
            throws IOException {
        meterRegistry.counter(
                "drive.rate_limit.rejected",
                "bucket", bucket.tag,
                "subject_type", subjectType).increment();
        response.setStatus(429);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setHeader(HttpHeaders.RETRY_AFTER, Long.toString(retryAfter));
        ApiResponse<Void> error = ApiResponse.error(
                ApiCode.RATE_LIMITED.getCode(), ApiCode.RATE_LIMITED.getMessageKey());
        response.getWriter().write(objectMapper.writeValueAsString(error));
    }

    private Subject resolveSubject(HttpServletRequest request) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof UserPrincipal principal) {
            return new Subject("user:" + principal.getUserId(), "user");
        }
        return new Subject("ip:" + clientIpResolver.resolve(request), "ip");
    }

    private Bucket resolveBucket(HttpServletRequest request) {
        String path = request.getRequestURI();
        if ("POST".equalsIgnoreCase(request.getMethod())
                && (PERSONAL_CHUNK.matcher(path).matches() || TEAM_CHUNK.matcher(path).matches())) {
            return Bucket.UPLOAD_CHUNK;
        }
        if (PERSONAL_UPLOAD.matcher(path).matches() || TEAM_UPLOAD.matcher(path).matches()) {
            return Bucket.UPLOAD_CONTROL;
        }
        if ("POST".equalsIgnoreCase(request.getMethod()) && AUTH_PATHS.contains(path)) {
            return Bucket.AUTH;
        }
        return Bucket.DEFAULT;
    }

    private int permits(Bucket bucket) {
        return switch (bucket) {
            case AUTH -> properties.getAuthPermits();
            case UPLOAD_CONTROL -> properties.getUploadPermits();
            case UPLOAD_CHUNK -> properties.getUploadChunkPermits();
            case DEFAULT -> properties.getDefaultPermits();
        };
    }

    private int periodSeconds(Bucket bucket) {
        return switch (bucket) {
            case AUTH -> properties.getAuthPeriodSeconds();
            case UPLOAD_CONTROL -> properties.getUploadPeriodSeconds();
            case UPLOAD_CHUNK -> properties.getUploadChunkPeriodSeconds();
            case DEFAULT -> properties.getDefaultPeriodSeconds();
        };
    }

    private long number(Object value) {
        return value instanceof Number number ? number.longValue() : Long.parseLong(String.valueOf(value));
    }

    private enum Bucket {
        AUTH("auth"),
        DEFAULT("default"),
        UPLOAD_CONTROL("upload-control"),
        UPLOAD_CHUNK("upload-chunk");

        private final String tag;

        Bucket(String tag) {
            this.tag = tag;
        }
    }

    private record Subject(String key, String type) { }
}
