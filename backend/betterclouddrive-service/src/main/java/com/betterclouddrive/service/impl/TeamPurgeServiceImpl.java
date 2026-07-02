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
import com.betterclouddrive.service.TeamPurgeService;
import com.betterclouddrive.storage.StorageService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.List;

@Service
@RequiredArgsConstructor
public class TeamPurgeServiceImpl implements TeamPurgeService {

    private static final int UPLOAD_STATUS_ACTIVE = 1;
    private static final String TEAM_STORAGE_INCR_PREFIX = "team:storage:incr:";

    private final TeamRepository teamRepository;
    private final FileRepository fileRepository;
    private final FileVersionRepository fileVersionRepository;
    private final FolderZipDownloadCacheRepository folderZipDownloadCacheRepository;
    private final UploadSessionRepository uploadSessionRepository;
    private final StorageService storageService;
    private final StringRedisTemplate redisTemplate;

    @Override
    @Transactional
    public void purgeTeam(Long teamId) {
        List<FileEntity> files = fileRepository.findByTeamId(teamId);
        List<Long> fileIds = files.stream().map(FileEntity::getId).toList();
        List<FileVersionEntity> versions = fileIds.isEmpty()
                ? List.of()
                : fileVersionRepository.findByFileIdIn(fileIds);
        List<Long> folderIds = files.stream()
                .filter(file -> "folder".equals(file.getFileType()))
                .map(FileEntity::getId)
                .toList();
        List<FolderZipDownloadCacheEntity> zipCaches = folderIds.isEmpty()
                ? List.of()
                : folderZipDownloadCacheRepository.findByFolderIdIn(folderIds);
        List<UploadSessionEntity> uploadSessions = uploadSessionRepository.findByTeamId(teamId);

        for (FileEntity file : files) {
            deleteObjectIfPresent(file.getStoragePath());
            deleteObjectIfPresent(file.getThumbnailPath());
        }
        for (FileVersionEntity version : versions) {
            deleteObjectIfPresent(version.getStoragePath());
        }
        for (FolderZipDownloadCacheEntity cache : zipCaches) {
            deleteObjectIfPresent(cache.getObjectKey());
        }
        for (UploadSessionEntity session : uploadSessions) {
            if (Integer.valueOf(UPLOAD_STATUS_ACTIVE).equals(session.getStatus())) {
                storageService.deleteParts(teamUploadChunkPrefix(teamId, session.getId()), safeChunkCount(session));
            }
        }

        teamRepository.deleteExistingById(teamId);
        redisTemplate.delete(TEAM_STORAGE_INCR_PREFIX + teamId);
    }

    private void deleteObjectIfPresent(String objectKey) {
        if (StringUtils.hasText(objectKey)) {
            storageService.deleteObject(objectKey);
        }
    }

    private int safeChunkCount(UploadSessionEntity session) {
        return session.getTotalChunks() != null ? session.getTotalChunks() : 0;
    }

    private String teamUploadChunkPrefix(Long teamId, String sessionId) {
        return "uploads/teams/" + teamId + "/" + sessionId + "/chunks";
    }
}
