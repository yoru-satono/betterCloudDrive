package com.betterclouddrive.scheduler.task;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

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
import org.springframework.data.jpa.domain.Specification;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

@ExtendWith(MockitoExtension.class)
class OldVersionCleanupTaskTest {

    @Mock private FileRepository fileRepository;
    @Mock private FileVersionRepository fileVersionRepository;
    @Mock private StorageService storageService;
    @InjectMocks private OldVersionCleanupTask task;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(task, "maxVersions", 3);
    }

    private FileVersionEntity version(int num) {
        return FileVersionEntity.builder().id((long) num).fileId(1L)
                .versionNumber(num).storagePath("path/v" + num).build();
    }

    @Test
    void shouldDeleteVersionsExceedingMax() {
        FileEntity file = FileEntity.builder().id(1L).versionCount(5).build();
        when(fileRepository.findAll(any(Specification.class))).thenReturn(List.of(file));
        List<FileVersionEntity> versions = List.of(
                version(5), version(4), version(3), version(2), version(1));
        when(fileVersionRepository.findTopVersionsByFileId(eq(1L), any(PageRequest.class)))
                .thenReturn(versions);
        when(fileRepository.save(any())).thenReturn(file);

        task.cleanupOldVersions();

        // versions[3] and versions[4] (v2, v1) should be deleted
        verify(storageService).deleteObject("path/v2");
        verify(storageService).deleteObject("path/v1");
        verify(storageService, never()).deleteObject("path/v5");
        verify(fileVersionRepository).deleteById(2L);
        verify(fileVersionRepository).deleteById(1L);
        assertThat(file.getVersionCount()).isEqualTo(3);
    }

    @Test
    void shouldSkipFilesWithinLimit() {
        FileEntity file = FileEntity.builder().id(1L).versionCount(3).build();
        when(fileRepository.findAll(any(Specification.class))).thenReturn(List.of(file));
        when(fileVersionRepository.findTopVersionsByFileId(eq(1L), any(PageRequest.class)))
                .thenReturn(List.of(version(3), version(2), version(1)));

        task.cleanupOldVersions();

        verify(storageService, never()).deleteObject(any());
        verify(fileVersionRepository, never()).deleteById(any());
    }

    @Test
    void shouldUpdateVersionCountAfterCleanup() {
        FileEntity file = FileEntity.builder().id(1L).versionCount(4).build();
        when(fileRepository.findAll(any(Specification.class))).thenReturn(List.of(file));
        when(fileVersionRepository.findTopVersionsByFileId(eq(1L), any(PageRequest.class)))
                .thenReturn(List.of(version(4), version(3), version(2), version(1)));

        task.cleanupOldVersions();

        assertThat(file.getVersionCount()).isEqualTo(3);
        verify(fileRepository).save(file);
    }

    @Test
    void shouldContinueOnStorageError() {
        FileEntity file = FileEntity.builder().id(1L).versionCount(5).build();
        when(fileRepository.findAll(any(Specification.class))).thenReturn(List.of(file));
        when(fileVersionRepository.findTopVersionsByFileId(eq(1L), any(PageRequest.class)))
                .thenReturn(List.of(version(5), version(4), version(3), version(2), version(1)));
        doThrow(new RuntimeException("storage error")).when(storageService).deleteObject(any());

        assertThatCode(() -> task.cleanupOldVersions()).doesNotThrowAnyException();
    }
}
