package com.betterclouddrive.web.controller;

import com.betterclouddrive.common.dto.ApiResponse;
import com.betterclouddrive.common.dto.UploadInitResult;
import com.betterclouddrive.service.UploadService;
import com.betterclouddrive.web.dto.request.InitUploadRequest;
import com.betterclouddrive.web.security.CurrentUser;
import com.betterclouddrive.web.security.UserPrincipal;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/upload")
@RequiredArgsConstructor
public class UploadController {

    private final UploadService uploadService;

    @PostMapping("/init")
    public ApiResponse<UploadInitResult> initUpload(
            @CurrentUser UserPrincipal user,
            @Valid @RequestBody InitUploadRequest request) {
        UploadInitResult result = uploadService.initUpload(
                user.getUserId(), request.getParentId(), request.getFileName(),
                request.getFileSize(), request.getMd5Hash());
        return ApiResponse.success(result);
    }

    @PostMapping("/{sessionId}/chunk")
    public ApiResponse<Map<String, Object>> uploadChunk(
            @CurrentUser UserPrincipal user,
            @PathVariable String sessionId,
            @RequestParam int chunkNumber,
            @RequestParam String chunkMd5,
            @RequestParam("file") MultipartFile file) {
        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (java.io.IOException e) {
            throw new RuntimeException("Failed to read chunk data", e);
        }
        uploadService.uploadChunk(sessionId, user.getUserId(), chunkNumber, bytes, chunkMd5);
        return ApiResponse.success(Map.of("chunkNumber", chunkNumber));
    }

    @GetMapping("/{sessionId}/status")
    public ApiResponse<Map<String, Object>> getUploadStatus(
            @CurrentUser UserPrincipal user,
            @PathVariable String sessionId) {
        return ApiResponse.success(uploadService.getUploadStatus(sessionId, user.getUserId()));
    }

    @PostMapping("/{sessionId}/complete")
    public ApiResponse<Map<String, Object>> completeUpload(
            @CurrentUser UserPrincipal user,
            @PathVariable String sessionId) {
        Long fileId = uploadService.completeUpload(sessionId, user.getUserId());
        return ApiResponse.success(Map.of("fileId", fileId));
    }

    @PostMapping("/{sessionId}/cancel")
    public ApiResponse<Void> cancelUpload(
            @CurrentUser UserPrincipal user,
            @PathVariable String sessionId) {
        uploadService.cancelUpload(sessionId, user.getUserId());
        return ApiResponse.success();
    }

}
