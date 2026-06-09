package com.betterclouddrive.web.controller;

import com.betterclouddrive.common.dto.ApiResponse;
import com.betterclouddrive.common.dto.PageResult;
import com.betterclouddrive.dal.entity.FileEntity;
import com.betterclouddrive.dal.entity.OperationLogEntity;
import com.betterclouddrive.dal.entity.UserEntity;
import com.betterclouddrive.service.AdminService;
import com.betterclouddrive.service.FileService;
import com.betterclouddrive.service.OperationLogService;
import com.betterclouddrive.web.dto.request.AdminUpdateUserQuotaRequest;
import com.betterclouddrive.web.dto.request.AdminUpdateUserStatusRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/admin")
@RequiredArgsConstructor
public class AdminController {

    private final AdminService adminService;
    private final FileService fileService;
    private final OperationLogService operationLogService;

    // ==================== User Management ====================

    @GetMapping("/users")
    public ApiResponse<PageResult<UserEntity>> listUsers(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Integer status,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.success(adminService.listUsers(keyword, status, page, size));
    }

    @PatchMapping("/users/{userId}/status")
    public ApiResponse<Void> updateUserStatus(
            @PathVariable Long userId,
            @Valid @RequestBody AdminUpdateUserStatusRequest request) {
        adminService.updateUserStatus(userId, request.getStatus());
        return ApiResponse.success();
    }

    @PatchMapping("/users/{userId}/quota")
    public ApiResponse<Void> updateUserQuota(
            @PathVariable Long userId,
            @Valid @RequestBody AdminUpdateUserQuotaRequest request) {
        adminService.updateUserQuota(userId, request.getStorageQuota());
        return ApiResponse.success();
    }

    // ==================== File Management ====================

    @GetMapping("/users/{userId}/files")
    public ApiResponse<PageResult<FileEntity>> listUserFiles(
            @PathVariable Long userId,
            @RequestParam(required = false) Long parentId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(defaultValue = "fileName") String sortBy,
            @RequestParam(defaultValue = "asc") String order) {
        return ApiResponse.success(fileService.listFiles(userId, parentId, page, size, sortBy, order));
    }

    @GetMapping("/files/{fileId}")
    public ApiResponse<FileEntity> getFile(@PathVariable Long fileId) {
        return ApiResponse.success(fileService.adminGetFile(fileId));
    }

    @DeleteMapping("/files/{fileId}")
    public ApiResponse<Void> deleteFile(@PathVariable Long fileId) {
        fileService.adminDeleteFile(fileId);
        return ApiResponse.success();
    }

    // ==================== Operation Logs ====================

    @GetMapping("/logs")
    public ApiResponse<PageResult<OperationLogEntity>> listLogs(
            @RequestParam(required = false) Long userId,
            @RequestParam(required = false) String actionType,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime startDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime endDate,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.success(
                operationLogService.listLogs(userId, actionType, startDate, endDate, page, size));
    }

    // ==================== System Statistics ====================

    @GetMapping("/stats")
    public ApiResponse<Map<String, Object>> getStats() {
        return ApiResponse.success(adminService.getSystemStats());
    }
}
