package com.betterclouddrive.service.impl;

import com.betterclouddrive.common.constant.ApiCode;
import com.betterclouddrive.common.dto.PageResult;
import com.betterclouddrive.common.exception.BusinessException;
import com.betterclouddrive.dal.entity.FileEntity;
import com.betterclouddrive.dal.entity.ShareLinkEntity;
import com.betterclouddrive.dal.entity.UserEntity;
import com.betterclouddrive.dal.repository.FileRepository;
import com.betterclouddrive.dal.repository.ShareLinkRepository;
import com.betterclouddrive.dal.repository.UserRepository;
import com.betterclouddrive.service.SharePasswordCryptoService;
import com.betterclouddrive.service.ShareService;
import com.betterclouddrive.service.TeamAuditService;
import com.betterclouddrive.service.TeamPermissionService;
import com.betterclouddrive.service.dto.ShareUpdateCommand;
import jakarta.persistence.criteria.Predicate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.security.SecureRandom;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@Slf4j
@Service
@RequiredArgsConstructor
public class ShareServiceImpl implements ShareService {

    private final ShareLinkRepository shareLinkRepository;
    private final FileRepository fileRepository;
    private final UserRepository userRepository;
    private final SharePasswordCryptoService sharePasswordCryptoService;
    private final TeamAuditService teamAuditService;
    private final TeamPermissionService teamPermissionService;
    private final StringRedisTemplate redisTemplate;
    private static final String SHARE_CODE_CHARS = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
    private static final int SHARE_CODE_LENGTH = 8;
    private static final int MIN_SHARE_PASSWORD_LENGTH = 4;
    private static final int MAX_SHARE_PASSWORD_LENGTH = 16;
    private static final String STORAGE_INCR_PREFIX = "storage:incr:";
    private final SecureRandom random = new SecureRandom();

    @Override
    @Transactional
    public ShareLinkEntity createShare(Long userId, Long fileId, String password, Long expireAtMs, Integer maxVisits) {
        FileEntity file = fileRepository.findById(fileId).orElse(null);
        if (file == null || file.getTeamId() != null || !file.getUserId().equals(userId) || file.getIsDeleted()) {
            throw new BusinessException(ApiCode.FILE_NOT_FOUND);
        }

        return createShareRecord(userId, null, fileId, password, expireAtMs, maxVisits);
    }

    @Override
    @Transactional
    public ShareLinkEntity createTeamShare(Long userId, Long teamId, Long fileId, String password, Long expireAtMs, Integer maxVisits) {
        FileEntity file = fileRepository.findById(fileId).orElse(null);
        if (file == null || !teamId.equals(file.getTeamId()) || file.getIsDeleted()) {
            throw new BusinessException(ApiCode.FILE_NOT_FOUND);
        }
        ShareLinkEntity share = createShareRecord(userId, teamId, fileId, password, expireAtMs, maxVisits);
        teamAuditService.record(teamId, userId, "SHARE_CREATE", "SHARE", share.getId(),
                "share " + share.getShareCode() + " file " + fileId);
        return share;
    }

    private ShareLinkEntity createShareRecord(Long userId, Long teamId, Long fileId, String password, Long expireAtMs, Integer maxVisits) {
        String normalizedPassword = normalizeOptionalPassword(password);
        validateMaxVisits(maxVisits);
        String shareCode = generateShareCode();
        ShareLinkEntity share = ShareLinkEntity.builder()
                .userId(userId)
                .teamId(teamId)
                .fileId(fileId)
                .shareCode(shareCode)
                .passwordCiphertext(normalizedPassword != null ? sharePasswordCryptoService.encrypt(normalizedPassword) : null)
                .expireAt(expireAtMs != null ? LocalDateTime.ofInstant(Instant.ofEpochMilli(expireAtMs), ZoneId.systemDefault()) : null)
                .maxVisits(maxVisits)
                .downloadCount(0)
                .visitCount(0)
                .isCanceled(false)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
        return shareLinkRepository.save(share);
    }

