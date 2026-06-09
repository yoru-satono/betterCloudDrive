package com.betterclouddrive.web.security;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class UserPrincipalTest {
    @Test
    void shouldCreatePrincipal() {
        UserPrincipal p = new UserPrincipal(1L, "user", "jti-123", "ROLE_USER");
        assertThat(p.getUserId()).isEqualTo(1L);
        assertThat(p.getUsername()).isEqualTo("user");
        assertThat(p.getJti()).isEqualTo("jti-123");
        assertThat(p.getRole()).isEqualTo("ROLE_USER");
    }
}
