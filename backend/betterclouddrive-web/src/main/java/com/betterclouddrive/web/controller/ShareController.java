package com.betterclouddrive.web.controller;

import com.betterclouddrive.common.constant.ApiCode;
import com.betterclouddrive.common.dto.ApiResponse;
import com.betterclouddrive.common.dto.PageResult;
import com.betterclouddrive.common.exception.BusinessException;
import com.betterclouddrive.dal.entity.FileEntity;
import com.betterclouddrive.dal.entity.ShareLinkEntity;
import com.betterclouddrive.service.AuthService;
import com.betterclouddrive.service.FileService;
import com.betterclouddrive.service.FolderZipDownloadService;
import com.betterclouddrive.service.ShareService;
import com.betterclouddrive.storage.StorageService;
import com.betterclouddrive.web.download.ByteRange;
import com.betterclouddrive.web.dto.request.CreateShareRequest;
import com.betterclouddrive.web.dto.request.SaveSharedItemRequest;
import com.betterclouddrive.web.dto.request.UpdateShareRequest;
import com.betterclouddrive.web.dto.response.ShareLinkResponse;
import com.betterclouddrive.web.dto.response.ShareLinkResponseMapper;
import com.betterclouddrive.web.dto.response.SharePasswordResponse;
import com.betterclouddrive.web.security.CurrentUser;
import com.betterclouddrive.web.security.UserPrincipal;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.InputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

@RestController
@RequiredArgsConstructor
public class ShareController {

    private final ShareService shareService;
    private final FileService fileService;
    private final AuthService authService;
    private final StorageService storageService;
    private final FolderZipDownloadService folderZipDownloadService;
    private final ShareLinkResponseMapper shareResponseMapper;

    @PostMapping("/api/v1/shares")
    public ApiResponse<ShareLinkResponse> createShare(@CurrentUser UserPrincipal user,
                                                       @Valid @RequestBody CreateShareRequest request) {
        ShareLinkEntity share = shareService.createShare(
                user.getUserId(), request.getFileId(), request.getPassword(),
                request.getExpireAt(), request.getMaxVisits());

        // Send share notification email if requested
        if (request.getNotifyEmail() != null && !request.getNotifyEmail().isBlank()) {
            FileEntity file = fileService.getFile(user.getUserId(), request.getFileId());
            authService.sendShareNotification(user.getUserId(), request.getNotifyEmail(), share.getShareCode(),
                    file.getFileName(), user.getUsername());
        }

        return ApiResponse.success(shareResponseMapper.map(share));
    }

