package com.betterclouddrive.web.security;

import com.betterclouddrive.common.constant.ApiCode;
import com.betterclouddrive.common.context.RequestTraceContext;
import com.betterclouddrive.common.dto.ApiResponse;
import com.betterclouddrive.dal.repository.UserTokenRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.util.StringUtils;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

@Slf4j
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtTokenProvider jwtTokenProvider;
    private final StringRedisTemplate redisTemplate;
    private final UserTokenRepository userTokenRepository;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AntPathMatcher pathMatcher = new AntPathMatcher();

    public JwtAuthenticationFilter(JwtTokenProvider jwtTokenProvider,
                                    StringRedisTemplate redisTemplate,
                                    UserTokenRepository userTokenRepository) {
        this.jwtTokenProvider = jwtTokenProvider;
        this.redisTemplate = redisTemplate;
        this.userTokenRepository = userTokenRepository;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = requestPath(request);
        String method = request.getMethod();
        return ("GET".equals(method) && pathMatcher.match("/api/v1/office/files/*/content", path))
                || ("GET".equals(method) && pathMatcher.match("/api/v1/teams/*/office/files/*/content", path))
                || ("POST".equals(method) && pathMatcher.match("/api/v1/teams/*/office/files/*/callback", path));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String token = extractToken(request);

        if (token == null) {
            filterChain.doFilter(request, response);
            return;
        }

        try {
            Claims claims = jwtTokenProvider.validateAccessToken(token);
            String jti = claims.getId();

            // Check Redis blacklist
            if (Boolean.TRUE.equals(redisTemplate.hasKey("token:blacklist:" + jti))) {
                writeAuthError(response, ApiCode.TOKEN_BLACKLISTED);
                return;
            }

            Long userId = Long.parseLong(claims.getSubject());
            String username = claims.get("username", String.class);
            String role = claims.get("role", String.class);
            if (role == null) role = "ROLE_USER";

            UserPrincipal principal = new UserPrincipal(userId, username, jti, role);
            UsernamePasswordAuthenticationToken authentication =
                    new UsernamePasswordAuthenticationToken(principal, null,
                            List.of(new SimpleGrantedAuthority(role)));
            SecurityContextHolder.getContext().setAuthentication(authentication);
            request.setAttribute(RequestTraceContext.ATTRIBUTE_USER_ID, userId);
            MDC.put(RequestTraceContext.MDC_USER_ID, String.valueOf(userId));
            MDC.put("userId", String.valueOf(userId));
        } catch (ExpiredJwtException e) {
            log.debug("Token expired: {}", e.getMessage());
            writeAuthError(response, ApiCode.TOKEN_EXPIRED);
            return;
        } catch (Exception e) {
            log.debug("Token validation failed: {}", e.getMessage());
            writeAuthError(response, ApiCode.UNAUTHORIZED);
            return;
        }

        filterChain.doFilter(request, response);
    }

    private void writeAuthError(HttpServletResponse response, ApiCode code) throws IOException {
        SecurityContextHolder.clearContext();
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write(objectMapper.writeValueAsString(
                ApiResponse.error(code.getCode(), code.getMessage())));
    }

    private String extractToken(HttpServletRequest request) {
        String bearerToken = request.getHeader("Authorization");
        if (StringUtils.hasText(bearerToken) && bearerToken.startsWith("Bearer ")) {
            return bearerToken.substring(7);
        }
        if ("GET".equals(request.getMethod()) && pathMatcher.match("/api/v1/notifications/stream", requestPath(request))) {
            String accessToken = request.getParameter("accessToken");
            if (StringUtils.hasText(accessToken)) {
                return accessToken;
            }
        }
        return null;
    }

    private String requestPath(HttpServletRequest request) {
        String path = request.getRequestURI();
        String contextPath = request.getContextPath();
        if (StringUtils.hasText(contextPath) && path.startsWith(contextPath)) {
            return path.substring(contextPath.length());
        }
        return path;
    }
}
