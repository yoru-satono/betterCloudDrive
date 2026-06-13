package com.betterclouddrive.service;

import com.betterclouddrive.common.dto.PageResult;
import com.betterclouddrive.dal.entity.FileEntity;

public interface FavoriteService {
    void addFavorite(Long userId, Long fileId);
    void removeFavorite(Long userId, Long fileId);
    boolean isFavorite(Long userId, Long fileId);
    PageResult<FileEntity> listFavorites(Long userId, int page, int size);
}
