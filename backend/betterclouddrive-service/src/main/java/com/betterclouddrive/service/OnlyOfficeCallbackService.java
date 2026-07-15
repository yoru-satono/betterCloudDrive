package com.betterclouddrive.service;

import com.betterclouddrive.dal.entity.OnlyOfficeCallbackEntity;

import java.util.Optional;

public interface OnlyOfficeCallbackService {

    String callbackKey(String rawKey);

    String contentUrlHash(String url);

    boolean reserveSave(Long teamId, Long fileId, Long actorUserId, String callbackKey, Integer status, String contentUrlHash, String downloadUrl);

    void attachTask(Long teamId, Long fileId, String callbackKey, Integer status, String contentUrlHash, Long taskId);

    Optional<OnlyOfficeCallbackEntity> findByTaskId(Long taskId);

    void markSaved(Long teamId, Long fileId, String callbackKey, Integer status, String contentUrlHash, Long versionId);

    void markSaved(Long callbackId, Long versionId);

    void markFailed(Long callbackId, String errorMessage);

    void resetFailedByTaskId(Long taskId);

    void releaseSaveReservation(Long teamId, Long fileId, String callbackKey, Integer status, String contentUrlHash);
}
