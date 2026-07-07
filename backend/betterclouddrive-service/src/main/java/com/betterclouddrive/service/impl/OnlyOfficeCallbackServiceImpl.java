package com.betterclouddrive.service.impl;

import com.betterclouddrive.common.constant.ApiCode;
import com.betterclouddrive.common.exception.BusinessException;
import com.betterclouddrive.dal.entity.OnlyOfficeCallbackEntity;
import com.betterclouddrive.dal.repository.OnlyOfficeCallbackRepository;
import com.betterclouddrive.service.OnlyOfficeCallbackService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;

@Service
@RequiredArgsConstructor
public class OnlyOfficeCallbackServiceImpl implements OnlyOfficeCallbackService {

    private final OnlyOfficeCallbackRepository onlyOfficeCallbackRepository;

    @Override
    public String callbackKey(String rawKey) {
        if (!StringUtils.hasText(rawKey)) {
            throw new BusinessException(ApiCode.BAD_REQUEST, "OnlyOffice callback key is missing");
        }
        String trimmed = rawKey.trim();
        if (trimmed.length() <= 128) {
            return trimmed;
        }
        return sha256(trimmed);
    }

    @Override
    public String contentUrlHash(String url) {
        if (!StringUtils.hasText(url)) {
            throw new BusinessException(ApiCode.BAD_REQUEST, "OnlyOffice callback URL is missing");
        }
        return sha256(url.trim());
    }

    @Transactional
    @Override
    public boolean reserveSave(
            Long teamId,
            Long fileId,
            Long actorUserId,
            String callbackKey,
            Integer status,
            String contentUrlHash,
            String downloadUrl) {
        if (!StringUtils.hasText(downloadUrl)) {
            throw new BusinessException(ApiCode.BAD_REQUEST, "OnlyOffice callback URL is missing");
        }
        return onlyOfficeCallbackRepository.insertProcessing(
                teamId,
                fileId,
                actorUserId,
                callbackKey,
                status,
                contentUrlHash,
                downloadUrl.trim()) == 1;
    }

    @Transactional
    @Override
    public void attachTask(Long teamId, Long fileId, String callbackKey, Integer status, String contentUrlHash, Long taskId) {
        if (taskId == null) {
            return;
        }
        onlyOfficeCallbackRepository.attachTask(
                teamId,
                fileId,
                callbackKey,
                status,
                contentUrlHash,
                taskId,
                LocalDateTime.now());
    }

    @Override
    public List<OnlyOfficeCallbackEntity> listPendingSaves(int limit) {
        return onlyOfficeCallbackRepository.findByStateOrderByCreatedAtAsc(1, PageRequest.of(0, Math.max(limit, 1)));
    }

    @Transactional
    @Override
    public void markSaved(Long teamId, Long fileId, String callbackKey, Integer status, String contentUrlHash, Long versionId) {
        onlyOfficeCallbackRepository.markSaved(
                teamId,
                fileId,
                callbackKey,
                status,
                contentUrlHash,
                versionId,
                LocalDateTime.now());
    }

    @Transactional
    @Override
    public void markSaved(Long callbackId, Long versionId) {
        if (callbackId == null) {
            return;
        }
        onlyOfficeCallbackRepository.markSavedById(callbackId, versionId, LocalDateTime.now());
    }

    @Transactional
    @Override
    public void markFailed(Long callbackId, String errorMessage) {
        if (callbackId == null) {
            return;
        }
        String normalized = StringUtils.hasText(errorMessage) ? errorMessage.trim() : "OnlyOffice save failed";
        if (normalized.length() > 512) {
            normalized = normalized.substring(0, 512);
        }
        onlyOfficeCallbackRepository.markFailedById(callbackId, normalized, LocalDateTime.now());
    }

    @Transactional
    @Override
    public void resetFailedByTaskId(Long taskId) {
        if (taskId == null) {
            return;
        }
        onlyOfficeCallbackRepository.resetFailedByTaskId(taskId, LocalDateTime.now());
    }

    @Transactional
    @Override
    public void releaseSaveReservation(Long teamId, Long fileId, String callbackKey, Integer status, String contentUrlHash) {
        onlyOfficeCallbackRepository.deleteByTeamIdAndFileIdAndCallbackKeyAndStatusAndContentUrlHash(
                teamId, fileId, callbackKey, status, contentUrlHash);
    }

    private String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
