package com.betterclouddrive.web.controller;

import com.betterclouddrive.common.constant.ApiCode;
import com.betterclouddrive.common.dto.ApiResponse;
import com.betterclouddrive.common.exception.BusinessException;
import com.betterclouddrive.dal.entity.FileEntity;
import com.betterclouddrive.dal.entity.TeamTaskEntity;
import com.betterclouddrive.service.FileService;
import com.betterclouddrive.service.OnlyOfficeCallbackService;
import com.betterclouddrive.service.TeamPermissionService;
import com.betterclouddrive.service.TeamTaskService;
import com.betterclouddrive.service.metrics.OnlyOfficeSaveMetrics;
import com.betterclouddrive.storage.StorageService;
import com.betterclouddrive.web.dto.request.OnlyOfficeCallbackRequest;
import com.betterclouddrive.web.dto.response.OfficeConfigResponse;
import com.betterclouddrive.web.security.CurrentUser;
import com.betterclouddrive.web.security.UserPrincipal;
import com.betterclouddrive.web.service.OnlyOfficeService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.InputStream;
import java.util.Map;
import java.util.Set;

@Slf4j
@RestController
@RequiredArgsConstructor
public class OfficeController {

    private static final Set<Integer> SAVE_STATUSES = Set.of(2, 6);

    private final FileService fileService;
    private final StorageService storageService;
    private final OnlyOfficeService onlyOfficeService;
    private final OnlyOfficeCallbackService onlyOfficeCallbackService;
    private final TeamPermissionService teamPermissionService;
    private final TeamTaskService teamTaskService;
    private final OnlyOfficeSaveMetrics onlyOfficeSaveMetrics;

    @GetMapping("/api/v1/office/files/{fileId}/config")
    public ApiResponse<OfficeConfigResponse> personalConfig(
            @CurrentUser UserPrincipal user,
            @PathVariable Long fileId) {
        FileEntity file = fileService.getFile(user.getUserId(), fileId);
        validateOfficeFile(file);
        return ApiResponse.success(onlyOfficeService.buildPersonalConfig(user.getUserId(), file));
    }

    @GetMapping("/api/v1/teams/{teamId}/office/files/{fileId}/config")
    public ApiResponse<OfficeConfigResponse> teamConfig(
            @CurrentUser UserPrincipal user,
            @PathVariable Long teamId,
            @PathVariable Long fileId) {
        teamPermissionService.requireRead(user.getUserId(), teamId, fileId);
        FileEntity file = fileService.getTeamFile(teamId, fileId);
        validateOfficeFile(file);
        boolean editable = teamPermissionService.effectivePermissions(user.getUserId(), teamId, fileId).canWrite();
        return ApiResponse.success(onlyOfficeService.buildTeamConfig(user.getUserId(), teamId, file, editable));
    }

    @GetMapping("/api/v1/office/files/{fileId}/content")
    public ResponseEntity<StreamingResponseBody> personalContent(
            @PathVariable Long fileId,
            @RequestParam String token) {
        Long userId = onlyOfficeService.validateContentToken(token, fileId, null);
        FileEntity file = fileService.getFile(userId, fileId);
        validateOfficeFile(file);
        return streamFile(file);
    }

    @GetMapping("/api/v1/teams/{teamId}/office/files/{fileId}/content")
    public ResponseEntity<StreamingResponseBody> teamContent(
            @PathVariable Long teamId,
            @PathVariable Long fileId,
            @RequestParam String token) {
        Long userId = onlyOfficeService.validateContentToken(token, fileId, teamId);
        teamPermissionService.requireRead(userId, teamId, fileId);
        FileEntity file = fileService.getTeamFile(teamId, fileId);
        validateOfficeFile(file);
        return streamFile(file);
    }

    @PostMapping("/api/v1/teams/{teamId}/office/files/{fileId}/callback")
    public Map<String, Integer> teamCallback(
            @PathVariable Long teamId,
            @PathVariable Long fileId,
            @RequestParam String token,
            @RequestBody OnlyOfficeCallbackRequest request) {
        onlyOfficeSaveMetrics.recordCallbackReceived(request.getStatus());
        Long userId = onlyOfficeService.validateCallbackToken(token, fileId, teamId, request.getKey());
        teamPermissionService.requireWrite(userId, teamId, fileId);
        if (request.getToken() != null && !request.getToken().isBlank()) {
            onlyOfficeService.validateDocumentServerToken(request.getToken());
        }
        if (request.getStatus() != null && SAVE_STATUSES.contains(request.getStatus())) {
            String callbackKey = onlyOfficeCallbackService.callbackKey(request.getKey());
            String contentUrlHash = onlyOfficeCallbackService.contentUrlHash(request.getUrl());
            String downloadUrl = onlyOfficeService.resolveDocumentServerDownloadUrl(request.getUrl());
            if (!onlyOfficeCallbackService.reserveSave(
                    teamId, fileId, userId, callbackKey, request.getStatus(), contentUrlHash, downloadUrl)) {
                log.info("Skipping duplicate OnlyOffice callback, teamId={}, fileId={}, key={}, status={}",
                        teamId, fileId, callbackKey, request.getStatus());
                onlyOfficeSaveMetrics.recordCallbackOutcome("duplicate");
                return Map.of("error", 0);
            }
            TeamTaskEntity task = teamTaskService.queueTask(
                    teamId, userId, "ONLYOFFICE_SAVE", "FILE", fileId, "Save online document");
            onlyOfficeCallbackService.attachTask(
                    teamId, fileId, callbackKey, request.getStatus(), contentUrlHash, task.getId());
            onlyOfficeSaveMetrics.recordCallbackOutcome("queued");
        } else {
            onlyOfficeSaveMetrics.recordCallbackOutcome("ignored");
        }
        return Map.of("error", 0);
    }

    private void validateOfficeFile(FileEntity file) {
        if (!"file".equals(file.getFileType())) {
            throw new BusinessException(ApiCode.FILE_NOT_FOUND, "Cannot open a folder in OnlyOffice");
        }
        if (!onlyOfficeService.isOfficeFile(file)) {
            throw new BusinessException(ApiCode.BAD_REQUEST, "Unsupported office file type");
        }
    }

    private ResponseEntity<StreamingResponseBody> streamFile(FileEntity file) {
        InputStream stream = storageService.downloadObject(file.getStoragePath());
        String mimeType = file.getMimeType() != null ? file.getMimeType() : "application/octet-stream";
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(mimeType))
                .contentLength(file.getFileSize())
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + file.getFileName() + "\"")
                .body(outputStream -> {
                    try (InputStream inputStream = stream) {
                        inputStream.transferTo(outputStream);
                    }
                });
    }
}
