package com.betterclouddrive.service.impl;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.betterclouddrive.common.dto.PageResult;
import com.betterclouddrive.common.exception.BusinessException;
import com.betterclouddrive.dal.entity.FileEntity;
import com.betterclouddrive.dal.repository.FileRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@ExtendWith(MockitoExtension.class)
class FileServiceImplTest {

    @Mock
    private FileRepository fileRepository;

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOps;

    @InjectMocks
    private FileServiceImpl fileService;

    @Test
    void listFiles_shouldReturnPage() {
        FileEntity file1 = FileEntity.builder().id(1L).fileName("a.txt").build();
        FileEntity file2 = FileEntity.builder().id(2L).fileName("b.txt").build();
        Page<FileEntity> page = new PageImpl<>(List.of(file1, file2), PageRequest.of(0, 20), 2);
        when(fileRepository.findAll(any(Specification.class), any(PageRequest.class))).thenReturn(page);

        PageResult<FileEntity> result = fileService.listFiles(1L, null, 1, 20, "fileName", "asc");

        assertThat(result.getRecords()).hasSize(2);
        assertThat(result.getTotal()).isEqualTo(2);
    }

    @Test
    void getFile_shouldReturnFile() {
        FileEntity file = FileEntity.builder().id(1L).userId(1L).isDeleted(false).build();
        when(fileRepository.findById(1L)).thenReturn(Optional.of(file));

        FileEntity result = fileService.getFile(1L, 1L);

        assertThat(result).isSameAs(file);
    }