    @Override
    public PageResult<ShareLinkEntity> listShares(Long userId, String keyword, Boolean passwordProtected, String expirationStatus, int page, int size) {
        Specification<ShareLinkEntity> spec = shareListSpec(userId, null, keyword, passwordProtected, expirationStatus);

        PageRequest pageable = PageRequest.of(page - 1, size,
                Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id")));
        Page<ShareLinkEntity> result = shareLinkRepository.findAll(spec, pageable);
        return PageResult.of(result.getContent(), result.getTotalElements(), page, size);
    }

    @Override
    public PageResult<ShareLinkEntity> listTeamShares(Long userId, Long teamId, String keyword, Boolean passwordProtected, String expirationStatus, int page, int size) {
        Specification<ShareLinkEntity> spec = shareListSpec(null, teamId, keyword, passwordProtected, expirationStatus);
        List<ShareLinkEntity> readable = shareLinkRepository
                .findAll(spec, Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id")))
                .stream()
                .filter(share -> canReadTeamFile(userId, teamId, share.getFileId()))
                .toList();
        return PageResults.fromFiltered(readable, page, size);
    }

    private Specification<ShareLinkEntity> shareListSpec(Long userId, Long teamId, String keyword, Boolean passwordProtected, String expirationStatus) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (teamId != null) {
                predicates.add(cb.equal(root.get("teamId"), teamId));
            } else {
                predicates.add(cb.equal(root.get("userId"), userId));
                predicates.add(cb.isNull(root.get("teamId")));
            }
            predicates.add(cb.equal(root.get("isCanceled"), false));
            if (StringUtils.hasText(keyword)) {
                String normalizedKeyword = keyword.trim().toLowerCase();
                List<Predicate> keywordPredicates = new ArrayList<>();
                keywordPredicates.add(cb.like(cb.lower(root.get("shareCode")), "%" + normalizedKeyword + "%"));
                try {
                    keywordPredicates.add(cb.equal(root.get("fileId"), Long.parseLong(normalizedKeyword)));
                } catch (NumberFormatException ignored) {
                }
                predicates.add(cb.or(keywordPredicates.toArray(new Predicate[0])));
            }
            if (passwordProtected != null) {
                predicates.add(passwordProtected ? cb.isNotNull(root.get("passwordCiphertext")) : cb.isNull(root.get("passwordCiphertext")));
            }
            if (StringUtils.hasText(expirationStatus)) {
                LocalDateTime now = LocalDateTime.now();
                switch (expirationStatus.trim().toUpperCase(Locale.ROOT)) {
                    case "ACTIVE" -> predicates.add(cb.or(cb.isNull(root.get("expireAt")), cb.greaterThanOrEqualTo(root.get("expireAt"), now)));
                    case "EXPIRED" -> predicates.add(cb.lessThan(root.get("expireAt"), now));
                    case "NEVER" -> predicates.add(cb.isNull(root.get("expireAt")));
                    default -> throw new BusinessException(ApiCode.BAD_REQUEST, "Unsupported expiration status filter");
                }
            }
            return cb.and(predicates.toArray(new Predicate[0]));
        };
    }

    @Override
    public ShareLinkEntity getShare(Long userId, Long shareId) {
        ShareLinkEntity share = shareLinkRepository.findById(shareId).orElse(null);
        if (share == null || share.getTeamId() != null || !share.getUserId().equals(userId)) {
            throw new BusinessException(ApiCode.FILE_NOT_FOUND);
        }
        return share;
    }

    @Override
    public ShareLinkEntity getTeamShare(Long teamId, Long shareId) {
        ShareLinkEntity share = shareLinkRepository.findById(shareId).orElse(null);
        if (share == null || !teamId.equals(share.getTeamId())) {
            throw new BusinessException(ApiCode.FILE_NOT_FOUND);
        }
        return share;
    }

