package com.betterclouddrive.service.impl;

import com.betterclouddrive.common.exception.BusinessException;
import com.betterclouddrive.dal.entity.FileEntity;
import com.betterclouddrive.dal.entity.FolderZipDownloadCacheEntity;
import com.betterclouddrive.dal.entity.TeamTaskEntity;
import com.betterclouddrive.dal.repository.FolderZipDownloadCacheRepository;
import com.betterclouddrive.service.FileService;
import com.betterclouddrive.service.FolderZipDownloadService;
import com.betterclouddrive.service.TeamPermissionService;
import com.betterclouddrive.service.TeamTaskService;
import com.betterclouddrive.service.dto.TeamTaskDownload;
import com.betterclouddrive.service.dto.TeamTaskResult;
import com.betterclouddrive.storage.StorageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.io.ByteArrayInputStream;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FolderZipTaskServiceImplTest {

    private TeamPermissionService teamPermissionService;
    private FileService fileService;
    private FolderZipDownloadService folderZipDownloadService;
    private FolderZipDownloadCacheRepository folderZipDownloadCacheRepository;
    private TeamTaskService teamTaskService;
    private StorageService storageService;
    private FolderZipTaskServiceImpl service;

    @BeforeEach
    void setUp() {
        teamPermissionService = mock(TeamPermissionService.class);
        fileService = mock(FileService.class);
        folderZipDownloadService = mock(FolderZipDownloadService.class);
        folderZipDownloadCacheRepository = mock(FolderZipDownloadCacheRepository.class);
        teamTaskService = mock(TeamTaskService.class);
        storageService = mock(StorageService.class);
        service = new FolderZipTaskServiceImpl(
                teamPermissionService,
                fileService,
                folderZipDownloadService,
                folderZipDownloadCacheRepository,
                teamTaskService,
                storageService);
    }

    @Test
    void queueTeamFolderZip_shouldValidateAndCreatePendingTask() {
        FileEntity folder = teamFolder();
        TeamTaskEntity task = TeamTaskEntity.builder().id(77L).status(TeamTaskEntity.STATUS_PENDING).build();
        when(fileService.getTeamFile(10L, 20L)).thenReturn(folder);
        when(teamTaskService.queueTask(10L, 2L, "FOLDER_ZIP", "FILE", 20L, "Generate team folder ZIP"))
                .thenReturn(task);

        TeamTaskEntity queued = service.queueTeamFolderZip(2L, 10L, 20L);

        assertThat(queued).isSameAs(task);
        verify(teamPermissionService).requireRead(2L, 10L, 20L);
        verify(folderZipDownloadService).validateDownloadable(folder);
    }

    @Test
    void processPendingFolderZips_shouldGenerateZipAndMarkSucceeded() {
        TeamTaskEntity task = TeamTaskEntity.builder()
                .id(77L)
                .teamId(10L)
                .actorUserId(2L)
                .taskType("FOLDER_ZIP")
                .targetType("FILE")
                .targetId(20L)
                .build();
        FolderZipDownloadCacheEntity cache = FolderZipDownloadCacheEntity.builder()
                .objectKey("folder-zips/teams/10/20/a.zip")
                .fileName("Docs.zip")
                .fileSize(123L)
                .build();
        FileEntity folder = teamFolder();
        when(teamTaskService.claimTasks("FOLDER_ZIP", 5)).thenReturn(List.of(task));
        when(fileService.getTeamFile(10L, 20L)).thenReturn(folder);
        when(folderZipDownloadService.getOrCreateCachedZip(folder)).thenReturn(cache);

        int processed = service.processPendingFolderZips(5);

        assertThat(processed).isEqualTo(1);
        ArgumentCaptor<TeamTaskResult> resultCaptor = ArgumentCaptor.forClass(TeamTaskResult.class);
        verify(teamTaskService).markSucceeded(eq(77L), eq("ZIP generated: Docs.zip"), resultCaptor.capture());
        TeamTaskResult result = resultCaptor.getValue();
        assertThat(result.path()).isEqualTo("folder-zips/teams/10/20/a.zip");
        assertThat(result.fileName()).isEqualTo("Docs.zip");
        assertThat(result.contentType()).isEqualTo("application/zip");
        assertThat(result.sizeBytes()).isEqualTo(123L);
    }

    @Test
    void downloadTeamFolderZipResult_shouldRequireSucceededTaskAndStreamObject() {
        TeamTaskEntity task = TeamTaskEntity.builder()
                .id(77L)
                .teamId(10L)
                .actorUserId(2L)
                .taskType("FOLDER_ZIP")
                .targetType("FILE")
                .targetId(20L)
                .status(TeamTaskEntity.STATUS_SUCCEEDED)
                .resultPath("folder-zips/teams/10/20/a.zip")
                .resultFileName("Docs.zip")
                .resultContentType("application/zip")
                .resultSizeBytes(123L)
                .build();
        FolderZipDownloadCacheEntity cache = FolderZipDownloadCacheEntity.builder()
                .objectKey("folder-zips/teams/10/20/a.zip")
                .build();
        when(teamTaskService.getTask(10L, 77L)).thenReturn(task);
        when(storageService.objectExists("folder-zips/teams/10/20/a.zip")).thenReturn(true);
        when(folderZipDownloadCacheRepository.findByObjectKey("folder-zips/teams/10/20/a.zip"))
                .thenReturn(Optional.of(cache));
        when(storageService.downloadObject("folder-zips/teams/10/20/a.zip"))
                .thenReturn(new ByteArrayInputStream("zip".getBytes()));

        TeamTaskDownload download = service.downloadTeamFolderZipResult(2L, 10L, 77L);

        assertThat(download.fileName()).isEqualTo("Docs.zip");
        assertThat(download.contentType()).isEqualTo("application/zip");
        assertThat(download.sizeBytes()).isEqualTo(123L);
        verify(teamPermissionService).requireRead(2L, 10L, 20L);
        verify(folderZipDownloadService).markDownloaded(cache);
    }

    @Test
    void downloadTeamFolderZipResult_shouldUseCacheMetadataWhenTaskMetadataIsMissing() {
        TeamTaskEntity task = TeamTaskEntity.builder()
                .id(77L)
                .teamId(10L)
                .actorUserId(2L)
                .taskType("FOLDER_ZIP")
                .targetType("FILE")
                .targetId(20L)
                .status(TeamTaskEntity.STATUS_SUCCEEDED)
                .resultPath("folder-zips/teams/10/20/a.zip")
                .build();
        FolderZipDownloadCacheEntity cache = FolderZipDownloadCacheEntity.builder()
                .objectKey("folder-zips/teams/10/20/a.zip")
                .fileName("Docs.zip")
                .fileSize(123L)
                .build();
        when(teamTaskService.getTask(10L, 77L)).thenReturn(task);
        when(storageService.objectExists("folder-zips/teams/10/20/a.zip")).thenReturn(true);
        when(folderZipDownloadCacheRepository.findByObjectKey("folder-zips/teams/10/20/a.zip"))
                .thenReturn(Optional.of(cache));
        when(storageService.downloadObject("folder-zips/teams/10/20/a.zip"))
                .thenReturn(new ByteArrayInputStream("zip".getBytes()));

        TeamTaskDownload download = service.downloadTeamFolderZipResult(2L, 10L, 77L);

        assertThat(download.fileName()).isEqualTo("Docs.zip");
        assertThat(download.contentType()).isEqualTo("application/zip");
        assertThat(download.sizeBytes()).isEqualTo(123L);
    }

    @Test
    void downloadTeamFolderZipResult_shouldRejectRunningTask() {
        when(teamTaskService.getTask(10L, 77L)).thenReturn(TeamTaskEntity.builder()
                .id(77L)
                .teamId(10L)
                .taskType("FOLDER_ZIP")
                .status(TeamTaskEntity.STATUS_RUNNING)
                .build());

        assertThatThrownBy(() -> service.downloadTeamFolderZipResult(2L, 10L, 77L))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void downloadTeamFolderZipResult_shouldRejectManagerThatDidNotCreateTask() {
        TeamTaskEntity task = TeamTaskEntity.builder()
                .id(77L)
                .teamId(10L)
                .actorUserId(3L)
                .taskType("FOLDER_ZIP")
                .targetType("FILE")
                .targetId(20L)
                .status(TeamTaskEntity.STATUS_SUCCEEDED)
                .resultPath("folder-zips/teams/10/20/a.zip")
                .build();
        when(teamTaskService.getTask(10L, 77L)).thenReturn(task);

        assertThatThrownBy(() -> service.downloadTeamFolderZipResult(2L, 10L, 77L))
                .isInstanceOf(BusinessException.class)
                .satisfies(error -> assertThat(((BusinessException) error).getCode()).isEqualTo(404));
    }

    private FileEntity teamFolder() {
        return FileEntity.builder()
                .id(20L)
                .teamId(10L)
                .userId(2L)
                .fileName("Docs")
                .fileType("folder")
                .isDeleted(false)
                .build();
    }
}
