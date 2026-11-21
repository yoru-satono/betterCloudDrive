package com.betterclouddrive.service.impl;

import com.betterclouddrive.dal.entity.FileEntity;
import com.betterclouddrive.dal.entity.FileVersionEntity;
import com.betterclouddrive.dal.entity.FolderZipDownloadCacheEntity;
import com.betterclouddrive.dal.entity.UploadSessionEntity;
import com.betterclouddrive.dal.repository.FileRepository;
import com.betterclouddrive.dal.repository.FileVersionRepository;
import com.betterclouddrive.dal.repository.FolderZipDownloadCacheRepository;
import com.betterclouddrive.dal.repository.TeamRepository;
import com.betterclouddrive.dal.repository.UploadSessionRepository;
import com.betterclouddrive.storage.StorageService;
import com.betterclouddrive.service.quota.QuotaAccountingService;
import com.betterclouddrive.dal.repository.QuotaAccountingRepository.Scope;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.List;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TeamPurgeServiceImplTest {

    @Mock private TeamRepository teamRepository;
    @Mock private FileRepository fileRepository;
    @Mock private FileVersionRepository fileVersionRepository;
    @Mock private FolderZipDownloadCacheRepository folderZipDownloadCacheRepository;
    @Mock private UploadSessionRepository uploadSessionRepository;
    @Mock private StorageService storageService;
    @Mock private StringRedisTemplate redisTemplate;

    @Mock private QuotaAccountingService quotaAccounting;
    @InjectMocks private TeamPurgeServiceImpl service;

    @Test
    void shouldCleanupStorageObjectsBeforeDeletingTeam() {
        FileEntity file = FileEntity.builder()
                .id(1L)
                .teamId(10L)
                .fileType("file")
                .storagePath("teams/10/file.bin")
                .thumbnailPath("teams/10/thumb.webp")
                .build();
        FileEntity folder = FileEntity.builder()
                .id(2L)
                .teamId(10L)
                .fileType("folder")
                .build();
        FileVersionEntity version = FileVersionEntity.builder()
                .id(3L)
                .fileId(1L)
                .storagePath("teams/10/version.bin")
                .build();
        FolderZipDownloadCacheEntity cache = FolderZipDownloadCacheEntity.builder()
                .id(4L)
                .folderId(2L)
                .objectKey("zip/teams/10/folder.zip")
                .build();
        UploadSessionEntity activeUpload = UploadSessionEntity.builder()
                .id("active-upload")
                .teamId(10L)
                .status(1)
                .totalChunks(2)
                .build();
        UploadSessionEntity completedUpload = UploadSessionEntity.builder()
                .id("completed-upload")
                .teamId(10L)
                .status(2)
                .totalChunks(2)
                .build();

        when(fileRepository.findByTeamId(10L)).thenReturn(List.of(file, folder));
        when(fileVersionRepository.findByFileIdIn(List.of(1L, 2L))).thenReturn(List.of(version));
        when(folderZipDownloadCacheRepository.findByFolderIdIn(List.of(2L))).thenReturn(List.of(cache));
        when(uploadSessionRepository.findByTeamId(10L)).thenReturn(List.of(activeUpload, completedUpload));

        service.purgeTeam(10L);

        verify(storageService).deleteObject("teams/10/file.bin");
        verify(storageService).deleteObject("teams/10/thumb.webp");
        verify(storageService).deleteObject("teams/10/version.bin");
        verify(storageService).deleteObject("zip/teams/10/folder.zip");
        verify(storageService).deleteParts("uploads/teams/10/active-upload/chunks", 2);
        verify(storageService, never()).deleteParts("uploads/teams/10/completed-upload/chunks", 2);
        verify(teamRepository).deleteExistingById(10L);
    }

    @Test
    void shouldBeIdempotentWhenTeamIsAlreadyGone() {
        when(fileRepository.findByTeamId(10L)).thenReturn(List.of());
        when(uploadSessionRepository.findByTeamId(10L)).thenReturn(List.of());

        service.purgeTeam(10L);

        verify(teamRepository).deleteExistingById(10L);
        verify(storageService, never()).deleteObject(org.mockito.ArgumentMatchers.anyString());
    }
}