    @Override
    public String getSharePassword(Long userId, Long shareId) {
        ShareLinkEntity share = getShare(userId, shareId);
        return share.getPasswordCiphertext() == null ? null : sharePasswordCryptoService.decrypt(share.getPasswordCiphertext());
    }

    @Override
    public String getTeamSharePassword(Long actorUserId, boolean canManageAll, Long teamId, Long shareId) {
        ShareLinkEntity share = getTeamShare(teamId, shareId);
        requireShareEditor(actorUserId, canManageAll, share);
        return share.getPasswordCiphertext() == null ? null : sharePasswordCryptoService.decrypt(share.getPasswordCiphertext());
    }

    @Override
    @Transactional
    public ShareLinkEntity updateShare(Long userId, Long shareId, String password, Long expireAtMs, Integer maxVisits) {
        return updateShare(userId, shareId, ShareUpdateCommand.legacy(password, expireAtMs, maxVisits));
    }

    @Override
    @Transactional
    public ShareLinkEntity updateShare(Long userId, Long shareId, ShareUpdateCommand command) {
        ShareLinkEntity share = getShare(userId, shareId);
        return updateShareRecord(share, command);
    }

    @Override
    @Transactional
    public ShareLinkEntity updateTeamShare(Long actorUserId, boolean canManageAll, Long teamId, Long shareId,
                                           String password, Long expireAtMs, Integer maxVisits) {
        return updateTeamShare(actorUserId, canManageAll, teamId, shareId,
                ShareUpdateCommand.legacy(password, expireAtMs, maxVisits));
    }

    @Override
    @Transactional
    public ShareLinkEntity updateTeamShare(Long actorUserId, boolean canManageAll, Long teamId, Long shareId,
                                           ShareUpdateCommand command) {
        ShareLinkEntity share = getTeamShare(teamId, shareId);
        requireShareEditor(actorUserId, canManageAll, share);
        ShareLinkEntity updated = updateShareRecord(share, command);
        teamAuditService.record(teamId, actorUserId, "SHARE_UPDATE", "SHARE", updated.getId(),
                "share " + updated.getShareCode() + " file " + updated.getFileId());
        return updated;
    }

    private ShareLinkEntity updateShareRecord(ShareLinkEntity share, ShareUpdateCommand command) {
        validateMaxVisits(command.maxVisits());
        if (command.clearPassword()) {
            share.setPasswordCiphertext(null);
        } else if (command.password() != null) {
            String normalizedPassword = normalizeOptionalPassword(command.password());
            share.setPasswordCiphertext(normalizedPassword == null ? null : sharePasswordCryptoService.encrypt(normalizedPassword));
        }
        if (command.clearExpireAt()) {
            share.setExpireAt(null);
        } else if (command.expireAtMs() != null) {
            share.setExpireAt(command.expireAtMs() > 0
                    ? LocalDateTime.ofInstant(Instant.ofEpochMilli(command.expireAtMs()), ZoneId.systemDefault())
                    : null);
        }
        if (command.clearMaxVisits()) {
            share.setMaxVisits(null);
        } else if (command.maxVisits() != null) {
            share.setMaxVisits(command.maxVisits());
        }
        share.setUpdatedAt(LocalDateTime.now());
        return shareLinkRepository.save(share);
    }

    private String normalizeOptionalPassword(String password) {
        if (!StringUtils.hasText(password)) {
            return null;
        }
        String normalizedPassword = password.trim();
        if (normalizedPassword.length() < MIN_SHARE_PASSWORD_LENGTH
                || normalizedPassword.length() > MAX_SHARE_PASSWORD_LENGTH) {
            throw new BusinessException(ApiCode.BAD_REQUEST, "Share password length must be between 4 and 16 characters");
        }
        return normalizedPassword;
    }

    private void validateMaxVisits(Integer maxVisits) {
        if (maxVisits != null && maxVisits < 1) {
            throw new BusinessException(ApiCode.BAD_REQUEST, "Share max visits must be a positive integer");
        }
    }

