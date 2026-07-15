package com.betterclouddrive.service.impl;

import com.betterclouddrive.common.constant.ApiCode;
import com.betterclouddrive.common.exception.BusinessException;
import com.betterclouddrive.dal.entity.FileEntity;
import com.betterclouddrive.dal.entity.FolderZipDownloadCacheEntity;
import com.betterclouddrive.dal.entity.TeamTaskEntity;
import com.betterclouddrive.dal.repository.FolderZipDownloadCacheRepository;
import com.betterclouddrive.service.FileService;
import com.betterclouddrive.service.FolderZipDownloadService;
import com.betterclouddrive.service.FolderZipTaskService;
import com.betterclouddrive.service.TeamPermissionService;
import com.betterclouddrive.service.TeamTaskService;
import com.betterclouddrive.service.dto.TeamTaskDownload;
import com.betterclouddrive.service.dto.TeamTaskResult;
import com.betterclouddrive.storage.StorageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class FolderZipTaskServiceImpl implements FolderZipTaskService {

    public static final String TASK_TYPE_FOLDER_ZIP = "FOLDER_ZIP";
    private static final String TARGET_TYPE_FILE = "FILE";
    private static final String CONTENT_TYPE_ZIP = "application/zip";

    private final TeamPermissionService teamPermissionService;
    private final FileService fileService;
    private final FolderZipDownloadService folderZipDownloadService;
    private final FolderZipDownloadCacheRepository folderZipDownloadCacheRepository;
    private final TeamTaskService teamTaskService;
    private final StorageService storageService;

    @Override
    public TeamTaskEntity queueTeamFolderZip(Long requesterUserId, Long teamId, Long fileId) {
        teamPermissionService.requireRead(requesterUserId, teamId, fileId);
        FileEntity folder = fileService.getTeamFile(teamId, fileId);
        folderZipDownloadService.validateDownloadable(folder);
        return teamTaskService.queueTask(
                teamId,
                requesterUserId,
                TASK_TYPE_FOLDER_ZIP,
                TARGET_TYPE_FILE,
                fileId,
                "Generate team folder ZIP");
    }

    @Override
    public int processPendingFolderZips(int batchSize) {
        int processed = 0;
        for (TeamTaskEntity task : teamTaskService.claimTasks(TASK_TYPE_FOLDER_ZIP, batchSize)) {
            processTask(task);
            processed++;
        }
        return processed;
    }

    @Override
    public TeamTaskDownload downloadTeamFolderZipResult(Long requesterUserId, Long teamId, Long taskId) {
        TeamTaskEntity task = teamTaskService.getTask(teamId, taskId);
        if (!TASK_TYPE_FOLDER_ZIP.equals(task.getTaskType())) {
            throw new BusinessException(ApiCode.NOT_FOUND, "Task result is not available");
        }
        if (!TeamTaskEntity.STATUS_SUCCEEDED.equals(task.getStatus())) {
            throw new BusinessException(ApiCode.BAD_REQUEST, "Task has not succeeded");
        }
        teamPermissionService.requireRead(requesterUserId, teamId, task.getTargetId());
        if (!StringUtils.hasText(task.getResultPath())) {
            throw new BusinessException(ApiCode.NOT_FOUND, "Task result is not available");
        }
        if (!storageService.objectExists(task.getResultPath())) {
            throw new BusinessException(ApiCode.NOT_FOUND, "Task result file is missing");
        }
        Optional<FolderZipDownloadCacheEntity> cache = folderZipDownloadCacheRepository.findByObjectKey(task.getResultPath());
        Long sizeBytes = task.getResultSizeBytes() != null
                ? task.getResultSizeBytes()
                : cache.map(FolderZipDownloadCacheEntity::getFileSize).orElse(null);
        if (sizeBytes == null) {
            throw new BusinessException(ApiCode.NOT_FOUND, "Task result metadata is missing");
        }
        String fileName = StringUtils.hasText(task.getResultFileName())
                ? task.getResultFileName()
                : cache.map(FolderZipDownloadCacheEntity::getFileName).orElse("folder.zip");
        cache.ifPresent(folderZipDownloadService::markDownloaded);
        return new TeamTaskDownload(
                fileName,
                StringUtils.hasText(task.getResultContentType()) ? task.getResultContentType() : CONTENT_TYPE_ZIP,
                sizeBytes,
                storageService.downloadObject(task.getResultPath()));
    }

    private void processTask(TeamTaskEntity task) {
        if (task == null || task.getId() == null) {
            return;
        }
        try {
            if (!TARGET_TYPE_FILE.equals(task.getTargetType()) || task.getTargetId() == null || task.getTeamId() == null) {
                throw new BusinessException(ApiCode.BAD_REQUEST, "Folder ZIP task target is invalid");
            }
            FileEntity folder = fileService.getTeamFile(task.getTeamId(), task.getTargetId());
            FolderZipDownloadCacheEntity cache = folderZipDownloadService.getOrCreateCachedZip(folder);
            teamTaskService.markSucceeded(
                    task.getId(),
                    "ZIP generated: " + cache.getFileName(),
                    new TeamTaskResult(
                            cache.getObjectKey(),
                            cache.getFileName(),
                            CONTENT_TYPE_ZIP,
                            cache.getFileSize(),
                            null));
        } catch (RuntimeException e) {
            log.warn("Failed to process team folder ZIP task {}", task.getId(), e);
            teamTaskService.markFailed(task.getId(), StringUtils.hasText(e.getMessage()) ? e.getMessage() : e.getClass().getSimpleName());
        }
    }
}
