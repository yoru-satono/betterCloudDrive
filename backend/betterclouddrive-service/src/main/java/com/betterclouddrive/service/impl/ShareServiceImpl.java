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
import com.betterclouddrive.service.ShareService;
import jakarta.persistence.criteria.Predicate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.security.SecureRandom;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class ShareServiceImpl implements ShareService {

    private final ShareLinkRepository shareLinkRepository;
    private final FileRepository fileRepository;
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
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
        if (file == null || !file.getUserId().equals(userId) || file.getIsDeleted()) {
            throw new BusinessException(ApiCode.FILE_NOT_FOUND);
        }

        String normalizedPassword = normalizeOptionalPassword(password);
        String shareCode = generateShareCode();
        ShareLinkEntity share = ShareLinkEntity.builder()
                .userId(userId)
                .fileId(fileId)
                .shareCode(shareCode)
                .passwordHash(normalizedPassword != null ? passwordEncoder.encode(normalizedPassword) : null)
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
    public PageResult<ShareLinkEntity> listShares(Long userId, int page, int size) {
        Specification<ShareLinkEntity> spec = (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            predicates.add(cb.equal(root.get("userId"), userId));
            predicates.add(cb.equal(root.get("isCanceled"), false));
            return cb.and(predicates.toArray(new Predicate[0]));
        };

        PageRequest pageable = PageRequest.of(page - 1, size, Sort.by(Sort.Direction.DESC, "createdAt"));
        Page<ShareLinkEntity> result = shareLinkRepository.findAll(spec, pageable);
        return PageResult.of(result.getContent(), result.getTotalElements(), page, size);
    }

    @Override
    public ShareLinkEntity getShare(Long userId, Long shareId) {
        ShareLinkEntity share = shareLinkRepository.findById(shareId).orElse(null);
        if (share == null || !share.getUserId().equals(userId)) {
            throw new BusinessException(ApiCode.FILE_NOT_FOUND);
        }
        return share;
    }

    @Override
    @Transactional
    public ShareLinkEntity updateShare(Long userId, Long shareId, String password, Long expireAtMs, Integer maxVisits) {
        ShareLinkEntity share = getShare(userId, shareId);
        if (password != null) {
            String normalizedPassword = normalizeOptionalPassword(password);
            share.setPasswordHash(normalizedPassword == null ? null : passwordEncoder.encode(normalizedPassword));
        }
        if (expireAtMs != null) {
            share.setExpireAt(expireAtMs > 0 ? LocalDateTime.ofInstant(Instant.ofEpochMilli(expireAtMs), ZoneId.systemDefault()) : null);
        }
        if (maxVisits != null) {
            share.setMaxVisits(maxVisits);
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

    @Override
    @Transactional
    public void cancelShare(Long userId, Long shareId) {
        ShareLinkEntity share = getShare(userId, shareId);
        share.setIsCanceled(true);
        share.setUpdatedAt(LocalDateTime.now());
        shareLinkRepository.save(share);
    }

    @Override
    public FileEntity accessShare(String shareCode, String password) {
        ShareLinkEntity share = validateShare(shareCode, password);
        validateVisitLimit(share, shareCode);
        redisTemplate.opsForZSet().incrementScore("share:visits", shareCode, 1);

        FileEntity file = fileRepository.findById(share.getFileId()).orElse(null);
        if (file == null || file.getIsDeleted()) {
            throw new BusinessException(ApiCode.FILE_NOT_FOUND);
        }
        return file;
    }

    @Override
    @Transactional
    public FileEntity downloadSharedFile(String shareCode, Long fileId, String password) {
        ShareLinkEntity share = validateShare(shareCode, password);
        FileEntity downloadFile = resolveSharedDownloadTarget(share, fileId);
        if (!"file".equals(downloadFile.getFileType())) {
            throw new BusinessException(ApiCode.FILE_NOT_FOUND, "Cannot download a folder");
        }

        shareLinkRepository.incrementDownloadCount(share.getId());
        return downloadFile;
    }

    @Override
    public FileEntity resolveSharedFolderDownload(String shareCode, Long fileId, String password) {
        ShareLinkEntity share = validateShare(shareCode, password);
        FileEntity downloadFolder = resolveSharedDownloadTarget(share, fileId);
        if (!"folder".equals(downloadFolder.getFileType())) {
            throw new BusinessException(ApiCode.BAD_REQUEST, "Only folders can be downloaded as ZIP");
        }
        return downloadFolder;
    }

    @Override
    @Transactional
    public void recordSharedDownload(String shareCode, String password) {
        ShareLinkEntity share = validateShare(shareCode, password);
        shareLinkRepository.incrementDownloadCount(share.getId());
    }

    @Override
    @Transactional
    public FileEntity saveSharedItem(String shareCode, Long fileId, Long targetParentId, Long userId, String password) {
        ShareLinkEntity share = validateShare(shareCode, password);
        FileEntity sharedRoot = fileRepository.findById(share.getFileId()).orElse(null);
        if (sharedRoot == null || sharedRoot.getIsDeleted()) {
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
        if (share.getPasswordHash() != null) {
            if (password == null || !passwordEncoder.matches(password, share.getPasswordHash())) {
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
        if (!sharedFile.getUserId().equals(downloadFile.getUserId())) {
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
            if (parent == null || parent.getIsDeleted() || !sharedFile.getUserId().equals(parent.getUserId())) {
                return false;
            }
            parentId = parent.getParentId();
        }
        return false;
    }

    private FileEntity resolveSharedDownloadTarget(ShareLinkEntity share, Long fileId) {
        FileEntity sharedFile = fileRepository.findById(share.getFileId()).orElse(null);
        FileEntity downloadFile = fileRepository.findById(fileId).orElse(null);

        if (sharedFile == null || sharedFile.getIsDeleted() || downloadFile == null || downloadFile.getIsDeleted()) {
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
        if (source == null || source.getIsDeleted() || !isWithinSharedScope(sharedRoot, source)) {
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
            if (current == null || current.getIsDeleted() || !current.getUserId().equals(sourceFolder.getUserId())) {
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
        return fileRepository.findByUserIdAndParentIdAndIsDeletedFalseOrderByFileTypeAscFileNameAsc(
                        source.getUserId(), source.getId())
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
                .thumbnailPath(source.getThumbnailPath())
                .isDeleted(false)
                .versionCount(source.getVersionCount() != null ? source.getVersionCount() : 1)
                .createdAt(now)
                .updatedAt(now)
                .build();
        FileEntity saved = fileRepository.save(copy);

        if ("folder".equals(source.getFileType())) {
            List<FileEntity> children = fileRepository.findByUserIdAndParentIdAndIsDeletedFalseOrderByFileTypeAscFileNameAsc(
                    source.getUserId(), source.getId());
            for (FileEntity child : children) {
                copyTree(child, targetUserId, saved.getId());
            }
        }
        return saved;
    }

    @Override
    public PageResult<FileEntity> listSharedFiles(String shareCode, Long parentId, int page, int size) {
        ShareLinkEntity share = shareLinkRepository.findByShareCode(shareCode).orElse(null);
        if (share == null || share.getIsCanceled()) {
            throw new BusinessException(ApiCode.INVALID_SHARE_CODE);
        }

        FileEntity sharedFile = fileRepository.findById(share.getFileId()).orElse(null);
        if (sharedFile == null) {
            throw new BusinessException(ApiCode.FILE_NOT_FOUND);
        }

        if ("file".equals(sharedFile.getFileType())) {
            return PageResult.of(List.of(sharedFile), 1, page, size);
        }

        Long targetParentId = parentId != null ? parentId : sharedFile.getId();
        final Long finalParentId = targetParentId;
        Specification<FileEntity> spec = (root, query, cb) -> cb.and(
                cb.equal(root.get("userId"), sharedFile.getUserId()),
                cb.equal(root.get("parentId"), finalParentId),
                cb.equal(root.get("isDeleted"), false)
        );
        PageRequest pageable = PageRequest.of(page - 1, size,
                Sort.by("fileType").ascending().and(Sort.by("fileName").ascending()));
        Page<FileEntity> result = fileRepository.findAll(spec, pageable);
        return PageResult.of(result.getContent(), result.getTotalElements(), page, size);
    }

    private String generateShareCode() {
        StringBuilder sb = new StringBuilder(SHARE_CODE_LENGTH);
        for (int i = 0; i < SHARE_CODE_LENGTH; i++) {
            sb.append(SHARE_CODE_CHARS.charAt(random.nextInt(SHARE_CODE_CHARS.length())));
        }
        return sb.toString();
    }
}
