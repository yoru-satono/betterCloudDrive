package com.betterclouddrive.service.impl;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.betterclouddrive.common.exception.BusinessException;
import com.betterclouddrive.dal.entity.FileEntity;
import com.betterclouddrive.dal.entity.FolderZipDownloadCacheEntity;
import com.betterclouddrive.dal.repository.FileRepository;
import com.betterclouddrive.dal.repository.FolderZipDownloadCacheRepository;
import com.betterclouddrive.service.config.FolderZipDownloadProperties;
import com.betterclouddrive.storage.StorageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.Optional;
import java.util.List;
import java.util.zip.ZipInputStream;

class FolderZipDownloadServiceImplTest {

    private FileRepository fileRepository;
    private FolderZipDownloadCacheRepository cacheRepository;
    private StorageService storageService;
    private FolderZipDownloadProperties properties;
    private FolderZipDownloadServiceImpl service;

    @BeforeEach
    void setUp() {
        fileRepository = mock(FileRepository.class);
        cacheRepository = mock(FolderZipDownloadCacheRepository.class);
        storageService = mock(StorageService.class);
        properties = new FolderZipDownloadProperties();
        service = new FolderZipDownloadServiceImpl(fileRepository, cacheRepository, storageService, properties);
    }

    @Test
    void writeZip_shouldIncludeFilesAndEmptyFolders() throws Exception {
        FileEntity root = folder(1L, null, "Root");
        FileEntity docs = folder(2L, 1L, "Docs");
        FileEntity empty = folder(3L, 1L, "Empty");
        FileEntity readme = file(4L, 2L, "readme.txt", 5L, "objects/readme");

        when(fileRepository.findByUserIdAndParentIdAndIsDeletedFalseOrderByFileTypeAscFileNameAsc(1L, 1L))
                .thenReturn(List.of(docs, empty));
        when(fileRepository.findByUserIdAndParentIdAndIsDeletedFalseOrderByFileTypeAscFileNameAsc(1L, 2L))
                .thenReturn(List.of(readme));
        when(fileRepository.findByUserIdAndParentIdAndIsDeletedFalseOrderByFileTypeAscFileNameAsc(1L, 3L))
                .thenReturn(List.of());
        when(storageService.downloadObject("objects/readme"))
                .thenReturn(new ByteArrayInputStream("hello".getBytes()));

        service.validateDownloadable(root);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        service.writeZip(root, out);

        assertThat(zipEntryNames(out.toByteArray()))
                .containsExactly("Root/", "Root/Docs/", "Root/Docs/readme.txt", "Root/Empty/");
    }

    @Test
    void validateDownloadable_shouldStopWhenFileCountLimitExceeded() {
        properties.setMaxFiles(1);
        FileEntity root = folder(1L, null, "Root");
        FileEntity first = file(2L, 1L, "a.txt", 1L, "objects/a");
        FileEntity second = file(3L, 1L, "b.txt", 1L, "objects/b");
        FileEntity laterFolder = folder(4L, 1L, "Later");
        when(fileRepository.findByUserIdAndParentIdAndIsDeletedFalseOrderByFileTypeAscFileNameAsc(1L, 1L))
                .thenReturn(List.of(first, second, laterFolder));

        assertThatThrownBy(() -> service.validateDownloadable(root))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getCode()).isEqualTo(419015));
        verify(fileRepository, never())
                .findByUserIdAndParentIdAndIsDeletedFalseOrderByFileTypeAscFileNameAsc(1L, 4L);
        verifyNoInteractions(storageService);
    }

    @Test
    void validateDownloadable_shouldStopWhenSizeLimitExceeded() {
        properties.setMaxSizeBytes(10L);
        FileEntity root = folder(1L, null, "Root");
        FileEntity first = file(2L, 1L, "a.bin", 7L, "objects/a");
        FileEntity second = file(3L, 1L, "b.bin", 4L, "objects/b");
        FileEntity laterFolder = folder(4L, 1L, "Later");
        when(fileRepository.findByUserIdAndParentIdAndIsDeletedFalseOrderByFileTypeAscFileNameAsc(1L, 1L))
                .thenReturn(List.of(first, second, laterFolder));

        assertThatThrownBy(() -> service.validateDownloadable(root))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getCode()).isEqualTo(419015));
        verify(fileRepository, never())
                .findByUserIdAndParentIdAndIsDeletedFalseOrderByFileTypeAscFileNameAsc(1L, 4L);
        verifyNoInteractions(storageService);
    }

    @Test
    void validateDownloadable_shouldRejectFiles() {
        assertThatThrownBy(() -> service.validateDownloadable(file(1L, null, "a.txt", 1L, "objects/a")))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getCode()).isEqualTo(400));
    }

    @Test
    void getOrCreateCachedZip_shouldCreateCacheObject() {
        FileEntity root = folder(1L, null, "Root");
        when(fileRepository.findByUserIdAndParentIdAndIsDeletedFalseOrderByFileTypeAscFileNameAsc(1L, 1L))
                .thenReturn(List.of());
        when(cacheRepository.findByUserIdAndFolderId(1L, 1L)).thenReturn(Optional.empty());
        when(storageService.uploadObject(anyString(), any(), anyLong(), eq("application/zip")))
                .thenAnswer(inv -> inv.getArgument(0));
        when(cacheRepository.save(any(FolderZipDownloadCacheEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        FolderZipDownloadCacheEntity result = service.getOrCreateCachedZip(root);

        assertThat(result.getUserId()).isEqualTo(1L);
        assertThat(result.getFolderId()).isEqualTo(1L);
        assertThat(result.getFileName()).isEqualTo("Root.zip");
        assertThat(result.getFileSize()).isGreaterThan(0);
        verify(storageService).uploadObject(startsWith("folder-zips/1/1/"), any(), eq(result.getFileSize()), eq("application/zip"));
    }

    private List<String> zipEntryNames(byte[] bytes) throws Exception {
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            java.util.ArrayList<String> names = new java.util.ArrayList<>();
            java.util.zip.ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                names.add(entry.getName());
            }
            return names;
        }
    }

    private FileEntity folder(Long id, Long parentId, String name) {
        return FileEntity.builder()
                .id(id)
                .userId(1L)
                .parentId(parentId)
                .fileName(name)
                .fileType("folder")
                .fileSize(0L)
                .isDeleted(false)
                .build();
    }

    private FileEntity file(Long id, Long parentId, String name, Long size, String storagePath) {
        return FileEntity.builder()
                .id(id)
                .userId(1L)
                .parentId(parentId)
                .fileName(name)
                .fileType("file")
                .fileSize(size)
                .storagePath(storagePath)
                .isDeleted(false)
                .build();
    }
}
