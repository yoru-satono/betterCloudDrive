package com.betterclouddrive.service.impl;

import com.betterclouddrive.dal.entity.FileVersionEntity;
import com.betterclouddrive.dal.entity.OnlyOfficeCallbackEntity;
import com.betterclouddrive.dal.entity.TeamTaskEntity;
import com.betterclouddrive.service.FileVersionService;
import com.betterclouddrive.service.NotificationService;
import com.betterclouddrive.service.OnlyOfficeCallbackService;
import com.betterclouddrive.service.OnlyOfficeContentDownloader;
import com.betterclouddrive.service.OnlyOfficeSaveService;
import com.betterclouddrive.service.TeamAuditService;
import com.betterclouddrive.service.TeamTaskService;
import com.betterclouddrive.service.metrics.OnlyOfficeSaveMetrics;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

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
    private final OnlyOfficeSaveMetrics onlyOfficeSaveMetrics;

    @Override
    public int processPendingSaves(int limit) {
        int processed = 0;
        for (TeamTaskEntity task : teamTaskService.claimTasks("ONLYOFFICE_SAVE", limit)) {
            OnlyOfficeCallbackEntity callback = onlyOfficeCallbackService.findByTaskId(task.getId()).orElse(null);
            if (callback == null) {
                teamTaskService.markFailed(task.getId(), "OnlyOffice callback is missing");
            } else {
                processCallback(callback);
            }
            processed++;
        }
        return processed;
    }

    private void processCallback(OnlyOfficeCallbackEntity callback) {
        Long taskId = callback.getTaskId();
        long startedAtNanos = onlyOfficeSaveMetrics.startSaveAttempt();
        try {
            byte[] content = onlyOfficeContentDownloader.download(callback.getDownloadUrl());
            FileVersionEntity version = fileVersionService.saveTeamEditedVersion(
                    callback.getTeamId(),
                    callback.getActorUserId(),
                    callback.getFileId(),
                    content);
            onlyOfficeCallbackService.markSaved(callback.getId(), version.getId());
            teamTaskService.markSucceeded(taskId, "Saved as version " + version.getVersionNumber());
            onlyOfficeSaveMetrics.recordSaveSucceeded(startedAtNanos);
            log.info("OnlyOffice async save succeeded, callbackId={}, teamId={}, fileId={}, versionId={}",
                    callback.getId(), callback.getTeamId(), callback.getFileId(), version.getId());
        } catch (RuntimeException e) {
            onlyOfficeSaveMetrics.recordSaveFailed(startedAtNanos);
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
                    "Online document save failed",
                    "Document save failed; retry from the team task center. Cause: " + message,
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