    @Override
    @Transactional
    public void cancelShare(Long userId, Long shareId) {
        ShareLinkEntity share = getShare(userId, shareId);
        cancelShareRecord(share);
    }

    @Override
    @Transactional
    public void cancelTeamShare(Long actorUserId, boolean canManageAll, Long teamId, Long shareId) {
        ShareLinkEntity share = getTeamShare(teamId, shareId);
        requireShareEditor(actorUserId, canManageAll, share);
        cancelShareRecord(share);
        teamAuditService.record(teamId, actorUserId, "SHARE_CANCEL", "SHARE", share.getId(),
                "share " + share.getShareCode() + " file " + share.getFileId());
    }

    private void cancelShareRecord(ShareLinkEntity share) {
        if (Boolean.TRUE.equals(share.getIsCanceled())) {
            return;
        }
        share.setIsCanceled(true);
        share.setUpdatedAt(LocalDateTime.now());
        shareLinkRepository.save(share);
    }

    private void requireShareEditor(Long actorUserId, boolean canManageAll, ShareLinkEntity share) {
        if (!canManageAll && !share.getUserId().equals(actorUserId)) {
            throw new BusinessException(ApiCode.FORBIDDEN, "Team role cannot manage this share");
        }
    }

    @Override
    public FileEntity accessShare(String shareCode, String password) {
        ShareLinkEntity share = validateShareAccess(shareCode, password, true);

        FileEntity file = fileRepository.findById(share.getFileId()).orElse(null);
        if (!isActiveInFileSpace(file)) {
            throw new BusinessException(ApiCode.FILE_NOT_FOUND);
        }
        return file;
    }

    @Override
    @Transactional
    public FileEntity downloadSharedFile(String shareCode, Long fileId, String password) {
        FileEntity downloadFile = resolveSharedFileDownload(shareCode, fileId, password);
        recordSharedDownload(shareCode, password);
        return downloadFile;
    }

    @Override
    public FileEntity resolveSharedFileDownload(String shareCode, Long fileId, String password) {
        ShareLinkEntity share = validateShareAccess(shareCode, password, false);
        FileEntity downloadFile = resolveSharedDownloadTarget(share, fileId);
        if (!"file".equals(downloadFile.getFileType())) {
            throw new BusinessException(ApiCode.FILE_NOT_FOUND, "Cannot download a folder");
        }
        return downloadFile;
    }

    @Override
    public FileEntity resolveSharedFolderDownload(String shareCode, Long fileId, String password) {
        ShareLinkEntity share = validateShareAccess(shareCode, password, false);
        FileEntity downloadFolder = resolveSharedDownloadTarget(share, fileId);
        if (!"folder".equals(downloadFolder.getFileType())) {
            throw new BusinessException(ApiCode.BAD_REQUEST, "Only folders can be downloaded as ZIP");
        }
        return downloadFolder;
    }

    @Override
    @Transactional
    public void recordSharedDownload(String shareCode, String password) {
        ShareLinkEntity share = validateShareAccess(shareCode, password, true);
        shareLinkRepository.incrementDownloadCount(share.getId());
    }

    @Override
    @Transactional
    public FileEntity saveSharedItem(String shareCode, Long fileId, Long targetParentId, Long userId, String password) {
        ShareLinkEntity share = validateShareAccess(shareCode, password, true);
        FileEntity sharedRoot = fileRepository.findById(share.getFileId()).orElse(null);
        if (!isActiveInFileSpace(sharedRoot)) {
            throw new BusinessException(ApiCode.FILE_NOT_FOUND);
        }

        FileEntity source = resolveSharedSource(sharedRoot, fileId);
        FileEntity targetParent = validateTargetParent(userId, targetParentId);
        if ("folder".equals(source.getFileType())
                && source.getUserId().equals(userId)
                && targetParent != null
                && isSameOrDescendant(targetParent, source)) {
            throw new BusinessException(ApiCode.BAD_REQUEST, "Cannot save a folder into itself or its subfolder");
        }
        if (nameExists(userId, targetParentId, source.getFileName())) {
            throw new BusinessException(ApiCode.FILE_NAME_CONFLICT);
        }

        long totalSize = calculateTreeFileSize(source);
        checkStorageQuota(userId, totalSize);

        FileEntity savedRoot = copyTree(source, userId, targetParentId);
        if (totalSize > 0) {
            redisTemplate.opsForValue().increment(STORAGE_INCR_PREFIX + userId, totalSize);
        }
        shareLinkRepository.incrementDownloadCount(share.getId());
        return savedRoot;
    }

