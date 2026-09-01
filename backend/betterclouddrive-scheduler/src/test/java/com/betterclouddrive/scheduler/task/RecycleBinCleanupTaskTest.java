package com.betterclouddrive.scheduler.task;

import com.betterclouddrive.dal.entity.FileEntity;
import com.betterclouddrive.dal.repository.FileRepository;
import com.betterclouddrive.service.FileService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.Collections;
import java.util.List;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

@ExtendWith(MockitoExtension.class)
class RecycleBinCleanupTaskTest {

    @Mock private FileRepository fileRepository;
    @Mock private FileService fileService;
    @InjectMocks private RecycleBinCleanupTask task;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(task, "retentionDays", 30);
    }

    @Test
    void shouldCleanupExpiredFiles() {
        FileEntity expired = FileEntity.builder()
                .id(1L).userId(100L).storagePath("uploads/100/key1")
                .thumbnailPath("thumbnails/1.webp").fileSize(2048L).build();
        when(fileRepository.findExpiredDeletedRoots(any(), any(PageRequest.class)))
                .thenReturn(List.of(expired), Collections.emptyList());

        task.cleanupExpiredRecycleBinFiles();

        verify(fileService).purgeExpiredRecycleRoot(1L);
    }

    @Test
    void shouldDelegateEachExpiredRootToSharedPurgePath() {
        FileEntity expired = FileEntity.builder()
                .id(2L).userId(200L).storagePath("uploads/200/doc.pdf")
                .thumbnailPath(null).fileSize(4096L).build();
        when(fileRepository.findExpiredDeletedRoots(any(), any(PageRequest.class)))
                .thenReturn(List.of(expired), Collections.emptyList());

        task.cleanupExpiredRecycleBinFiles();

        verify(fileService).purgeExpiredRecycleRoot(2L);
    }

    @Test
    void shouldNotCleanupRecentFiles() {
        when(fileRepository.findExpiredDeletedRoots(any(), any(PageRequest.class)))
                .thenReturn(Collections.emptyList());

        task.cleanupExpiredRecycleBinFiles();

        verify(fileService, never()).purgeExpiredRecycleRoot(anyLong());
    }

    @Test
    void shouldContinueOnStorageError() {
        FileEntity expired = FileEntity.builder()
                .id(3L).userId(300L).storagePath("bad/path").fileSize(100L).build();
        when(fileRepository.findExpiredDeletedRoots(any(), any(PageRequest.class)))
                .thenReturn(List.of(expired), Collections.emptyList());
        doThrow(new RuntimeException("S3 error"))
                .when(fileService).purgeExpiredRecycleRoot(3L);

        // Should NOT throw — error is caught and logged
        task.cleanupExpiredRecycleBinFiles();

        verify(fileService).purgeExpiredRecycleRoot(3L);
    }
}
