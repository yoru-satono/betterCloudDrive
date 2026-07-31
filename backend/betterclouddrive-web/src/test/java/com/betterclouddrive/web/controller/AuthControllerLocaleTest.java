package com.betterclouddrive.web.controller;

import com.betterclouddrive.dal.entity.UserEntity;
import com.betterclouddrive.dal.repository.UserTokenRepository;
import com.betterclouddrive.service.AuthService;
import com.betterclouddrive.web.dto.request.UpdateLocaleRequest;
import com.betterclouddrive.web.security.JwtTokenProvider;
import com.betterclouddrive.web.security.UserPrincipal;
import com.betterclouddrive.web.service.GrafanaLocaleSyncService;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AuthControllerLocaleTest {

    @Test
    void updatingLocaleAlsoSynchronizesGrafanaForEligibleAdmin() {
        AuthService authService = mock(AuthService.class);
        GrafanaLocaleSyncService grafanaLocaleSyncService = mock(GrafanaLocaleSyncService.class);
        AuthController controller = new AuthController(
                authService,
                mock(JwtTokenProvider.class),
                mock(UserTokenRepository.class),
                mock(StringRedisTemplate.class),
                grafanaLocaleSyncService);
        UserEntity updated = UserEntity.builder()
                .id(2L)
                .username("admin")
                .preferredLocale("en-US")
                .role("ROLE_ADMIN")
                .status(1)
                .build();
        when(authService.updatePreferredLocale(2L, "en-US")).thenReturn(updated);
        UpdateLocaleRequest request = new UpdateLocaleRequest();
        request.setPreferredLocale("en-US");

        controller.updatePreferredLocale(new UserPrincipal(2L, "admin", "jti", "ROLE_ADMIN"), request);

        verify(grafanaLocaleSyncService).syncIfAdmin(updated);
    }
}