    private ShareLinkEntity validateShareAccess(String shareCode, String password, boolean recordVisit) {
        ShareLinkEntity share = validateShare(shareCode, password);
        validateVisitLimit(share, shareCode);
        if (recordVisit) {
            var zSetOps = redisTemplate.opsForZSet();
            if (zSetOps != null) {
                zSetOps.incrementScore("share:visits", shareCode, 1);
            }
        }
        return share;
    }

    private ShareLinkEntity validateShare(String shareCode, String password) {
        ShareLinkEntity share = shareLinkRepository.findByShareCode(shareCode).orElse(null);
        if (share == null) {
            throw new BusinessException(ApiCode.INVALID_SHARE_CODE);
        }
        if (share.getIsCanceled()) {
            throw new BusinessException(ApiCode.INVALID_SHARE_CODE);
        }
        if (share.getExpireAt() != null && share.getExpireAt().isBefore(LocalDateTime.now())) {
            throw new BusinessException(ApiCode.SHARE_EXPIRED);
        }
        if (share.getPasswordCiphertext() != null) {
            if (!sharePasswordCryptoService.matches(password, share.getPasswordCiphertext())) {
                throw new BusinessException(ApiCode.SHARE_PASSWORD_REQUIRED);
            }
        }
        return share;
    }

    private void validateVisitLimit(ShareLinkEntity share, String shareCode) {
        if (share.getMaxVisits() == null) {
            return;
        }
        long persistedVisits = share.getVisitCount() != null ? share.getVisitCount() : 0L;
        Double pendingScore = redisTemplate.opsForZSet().score("share:visits", shareCode);
        long pendingVisits = pendingScore != null ? Math.round(pendingScore) : 0L;
        if (persistedVisits + pendingVisits >= share.getMaxVisits()) {
            throw new BusinessException(ApiCode.SHARE_VISIT_LIMIT);
        }
    }

    private boolean isWithinSharedScope(FileEntity sharedFile, FileEntity downloadFile) {
        if (!sameFileSpace(sharedFile, downloadFile)) {
            return false;
        }
        if (sharedFile.getId().equals(downloadFile.getId())) {
            return true;
        }
        if ("file".equals(sharedFile.getFileType())) {
            return false;
        }

        Long parentId = downloadFile.getParentId();
        while (parentId != null) {
            if (parentId.equals(sharedFile.getId())) {
                return true;
            }
            FileEntity parent = fileRepository.findById(parentId).orElse(null);
            if (parent == null || parent.getIsDeleted() || !sameFileSpace(sharedFile, parent)) {
                return false;
            }
            parentId = parent.getParentId();
        }
        return false;
    }

    private boolean sameFileSpace(FileEntity left, FileEntity right) {
        if (left.getTeamId() != null || right.getTeamId() != null) {
            return left.getTeamId() != null && left.getTeamId().equals(right.getTeamId());
        }
        return left.getUserId().equals(right.getUserId());
    }

    private FileEntity resolveSharedDownloadTarget(ShareLinkEntity share, Long fileId) {
        FileEntity sharedFile = fileRepository.findById(share.getFileId()).orElse(null);
        FileEntity downloadFile = fileRepository.findById(fileId).orElse(null);

        if (!isActiveInFileSpace(sharedFile) || !isActiveInFileSpace(downloadFile)) {
            throw new BusinessException(ApiCode.FILE_NOT_FOUND);
        }
        if (!isWithinSharedScope(sharedFile, downloadFile)) {
            throw new BusinessException(ApiCode.FILE_NOT_FOUND);
        }
        return downloadFile;
    }

