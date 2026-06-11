package com.betterclouddrive.scheduler.task;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.betterclouddrive.dal.entity.UploadSessionEntity;
import com.betterclouddrive.dal.repository.UploadSessionRepository;
import com.betterclouddrive.storage.StorageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;

@ExtendWith(MockitoExtension.class)
class ExpiredUploadSessionCleanupTaskTest {

    @Mock private UploadSessionRepository uploadSessionRepository;
    @Mock private StorageService storageService;
    @Mock private StringRedisTemplate redisTemplate;
    @InjectMocks private ExpiredUploadSessionCleanupTask task;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(task, "expireHours", 24);
    }

    private UploadSessionEntity session(String id, Long userId, int totalChunks) {
        return UploadSessionEntity.builder()
                .id(id).userId(userId).totalChunks(totalChunks).status(1)
                .createdAt(LocalDateTime.now().minusHours(30)).build();
    }

    @Test
    void shouldCleanupExpiredSessions() {
        UploadSessionEntity s = session("sess-abc", 1L, 5);
        when(uploadSessionRepository.findByStatusAndCreatedAtLessThanEqual(eq(1), any(LocalDateTime.class)))
                .thenReturn(List.of(s));

        task.cleanupExpiredUploadSessions();

        verify(storageService).deleteParts("uploads/1/sess-abc/chunks", 5);
        verify(redisTemplate).delete("upload:bitmap:sess-abc");
        assertThat(s.getStatus()).isEqualTo(3);
        verify(uploadSessionRepository).save(s);
    }

    @Test
    void shouldDoNothingWhenNoExpiredSessions() {
        when(uploadSessionRepository.findByStatusAndCreatedAtLessThanEqual(eq(1), any(LocalDateTime.class)))
                .thenReturn(List.of());

        task.cleanupExpiredUploadSessions();

        verify(storageService, never()).deleteParts(any(), anyInt());
        verify(uploadSessionRepository, never()).save(any());
    }

    @Test
    void shouldContinueOnStorageError() {
        UploadSessionEntity s = session("sess-err", 2L, 3);
        when(uploadSessionRepository.findByStatusAndCreatedAtLessThanEqual(eq(1), any(LocalDateTime.class)))
                .thenReturn(List.of(s));
        doThrow(new RuntimeException("S3 error")).when(storageService).deleteParts(any(), anyInt());

        assertThatCode(() -> task.cleanupExpiredUploadSessions()).doesNotThrowAnyException();
        verify(uploadSessionRepository, never()).save(any());
    }
}
