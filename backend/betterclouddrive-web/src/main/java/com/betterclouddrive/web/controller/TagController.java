package com.betterclouddrive.web.controller;

import com.betterclouddrive.common.dto.ApiResponse;
import com.betterclouddrive.common.dto.PageResult;
import com.betterclouddrive.dal.entity.FileEntity;
import com.betterclouddrive.dal.entity.TagEntity;
import com.betterclouddrive.service.TagService;
import com.betterclouddrive.web.dto.request.CreateTagRequest;
import com.betterclouddrive.web.dto.request.UpdateTagRequest;
import com.betterclouddrive.web.security.CurrentUser;
import com.betterclouddrive.web.security.UserPrincipal;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/tags")
@RequiredArgsConstructor
public class TagController {

    private final TagService tagService;

    @PostMapping
    public ApiResponse<TagEntity> createTag(@CurrentUser UserPrincipal user,
                                             @Valid @RequestBody CreateTagRequest request) {
        return ApiResponse.success(tagService.createTag(user.getUserId(), request.getTagName(), request.getColor()));
    }

    @GetMapping
    public ApiResponse<List<TagEntity>> listTags(@CurrentUser UserPrincipal user) {
        return ApiResponse.success(tagService.listTags(user.getUserId()));
    }

    @PutMapping("/{tagId}")
    public ApiResponse<TagEntity> updateTag(@CurrentUser UserPrincipal user,
                                             @PathVariable Long tagId,
                                             @Valid @RequestBody UpdateTagRequest request) {
        return ApiResponse.success(tagService.updateTag(user.getUserId(), tagId, request.getTagName(), request.getColor()));
    }

    @DeleteMapping("/{tagId}")
    public ApiResponse<Void> deleteTag(@CurrentUser UserPrincipal user, @PathVariable Long tagId) {
        tagService.deleteTag(user.getUserId(), tagId);
        return ApiResponse.success();
    }

    @PostMapping("/{tagId}/files")
    public ApiResponse<Void> tagFiles(@CurrentUser UserPrincipal user,
                                       @PathVariable Long tagId,
                                       @RequestBody Map<String, List<Long>> body) {
        tagService.tagFiles(user.getUserId(), tagId, body.get("fileIds"));
        return ApiResponse.success();
    }

    @DeleteMapping("/{tagId}/files/{fileId}")
    public ApiResponse<Void> untagFile(@CurrentUser UserPrincipal user,
                                        @PathVariable Long tagId,
                                        @PathVariable Long fileId) {
        tagService.untagFile(user.getUserId(), tagId, fileId);
        return ApiResponse.success();
    }

    @GetMapping("/{tagId}/files")
    public ApiResponse<PageResult<FileEntity>> listFilesByTag(
            @CurrentUser UserPrincipal user,
            @PathVariable Long tagId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.success(tagService.listFilesByTag(user.getUserId(), tagId, page, size));
    }
}
