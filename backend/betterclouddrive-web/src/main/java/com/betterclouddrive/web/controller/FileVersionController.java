package com.betterclouddrive.web.controller;

import com.betterclouddrive.common.dto.ApiResponse;
import com.betterclouddrive.dal.entity.FileVersionEntity;
import com.betterclouddrive.service.FileVersionService;
import com.betterclouddrive.web.security.CurrentUser;
import com.betterclouddrive.web.security.UserPrincipal;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/files/{fileId}/versions")
@RequiredArgsConstructor
public class FileVersionController {

    private final FileVersionService fileVersionService;

    @GetMapping
    public ApiResponse<List<FileVersionEntity>> listVersions(
            @CurrentUser UserPrincipal user, @PathVariable Long fileId) {
        return ApiResponse.success(fileVersionService.listVersions(user.getUserId(), fileId));
    }

    @DeleteMapping("/{versionNumber}")
    public ApiResponse<Void> deleteVersion(
            @CurrentUser UserPrincipal user,
            @PathVariable Long fileId,
            @PathVariable int versionNumber) {
        fileVersionService.deleteVersion(user.getUserId(), fileId, versionNumber);
        return ApiResponse.success();
    }
}
