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
import com.betterclouddrive.web.dto.response.SystemLogEntryResponse;
import com.betterclouddrive.web.service.GrafanaAuthService;
import com.betterclouddrive.web.service.SystemLogQueryService;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/admin")
@RequiredArgsConstructor
public class AdminController {

    private final AdminService adminService;
    private final FileService fileService;
    private final OperationLogService operationLogService;
    private final SystemLogQueryService systemLogQueryService;
    private final GrafanaAuthService grafanaAuthService;

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
            @RequestParam(required = false) String requestId,
            @RequestParam(required = false) String traceId,
            @RequestParam(required = false) Integer statusCode,
            @RequestParam(required = false) Integer result,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime startDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime endDate,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.success(
                operationLogService.listLogs(userId, actionType, requestId, traceId, statusCode, result,
                        startDate, endDate, page, size));
    }

    @GetMapping("/system-logs")
    public ApiResponse<List<SystemLogEntryResponse>> listSystemLogs(
            @RequestParam(required = false) String traceId,
            @RequestParam(required = false) String requestId,
            @RequestParam(required = false) String level,
            @RequestParam(required = false) String logType,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant startTime,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant endTime,
            @RequestParam(required = false) Integer limit) {
        return ApiResponse.success(systemLogQueryService.listSystemLogs(
                traceId, requestId, level, logType, keyword, startTime, endTime, limit));
    }

    @PostMapping("/grafana/session")
    public ApiResponse<Void> createGrafanaSession(Authentication authentication, HttpServletResponse response) {
        grafanaAuthService.issueSession(authentication, response);
        return ApiResponse.success();
    }

    // ==================== System Statistics ====================

    @GetMapping("/stats")
    public ApiResponse<Map<String, Object>> getStats() {
        return ApiResponse.success(adminService.getSystemStats());
    }
}
