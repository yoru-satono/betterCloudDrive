package com.betterclouddrive.web.controller;

import com.betterclouddrive.common.dto.ApiResponse;
import com.betterclouddrive.common.dto.PageResult;
import com.betterclouddrive.dal.entity.NotificationEntity;
import com.betterclouddrive.service.NotificationService;
import com.betterclouddrive.web.dto.request.UpdateNotificationPreferencesRequest;
import com.betterclouddrive.web.security.CurrentUser;
import com.betterclouddrive.web.security.UserPrincipal;
import com.betterclouddrive.web.service.NotificationSseService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/notifications")
@RequiredArgsConstructor
public class NotificationController {

    private final NotificationService notificationService;
    private final NotificationSseService notificationSseService;

    @GetMapping
    public ApiResponse<PageResult<NotificationEntity>> listNotifications(
            @CurrentUser UserPrincipal user,
            @RequestParam(required = false) Boolean unreadOnly,
            @RequestParam(required = false) String type,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") @Max(100) int size) {
        return ApiResponse.success(notificationService.listNotifications(user.getUserId(), unreadOnly, type, page, size));
    }

    @GetMapping("/unread-count")
    public ApiResponse<Long> unreadCount(@CurrentUser UserPrincipal user) {
        return ApiResponse.success(notificationService.countUnread(user.getUserId()));
    }

    @GetMapping("/preferences")
    public ApiResponse<Map<String, Boolean>> getPreferences(@CurrentUser UserPrincipal user) {
        return ApiResponse.success(notificationService.getPreferences(user.getUserId()));
    }

    @PutMapping("/preferences")
    public ApiResponse<Map<String, Boolean>> updatePreferences(
            @CurrentUser UserPrincipal user,
            @Valid @RequestBody UpdateNotificationPreferencesRequest request) {
        return ApiResponse.success(notificationService.updatePreferences(user.getUserId(), request.getPreferences()));
    }

    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@CurrentUser UserPrincipal user) {
        long unreadCount = notificationService.countUnread(user.getUserId());
        return notificationSseService.subscribe(user.getUserId(), unreadCount);
    }

    @PostMapping("/{notificationId}/read")
    public ApiResponse<Void> markRead(
            @CurrentUser UserPrincipal user,
            @PathVariable Long notificationId) {
        notificationService.markRead(user.getUserId(), notificationId);
        return ApiResponse.success();
    }

    @PostMapping("/read-all")
    public ApiResponse<Void> markAllRead(@CurrentUser UserPrincipal user) {
        notificationService.markAllRead(user.getUserId());
        return ApiResponse.success();
    }
}
