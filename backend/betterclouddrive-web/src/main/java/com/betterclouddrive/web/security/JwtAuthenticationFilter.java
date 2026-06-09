package com.betterclouddrive.web.security;

import com.betterclouddrive.common.constant.ApiCode;
import com.betterclouddrive.dal.entity.UserTokenEntity;
import com.betterclouddrive.dal.mapper.UserTokenMapper;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

@Slf4j
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtTokenProvider jwtTokenProvider;
    private final StringRedisTemplate redisTemplate;
    private final UserTokenMapper userTokenMapper;

    public JwtAuthenticationFilter(JwtTokenProvider jwtTokenProvider,
                                    StringRedisTemplate redisTemplate,
                                    UserTokenMapper userTokenMapper) {
        this.jwtTokenProvider = jwtTokenProvider;
        this.redisTemplate = redisTemplate;
        this.userTokenMapper = userTokenMapper;
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
                response.setStatus(401);
                filterChain.doFilter(request, response);
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
        } catch (ExpiredJwtException e) {
            log.debug("Token expired: {}", e.getMessage());
        } catch (Exception e) {
            log.debug("Token validation failed: {}", e.getMessage());
        }

        filterChain.doFilter(request, response);
    }

    private String extractToken(HttpServletRequest request) {
        String bearerToken = request.getHeader("Authorization");
        if (StringUtils.hasText(bearerToken) && bearerToken.startsWith("Bearer ")) {
            return bearerToken.substring(7);
        }
        return null;
    }
}
