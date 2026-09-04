package com.betterclouddrive.service.impl;

import com.betterclouddrive.common.constant.ApiCode;
import com.betterclouddrive.common.dto.PageResult;
import com.betterclouddrive.common.exception.BusinessException;
import com.betterclouddrive.dal.entity.FavoriteEntity;
import com.betterclouddrive.dal.entity.FileEntity;
import com.betterclouddrive.dal.repository.FavoriteRepository;
import com.betterclouddrive.dal.repository.FileRepository;
import com.betterclouddrive.service.FavoriteService;
import com.betterclouddrive.service.TeamAuditService;
import com.betterclouddrive.service.TeamPermissionService;
import com.betterclouddrive.service.TeamService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class FavoriteServiceImpl implements FavoriteService {

    private final FavoriteRepository favoriteRepository;
    private final FileRepository fileRepository;
    private final TeamService teamService;
    private final TeamAuditService teamAuditService;
    private final TeamPermissionService teamPermissionService;

    @Override
    @Transactional
    public void addFavorite(Long userId, Long fileId) {
        FileEntity file = fileRepository.findById(fileId).orElse(null);
        if (file == null || file.getTeamId() != null || file.getIsDeleted() || !userId.equals(file.getUserId())) {
            throw new BusinessException(ApiCode.FILE_NOT_FOUND);
        }
        saveFavoriteIfNeeded(userId, fileId);
        log.info("User {} added file {} to favorites", userId, fileId);
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
        PageRequest pageRequest = PageRequest.of(page - 1, size);
        Page<FileEntity> favPage = normalizedKeyword == null
                ? favoriteRepository.findFavoriteFiles(userId, pageRequest)
                : favoriteRepository.findFavoriteFilesByKeyword(userId, normalizedKeyword, pageRequest);
        return PageResult.of(favPage.getContent(), favPage.getTotalElements(), page, size);
    }

    @Override
    @Transactional
    public void addTeamFavorite(Long userId, Long teamId, Long fileId) {
        teamService.requireRead(userId, teamId);
        FileEntity file = requireTeamFile(teamId, fileId);
        saveFavoriteIfNeeded(userId, file.getId());
        teamAuditService.record(teamId, userId, "FAVORITE_ADD", "FILE", fileId, file.getFileName());
        log.info("User {} added team file {} to favorites", userId, fileId);
    }

    @Override
    @Transactional
    public void removeTeamFavorite(Long userId, Long teamId, Long fileId) {
        teamService.requireRead(userId, teamId);
        requireTeamFile(teamId, fileId);
        favoriteRepository.deleteByUserIdAndFileId(userId, fileId);
        teamAuditService.record(teamId, userId, "FAVORITE_REMOVE", "FILE", fileId, null);
        log.info("User {} removed team file {} from favorites", userId, fileId);
    }

    @Override
    public boolean isTeamFavorite(Long userId, Long teamId, Long fileId) {
        teamService.requireRead(userId, teamId);
        requireTeamFile(teamId, fileId);
        return favoriteRepository.existsByUserIdAndFileId(userId, fileId);
    }

    @Override
    public PageResult<FileEntity> listTeamFavorites(Long userId, Long teamId, String keyword, int page, int size) {
        teamService.requireRead(userId, teamId);
        String normalizedKeyword = StringUtils.hasText(keyword) ? keyword.trim() : null;
        List<FileEntity> readable = (normalizedKeyword == null
                ? favoriteRepository.findTeamFavoriteFiles(userId, teamId)
                : favoriteRepository.findTeamFavoriteFilesByKeyword(userId, teamId, normalizedKeyword))
                .stream()
                .filter(file -> canReadTeamFile(userId, teamId, file.getId()))
                .toList();
        return PageResults.fromFiltered(readable, page, size);
    }

    private boolean canReadTeamFile(Long userId, Long teamId, Long fileId) {
        try {
            return teamPermissionService.effectivePermissions(userId, teamId, fileId).canRead();
        } catch (BusinessException exception) {
            return false;
        }
    }

    private void saveFavoriteIfNeeded(Long userId, Long fileId) {
        if (favoriteRepository.existsByUserIdAndFileId(userId, fileId)) {
            log.debug("File {} is already favorited by user {}", fileId, userId);
            return;
        }
        favoriteRepository.save(FavoriteEntity.builder()
                .userId(userId)
                .fileId(fileId)
                .createdAt(LocalDateTime.now())
                .build());
    }

    private FileEntity requireTeamFile(Long teamId, Long fileId) {
        FileEntity file = fileRepository.findById(fileId).orElse(null);
        if (file == null || !teamId.equals(file.getTeamId()) || file.getIsDeleted()) {
            throw new BusinessException(ApiCode.FILE_NOT_FOUND);
        }
        return file;
    }

}
