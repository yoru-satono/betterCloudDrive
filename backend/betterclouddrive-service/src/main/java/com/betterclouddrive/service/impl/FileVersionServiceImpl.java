package com.betterclouddrive.service.impl;

import com.betterclouddrive.common.constant.ApiCode;
import com.betterclouddrive.common.exception.BusinessException;
import com.betterclouddrive.dal.entity.FileEntity;
import com.betterclouddrive.dal.entity.FileVersionEntity;
import com.betterclouddrive.dal.entity.TeamEntity;
import com.betterclouddrive.dal.repository.FileRepository;
import com.betterclouddrive.dal.repository.FileVersionRepository;
import com.betterclouddrive.dal.repository.TeamRepository;
import com.betterclouddrive.dal.repository.UserRepository;
import com.betterclouddrive.service.FileVersionService;
import com.betterclouddrive.service.NotificationService;
import com.betterclouddrive.service.TeamAuditService;
import com.betterclouddrive.storage.StorageService;
import com.betterclouddrive.service.quota.QuotaAccountingService;
import com.betterclouddrive.dal.repository.QuotaAccountingRepository.Scope;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
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

    private static final String CANNOT_DELETE_ONLY_VERSION = "error.cannotDeleteOnlyVersion";

    private final FileVersionRepository fileVersionRepository;
    private final FileRepository fileRepository;
    private final QuotaAccountingService quotaAccounting;
    private final UserRepository userRepository;
    private final TeamRepository teamRepository;
    private final StorageService storageService;
    private final TeamAuditService teamAuditService;
    private final NotificationService notificationService;

    @Override
    public List<FileVersionEntity> listVersions(Long userId, Long fileId) {
        FileEntity file = requirePersonalFile(userId, fileId);
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
        quotaAccounting.lock(Scope.PERSONAL, userId);
        FileEntity file = requirePersonalFileForUpdate(userId, fileId);
        deleteVersionRecord(file, versionNumber);
    }

    @Override
    @Transactional
    public FileEntity restoreVersion(Long userId, Long fileId, int versionNumber) {
        quotaAccounting.lock(Scope.PERSONAL, userId);
        FileEntity file = requirePersonalFileForUpdate(userId, fileId);
        FileVersionEntity version = requireVersion(file.getId(), versionNumber);
        return restoreFromVersion(file, userId, version);
    }

    @Override
    public List<FileVersionEntity> listTeamVersions(Long teamId, Long fileId) {
        FileEntity file = requireTeamFile(teamId, fileId);
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
        quotaAccounting.lock(Scope.TEAM, teamId);
        FileEntity file = requireTeamFileForUpdate(teamId, fileId);
        deleteVersionRecord(file, versionNumber);
        teamAuditService.record(teamId, userId, "VERSION_DELETE", "FILE", fileId,
                "version " + versionNumber);
    }

    @Override
    @Transactional
    public FileEntity restoreTeamVersion(Long teamId, Long userId, Long fileId, int versionNumber) {
        quotaAccounting.lock(Scope.TEAM, teamId);
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
        quotaAccounting.lock(Scope.TEAM, teamId);
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

        quotaAccounting.change(Scope.TEAM, teamId, content.length);
        notificationService.checkTeamQuota(teamId);
        teamAuditService.record(teamId, userId, "OFFICE_SAVE_VERSION", "FILE", fileId,
                file.getFileName() + " archived version " + archivedVersionNumber);
        return version;
    }

    private FileEntity restoreFromVersion(FileEntity file, Long actorUserId, FileVersionEntity targetVersion) {
        checkRestoreQuota(file, targetVersion.getFileSize());
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
        FileEntity restored = fileRepository.save(file);
        incrementStorage(file, targetVersion.getFileSize());
        return restored;
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
        if (file == null || file.getTeamId() != null || !file.getUserId().equals(userId)
                || file.getIsDeleted() || !"file".equals(file.getFileType())) {
            throw new BusinessException(ApiCode.FILE_NOT_FOUND);
        }
        return file;
    }

    private FileEntity requirePersonalFileForUpdate(Long userId, Long fileId) {
        FileEntity file = fileRepository.findByIdForUpdate(fileId).orElse(null);
        if (file == null || file.getTeamId() != null || !file.getUserId().equals(userId)
                || file.getIsDeleted() || !"file".equals(file.getFileType())) {
            throw new BusinessException(ApiCode.FILE_NOT_FOUND);
        }
        return file;
    }

    private FileEntity requireTeamFile(Long teamId, Long fileId) {
        FileEntity file = fileRepository.findById(fileId).orElse(null);
        if (!isActiveTeamFile(file, teamId)) {
            throw new BusinessException(ApiCode.FILE_NOT_FOUND);
        }
        return file;
    }

    private FileEntity requireTeamFileForUpdate(Long teamId, Long fileId) {
        FileEntity file = fileRepository.findByIdForUpdate(fileId).orElse(null);
        if (!isActiveTeamFile(file, teamId)) {
            throw new BusinessException(ApiCode.FILE_NOT_FOUND);
        }
        return file;
    }

    private boolean isActiveTeamFile(FileEntity file, Long teamId) {
        if (file == null || !Objects.equals(file.getTeamId(), teamId)
                || Boolean.TRUE.equals(file.getIsDeleted()) || !"file".equals(file.getFileType())) {
            return false;
        }
        Long parentId = file.getParentId();
        java.util.Set<Long> seen = new java.util.HashSet<>();
        while (parentId != null && seen.add(parentId)) {
            FileEntity parent = fileRepository.findById(parentId).orElse(null);
            if (parent == null || !Objects.equals(parent.getTeamId(), teamId)
                    || Boolean.TRUE.equals(parent.getIsDeleted())) {
                return false;
            }
            parentId = parent.getParentId();
        }
        return parentId == null;
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
            throw new BusinessException(ApiCode.VERSION_DELETE_CONFLICT.getCode(), CANNOT_DELETE_ONLY_VERSION,
                    java.util.Map.of(), "Cannot delete the only historical version of a file");
        }

        incrementStorage(file, -Math.max(0L, toDelete.getFileSize() == null ? 0L : toDelete.getFileSize()));
        storageService.deleteObject(toDelete.getStoragePath());
        fileVersionRepository.deleteById(toDelete.getId());
        file.setVersionCount(Math.max(1, versions.size()));
        fileRepository.save(file);
    }

    private void checkRestoreQuota(FileEntity file, Long additionalBytes) {
        quotaAccounting.check(file.getTeamId() == null ? Scope.PERSONAL : Scope.TEAM,
                file.getTeamId() == null ? file.getUserId() : file.getTeamId(),
                Math.max(0L, additionalBytes == null ? 0L : additionalBytes));
    }

    private void incrementStorage(FileEntity file, long delta) {
        quotaAccounting.change(file.getTeamId() == null ? Scope.PERSONAL : Scope.TEAM,
                file.getTeamId() == null ? file.getUserId() : file.getTeamId(), delta);
    }

    @Override
    @Transactional
    public int cleanupOldVersions(Long fileId, int maxVersions) {
        if (maxVersions < 1) throw new IllegalArgumentException("invalid_version_retention");
        quotaAccounting.lockForFile(fileId);
        FileEntity file = fileRepository.findByIdForUpdate(fileId).orElse(null);
        if (file == null || Boolean.TRUE.equals(file.getIsDeleted())) return 0;
        if (file.getTeamId() != null && !isActiveTeamFile(file, file.getTeamId())) return 0;
        Long parentId = file.getParentId();
        java.util.Set<Long> seen = new java.util.HashSet<>();
        while (parentId != null && seen.add(parentId)) {
            FileEntity parent = fileRepository.findById(parentId).orElse(null);
            if (parent == null || Boolean.TRUE.equals(parent.getIsDeleted()) || !"folder".equals(parent.getFileType())
                    || !Objects.equals(parent.getTeamId(), file.getTeamId())
                    || (file.getTeamId() == null && !Objects.equals(parent.getUserId(), file.getUserId()))) return 0;
            parentId = parent.getParentId();
        }
        if (parentId != null) return 0;
        List<FileVersionEntity> versions = fileVersionRepository.findTopVersionsByFileId(fileId, PageRequest.of(0, 1000));
        if (versions.size() <= maxVersions) return 0;
        long released = 0;
        var expired = versions.subList(maxVersions, versions.size());
        for (FileVersionEntity version : expired) {
            if (version.getFileSize() == null || version.getFileSize() < 0) {
                throw new BusinessException(409, "error.quotaMetadataInvalid");
            }
            released = Math.addExact(released, version.getFileSize());
        }
        incrementStorage(file, -released);
        for (FileVersionEntity version : expired) {
            storageService.deleteObject(version.getStoragePath());
            fileVersionRepository.deleteById(version.getId());
        }
        file.setVersionCount(maxVersions);
        fileRepository.save(file);
        return expired.size();
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
