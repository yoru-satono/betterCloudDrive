package com.betterclouddrive.service.impl;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.betterclouddrive.common.exception.BusinessException;
import com.betterclouddrive.dal.entity.FileEntity;
import com.betterclouddrive.dal.entity.FileVersionEntity;
import com.betterclouddrive.dal.repository.FileRepository;
import com.betterclouddrive.dal.repository.FileVersionRepository;
import com.betterclouddrive.storage.StorageService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageRequest;

import java.util.List;
import java.util.Optional;

@ExtendWith(MockitoExtension.class)
class FileVersionServiceImplTest {

    @Mock private FileVersionRepository fileVersionRepository;
    @Mock private FileRepository fileRepository;
    @Mock private StorageService storageService;
    @InjectMocks private FileVersionServiceImpl fileVersionService;

    private FileEntity ownedFile(Long fileId, Long userId) {
        return FileEntity.builder().id(fileId).userId(userId).versionCount(2).build();
    }

    private FileVersionEntity version(int num, String path) {
        return FileVersionEntity.builder().id((long) num).fileId(1L)
                .versionNumber(num).storagePath(path).build();
    }

    @Test
    void listVersions_shouldReturnTop10Versions() {
        when(fileRepository.findById(1L)).thenReturn(Optional.of(ownedFile(1L, 1L)));
        List<FileVersionEntity> versions = List.of(version(2, "p2"), version(1, "p1"));
        when(fileVersionRepository.findTopVersionsByFileId(eq(1L), any(PageRequest.class)))
                .thenReturn(versions);

        List<FileVersionEntity> result = fileVersionService.listVersions(1L, 1L);

        assertThat(result).hasSize(2);
        assertThat(result.get(0).getVersionNumber()).isEqualTo(2);
    }

    @Test
    void listVersions_shouldThrowWhenFileNotFound() {
        when(fileRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> fileVersionService.listVersions(1L, 99L))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void listVersions_shouldThrowWhenNotOwner() {
        when(fileRepository.findById(1L)).thenReturn(Optional.of(ownedFile(1L, 2L)));

        assertThatThrownBy(() -> fileVersionService.listVersions(1L, 1L))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void deleteVersion_shouldRemoveVersionAndDecrementCount() {
        FileEntity file = ownedFile(1L, 1L);
        when(fileRepository.findById(1L)).thenReturn(Optional.of(file));
        List<FileVersionEntity> versions = List.of(version(2, "path/v2"), version(1, "path/v1"));
        when(fileVersionRepository.findTopVersionsByFileId(eq(1L), any(PageRequest.class)))
                .thenReturn(versions);
        when(fileRepository.save(any())).thenReturn(file);

        fileVersionService.deleteVersion(1L, 1L, 1);

        verify(storageService).deleteObject("path/v1");
        verify(fileVersionRepository).deleteById(1L);
        assertThat(file.getVersionCount()).isEqualTo(1);
    }

    @Test
    void deleteVersion_shouldThrowWhenVersionNotFound() {
        when(fileRepository.findById(1L)).thenReturn(Optional.of(ownedFile(1L, 1L)));
        when(fileVersionRepository.findTopVersionsByFileId(eq(1L), any(PageRequest.class)))
                .thenReturn(List.of(version(1, "path/v1")));

        assertThatThrownBy(() -> fileVersionService.deleteVersion(1L, 1L, 99))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void deleteVersion_shouldThrowWhenOnlyOneVersionExists() {
        when(fileRepository.findById(1L)).thenReturn(Optional.of(ownedFile(1L, 1L)));
        when(fileVersionRepository.findTopVersionsByFileId(eq(1L), any(PageRequest.class)))
                .thenReturn(List.of(version(1, "path/v1")));

        // size() == 1, version exists but only one — should throw conflict
        assertThatThrownBy(() -> fileVersionService.deleteVersion(1L, 1L, 1))
                .isInstanceOf(BusinessException.class);
    }
}
