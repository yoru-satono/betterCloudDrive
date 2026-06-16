package com.betterclouddrive.web.controller;

import com.betterclouddrive.common.constant.ApiCode;
import com.betterclouddrive.common.dto.ApiResponse;
import com.betterclouddrive.common.exception.BusinessException;
import com.betterclouddrive.dal.entity.FileEntity;
import com.betterclouddrive.dal.entity.FolderZipDownloadCacheEntity;
import com.betterclouddrive.service.FileService;
import com.betterclouddrive.service.FolderZipDownloadService;
import com.betterclouddrive.storage.StorageService;
import com.betterclouddrive.web.download.ByteRange;
import com.betterclouddrive.web.download.DownloadTicket;
import com.betterclouddrive.web.download.DownloadTicketService;
import com.betterclouddrive.web.download.DownloadTicketType;
import com.betterclouddrive.web.dto.response.DownloadTicketResponse;
import com.betterclouddrive.web.security.CurrentUser;
import com.betterclouddrive.web.security.UserPrincipal;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.InputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

@Slf4j
@RestController
@RequiredArgsConstructor
public class DownloadController {

    private final FileService fileService;
    private final StorageService storageService;
    private final FolderZipDownloadService folderZipDownloadService;
    private final DownloadTicketService downloadTicketService;

    @PostMapping("/api/v1/download/{fileId}/ticket")
    public ApiResponse<DownloadTicketResponse> createFileDownloadTicket(
            @CurrentUser UserPrincipal user,
            @PathVariable Long fileId) {
        FileEntity file = fileService.getFile(user.getUserId(), fileId);
        if (!"file".equals(file.getFileType())) {
            throw new BusinessException(ApiCode.FILE_NOT_FOUND, "Cannot download a folder");
        }
        String ticket = downloadTicketService.createTicket(user.getUserId(), fileId, DownloadTicketType.FILE);
        return ApiResponse.success(new DownloadTicketResponse("/api/v1/download/" + fileId + "?ticket=" + ticket));
    }

    @PostMapping("/api/v1/download/folders/{fileId}/zip/ticket")
    public ApiResponse<DownloadTicketResponse> createFolderZipDownloadTicket(
            @CurrentUser UserPrincipal user,
            @PathVariable Long fileId) {
        FileEntity folder = fileService.getFile(user.getUserId(), fileId);
        folderZipDownloadService.validateDownloadable(folder);
        String ticket = downloadTicketService.createTicket(user.getUserId(), fileId, DownloadTicketType.FOLDER_ZIP);
        return ApiResponse.success(new DownloadTicketResponse("/api/v1/download/folders/" + fileId + "/zip?ticket=" + ticket));
    }

    @GetMapping("/api/v1/download/{fileId}")
    public ResponseEntity<StreamingResponseBody> download(
            @CurrentUser UserPrincipal user,
            @PathVariable Long fileId,
            @RequestParam(required = false) String ticket,
            @RequestHeader(value = "Range", required = false) String rangeHeader) {

        Long userId = resolveDownloadUserId(user, ticket, fileId, DownloadTicketType.FILE);
        FileEntity file = fileService.getFile(userId, fileId);
        if (!"file".equals(file.getFileType())) {
            throw new BusinessException(ApiCode.FILE_NOT_FOUND, "Cannot download a folder");
        }

        long fileSize = file.getFileSize();
        String mimeType = file.getMimeType() != null ? file.getMimeType() : "application/octet-stream";
        String disposition = attachmentDisposition(file.getFileName());

        return rangedObjectResponse(file.getStoragePath(), fileSize, mimeType, disposition, rangeHeader);
    }

    @GetMapping("/api/v1/download/folders/{fileId}/zip")
    public ResponseEntity<StreamingResponseBody> downloadFolderZip(
            @CurrentUser UserPrincipal user,
            @PathVariable Long fileId,
            @RequestParam(required = false) String ticket,
            @RequestHeader(value = "Range", required = false) String rangeHeader) {

        Long userId = resolveDownloadUserId(user, ticket, fileId, DownloadTicketType.FOLDER_ZIP);
        FileEntity folder = fileService.getFile(userId, fileId);
        FolderZipDownloadCacheEntity cache = folderZipDownloadService.getOrCreateCachedZip(folder);

        ResponseEntity<StreamingResponseBody> response = rangedObjectResponse(
                cache.getObjectKey(),
                cache.getFileSize(),
                "application/zip",
                attachmentDisposition(cache.getFileName()),
                rangeHeader);
        folderZipDownloadService.markDownloaded(cache);
        return response;
    }

    @GetMapping("/api/v1/preview/{fileId}")
    public ResponseEntity<StreamingResponseBody> preview(
            @CurrentUser UserPrincipal user,
            @PathVariable Long fileId) {

        FileEntity file = fileService.getFile(user.getUserId(), fileId);
        if (!"file".equals(file.getFileType())) {
            throw new BusinessException(ApiCode.FILE_NOT_FOUND, "Cannot preview a folder");
        }

        InputStream stream = storageService.downloadObject(file.getStoragePath());
        String mimeType = file.getMimeType() != null ? file.getMimeType() : "application/octet-stream";

        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(mimeType))
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + file.getFileName() + "\"")
                .body(outputStream -> {
                    try (InputStream is = stream) {
                        is.transferTo(outputStream);
                    }
                });
    }

    private Long resolveDownloadUserId(UserPrincipal user, String ticket, Long resourceId, DownloadTicketType type) {
        if (user != null) {
            return user.getUserId();
        }
        DownloadTicket resolved = downloadTicketService.consumeTicket(ticket, resourceId, type);
        return resolved.userId();
    }

    private ResponseEntity<StreamingResponseBody> rangedObjectResponse(
            String objectKey,
            long fileSize,
            String mimeType,
            String disposition,
            String rangeHeader) {
        ByteRange range;
        try {
            range = ByteRange.parse(rangeHeader, fileSize);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.REQUESTED_RANGE_NOT_SATISFIABLE)
                    .header(HttpHeaders.ACCEPT_RANGES, "bytes")
                    .header(HttpHeaders.CONTENT_RANGE, ByteRange.unsatisfiedRangeHeader(fileSize))
                    .build();
        }

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
