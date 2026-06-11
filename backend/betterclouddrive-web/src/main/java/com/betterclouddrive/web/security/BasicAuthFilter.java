package com.betterclouddrive.web.security;

import com.betterclouddrive.dal.entity.UserEntity;
import com.betterclouddrive.dal.repository.UserRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Base64;
import java.util.List;

@Component
@RequiredArgsConstructor
public class BasicAuthFilter extends OncePerRequestFilter {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (!request.getServletPath().startsWith("/webdav")) {
            chain.doFilter(request, response);
            return;
        }

        String authHeader = request.getHeader("Authorization");
        if (authHeader == null || !authHeader.startsWith("Basic ")) {
            response.setHeader("WWW-Authenticate", "Basic realm=\"BetterCloudDrive WebDAV\"");
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            return;
        }

        String decoded;
        try {
            decoded = new String(Base64.getDecoder().decode(authHeader.substring(6)));
        } catch (Exception e) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            return;
        }

        int colon = decoded.indexOf(':');
        if (colon < 0) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            return;
        }

        String username = decoded.substring(0, colon);
        String password = decoded.substring(colon + 1);

        UserEntity user = userRepository.findByUsername(username).orElse(null);
        if (user == null || !passwordEncoder.matches(password, user.getPasswordHash())) {
            response.setHeader("WWW-Authenticate", "Basic realm=\"BetterCloudDrive WebDAV\"");
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            return;
        }

        String role = user.getRole() != null ? user.getRole() : "ROLE_USER";
        UserPrincipal principal = new UserPrincipal(user.getId(), user.getUsername(), null, role);
        UsernamePasswordAuthenticationToken auth =
                new UsernamePasswordAuthenticationToken(principal, null, List.of(new SimpleGrantedAuthority(role)));
        SecurityContextHolder.getContext().setAuthentication(auth);

        chain.doFilter(request, response);
    }
}
