package com.betterclouddrive.service.impl;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.betterclouddrive.common.exception.BusinessException;
import com.betterclouddrive.dal.entity.UserEntity;
import com.betterclouddrive.dal.entity.UserTokenEntity;
import com.betterclouddrive.dal.repository.UserRepository;
import com.betterclouddrive.dal.repository.UserTokenRepository;
import com.betterclouddrive.service.EmailService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

@ExtendWith(MockitoExtension.class)
class AuthServiceImplTest {

    @Mock private UserRepository userRepository;
    @Mock private UserTokenRepository userTokenRepository;
    @Mock private PasswordEncoder passwordEncoder;
    @Mock private StringRedisTemplate redisTemplate;
    @Mock private ValueOperations<String, String> valueOps;
    @Mock private EmailService emailService;

    @InjectMocks
    private AuthServiceImpl authService;

    // ── register ─────────────────────────────────────────────────────────────

    @Test
    void register_shouldCreateUser() {
        when(userRepository.existsByUsername(any())).thenReturn(false);
        when(userRepository.existsByEmail(any())).thenReturn(false);
        when(passwordEncoder.encode("pass")).thenReturn("hashed");
        when(userRepository.save(any(UserEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        authService.register("user", "pass", "e@m.com");

        verify(userRepository).save(any(UserEntity.class));
    }

    @Test
    void register_shouldThrowWhenUsernameExists() {
        when(userRepository.existsByUsername(any())).thenReturn(true);

        assertThatThrownBy(() -> authService.register("user", "pass", "e@m.com"))
                .isInstanceOf(BusinessException.class)
                .hasMessage("Username already exists")
                .satisfies(ex -> assertThat(((BusinessException) ex).getCode()).isEqualTo(409));
    }

    @Test
    void register_shouldThrowWhenEmailExists() {
        when(userRepository.existsByUsername(any())).thenReturn(false);
        when(userRepository.existsByEmail(any())).thenReturn(true);

        assertThatThrownBy(() -> authService.register("user", "pass", "e@m.com"))
                .isInstanceOf(BusinessException.class)
                .hasMessage("Email already exists");
    }

    // ── login ─────────────────────────────────────────────────────────────────

    @Test
    void login_shouldReturnUser() {
        UserEntity user = UserEntity.builder()
                .id(1L).username("user").passwordHash("hash").status(1).build();
        when(userRepository.findByUsername("user")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("pass", "hash")).thenReturn(true);

        UserEntity result = authService.login("user", "pass");

        assertThat(result.getId()).isEqualTo(1L);
        assertThat(result.getUsername()).isEqualTo("user");
    }

    @Test
    void login_shouldThrowWhenUserNotFound() {
        when(userRepository.findByUsername("user")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> authService.login("user", "pass"))
                .isInstanceOf(BusinessException.class)
                .hasMessage("Invalid username or password")
                .satisfies(ex -> assertThat(((BusinessException) ex).getCode()).isEqualTo(401));
    }

    @Test
    void login_shouldThrowWhenPasswordWrong() {
        UserEntity user = UserEntity.builder().id(1L).passwordHash("hash").status(1).build();
        when(userRepository.findByUsername("user")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("pass", "hash")).thenReturn(false);

        assertThatThrownBy(() -> authService.login("user", "pass"))
                .isInstanceOf(BusinessException.class)
                .hasMessage("Invalid username or password");
    }

    @Test
    void login_shouldThrowWhenAccountDisabled() {
        UserEntity user = UserEntity.builder().id(1L).passwordHash("hash").status(0).build();
        when(userRepository.findByUsername("user")).thenReturn(Optional.of(user));

        assertThatThrownBy(() -> authService.login("user", "pass"))
                .isInstanceOf(BusinessException.class)
                .hasMessage("Account is disabled");
    }

    // ── getCurrentUser ────────────────────────────────────────────────────────

    @Test
    void getCurrentUser_shouldReturnUser() {
        UserEntity user = UserEntity.builder().id(1L).username("testuser").build();
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));

        assertThat(authService.getCurrentUser(1L)).isSameAs(user);
    }

    @Test
    void getCurrentUser_shouldThrowWhenNotFound() {
        when(userRepository.findById(1L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> authService.getCurrentUser(1L))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void getCurrentUser_shouldThrowWhenDeleted() {
        UserEntity user = UserEntity.builder().id(1L).deletedAt(LocalDateTime.now()).build();
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));

        assertThatThrownBy(() -> authService.getCurrentUser(1L))
                .isInstanceOf(BusinessException.class);
    }

    // ── logout ────────────────────────────────────────────────────────────────

    @Test
    void logout_shouldBlacklistTokenInRedis() {
        UserTokenEntity token = UserTokenEntity.builder()
                .id(1L).jti("test-jti")
                .expiresAt(LocalDateTime.now().plusHours(1))
                .isRevoked(false)
                .build();
        when(userTokenRepository.findByJti("test-jti")).thenReturn(Optional.of(token));
        when(redisTemplate.opsForValue()).thenReturn(valueOps);

        authService.logout("test-jti");

        verify(valueOps).set(eq("token:blacklist:test-jti"), eq("1"), anyLong(), eq(TimeUnit.MILLISECONDS));
        verify(userTokenRepository).save(argThat(t -> t.getIsRevoked()));
    }

    @Test
    void logout_shouldDoNothingWhenJtiNotFound() {
        when(userTokenRepository.findByJti("ghost-jti")).thenReturn(Optional.empty());

        authService.logout("ghost-jti");

        verify(userTokenRepository, never()).save(any());
    }

    // ── verifyEmail ───────────────────────────────────────────────────────────

    @Test
    void verifyEmail_shouldMarkVerified() {
        UserEntity user = UserEntity.builder().id(1L).emailVerified(false).build();
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.get("email:verify:1")).thenReturn("123456");

        authService.verifyEmail(1L, "123456");

        assertThat(user.getEmailVerified()).isTrue();
        verify(userRepository).save(user);
        verify(redisTemplate).delete("email:verify:1");
    }

