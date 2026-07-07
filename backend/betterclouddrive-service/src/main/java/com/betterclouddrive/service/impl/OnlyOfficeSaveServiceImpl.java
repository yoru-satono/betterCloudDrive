package com.betterclouddrive.service.impl;

import com.betterclouddrive.dal.entity.FileVersionEntity;
import com.betterclouddrive.dal.entity.OnlyOfficeCallbackEntity;
import com.betterclouddrive.service.FileVersionService;
import com.betterclouddrive.service.NotificationService;
import com.betterclouddrive.service.OnlyOfficeCallbackService;
import com.betterclouddrive.service.OnlyOfficeContentDownloader;
import com.betterclouddrive.service.OnlyOfficeSaveService;
import com.betterclouddrive.service.TeamAuditService;
import com.betterclouddrive.service.TeamTaskService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class OnlyOfficeSaveServiceImpl implements OnlyOfficeSaveService {

    private final OnlyOfficeCallbackService onlyOfficeCallbackService;
    private final OnlyOfficeContentDownloader onlyOfficeContentDownloader;
    private final FileVersionService fileVersionService;
    private final TeamTaskService teamTaskService;
    private final TeamAuditService teamAuditService;
    private final NotificationService notificationService;

    @Override
    public int processPendingSaves(int limit) {
        List<OnlyOfficeCallbackEntity> callbacks = onlyOfficeCallbackService.listPendingSaves(limit);
        int processed = 0;
        for (OnlyOfficeCallbackEntity callback : callbacks) {
            processCallback(callback);
            processed++;
        }
        return processed;
    }

    private void processCallback(OnlyOfficeCallbackEntity callback) {
        Long taskId = callback.getTaskId();
        teamTaskService.markRunning(taskId);
        try {
            byte[] content = onlyOfficeContentDownloader.download(callback.getDownloadUrl());
            FileVersionEntity version = fileVersionService.saveTeamEditedVersion(
                    callback.getTeamId(),
                    callback.getActorUserId(),
                    callback.getFileId(),
                    content);
            onlyOfficeCallbackService.markSaved(callback.getId(), version.getId());
            teamTaskService.markSucceeded(taskId, "保存为版本 " + version.getVersionNumber());
        } catch (RuntimeException e) {
            String message = errorMessage(e);
            onlyOfficeCallbackService.markFailed(callback.getId(), message);
            teamTaskService.markFailed(taskId, message);
            teamAuditService.recordFailure(
                    callback.getTeamId(),
                    callback.getActorUserId(),
                    "OFFICE_SAVE_FAILED",
                    "FILE",
                    callback.getFileId(),
                    500003,
                    message);
            notificationService.notifyUser(
                    callback.getActorUserId(),
                    callback.getTeamId(),
                    "TEAM_OFFICE_SAVE_FAILED",
                    "在线文档保存失败",
                    "文档保存失败，请在团队任务中心重试。原因：" + message,
                    "/teams/" + callback.getTeamId() + "/settings",
                    false);
            log.warn("OnlyOffice async save failed, callbackId={}, teamId={}, fileId={}",
                    callback.getId(), callback.getTeamId(), callback.getFileId(), e);
        }
    }

    private String errorMessage(RuntimeException e) {
        return e.getMessage() == null || e.getMessage().isBlank()
                ? "OnlyOffice save failed"
                : e.getMessage();
    }
}