    private FileEntity resolveSharedSource(FileEntity sharedRoot, Long fileId) {
        if (fileId == null) {
            return sharedRoot;
        }
        FileEntity source = fileRepository.findById(fileId).orElse(null);
        if (!isActiveInFileSpace(source) || !isWithinSharedScope(sharedRoot, source)) {
            throw new BusinessException(ApiCode.FILE_NOT_FOUND);
        }
        return source;
    }

    private FileEntity validateTargetParent(Long userId, Long targetParentId) {
        if (targetParentId == null) {
            return null;
        }
        FileEntity targetParent = fileRepository.findById(targetParentId).orElse(null);
        if (targetParent == null
                || targetParent.getIsDeleted()
                || !targetParent.getUserId().equals(userId)
                || !"folder".equals(targetParent.getFileType())) {
            throw new BusinessException(ApiCode.FILE_NOT_FOUND);
        }
        return targetParent;
    }

    private boolean isSameOrDescendant(FileEntity targetParent, FileEntity sourceFolder) {
        FileEntity current = targetParent;
        while (current != null) {
            if (current.getId().equals(sourceFolder.getId())) {
                return true;
            }
            Long parentId = current.getParentId();
            if (parentId == null) {
                return false;
            }
            current = fileRepository.findById(parentId).orElse(null);
            if (current == null || current.getIsDeleted() || !sameFileSpace(current, sourceFolder)) {
                return false;
            }
        }
        return false;
    }

    private boolean nameExists(Long userId, Long targetParentId, String fileName) {
        if (targetParentId == null) {
            return fileRepository.existsByUserIdAndParentIdIsNullAndFileNameAndIsDeletedFalse(userId, fileName);
        }
        return fileRepository.existsByUserIdAndParentIdAndFileNameAndIsDeletedFalse(userId, targetParentId, fileName);
    }

    private long calculateTreeFileSize(FileEntity source) {
        if ("file".equals(source.getFileType())) {
            return source.getFileSize() != null ? source.getFileSize() : 0L;
        }
        return activeChildren(source)
                .stream()
                .mapToLong(this::calculateTreeFileSize)
                .sum();
    }

    private void checkStorageQuota(Long userId, long totalSize) {
        UserEntity user = userRepository.findById(userId).orElse(null);
        if (user == null) {
            throw new BusinessException(ApiCode.UNAUTHORIZED);
        }
        String incrStr = redisTemplate.opsForValue().get(STORAGE_INCR_PREFIX + userId);
        long pendingIncr = 0L;
        if (incrStr != null) {
            try {
                pendingIncr = Long.parseLong(incrStr);
            } catch (NumberFormatException ignored) {
            }
        }

        long storageUsed = user.getStorageUsed() != null ? user.getStorageUsed() : 0L;
        long storageQuota = user.getStorageQuota() != null ? user.getStorageQuota() : 0L;
        long currentUsed = storageUsed + pendingIncr;
        if (currentUsed + totalSize > storageQuota) {
            throw new BusinessException(ApiCode.STORAGE_QUOTA_EXCEEDED,
                    "Storage quota exceeded. Used: " + currentUsed + ", Quota: " + storageQuota);
        }
    }

    private FileEntity copyTree(FileEntity source, Long targetUserId, Long targetParentId) {
        LocalDateTime now = LocalDateTime.now();
        FileEntity copy = FileEntity.builder()
                .userId(targetUserId)
                .parentId(targetParentId)
                .fileName(source.getFileName())
                .fileType(source.getFileType())
                .mimeType(source.getMimeType())
                .fileSize(source.getFileSize() != null ? source.getFileSize() : 0L)
                .storagePath(source.getStoragePath())
                .md5Hash(source.getMd5Hash())
                .isDeleted(false)
                .versionCount(source.getVersionCount() != null ? source.getVersionCount() : 1)
                .createdAt(now)
                .updatedAt(now)
                .build();
        FileEntity saved = fileRepository.save(copy);

        if ("folder".equals(source.getFileType())) {
            for (FileEntity child : activeChildren(source)) {
                copyTree(child, targetUserId, saved.getId());
            }
        }
        return saved;
    }

