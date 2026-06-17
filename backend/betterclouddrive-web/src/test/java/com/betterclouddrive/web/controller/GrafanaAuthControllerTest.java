package com.betterclouddrive.web.controller;

import com.betterclouddrive.web.service.GrafanaAuthService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.access.AccessDeniedException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

class GrafanaAuthControllerTest {

    @Test
    void authRequestRejectionReturns401() {
        GrafanaAuthService service = mock(GrafanaAuthService.class);
        doThrow(new AccessDeniedException("denied"))
                .when(service).authenticateProxyRequest(
                        org.mockito.ArgumentMatchers.any(HttpServletRequest.class),
                        org.mockito.ArgumentMatchers.any(HttpServletResponse.class));
        GrafanaAuthController controller = new GrafanaAuthController(service);
        MockHttpServletResponse response = new MockHttpServletResponse();

        controller.authenticate(new MockHttpServletRequest(), response);

        assertThat(response.getStatus()).isEqualTo(401);
    }
}
