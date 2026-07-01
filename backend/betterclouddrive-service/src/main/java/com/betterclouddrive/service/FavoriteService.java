package com.betterclouddrive.service;

import com.betterclouddrive.common.dto.PageResult;
import com.betterclouddrive.dal.entity.FileEntity;

public interface FavoriteService {
    void addFavorite(Long userId, Long fileId);
    void removeFavorite(Long userId, Long fileId);
    boolean isFavorite(Long userId, Long fileId);
    PageResult<FileEntity> listFavorites(Long userId, String keyword, int page, int size);
    void addTeamFavorite(Long userId, Long teamId, Long fileId);
    void removeTeamFavorite(Long userId, Long teamId, Long fileId);
    boolean isTeamFavorite(Long userId, Long teamId, Long fileId);
    PageResult<FileEntity> listTeamFavorites(Long userId, Long teamId, String keyword, int page, int size);
}
