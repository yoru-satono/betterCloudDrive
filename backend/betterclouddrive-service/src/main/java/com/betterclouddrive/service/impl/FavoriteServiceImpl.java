package com.betterclouddrive.service.impl;

import com.betterclouddrive.common.constant.ApiCode;
import com.betterclouddrive.common.dto.PageResult;
import com.betterclouddrive.common.exception.BusinessException;
import com.betterclouddrive.dal.entity.FavoriteEntity;
import com.betterclouddrive.dal.entity.FileEntity;
import com.betterclouddrive.dal.mapper.FavoriteMapper;
import com.betterclouddrive.dal.mapper.FileMapper;
import com.betterclouddrive.service.FavoriteService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class FavoriteServiceImpl implements FavoriteService {

    private final FavoriteMapper favoriteMapper;
    private final FileMapper fileMapper;

    @Override
    @Transactional
    public void addFavorite(Long userId, Long fileId) {
        FileEntity file = fileMapper.selectById(fileId);
        if (file == null || file.getIsDeleted()) {
            throw new BusinessException(ApiCode.FILE_NOT_FOUND);
        }
        // Check if already favorited
        Long count = favoriteMapper.selectCount(new LambdaQueryWrapper<FavoriteEntity>()
                .eq(FavoriteEntity::getUserId, userId)
                .eq(FavoriteEntity::getFileId, fileId));
        if (count == 0) {
            FavoriteEntity fav = FavoriteEntity.builder()
                    .userId(userId)
                    .fileId(fileId)
                    .createdAt(LocalDateTime.now())
                    .build();
            favoriteMapper.insert(fav);
            log.info("User {} added file {} to favorites", userId, fileId);
        } else {
            log.debug("File {} is already favorited by user {}", fileId, userId);
        }
    }

    @Override
    @Transactional
    public void removeFavorite(Long userId, Long fileId) {
        favoriteMapper.delete(new LambdaQueryWrapper<FavoriteEntity>()
                .eq(FavoriteEntity::getUserId, userId)
                .eq(FavoriteEntity::getFileId, fileId));
        log.info("User {} removed file {} from favorites", userId, fileId);
    }

    @Override
    public PageResult<FileEntity> listFavorites(Long userId, int page, int size) {
        Page<FavoriteEntity> favPage = favoriteMapper.selectPage(new Page<>(page, size),
                new LambdaQueryWrapper<FavoriteEntity>()
                        .eq(FavoriteEntity::getUserId, userId)
                        .orderByDesc(FavoriteEntity::getCreatedAt));

        if (favPage.getRecords().isEmpty()) {
            return PageResult.of(List.of(), 0L, page, size);
        }

        List<Long> fileIds = favPage.getRecords().stream()
                .map(FavoriteEntity::getFileId)
                .collect(Collectors.toList());

        List<FileEntity> files = fileMapper.selectBatchIds(fileIds);
        return PageResult.of(files, favPage.getTotal(), page, size);
    }

    @Override
    public boolean isFavorited(Long userId, Long fileId) {
        Long count = favoriteMapper.selectCount(new LambdaQueryWrapper<FavoriteEntity>()
                .eq(FavoriteEntity::getUserId, userId)
                .eq(FavoriteEntity::getFileId, fileId));
        return count > 0;
    }
}