    private List<FileEntity> activeChildren(FileEntity source) {
        if (source.getTeamId() != null) {
            return fileRepository.findByTeamIdAndParentIdAndIsDeletedFalseOrderByFileTypeAscFileNameAsc(
                    source.getTeamId(), source.getId());
        }
        return fileRepository.findByUserIdAndParentIdAndIsDeletedFalseOrderByFileTypeAscFileNameAsc(
                source.getUserId(), source.getId());
    }

    @Override
    public PageResult<FileEntity> listSharedFiles(String shareCode, Long parentId, int page, int size, String password) {
        ShareLinkEntity share = validateShareAccess(shareCode, password, true);

        FileEntity sharedFile = fileRepository.findById(share.getFileId()).orElse(null);
        if (!isActiveInFileSpace(sharedFile)) {
            throw new BusinessException(ApiCode.FILE_NOT_FOUND);
        }

        if ("file".equals(sharedFile.getFileType())) {
            return PageResult.of(List.of(sharedFile), 1, page, size);
        }

        FileEntity targetParent = parentId != null ? resolveSharedSource(sharedFile, parentId) : sharedFile;
        if (!"folder".equals(targetParent.getFileType())) {
            throw new BusinessException(ApiCode.FILE_NOT_FOUND);
        }

        Long targetParentId = targetParent.getId();
        final Long finalParentId = targetParentId;
        Specification<FileEntity> spec = (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (sharedFile.getTeamId() != null) {
                predicates.add(cb.equal(root.get("teamId"), sharedFile.getTeamId()));
            } else {
                predicates.add(cb.equal(root.get("userId"), sharedFile.getUserId()));
                predicates.add(cb.isNull(root.get("teamId")));
            }
            predicates.add(cb.equal(root.get("parentId"), finalParentId));
            predicates.add(cb.equal(root.get("isDeleted"), false));
            return cb.and(predicates.toArray(new Predicate[0]));
        };
        PageRequest pageable = PageRequest.of(page - 1, size,
                Sort.by("fileType").ascending().and(Sort.by("fileName").ascending()));
        Page<FileEntity> result = fileRepository.findAll(spec, pageable);
        return PageResult.of(result.getContent(), result.getTotalElements(), page, size);
    }

    private boolean canReadTeamFile(Long userId, Long teamId, Long fileId) {
        try {
            return teamPermissionService.effectivePermissions(userId, teamId, fileId).canRead();
        } catch (BusinessException exception) {
            return false;
        }
    }

    private boolean isActiveInFileSpace(FileEntity file) {
        if (file == null || Boolean.TRUE.equals(file.getIsDeleted())) {
            return false;
        }
        FileEntity cursor = file;
        java.util.Set<Long> seen = new java.util.HashSet<>();
        while (cursor.getParentId() != null && seen.add(cursor.getId())) {
            FileEntity parent = fileRepository.findById(cursor.getParentId()).orElse(null);
            if (parent == null || Boolean.TRUE.equals(parent.getIsDeleted()) || !sameFileSpace(file, parent)) {
                return false;
            }
            cursor = parent;
        }
        return cursor.getParentId() == null;
    }

    private String generateShareCode() {
        StringBuilder sb = new StringBuilder(SHARE_CODE_LENGTH);
        for (int i = 0; i < SHARE_CODE_LENGTH; i++) {
            sb.append(SHARE_CODE_CHARS.charAt(random.nextInt(SHARE_CODE_CHARS.length())));
        }
        return sb.toString();
    }
}
