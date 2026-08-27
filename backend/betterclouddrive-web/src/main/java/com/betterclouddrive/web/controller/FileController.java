package com.betterclouddrive.web.controller;

import com.betterclouddrive.common.dto.ApiResponse;
import com.betterclouddrive.common.dto.PageResult;
import com.betterclouddrive.dal.entity.FileEntity;
import com.betterclouddrive.dal.entity.TagEntity;
import com.betterclouddrive.service.FileService;
import com.betterclouddrive.service.TagService;
import com.betterclouddrive.web.dto.request.BatchDeleteRequest;
import com.betterclouddrive.web.dto.request.CreateFolderRequest;
import com.betterclouddrive.web.dto.request.MoveFileRequest;
import com.betterclouddrive.web.dto.request.RenameRequest;
import com.betterclouddrive.web.dto.request.ReplaceFileTagsRequest;
import com.betterclouddrive.web.security.CurrentUser;
import com.betterclouddrive.web.security.UserPrincipal;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Validated

@RestController
@RequestMapping("/api/v1/files")
@RequiredArgsConstructor
public class FileController {

    private final FileService fileService;
    private final TagService tagService;

    @GetMapping
    public ApiResponse<PageResult<FileEntity>> listFiles(
            @CurrentUser UserPrincipal user,
            @RequestParam(required = false) Long parentId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") @Max(100) int size,
            @RequestParam(defaultValue = "fileName") String sortBy,
            @RequestParam(defaultValue = "asc") String order) {
        return ApiResponse.success(fileService.listFiles(user.getUserId(), parentId, page, size, sortBy, order));
    }

    @GetMapping("/{fileId}")
    public ApiResponse<FileEntity> getFile(@CurrentUser UserPrincipal user, @PathVariable Long fileId) {
        return ApiResponse.success(fileService.getFile(user.getUserId(), fileId));
    }

    @GetMapping("/{fileId}/tags")
    public ApiResponse<List<TagEntity>> listFileTags(@CurrentUser UserPrincipal user, @PathVariable Long fileId) {
        return ApiResponse.success(tagService.listFileTags(user.getUserId(), fileId));
    }

    @PutMapping("/{fileId}/tags")
    public ApiResponse<List<TagEntity>> replaceFileTags(
            @CurrentUser UserPrincipal user,
            @PathVariable Long fileId,
            @Valid @RequestBody ReplaceFileTagsRequest request) {
        return ApiResponse.success(tagService.replaceFileTags(user.getUserId(), fileId, request.getTagIds()));
    }

    @PostMapping("/folder")
    public ApiResponse<FileEntity> createFolder(@CurrentUser UserPrincipal user,
                                                 @Valid @RequestBody CreateFolderRequest request) {
        return ApiResponse.success(fileService.createFolder(user.getUserId(), request.getParentId(), request.getFolderName()));
    }

    @PutMapping("/{fileId}")
    public ApiResponse<FileEntity> renameFile(@CurrentUser UserPrincipal user,
                                               @PathVariable Long fileId,
                                               @Valid @RequestBody RenameRequest request) {
        return ApiResponse.success(fileService.renameFile(user.getUserId(), fileId, request.getNewName()));
    }

    @PostMapping("/{fileId}/move")
    public ApiResponse<Void> moveFile(@CurrentUser UserPrincipal user,
                                       @PathVariable Long fileId,
                                       @Valid @RequestBody MoveFileRequest request) {
        fileService.moveFile(user.getUserId(), fileId, request.getTargetParentId());
        return ApiResponse.success();
    }

    @PostMapping("/{fileId}/copy")
    public ApiResponse<Void> copyFile(@CurrentUser UserPrincipal user,
                                       @PathVariable Long fileId,
                                       @Valid @RequestBody MoveFileRequest request) {
        fileService.copyFile(user.getUserId(), fileId, request.getTargetParentId());
        return ApiResponse.success();
    }

    @DeleteMapping
    public ApiResponse<Void> deleteFiles(@CurrentUser UserPrincipal user,
                                          @Valid @RequestBody BatchDeleteRequest request) {
        fileService.deleteFiles(user.getUserId(), request.getFileIds());
        return ApiResponse.success();
    }

    @GetMapping("/search")
    public ApiResponse<PageResult<FileEntity>> searchFiles(
            @CurrentUser UserPrincipal user,
            @RequestParam String q,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") @Max(100) int size) {
        PageResult<FileEntity> results = fileService.searchFiles(user.getUserId(), q, page, size);
        return ApiResponse.success(results);
    }
}
