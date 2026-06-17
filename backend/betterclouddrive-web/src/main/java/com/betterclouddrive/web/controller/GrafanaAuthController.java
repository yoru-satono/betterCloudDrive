package com.betterclouddrive.web.controller;

import com.betterclouddrive.web.service.GrafanaAuthService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class GrafanaAuthController {

    private final GrafanaAuthService grafanaAuthService;

    @GetMapping("/api/v1/grafana/auth")
    public void authenticate(HttpServletRequest request, HttpServletResponse response) {
        try {
            grafanaAuthService.authenticateProxyRequest(request, response);
        } catch (AccessDeniedException e) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        }
    }
}