    @Test
    void verifyEmail_shouldThrowWhenCodeExpired() {
        UserEntity user = UserEntity.builder().id(1L).emailVerified(false).build();
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.get("email:verify:1")).thenReturn(null);

        assertThatThrownBy(() -> authService.verifyEmail(1L, "123456"))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void verifyEmail_shouldThrowWhenCodeMismatch() {
        UserEntity user = UserEntity.builder().id(1L).emailVerified(false).build();
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.get("email:verify:1")).thenReturn("999999");

        assertThatThrownBy(() -> authService.verifyEmail(1L, "123456"))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void verifyEmail_shouldDoNothingWhenAlreadyVerified() {
        UserEntity user = UserEntity.builder().id(1L).emailVerified(true).build();
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));

        authService.verifyEmail(1L, "123456");

        verify(userRepository, never()).save(any());
    }

    // ── resetPassword ─────────────────────────────────────────────────────────

    @Test
    void resetPassword_shouldChangePasswordHash() {
        UserEntity user = UserEntity.builder().id(1L).email("u@test.com").build();
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.get("pwd:reset:u@test.com")).thenReturn("654321");
        when(userRepository.findByEmail("u@test.com")).thenReturn(Optional.of(user));
        when(passwordEncoder.encode("newpass")).thenReturn("newHash");

        authService.resetPassword("u@test.com", "654321", "newpass");

        assertThat(user.getPasswordHash()).isEqualTo("newHash");
        verify(userRepository).save(user);
        verify(redisTemplate).delete("pwd:reset:u@test.com");
    }

    @Test
    void resetPassword_shouldThrowWhenCodeExpired() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.get("pwd:reset:u@test.com")).thenReturn(null);

        assertThatThrownBy(() -> authService.resetPassword("u@test.com", "654321", "newpass"))
                .isInstanceOf(BusinessException.class);
    }

    // ── sendVerificationCode ──────────────────────────────────────────────────

    @Test
    void sendVerificationCode_shouldThrowWhenNoEmail() {
        UserEntity user = UserEntity.builder().id(1L).email(null).build();
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));

        assertThatThrownBy(() -> authService.sendVerificationCode(1L))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void sendVerificationCode_shouldSendCode() {
        UserEntity user = UserEntity.builder().id(1L).email("u@test.com").emailVerified(false).build();
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
        when(redisTemplate.opsForValue()).thenReturn(valueOps);

        authService.sendVerificationCode(1L);

        verify(emailService).sendVerificationCode(eq("u@test.com"), anyString());
    }
}
