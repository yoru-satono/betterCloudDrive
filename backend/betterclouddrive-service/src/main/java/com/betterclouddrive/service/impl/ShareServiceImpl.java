package com.betterclouddrive.service.impl;

import com.betterclouddrive.common.constant.ApiCode;
import com.betterclouddrive.common.dto.PageResult;
import com.betterclouddrive.common.exception.BusinessException;
import com.betterclouddrive.dal.entity.FileEntity;
import com.betterclouddrive.dal.entity.ShareLinkEntity;
import com.betterclouddrive.dal.repository.FileRepository;
import com.betterclouddrive.dal.repository.ShareLinkRepository;
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
    private final PasswordEncoder passwordEncoder;
    private final StringRedisTemplate redisTemplate;
    private static final String SHARE_CODE_CHARS = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
    private static final int SHARE_CODE_LENGTH = 8;
    private final SecureRandom random = new SecureRandom();

    @Override
    @Transactional
    public ShareLinkEntity createShare(Long userId, Long fileId, String password, Long expireAtMs, Integer maxDownloads) {
        FileEntity file = fileRepository.findById(fileId).orElse(null);
        if (file == null || !file.getUserId().equals(userId) || file.getIsDeleted()) {
            throw new BusinessException(ApiCode.FILE_NOT_FOUND);
        }

        String shareCode = generateShareCode();
        ShareLinkEntity share = ShareLinkEntity.builder()
                .userId(userId)
                .fileId(fileId)
                .shareCode(shareCode)
                .passwordHash(password != null ? passwordEncoder.encode(password) : null)
                .expireAt(expireAtMs != null ? LocalDateTime.ofInstant(Instant.ofEpochMilli(expireAtMs), ZoneId.systemDefault()) : null)
                .maxDownloads(maxDownloads)
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
    public ShareLinkEntity updateShare(Long userId, Long shareId, String password, Long expireAtMs, Integer maxDownloads) {
        ShareLinkEntity share = getShare(userId, shareId);
        if (password != null) {
            share.setPasswordHash(password.isEmpty() ? null : passwordEncoder.encode(password));
        }
        if (expireAtMs != null) {
            share.setExpireAt(expireAtMs > 0 ? LocalDateTime.ofInstant(Instant.ofEpochMilli(expireAtMs), ZoneId.systemDefault()) : null);
        }
        if (maxDownloads != null) {
            share.setMaxDownloads(maxDownloads);
        }
        share.setUpdatedAt(LocalDateTime.now());
        return shareLinkRepository.save(share);
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
        if (share.getMaxDownloads() != null && share.getDownloadCount() >= share.getMaxDownloads()) {
            throw new BusinessException(ApiCode.SHARE_DOWNLOAD_LIMIT);
        }

        redisTemplate.opsForZSet().incrementScore("share:visits", shareCode, 1);

        FileEntity file = fileRepository.findById(share.getFileId()).orElse(null);
        if (file == null || file.getIsDeleted()) {
            throw new BusinessException(ApiCode.FILE_NOT_FOUND);
        }
        return file;
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
