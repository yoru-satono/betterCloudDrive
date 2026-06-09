package com.betterclouddrive.service.impl;

import com.betterclouddrive.common.exception.BusinessException;
import com.betterclouddrive.dal.entity.UserEntity;
import com.betterclouddrive.dal.mapper.FileMapper;
import com.betterclouddrive.dal.mapper.UploadSessionMapper;
import com.betterclouddrive.dal.mapper.UserMapper;
import com.betterclouddrive.storage.StorageService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class UploadServiceImplTest {

    @Mock private UploadSessionMapper uploadSessionMapper;
    @Mock private FileMapper fileMapper;
    @Mock private UserMapper userMapper;
    @Mock private StorageService storageService;
    @Mock private StringRedisTemplate redisTemplate;
    @Mock private ValueOperations<String, String> valueOps;
    @InjectMocks private UploadServiceImpl uploadService;

    @Test
    void initUpload_shouldThrowWhenQuotaExceeded() {
        UserEntity user = UserEntity.builder()
                .id(1L).storageQuota(1024L).storageUsed(1000L).build();
        when(userMapper.selectById(1L)).thenReturn(user);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.get("storage:incr:1")).thenReturn(null);

        assertThatThrownBy(() -> uploadService.initUpload(1L, null, "big.bin", 100L, null, 1))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getCode()).isEqualTo(419001));
    }

    @Test
    void initUpload_shouldThrowWhenQuotaExceededWithPendingIncr() {
        UserEntity user = UserEntity.builder()
                .id(1L).storageQuota(1024L).storageUsed(500L).build();
        when(userMapper.selectById(1L)).thenReturn(user);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.get("storage:incr:1")).thenReturn("600"); // 500+600=1100 used

        assertThatThrownBy(() -> uploadService.initUpload(1L, null, "big.bin", 100L, null, 1))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getCode()).isEqualTo(419001));
    }

    @Test
    void initUpload_shouldSucceedWhenQuotaSufficient() {
        UserEntity user = UserEntity.builder()
                .id(1L).storageQuota(10737418240L).storageUsed(0L).build();
        when(userMapper.selectById(1L)).thenReturn(user);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.get("storage:incr:1")).thenReturn(null);

        // Should not throw
        uploadService.initUpload(1L, null, "small.bin", 100L, null, 1);

        verify(uploadSessionMapper).insert(any(com.betterclouddrive.dal.entity.UploadSessionEntity.class));
    }

    @Test
    void instantUpload_shouldThrowWhenQuotaExceeded() {
        UserEntity user = UserEntity.builder()
                .id(1L).storageQuota(100L).storageUsed(90L).build();
        when(userMapper.selectById(1L)).thenReturn(user);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.get("storage:incr:1")).thenReturn(null);

        assertThatThrownBy(() -> uploadService.instantUpload(1L, null, "f", 20L, "md5"))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getCode()).isEqualTo(419001));
    }
}
