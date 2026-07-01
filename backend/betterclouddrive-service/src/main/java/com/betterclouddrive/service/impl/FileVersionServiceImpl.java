package com.betterclouddrive.service.impl;

import com.betterclouddrive.common.constant.ApiCode;
import com.betterclouddrive.common.exception.BusinessException;
import com.betterclouddrive.dal.entity.FileEntity;
import com.betterclouddrive.dal.entity.FileVersionEntity;
import com.betterclouddrive.dal.repository.FileRepository;
import com.betterclouddrive.dal.repository.FileVersionRepository;
import com.betterclouddrive.service.FileVersionService;
import com.betterclouddrive.service.NotificationService;
import com.betterclouddrive.service.TeamAuditService;
import com.betterclouddrive.storage.StorageService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class FileVersionServiceImpl implements FileVersionService {

    private static final String TEAM_STORAGE_INCR_PREFIX = "team:storage:incr:";

    private final FileVersionRepository fileVersionRepository;
    private final FileRepository fileRepository;
    private final StorageService storageService;
    private final StringRedisTemplate redisTemplate;
    private final TeamAuditService teamAuditService;
    private final NotificationService notificationService;

    @Override
    public List<FileVersionEntity> listVersions(Long userId, Long fileId) {
        FileEntity file = fileRepository.findById(fileId).orElse(null);
        if (file == null || file.getTeamId() != null || !file.getUserId().equals(userId)) {
            throw new BusinessException(ApiCode.FILE_NOT_FOUND);
        }
        return fileVersionRepository.findTopVersionsByFileId(fileId, PageRequest.of(0, 10));
    }

    @Override
    public FileVersionEntity getVersion(Long userId, Long fileId, int versionNumber) {
        FileEntity file = requirePersonalFile(userId, fileId);
        return requireVersion(file.getId(), versionNumber);
    }

    @Override
    @Transactional
    public void deleteVersion(Long userId, Long fileId, int versionNumber) {
        FileEntity file = requirePersonalFile(userId, fileId);
        deleteVersionRecord(file, versionNumber);
    }

    @Override
    @Transactional
    public FileEntity restoreVersion(Long userId, Long fileId, int versionNumber) {
        FileEntity file = requirePersonalFileForUpdate(userId, fileId);
        FileVersionEntity version = requireVersion(file.getId(), versionNumber);
        return restoreFromVersion(file, userId, version);
    }

    @Override
    public List<FileVersionEntity> listTeamVersions(Long teamId, Long fileId) {
        FileEntity file = fileRepository.findById(fileId).orElse(null);
        if (file == null || !Objects.equals(file.getTeamId(), teamId)) {
            throw new BusinessException(ApiCode.FILE_NOT_FOUND);
        }
        return fileVersionRepository.findTopVersionsByFileId(fileId, PageRequest.of(0, 10));
    }

    @Override
    public FileVersionEntity getTeamVersion(Long teamId, Long fileId, int versionNumber) {
        FileEntity file = requireTeamFile(teamId, fileId);
        return requireVersion(file.getId(), versionNumber);
    }

    @Override
    @Transactional
    public void deleteTeamVersion(Long teamId, Long userId, Long fileId, int versionNumber) {
        FileEntity file = requireTeamFile(teamId, fileId);
        deleteVersionRecord(file, versionNumber);
        teamAuditService.record(teamId, userId, "VERSION_DELETE", "FILE", fileId,
                "version " + versionNumber);
    }

    @Override
    @Transactional
    public FileEntity restoreTeamVersion(Long teamId, Long userId, Long fileId, int versionNumber) {
        FileEntity file = requireTeamFileForUpdate(teamId, fileId);
        FileVersionEntity version = requireVersion(file.getId(), versionNumber);
        FileEntity restored = restoreFromVersion(file, userId, version);
        teamAuditService.record(teamId, userId, "VERSION_RESTORE", "FILE", fileId,
                "version " + versionNumber);
        return restored;
    }

    @Override
    @Transactional
    public FileVersionEntity saveTeamEditedVersion(Long teamId, Long userId, Long fileId, byte[] content) {
        FileEntity file = fileRepository.findByIdForUpdate(fileId).orElse(null);
        if (file == null || !Objects.equals(file.getTeamId(), teamId) || file.getIsDeleted() || !"file".equals(file.getFileType())) {
            throw new BusinessException(ApiCode.FILE_NOT_FOUND);
        }
        if (content == null || content.length == 0) {
            throw new BusinessException(ApiCode.BAD_REQUEST, "Edited document content is empty");
        }

        int maxExistingVersion = fileVersionRepository.findMaxVersionNumberByFileId(fileId);
        int currentVersionCount = file.getVersionCount() != null ? file.getVersionCount() : 1;
        int archivedVersionNumber = maxExistingVersion > 0 ? maxExistingVersion + 1 : currentVersionCount;

        FileVersionEntity version = FileVersionEntity.builder()
                .fileId(file.getId())
                .userId(userId)
                .versionNumber(archivedVersionNumber)
                .fileSize(file.getFileSize())
                .md5Hash(file.getMd5Hash())
                .storagePath(file.getStoragePath())
                .createdAt(LocalDateTime.now())
                .build();
        fileVersionRepository.save(version);

        String objectKey = generateOfficeStoragePath(teamId, file.getId(), file.getFileName());
        String md5Hash = md5(content);
        storageService.uploadObject(objectKey, new ByteArrayInputStream(content), content.length,
                file.getMimeType() != null ? file.getMimeType() : "application/octet-stream");

        file.setStoragePath(objectKey);
        file.setFileSize((long) content.length);
        file.setMd5Hash(md5Hash);
        file.setVersionCount(Math.max(currentVersionCount, archivedVersionNumber) + 1);
        file.setUpdatedAt(LocalDateTime.now());
        fileRepository.save(file);

        redisTemplate.opsForValue().increment(TEAM_STORAGE_INCR_PREFIX + teamId, content.length);
        notificationService.checkTeamQuota(teamId);
        teamAuditService.record(teamId, userId, "OFFICE_SAVE_VERSION", "FILE", fileId,
                file.getFileName() + " archived version " + archivedVersionNumber);
        return version;
    }

    private FileEntity restoreFromVersion(FileEntity file, Long actorUserId, FileVersionEntity targetVersion) {
        archiveCurrentVersion(file, actorUserId);
        String restoredPath = generateRestoredStoragePath(file);
        try (InputStream stream = storageService.downloadObject(targetVersion.getStoragePath())) {
            storageService.uploadObject(
                    restoredPath,
                    stream,
                    targetVersion.getFileSize(),
                    file.getMimeType() != null ? file.getMimeType() : "application/octet-stream");
        } catch (Exception e) {
            throw new RuntimeException("Failed to restore file version", e);
        }
        file.setStoragePath(restoredPath);
        file.setFileSize(targetVersion.getFileSize());
        file.setMd5Hash(targetVersion.getMd5Hash());
        file.setVersionCount(fileVersionRepository.findMaxVersionNumberByFileId(file.getId()) + 1);
        file.setUpdatedAt(LocalDateTime.now());
        return fileRepository.save(file);
    }

    private FileVersionEntity archiveCurrentVersion(FileEntity file, Long actorUserId) {
        int nextVersionNumber = Math.max(
                fileVersionRepository.findMaxVersionNumberByFileId(file.getId()) + 1,
                file.getVersionCount() != null ? file.getVersionCount() : 1);
        FileVersionEntity archived = FileVersionEntity.builder()
                .fileId(file.getId())
                .userId(actorUserId)
                .versionNumber(nextVersionNumber)
                .fileSize(file.getFileSize())
                .md5Hash(file.getMd5Hash())
                .storagePath(file.getStoragePath())
                .createdAt(LocalDateTime.now())
                .build();
        return fileVersionRepository.save(archived);
    }

    private FileEntity requirePersonalFile(Long userId, Long fileId) {
        FileEntity file = fileRepository.findById(fileId).orElse(null);
        if (file == null || file.getTeamId() != null || !file.getUserId().equals(userId) || file.getIsDeleted()) {
            throw new BusinessException(ApiCode.FILE_NOT_FOUND);
        }
        return file;
    }

    private FileEntity requirePersonalFileForUpdate(Long userId, Long fileId) {
        FileEntity file = fileRepository.findByIdForUpdate(fileId).orElse(null);
        if (file == null || file.getTeamId() != null || !file.getUserId().equals(userId) || file.getIsDeleted()) {
            throw new BusinessException(ApiCode.FILE_NOT_FOUND);
        }
        return file;
    }

    private FileEntity requireTeamFile(Long teamId, Long fileId) {
        FileEntity file = fileRepository.findById(fileId).orElse(null);
        if (file == null || !Objects.equals(file.getTeamId(), teamId) || file.getIsDeleted()) {
            throw new BusinessException(ApiCode.FILE_NOT_FOUND);
        }
        return file;
    }

    private FileEntity requireTeamFileForUpdate(Long teamId, Long fileId) {
        FileEntity file = fileRepository.findByIdForUpdate(fileId).orElse(null);
        if (file == null || !Objects.equals(file.getTeamId(), teamId) || file.getIsDeleted()) {
            throw new BusinessException(ApiCode.FILE_NOT_FOUND);
        }
        return file;
    }

    private FileVersionEntity requireVersion(Long fileId, int versionNumber) {
        return fileVersionRepository.findByFileIdAndVersionNumber(fileId, versionNumber)
                .orElseThrow(() -> new BusinessException(ApiCode.NOT_FOUND, "Version not found"));
    }

    private void deleteVersionRecord(FileEntity file, int versionNumber) {
        List<FileVersionEntity> versions = fileVersionRepository.findTopVersionsByFileId(file.getId(), PageRequest.of(0, 100));
        FileVersionEntity toDelete = versions.stream()
                .filter(v -> v.getVersionNumber() == versionNumber)
                .findFirst()
                .orElseThrow(() -> new BusinessException(ApiCode.NOT_FOUND, "Version not found"));

        if (versions.size() <= 1) {
            throw new BusinessException(ApiCode.CONFLICT, "Cannot delete the only version of a file");
        }

        storageService.deleteObject(toDelete.getStoragePath());
        fileVersionRepository.deleteById(toDelete.getId());
        int currentVersionCount = file.getVersionCount() != null ? file.getVersionCount() : versions.size();
        file.setVersionCount(Math.max(1, currentVersionCount - 1));
        fileRepository.save(file);
    }

    private String generateOfficeStoragePath(Long teamId, Long fileId, String fileName) {
        String randomId = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        String safeName = fileName != null ? fileName : "document";
        return "teams/" + teamId + "/office/" + fileId + "/" + randomId + "_" + safeName;
    }

    private String generateRestoredStoragePath(FileEntity file) {
        String randomId = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        String safeName = file.getFileName() != null ? file.getFileName() : "document";
        if (file.getTeamId() != null) {
            return "teams/" + file.getTeamId() + "/versions/restore/" + file.getId() + "/" + randomId + "_" + safeName;
        }
        return "users/" + file.getUserId() + "/versions/restore/" + file.getId() + "/" + randomId + "_" + safeName;
    }

    private String md5(byte[] content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("MD5");
            return HexFormat.of().formatHex(digest.digest(content));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("MD5 not available", e);
        }
    }
}
