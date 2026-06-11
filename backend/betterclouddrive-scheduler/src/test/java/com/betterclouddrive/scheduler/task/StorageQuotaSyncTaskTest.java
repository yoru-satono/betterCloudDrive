package com.betterclouddrive.scheduler.task;

import com.betterclouddrive.dal.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import java.util.Set;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

@ExtendWith(MockitoExtension.class)
class StorageQuotaSyncTaskTest {

    @Mock private StringRedisTemplate redisTemplate;
    @Mock private UserRepository userRepository;
    @Mock private ValueOperations<String, String> valueOps;
    @InjectMocks private StorageQuotaSyncTask task;

    @Test
    void shouldSyncQuotaIncrements() {
        when(redisTemplate.keys("storage:incr:*"))
                .thenReturn(Set.of("storage:incr:1", "storage:incr:2"));
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.get("storage:incr:1")).thenReturn("1024");
        when(valueOps.get("storage:incr:2")).thenReturn("-512");

        task.syncStorageQuota();

        verify(userRepository).updateStorageUsed(1L, 1024L);
        verify(userRepository).updateStorageUsed(2L, -512L);
        verify(redisTemplate).delete("storage:incr:1");
        verify(redisTemplate).delete("storage:incr:2");
    }

    @Test
    void shouldHandleEmptyKeys() {
        when(redisTemplate.keys("storage:incr:*")).thenReturn(Set.of());

        task.syncStorageQuota();

        verify(userRepository, never()).updateStorageUsed(anyLong(), anyLong());
        verify(redisTemplate, never()).delete(anyString());
    }

    @Test
    void shouldHandleNullKeys() {
        when(redisTemplate.keys("storage:incr:*")).thenReturn(null);

        task.syncStorageQuota();

        verify(userRepository, never()).updateStorageUsed(anyLong(), anyLong());
    }

    @Test
    void shouldSkipZeroDelta() {
        when(redisTemplate.keys("storage:incr:*")).thenReturn(Set.of("storage:incr:1"));
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.get("storage:incr:1")).thenReturn("0");

        task.syncStorageQuota();

        verify(userRepository, never()).updateStorageUsed(anyLong(), anyLong());
        verify(redisTemplate).delete("storage:incr:1");
    }

    @Test
    void shouldHandleMalformedKey() {
        when(redisTemplate.keys("storage:incr:*")).thenReturn(Set.of("storage:incr:abc"));
        // Parsing "abc" will fail, but task should catch the error and continue
        task.syncStorageQuota();
        // No exception should propagate
        verify(userRepository, never()).updateStorageUsed(anyLong(), anyLong());
    }
}
