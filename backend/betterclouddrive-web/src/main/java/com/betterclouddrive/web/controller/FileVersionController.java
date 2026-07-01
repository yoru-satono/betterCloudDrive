package com.betterclouddrive.web.controller;

import com.betterclouddrive.common.dto.ApiResponse;
import com.betterclouddrive.dal.entity.FileVersionEntity;
import com.betterclouddrive.dal.entity.FileEntity;
import com.betterclouddrive.service.FileVersionService;
import com.betterclouddrive.service.FileService;
import com.betterclouddrive.storage.StorageService;
import com.betterclouddrive.web.security.CurrentUser;
import com.betterclouddrive.web.security.UserPrincipal;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.InputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;

@RestController
@RequestMapping("/api/v1/files/{fileId}/versions")
@RequiredArgsConstructor
public class FileVersionController {

    private final FileVersionService fileVersionService;
    private final FileService fileService;
    private final StorageService storageService;

    @GetMapping
    public ApiResponse<List<FileVersionEntity>> listVersions(
            @CurrentUser UserPrincipal user, @PathVariable Long fileId) {
        return ApiResponse.success(fileVersionService.listVersions(user.getUserId(), fileId));
    }

    @GetMapping("/{versionNumber}/download")
    public ResponseEntity<StreamingResponseBody> downloadVersion(
            @CurrentUser UserPrincipal user,
            @PathVariable Long fileId,
            @PathVariable int versionNumber) {
        FileEntity file = fileService.getFile(user.getUserId(), fileId);
        FileVersionEntity version = fileVersionService.getVersion(user.getUserId(), fileId, versionNumber);
        InputStream stream = storageService.downloadObject(version.getStoragePath());
        String mimeType = file.getMimeType() != null ? file.getMimeType() : "application/octet-stream";
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(mimeType))
                .contentLength(version.getFileSize())
                .header(HttpHeaders.CONTENT_DISPOSITION, attachmentDisposition("v" + versionNumber + "_" + file.getFileName()))
                .body(outputStream -> {
                    try (InputStream is = stream) {
                        is.transferTo(outputStream);
                    }
                });
    }

    @PostMapping("/{versionNumber}/restore")
    public ApiResponse<FileEntity> restoreVersion(
            @CurrentUser UserPrincipal user,
            @PathVariable Long fileId,
            @PathVariable int versionNumber) {
        return ApiResponse.success(fileVersionService.restoreVersion(user.getUserId(), fileId, versionNumber));
    }

    @DeleteMapping("/{versionNumber}")
    public ApiResponse<Void> deleteVersion(
            @CurrentUser UserPrincipal user,
            @PathVariable Long fileId,
            @PathVariable int versionNumber) {
        fileVersionService.deleteVersion(user.getUserId(), fileId, versionNumber);
        return ApiResponse.success();
    }

    private String attachmentDisposition(String fileName) {
        return "attachment; filename=\"" + URLEncoder.encode(fileName, StandardCharsets.UTF_8) + "\"";
    }
}
