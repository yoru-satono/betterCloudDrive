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

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

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
    public PageResult<FileEntity> listFavorites(Long userId, int page, int size) {
        Page<FavoriteEntity> favPage = favoriteRepository.findByUserIdOrderByCreatedAtDesc(
                userId, PageRequest.of(page - 1, size));

        if (favPage.getContent().isEmpty()) {
            return PageResult.of(List.of(), 0L, page, size);
        }

        List<Long> fileIds = favPage.getContent().stream()
                .map(FavoriteEntity::getFileId)
                .collect(Collectors.toList());

        List<FileEntity> files = fileRepository.findAllById(fileIds);
        return PageResult.of(files, favPage.getTotalElements(), page, size);
    }

}