    @GetMapping("/api/v1/shares")
    public ApiResponse<PageResult<ShareLinkResponse>> listShares(
            @CurrentUser UserPrincipal user,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) Boolean passwordProtected,
            @RequestParam(required = false) String expirationStatus,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        PageResult<ShareLinkEntity> shares = shareService.listShares(user.getUserId(), q, passwordProtected, expirationStatus, page, size);
        List<ShareLinkResponse> records = shareResponseMapper.mapAll(shares.getRecords());
        return ApiResponse.success(PageResult.of(records, shares.getTotal(), shares.getPage(), shares.getSize()));
    }

    @GetMapping("/api/v1/shares/{shareId}")
    public ApiResponse<ShareLinkResponse> getShare(@CurrentUser UserPrincipal user, @PathVariable Long shareId) {
        return ApiResponse.success(shareResponseMapper.map(shareService.getShare(user.getUserId(), shareId)));
    }

    @GetMapping("/api/v1/shares/{shareId}/password")
    public ApiResponse<SharePasswordResponse> getSharePassword(@CurrentUser UserPrincipal user, @PathVariable Long shareId) {
        String password = shareService.getSharePassword(user.getUserId(), shareId);
        return ApiResponse.success(new SharePasswordResponse(password));
    }

    @PutMapping("/api/v1/shares/{shareId}")
    public ApiResponse<ShareLinkResponse> updateShare(@CurrentUser UserPrincipal user,
                                                       @PathVariable Long shareId,
                                                       @RequestBody UpdateShareRequest request) {
        ShareLinkEntity share = shareService.updateShare(
                user.getUserId(), shareId, request.toCommand());
        return ApiResponse.success(shareResponseMapper.map(share));
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
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String password) {
        return ApiResponse.success(shareService.listSharedFiles(shareCode, parentId, page, size, password));
    }

    /** Authenticated endpoint: save a shared file or folder into the current user's drive */
    @PostMapping("/api/v1/shares/access/{shareCode}/save")
    public ApiResponse<FileEntity> saveSharedItem(
            @CurrentUser UserPrincipal user,
            @PathVariable String shareCode,
            @RequestBody(required = false) SaveSharedItemRequest request) {
        if (user == null) {
            throw new BusinessException(ApiCode.UNAUTHORIZED);
        }
        SaveSharedItemRequest body = request != null ? request : new SaveSharedItemRequest();
        return ApiResponse.success(shareService.saveSharedItem(
                shareCode,
                body.getFileId(),
                body.getTargetParentId(),
                user.getUserId(),
                body.getPassword()));
    }

    /** Public endpoint: download a file from a shared link */
    @PostMapping("/api/v1/shares/access/{shareCode}/download/{fileId}")
    public ResponseEntity<StreamingResponseBody> downloadSharedFile(
            @PathVariable String shareCode,
            @PathVariable Long fileId,
            @RequestBody(required = false) Map<String, String> body,
            @RequestHeader(value = "Range", required = false) String rangeHeader) {
        String password = body != null ? body.get("password") : null;
        FileEntity file = shareService.resolveSharedFileDownload(shareCode, fileId, password);
        String mimeType = file.getMimeType() != null ? file.getMimeType() : "application/octet-stream";
        String disposition = attachmentDisposition(file.getFileName());

        return sharedObjectResponse(file.getStoragePath(), file.getFileSize(), mimeType, disposition,
                rangeHeader, shareCode, password);
    }

    /** Public endpoint: download a shared folder as ZIP from the webpage */
    @PostMapping("/api/v1/shares/access/{shareCode}/download/{fileId}/zip")
    public ResponseEntity<StreamingResponseBody> downloadSharedFolderZip(
            @PathVariable String shareCode,
            @PathVariable Long fileId,
            @RequestBody(required = false) Map<String, String> body) {
        String password = body != null ? body.get("password") : null;
        FileEntity folder = shareService.resolveSharedFolderDownload(shareCode, fileId, password);
        folderZipDownloadService.validateDownloadable(folder);
        shareService.recordSharedDownload(shareCode, password);
        String zipFileName = folder.getFileName() + ".zip";

        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("application/zip"))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + URLEncoder.encode(zipFileName, StandardCharsets.UTF_8) + "\"")
                .body(outputStream -> folderZipDownloadService.writeZip(folder, outputStream));
    }

    private ResponseEntity<StreamingResponseBody> sharedObjectResponse(
            String objectKey,
            long fileSize,
            String mimeType,
            String disposition,
            String rangeHeader,
            String shareCode,
            String password) {
        ByteRange range;
        try {
            range = ByteRange.parse(rangeHeader, fileSize);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.REQUESTED_RANGE_NOT_SATISFIABLE)
                    .header(HttpHeaders.ACCEPT_RANGES, "bytes")
                    .header(HttpHeaders.CONTENT_RANGE, ByteRange.unsatisfiedRangeHeader(fileSize))
                    .build();
        }

        shareService.recordSharedDownload(shareCode, password);

        if (range == null) {
            InputStream stream = storageService.downloadObject(objectKey);
            return ResponseEntity.ok()
                    .contentType(MediaType.parseMediaType(mimeType))
                    .contentLength(fileSize)
                    .header(HttpHeaders.CONTENT_DISPOSITION, disposition)
                    .header(HttpHeaders.ACCEPT_RANGES, "bytes")
                    .body(outputStream -> {
                        try (InputStream is = stream) {
                            is.transferTo(outputStream);
                        }
                    });
        }

        ByteRange selectedRange = range;
        InputStream stream = storageService.downloadObjectRange(objectKey, selectedRange.start(), selectedRange.length());
        return ResponseEntity.status(HttpStatus.PARTIAL_CONTENT)
                .contentType(MediaType.parseMediaType(mimeType))
                .headers(selectedRange.headers(disposition))
                .body(outputStream -> {
                    try (InputStream is = stream) {
                        is.transferTo(outputStream);
                    }
                });
    }

    private String attachmentDisposition(String fileName) {
        return "attachment; filename=\"" + URLEncoder.encode(fileName, StandardCharsets.UTF_8) + "\"";
    }
}