    @Test
    void getFile_shouldThrowWhenNotOwner() {
        FileEntity file = FileEntity.builder().id(1L).userId(2L).isDeleted(false).build();
        when(fileRepository.findById(1L)).thenReturn(Optional.of(file));

        assertThatThrownBy(() -> fileService.getFile(1L, 1L))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void getFile_shouldThrowWhenDeleted() {
        FileEntity file = FileEntity.builder().id(1L).userId(1L).isDeleted(true).build();
        when(fileRepository.findById(1L)).thenReturn(Optional.of(file));

        assertThatThrownBy(() -> fileService.getFile(1L, 1L))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void createFolder_shouldSucceed() {
        when(fileRepository.count(any(Specification.class))).thenReturn(0L);
        FileEntity saved = FileEntity.builder().id(10L).fileName("NewFolder").build();
        when(fileRepository.save(any(FileEntity.class))).thenReturn(saved);

        fileService.createFolder(1L, null, "NewFolder");

        verify(fileRepository).save(any(FileEntity.class));
    }

    @Test
    void createFolder_shouldThrowWhenNameConflict() {
        when(fileRepository.count(any(Specification.class))).thenReturn(1L);

        assertThatThrownBy(() -> fileService.createFolder(1L, null, "NewFolder"))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void renameFile_shouldSucceed() {
        FileEntity file = FileEntity.builder().id(1L).userId(1L).fileName("old.txt").isDeleted(false).build();
        when(fileRepository.findById(1L)).thenReturn(Optional.of(file));
        when(fileRepository.save(any(FileEntity.class))).thenReturn(file);

        fileService.renameFile(1L, 1L, "NewName");

        assertThat(file.getFileName()).isEqualTo("NewName");
    }

    @Test
    void moveFile_shouldSucceed() {
        FileEntity file = FileEntity.builder().id(1L).userId(1L).parentId(50L).isDeleted(false).build();
        when(fileRepository.findById(1L)).thenReturn(Optional.of(file));
        when(fileRepository.save(any(FileEntity.class))).thenReturn(file);

        fileService.moveFile(1L, 1L, 100L);

        assertThat(file.getParentId()).isEqualTo(100L);
    }

    @Test
    void copyFile_shouldAppendCopySuffix() {
        FileEntity source = FileEntity.builder()
                .id(1L).userId(1L).fileName("doc.txt")
                .fileType("file").isDeleted(false).build();
        when(fileRepository.findById(1L)).thenReturn(Optional.of(source));
        ArgumentCaptor<FileEntity> captor = ArgumentCaptor.forClass(FileEntity.class);
        when(fileRepository.save(captor.capture())).thenAnswer(inv -> inv.getArgument(0));

        fileService.copyFile(1L, 1L, 100L);

        assertThat(captor.getValue().getFileName()).isEqualTo("doc.txt (copy)");
        assertThat(captor.getValue().getParentId()).isEqualTo(100L);
    }

    @Test
    void deleteFiles_shouldSoftDelete() {
        FileEntity file = FileEntity.builder().id(1L).userId(1L).isDeleted(false).build();
        when(fileRepository.findById(1L)).thenReturn(Optional.of(file));
        when(fileRepository.save(any(FileEntity.class))).thenReturn(file);

        fileService.deleteFiles(1L, List.of(1L));

        assertThat(file.getIsDeleted()).isTrue();
        assertThat(file.getDeletedAt()).isNotNull();
    }

    @Test
    void restoreFile_shouldClearDeletedAt() {
        LocalDateTime now = LocalDateTime.now();
        FileEntity file = FileEntity.builder()
                .id(1L).userId(1L).isDeleted(true).deletedAt(now).build();
        when(fileRepository.findById(1L)).thenReturn(Optional.of(file));
        when(fileRepository.save(any(FileEntity.class))).thenReturn(file);

        fileService.restoreFile(1L, 1L);

        assertThat(file.getIsDeleted()).isFalse();
        assertThat(file.getDeletedAt()).isNull();
    }

    @Test
    void listRecycleBin_shouldReturnDeletedFiles() {
        FileEntity file = FileEntity.builder().id(1L).fileName("deleted.txt").build();
        Page<FileEntity> page = new PageImpl<>(List.of(file), PageRequest.of(0, 20), 1);
        when(fileRepository.findAll(any(Specification.class), any(PageRequest.class))).thenReturn(page);

        PageResult<FileEntity> result = fileService.listRecycleBin(1L, 1, 20);

        assertThat(result.getRecords()).isNotEmpty();
        assertThat(result.getRecords()).hasSize(1);
    }

    @Test
    void permanentDelete_shouldRemoveFromDb() {
        FileEntity file = FileEntity.builder().id(1L).userId(1L).isDeleted(true).build();
        when(fileRepository.findById(1L)).thenReturn(Optional.of(file));

        fileService.permanentDelete(1L, 1L);

        verify(fileRepository).deleteById(1L);
    }

    @Test
    void emptyRecycleBin_shouldDeleteAll() {
        FileEntity f1 = FileEntity.builder().id(1L).userId(1L).isDeleted(true).build();
        FileEntity f2 = FileEntity.builder().id(2L).userId(1L).isDeleted(true).build();
        when(fileRepository.findByUserIdAndIsDeletedTrue(1L)).thenReturn(List.of(f1, f2));

        fileService.emptyRecycleBin(1L);

        verify(fileRepository).deleteAll(List.of(f1, f2));
    }

    @Test
    void deleteFiles_shouldDecrementStorage() {
        FileEntity file = FileEntity.builder()
                .id(1L).userId(100L).fileType("file").fileSize(2048L)
                .isDeleted(false).build();
        when(fileRepository.findById(1L)).thenReturn(Optional.of(file));
        when(fileRepository.save(any(FileEntity.class))).thenReturn(file);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);

        fileService.deleteFiles(100L, List.of(1L));

        verify(valueOps).increment("storage:incr:100", -2048L);
    }

    @Test
    void searchFiles_shouldReturnMatchingFiles() {
        FileEntity file = FileEntity.builder().id(1L).userId(1L).fileName("report.pdf").build();
        Page<FileEntity> page = new PageImpl<>(List.of(file), PageRequest.of(0, 20), 1);
        when(fileRepository.findAll(any(Specification.class), any(PageRequest.class))).thenReturn(page);

        PageResult<FileEntity> result = fileService.searchFiles(1L, "report", 1, 20);

        assertThat(result.getRecords()).hasSize(1);
        assertThat(result.getTotal()).isEqualTo(1);
    }

    @Test
    void adminGetFile_shouldReturnFile() {
        FileEntity file = FileEntity.builder().id(1L).isDeleted(false).build();
        when(fileRepository.findById(1L)).thenReturn(Optional.of(file));

        FileEntity result = fileService.adminGetFile(1L);

        assertThat(result).isSameAs(file);
    }

    @Test
    void adminGetFile_shouldThrowWhenDeleted() {
        FileEntity file = FileEntity.builder().id(1L).isDeleted(true).build();
        when(fileRepository.findById(1L)).thenReturn(Optional.of(file));

        assertThatThrownBy(() -> fileService.adminGetFile(1L))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void adminDeleteFile_shouldSoftDelete() {
        FileEntity file = FileEntity.builder().id(1L).isDeleted(false).build();
        when(fileRepository.findById(1L)).thenReturn(Optional.of(file));
        ArgumentCaptor<FileEntity> captor = ArgumentCaptor.forClass(FileEntity.class);
        when(fileRepository.save(captor.capture())).thenAnswer(inv -> inv.getArgument(0));

        fileService.adminDeleteFile(1L);

        assertThat(captor.getValue().getIsDeleted()).isTrue();
        assertThat(captor.getValue().getDeletedAt()).isNotNull();
    }
}
