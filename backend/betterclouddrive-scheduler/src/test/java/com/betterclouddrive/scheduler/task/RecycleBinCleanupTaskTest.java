package com.betterclouddrive.scheduler.task;

import com.betterclouddrive.dal.entity.FileEntity;
import com.betterclouddrive.dal.entity.FileVersionEntity;
import com.betterclouddrive.dal.repository.FileRepository;
import com.betterclouddrive.dal.repository.FileVersionRepository;
import com.betterclouddrive.storage.StorageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.Collections;
import java.util.List;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

@ExtendWith(MockitoExtension.class)
class RecycleBinCleanupTaskTest {

    @Mock private FileRepository fileRepository;
    @Mock private FileVersionRepository fileVersionRepository;
    @Mock private StorageService storageService;
    @Mock private StringRedisTemplate redisTemplate;
    @Mock private ValueOperations<String, String> valueOps;
    @InjectMocks private RecycleBinCleanupTask task;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(task, "retentionDays", 30);
    }

    @Test
    void shouldCleanupExpiredFiles() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);

        FileEntity expired = FileEntity.builder()
                .id(1L).userId(100L).storagePath("uploads/100/key1")
                .thumbnailPath("thumbnails/1.webp").fileSize(2048L).build();
        when(fileRepository.findExpiredDeletedFiles(any(), any(PageRequest.class)))
                .thenReturn(List.of(expired), Collections.emptyList());
        when(fileVersionRepository.findTopVersionsByFileId(eq(1L), any(PageRequest.class)))
                .thenReturn(Collections.emptyList());

        task.cleanupExpiredRecycleBinFiles();

        verify(storageService).deleteObject("uploads/100/key1");
        verify(storageService).deleteObject("thumbnails/1.webp");
        verify(fileRepository).deleteById(1L);
        verify(valueOps).decrement("storage:incr:100", 2048L);
    }

    @Test
    void shouldCleanupExpiredFilesWithVersions() {
        FileEntity expired = FileEntity.builder()
                .id(2L).userId(200L).storagePath("uploads/200/doc.pdf")
                .thumbnailPath(null).fileSize(4096L).build();
        FileVersionEntity version = FileVersionEntity.builder()
                .fileId(2L).storagePath("versions/200/doc_v1.pdf").build();
        when(fileRepository.findExpiredDeletedFiles(any(), any(PageRequest.class)))
                .thenReturn(List.of(expired), Collections.emptyList());
        when(fileVersionRepository.findTopVersionsByFileId(eq(2L), any(PageRequest.class)))
                .thenReturn(List.of(version));

        task.cleanupExpiredRecycleBinFiles();

        verify(storageService).deleteObject("uploads/200/doc.pdf");
        verify(storageService).deleteObject("versions/200/doc_v1.pdf");
        verify(fileRepository).deleteById(2L);
    }

    @Test
    void shouldNotCleanupRecentFiles() {
        when(fileRepository.findExpiredDeletedFiles(any(), any(PageRequest.class)))
                .thenReturn(Collections.emptyList());

        task.cleanupExpiredRecycleBinFiles();

        verify(storageService, never()).deleteObject(any());
        verify(fileRepository, never()).deleteById(anyLong());
    }

    @Test
    void shouldContinueOnStorageError() {
        FileEntity expired = FileEntity.builder()
                .id(3L).userId(300L).storagePath("bad/path").fileSize(100L).build();
        when(fileRepository.findExpiredDeletedFiles(any(), any(PageRequest.class)))
                .thenReturn(List.of(expired), Collections.emptyList());
        doThrow(new RuntimeException("S3 error"))
                .when(storageService).deleteObject("bad/path");

        // Should NOT throw — error is caught and logged
        task.cleanupExpiredRecycleBinFiles();

        // deleteById is inside the try block, so it should NOT be called when storage fails
        verify(fileRepository, never()).deleteById(3L);
    }
}
