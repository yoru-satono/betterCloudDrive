package com.betterclouddrive.web.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.betterclouddrive.web.security.JwtAuthenticationFilter;
import com.betterclouddrive.web.security.RateLimitFilter;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationContext;
import org.springframework.context.support.StaticApplicationContext;
import org.springframework.security.config.ObjectPostProcessor;
import org.springframework.security.config.annotation.authentication.builders.AuthenticationManagerBuilder;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.cors.CorsConfiguration;

import java.util.HashMap;

class SecurityConfigTest {

    @Test
    void corsConfigurationAllowsPatchRequests() {
        SecurityConfig config = new SecurityConfig();
        MockHttpServletRequest request = new MockHttpServletRequest("OPTIONS", "/api/v1/admin/users/1/quota");
        CorsConfiguration cors = config.corsConfigurationSource().getCorsConfiguration(request);

        assertThat(cors).isNotNull();
        assertThat(cors.getAllowedMethods()).contains("PATCH");
    }

    @Test
    void jwtAuthenticationRunsBeforeRateLimiting() throws Exception {
        ObjectPostProcessor<Object> postProcessor = new ObjectPostProcessor<>() {
            @Override
            public <O extends Object> O postProcess(O object) {
                return object;
            }
        };
        AuthenticationManagerBuilder authenticationBuilder = new AuthenticationManagerBuilder(postProcessor);
        HttpSecurity http = new HttpSecurity(postProcessor, authenticationBuilder, new HashMap<>());
        StaticApplicationContext applicationContext = new StaticApplicationContext();
        applicationContext.refresh();
        http.setSharedObject(ApplicationContext.class, applicationContext);
        JwtAuthenticationFilter jwt = mock(JwtAuthenticationFilter.class);
        RateLimitFilter rateLimit = mock(RateLimitFilter.class);

        var chain = new SecurityConfig().filterChain(http, jwt, rateLimit);

        assertThat(chain.getFilters().indexOf(jwt))
                .isGreaterThanOrEqualTo(0)
                .isLessThan(chain.getFilters().indexOf(rateLimit));
        applicationContext.close();
    }
}
