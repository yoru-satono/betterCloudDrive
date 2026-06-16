package com.betterclouddrive.web.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.cors.CorsConfiguration;

class SecurityConfigTest {

    @Test
    void corsConfigurationAllowsPatchRequests() {
        SecurityConfig config = new SecurityConfig();
        MockHttpServletRequest request = new MockHttpServletRequest("OPTIONS", "/api/v1/admin/users/1/quota");
        CorsConfiguration cors = config.corsConfigurationSource().getCorsConfiguration(request);

        assertThat(cors).isNotNull();
        assertThat(cors.getAllowedMethods()).contains("PATCH");
    }
}
