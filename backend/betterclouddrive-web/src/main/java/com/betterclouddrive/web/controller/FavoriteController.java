package com.betterclouddrive.web.controller;

import com.betterclouddrive.common.dto.ApiResponse;
import com.betterclouddrive.common.dto.PageResult;
import com.betterclouddrive.dal.entity.FileEntity;
import com.betterclouddrive.service.FavoriteService;
import com.betterclouddrive.web.security.CurrentUser;
import com.betterclouddrive.web.security.UserPrincipal;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/favorites")
@RequiredArgsConstructor
public class FavoriteController {

    private final FavoriteService favoriteService;

    @PostMapping("/{fileId}")
    public ApiResponse<Void> addFavorite(@CurrentUser UserPrincipal user, @PathVariable Long fileId) {
        favoriteService.addFavorite(user.getUserId(), fileId);
        return ApiResponse.success();
    }

    @DeleteMapping("/{fileId}")
    public ApiResponse<Void> removeFavorite(@CurrentUser UserPrincipal user, @PathVariable Long fileId) {
        favoriteService.removeFavorite(user.getUserId(), fileId);
        return ApiResponse.success();
    }

    @GetMapping("/{fileId}/status")
    public ApiResponse<Boolean> isFavorite(@CurrentUser UserPrincipal user, @PathVariable Long fileId) {
        return ApiResponse.success(favoriteService.isFavorite(user.getUserId(), fileId));
    }

    @GetMapping
    public ApiResponse<PageResult<FileEntity>> listFavorites(
            @CurrentUser UserPrincipal user,
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.success(favoriteService.listFavorites(user.getUserId(), q, page, size));
    }

}
