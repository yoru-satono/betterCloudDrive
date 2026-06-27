package com.betterclouddrive.service.impl;

import com.betterclouddrive.common.constant.ApiCode;
import com.betterclouddrive.common.dto.PageResult;
import com.betterclouddrive.common.exception.BusinessException;
import com.betterclouddrive.dal.entity.FavoriteEntity;
import com.betterclouddrive.dal.entity.FileEntity;
import com.betterclouddrive.dal.repository.FavoriteRepository;
import com.betterclouddrive.dal.repository.FileRepository;
import com.betterclouddrive.service.FavoriteService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;

@Slf4j
@Service
@RequiredArgsConstructor
public class FavoriteServiceImpl implements FavoriteService {

    private final FavoriteRepository favoriteRepository;
    private final FileRepository fileRepository;

    @Override
    @Transactional
    public void addFavorite(Long userId, Long fileId) {
        FileEntity file = fileRepository.findById(fileId).orElse(null);
        if (file == null || file.getIsDeleted()) {
            throw new BusinessException(ApiCode.FILE_NOT_FOUND);
        }
        if (!favoriteRepository.existsByUserIdAndFileId(userId, fileId)) {
            FavoriteEntity fav = FavoriteEntity.builder()
                    .userId(userId)
                    .fileId(fileId)
                    .createdAt(LocalDateTime.now())
                    .build();
            favoriteRepository.save(fav);
            log.info("User {} added file {} to favorites", userId, fileId);
        } else {
            log.debug("File {} is already favorited by user {}", fileId, userId);
        }
    }

    @Override
    @Transactional
    public void removeFavorite(Long userId, Long fileId) {
        favoriteRepository.deleteByUserIdAndFileId(userId, fileId);
        log.info("User {} removed file {} from favorites", userId, fileId);
    }

    @Override
    public boolean isFavorite(Long userId, Long fileId) {
        return favoriteRepository.existsByUserIdAndFileId(userId, fileId);
    }

    @Override
    public PageResult<FileEntity> listFavorites(Long userId, String keyword, int page, int size) {
        String normalizedKeyword = StringUtils.hasText(keyword) ? keyword.trim() : null;
        Page<FileEntity> favPage = favoriteRepository.findFavoriteFiles(
                userId, normalizedKeyword, PageRequest.of(page - 1, size));
        return PageResult.of(favPage.getContent(), favPage.getTotalElements(), page, size);
    }

}
