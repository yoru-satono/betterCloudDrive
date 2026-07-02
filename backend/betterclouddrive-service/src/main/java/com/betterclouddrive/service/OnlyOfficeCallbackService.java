package com.betterclouddrive.service;

public interface OnlyOfficeCallbackService {

    String callbackKey(String rawKey);

    String contentUrlHash(String url);

    boolean reserveSave(Long teamId, Long fileId, String callbackKey, Integer status, String contentUrlHash);

    void markSaved(Long teamId, Long fileId, String callbackKey, Integer status, String contentUrlHash, Long versionId);

    void releaseSaveReservation(Long teamId, Long fileId, String callbackKey, Integer status, String contentUrlHash);
}
