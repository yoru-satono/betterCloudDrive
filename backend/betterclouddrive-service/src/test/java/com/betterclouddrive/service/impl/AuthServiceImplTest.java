package com.betterclouddrive.service.impl;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.betterclouddrive.common.exception.BusinessException;
import com.betterclouddrive.dal.entity.UserEntity;
import com.betterclouddrive.dal.mapper.UserMapper;
import com.betterclouddrive.dal.mapper.UserTokenMapper;
import com.betterclouddrive.service.EmailService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.LocalDateTime;

@ExtendWith(MockitoExtension.class)
class AuthServiceImplTest {

    @Mock
    private UserMapper userMapper;

    @Mock
    private UserTokenMapper userTokenMapper;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private EmailService emailService;

    @InjectMocks
    private AuthServiceImpl authService;

    @Test
    void register_shouldCreateUser() {
        when(userMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(0L);
        when(passwordEncoder.encode("pass")).thenReturn("hashed");

        authService.register("user", "pass", "e@m.com");

        verify(userMapper).insert(any(UserEntity.class));
    }

    @Test
    void register_shouldThrowWhenUsernameExists() {
        when(userMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(1L);

        assertThatThrownBy(() -> authService.register("user", "pass", "e@m.com"))
                .isInstanceOf(BusinessException.class)
                .hasMessage("Username already exists")
                .satisfies(ex -> assertThat(((BusinessException) ex).getCode()).isEqualTo(409));
    }

    @Test
    void register_shouldThrowWhenEmailExists() {
        when(userMapper.selectCount(any(LambdaQueryWrapper.class)))
                .thenReturn(0L)
                .thenReturn(1L);

        assertThatThrownBy(() -> authService.register("user", "pass", "e@m.com"))
                .isInstanceOf(BusinessException.class)
                .hasMessage("Email already exists");
    }

    @Test
    void login_shouldReturnUser() {
        UserEntity user = UserEntity.builder()
                .id(1L)
                .username("user")
                .passwordHash("hash")
                .status(1)
                .build();
        when(userMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(user);
        when(passwordEncoder.matches("pass", "hash")).thenReturn(true);

        UserEntity result = authService.login("user", "pass");

        assertThat(result.getId()).isEqualTo(1L);
        assertThat(result.getUsername()).isEqualTo("user");
    }

    @Test
    void login_shouldThrowWhenUserNotFound() {
        when(userMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);

        assertThatThrownBy(() -> authService.login("user", "pass"))
                .isInstanceOf(BusinessException.class)
                .hasMessage("Invalid username or password")
                .satisfies(ex -> assertThat(((BusinessException) ex).getCode()).isEqualTo(401));
    }

    @Test
    void login_shouldThrowWhenPasswordWrong() {
        UserEntity user = UserEntity.builder()
                .id(1L)
                .passwordHash("hash")
                .status(1)
                .build();
        when(userMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(user);
        when(passwordEncoder.matches("pass", "hash")).thenReturn(false);

        assertThatThrownBy(() -> authService.login("user", "pass"))
                .isInstanceOf(BusinessException.class)
                .hasMessage("Invalid username or password");
    }

    @Test
    void login_shouldThrowWhenAccountDisabled() {
        UserEntity user = UserEntity.builder()
                .id(1L)
                .passwordHash("hash")
                .status(0)
                .build();
        when(userMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(user);

        assertThatThrownBy(() -> authService.login("user", "pass"))
                .isInstanceOf(BusinessException.class)
                .hasMessage("Account is disabled");
    }

    @Test
    void getCurrentUser_shouldReturnUser() {
        UserEntity user = UserEntity.builder()
                .id(1L)
                .username("testuser")
                .build();
        when(userMapper.selectById(1L)).thenReturn(user);

        UserEntity result = authService.getCurrentUser(1L);

        assertThat(result).isSameAs(user);
    }

    @Test
    void getCurrentUser_shouldThrowWhenNotFound() {
        when(userMapper.selectById(1L)).thenReturn(null);

        assertThatThrownBy(() -> authService.getCurrentUser(1L))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void getCurrentUser_shouldThrowWhenDeleted() {
        UserEntity user = UserEntity.builder()
                .id(1L)
                .deletedAt(LocalDateTime.now())
                .build();
        when(userMapper.selectById(1L)).thenReturn(user);

        assertThatThrownBy(() -> authService.getCurrentUser(1L))
                .isInstanceOf(BusinessException.class);
    }
}
