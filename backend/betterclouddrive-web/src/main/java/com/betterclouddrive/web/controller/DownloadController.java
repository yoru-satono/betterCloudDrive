package com.betterclouddrive.web.controller;

import com.betterclouddrive.common.constant.ApiCode;
import com.betterclouddrive.common.exception.BusinessException;
import com.betterclouddrive.dal.entity.FileEntity;
import com.betterclouddrive.service.FileService;
import com.betterclouddrive.service.FolderZipDownloadService;
import com.betterclouddrive.storage.StorageService;
import com.betterclouddrive.web.security.CurrentUser;
import com.betterclouddrive.web.security.UserPrincipal;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
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

    @GetMapping("/api/v1/download/{fileId}")
    public ResponseEntity<StreamingResponseBody> download(
            @CurrentUser UserPrincipal user,
            @PathVariable Long fileId,
            @RequestHeader(value = "Range", required = false) String rangeHeader) {

        FileEntity file = fileService.getFile(user.getUserId(), fileId);
        if (!"file".equals(file.getFileType())) {
            throw new BusinessException(ApiCode.FILE_NOT_FOUND, "Cannot download a folder");
        }

        InputStream stream = storageService.downloadObject(file.getStoragePath());
        long fileSize = file.getFileSize();
        String mimeType = file.getMimeType() != null ? file.getMimeType() : "application/octet-stream";

        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(mimeType))
                .contentLength(fileSize)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + URLEncoder.encode(file.getFileName(), StandardCharsets.UTF_8) + "\"")
                .header(HttpHeaders.ACCEPT_RANGES, "bytes")
                .body(outputStream -> {
                    try (InputStream is = stream) {
                        is.transferTo(outputStream);
                    }
                });
    }

    @GetMapping("/api/v1/download/folders/{fileId}/zip")
    public ResponseEntity<StreamingResponseBody> downloadFolderZip(
            @CurrentUser UserPrincipal user,
            @PathVariable Long fileId) {

        FileEntity folder = fileService.getFile(user.getUserId(), fileId);
        folderZipDownloadService.validateDownloadable(folder);
        String zipFileName = folder.getFileName() + ".zip";

        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("application/zip"))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + URLEncoder.encode(zipFileName, StandardCharsets.UTF_8) + "\"")
                .body(outputStream -> folderZipDownloadService.writeZip(folder, outputStream));
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
}
