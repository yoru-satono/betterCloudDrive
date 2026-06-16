package com.betterclouddrive.web.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import com.betterclouddrive.dal.entity.UserEntity;
import com.betterclouddrive.dal.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

class BasicAuthFilterTest {

    private final UserRepository userRepository = mock(UserRepository.class);
    private final PasswordEncoder passwordEncoder = mock(PasswordEncoder.class);
    private final BasicAuthFilter filter = new BasicAuthFilter(userRepository, passwordEncoder);

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void rejectsWebDavWhenDisabledByDefault() throws Exception {
        UserEntity user = UserEntity.builder()
                .id(1L)
                .username("alice")
                .passwordHash("login-hash")
                .webdavEnabled(false)
                .status(1)
                .build();
        when(userRepository.findByUsername("alice")).thenReturn(Optional.of(user));

        MockHttpServletResponse response = runWithBasicAuth("alice", "login-pass");

        assertThat(response.getStatus()).isEqualTo(401);
        verify(passwordEncoder, never()).matches(anyString(), anyString());
    }

    @Test
    void rejectsLoginPasswordAndAcceptsIndependentWebDavPassword() throws Exception {
        UserEntity user = UserEntity.builder()
                .id(1L)
                .username("alice")
                .passwordHash("login-hash")
                .webdavEnabled(true)
                .webdavPasswordHash("dav-hash")
                .role("ROLE_USER")
                .status(1)
                .build();
        when(userRepository.findByUsername("alice")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("login-pass", "dav-hash")).thenReturn(false);
        when(passwordEncoder.matches("dav-pass", "dav-hash")).thenReturn(true);

        MockHttpServletResponse rejected = runWithBasicAuth("alice", "login-pass");
        MockHttpServletResponse accepted = runWithBasicAuth("alice", "dav-pass");

        assertThat(rejected.getStatus()).isEqualTo(401);
        assertThat(accepted.getStatus()).isEqualTo(200);
    }

    @Test
    void rejectsEnabledUserWithoutWebDavPasswordHash() throws Exception {
        UserEntity user = UserEntity.builder()
                .id(1L)
                .username("alice")
                .webdavEnabled(true)
                .status(1)
                .build();
        when(userRepository.findByUsername("alice")).thenReturn(Optional.of(user));

        MockHttpServletResponse response = runWithBasicAuth("alice", "anything");

        assertThat(response.getStatus()).isEqualTo(401);
        verify(passwordEncoder, never()).matches(anyString(), anyString());
    }

    private MockHttpServletResponse runWithBasicAuth(String username, String password) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("PROPFIND", "/webdav/");
        request.setServletPath("/webdav/");
        String raw = username + ":" + password;
        request.addHeader("Authorization", "Basic " + Base64.getEncoder()
                .encodeToString(raw.getBytes(StandardCharsets.UTF_8)));
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean chainCalled = new AtomicBoolean(false);

        filter.doFilter(request, response, (req, res) -> {
            chainCalled.set(true);
            ((MockHttpServletResponse) res).setStatus(200);
        });

        if (response.getStatus() == 200) {
            assertThat(chainCalled).isTrue();
        } else {
            assertThat(chainCalled).isFalse();
        }
        return response;
    }
}
