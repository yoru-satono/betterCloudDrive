package com.betterclouddrive.web.controller;

import com.betterclouddrive.common.constant.ApiCode;
import com.betterclouddrive.common.dto.ApiResponse;
import com.betterclouddrive.common.dto.PageResult;
import com.betterclouddrive.common.exception.BusinessException;
import com.betterclouddrive.dal.entity.FileEntity;
import com.betterclouddrive.dal.entity.ShareLinkEntity;
import com.betterclouddrive.service.AuthService;
import com.betterclouddrive.service.FileService;
import com.betterclouddrive.service.ShareService;
import com.betterclouddrive.web.dto.request.CreateShareRequest;
import com.betterclouddrive.web.dto.request.UpdateShareRequest;
import com.betterclouddrive.web.security.CurrentUser;
import com.betterclouddrive.web.security.UserPrincipal;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequiredArgsConstructor
public class ShareController {

    private final ShareService shareService;
    private final FileService fileService;
    private final AuthService authService;

    @PostMapping("/api/v1/shares")
    public ApiResponse<ShareLinkEntity> createShare(@CurrentUser UserPrincipal user,
                                                     @Valid @RequestBody CreateShareRequest request) {
        ShareLinkEntity share = shareService.createShare(
                user.getUserId(), request.getFileId(), request.getPassword(),
                request.getExpireAt(), request.getMaxDownloads());

        // Send share notification email if requested
        if (request.getNotifyEmail() != null && !request.getNotifyEmail().isBlank()) {
            FileEntity file = fileService.getFile(user.getUserId(), request.getFileId());
            authService.sendShareNotification(request.getNotifyEmail(), share.getShareCode(),
                    file.getFileName(), user.getUsername());
        }

        return ApiResponse.success(share);
    }

    @GetMapping("/api/v1/shares")
    public ApiResponse<PageResult<ShareLinkEntity>> listShares(
            @CurrentUser UserPrincipal user,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.success(shareService.listShares(user.getUserId(), page, size));
    }

    @GetMapping("/api/v1/shares/{shareId}")
    public ApiResponse<ShareLinkEntity> getShare(@CurrentUser UserPrincipal user, @PathVariable Long shareId) {
        return ApiResponse.success(shareService.getShare(user.getUserId(), shareId));
    }

    @PutMapping("/api/v1/shares/{shareId}")
    public ApiResponse<ShareLinkEntity> updateShare(@CurrentUser UserPrincipal user,
                                                     @PathVariable Long shareId,
                                                     @RequestBody UpdateShareRequest request) {
        return ApiResponse.success(shareService.updateShare(
                user.getUserId(), shareId, request.getPassword(), request.getExpireAt(), request.getMaxDownloads()));
    }

    @DeleteMapping("/api/v1/shares/{shareId}")
    public ApiResponse<Void> cancelShare(@CurrentUser UserPrincipal user, @PathVariable Long shareId) {
        shareService.cancelShare(user.getUserId(), shareId);
        return ApiResponse.success();
    }

    /** Public endpoint: access share by code */
    @PostMapping("/api/v1/shares/access/{shareCode}")
    public ApiResponse<Map<String, Object>> accessShare(@PathVariable String shareCode,
                                                         @RequestBody(required = false) Map<String, String> body) {
        String password = body != null ? body.get("password") : null;
        FileEntity file = shareService.accessShare(shareCode, password);
        return ApiResponse.success(Map.of(
                "fileId", file.getId(),
                "fileName", file.getFileName(),
                "fileType", file.getFileType(),
                "fileSize", file.getFileSize()
        ));
    }

    /** Public endpoint: browse shared content */
    @GetMapping("/api/v1/shares/access/{shareCode}/files")
    public ApiResponse<PageResult<FileEntity>> listSharedFiles(
            @PathVariable String shareCode,
            @RequestParam(required = false) Long parentId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.success(shareService.listSharedFiles(shareCode, parentId, page, size));
    }
}
