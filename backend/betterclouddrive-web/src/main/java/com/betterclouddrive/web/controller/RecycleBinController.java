package com.betterclouddrive.web.controller;

import com.betterclouddrive.common.dto.ApiResponse;
import com.betterclouddrive.common.dto.PageResult;
import com.betterclouddrive.dal.entity.FileEntity;
import com.betterclouddrive.service.FileService;
import com.betterclouddrive.web.dto.response.RecycleBinItemResponse;
import com.betterclouddrive.web.security.CurrentUser;
import com.betterclouddrive.web.security.UserPrincipal;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/recycle-bin")
@RequiredArgsConstructor
public class RecycleBinController {

    private final FileService fileService;

    @GetMapping
    public ApiResponse<PageResult<RecycleBinItemResponse>> listRecycleBin(
            @CurrentUser UserPrincipal user,
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        PageResult<FileEntity> result = fileService.listRecycleBin(user.getUserId(), q, page, size);
        return ApiResponse.success(PageResult.of(
                result.getRecords().stream().map(RecycleBinItemResponse::from).toList(),
                result.getTotal(), result.getPage(), result.getSize()));
    }

    @PostMapping("/{fileId}/restore")
    public ApiResponse<Void> restore(@CurrentUser UserPrincipal user, @PathVariable Long fileId) {
        fileService.restoreFile(user.getUserId(), fileId);
        return ApiResponse.success();
    }

    @DeleteMapping
    public ApiResponse<Void> emptyRecycleBin(@CurrentUser UserPrincipal user) {
        fileService.emptyRecycleBin(user.getUserId());
        return ApiResponse.success();
    }

    @DeleteMapping("/{fileId}")
    public ApiResponse<Void> permanentDelete(@CurrentUser UserPrincipal user, @PathVariable Long fileId) {
        fileService.permanentDelete(user.getUserId(), fileId);
        return ApiResponse.success();
    }
}
