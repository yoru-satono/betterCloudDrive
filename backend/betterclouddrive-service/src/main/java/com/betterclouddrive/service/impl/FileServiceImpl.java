package com.betterclouddrive.service.impl;

import com.betterclouddrive.common.constant.ApiCode;
import com.betterclouddrive.common.dto.PageResult;
import com.betterclouddrive.common.exception.BusinessException;
import com.betterclouddrive.dal.entity.FileEntity;
import com.betterclouddrive.dal.mapper.FileMapper;
import com.betterclouddrive.service.FileService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class FileServiceImpl implements FileService {

    private final FileMapper fileMapper;
    private final StringRedisTemplate redisTemplate;

    private static final String STORAGE_INCR_PREFIX = "storage:incr:";

    @Override
    public PageResult<FileEntity> listFiles(Long userId, Long parentId, int page, int size, String sortBy, String order) {
        LambdaQueryWrapper<FileEntity> wrapper = new LambdaQueryWrapper<FileEntity>()
                .eq(FileEntity::getUserId, userId)
                .eq(parentId != null, FileEntity::getParentId, parentId)
                .isNull(parentId == null, FileEntity::getParentId)
                .eq(FileEntity::getIsDeleted, false)
                .orderByAsc(FileEntity::getFileType)
                .orderByAsc(FileEntity::getFileName);

        Page<FileEntity> result = fileMapper.selectPage(new Page<>(page, size), wrapper);
        return PageResult.of(result.getRecords(), result.getTotal(), page, size);
    }

    @Override
    public FileEntity getFile(Long userId, Long fileId) {
        FileEntity file = fileMapper.selectById(fileId);
        if (file == null || !file.getUserId().equals(userId) || file.getIsDeleted()) {
            throw new BusinessException(ApiCode.FILE_NOT_FOUND);
        }
        return file;
    }

    @Override
    @Transactional
    public FileEntity createFolder(Long userId, Long parentId, String folderName) {
        // Check for name conflict
        Long count = fileMapper.selectCount(new LambdaQueryWrapper<FileEntity>()
                .eq(FileEntity::getUserId, userId)
                .eq(parentId != null, FileEntity::getParentId, parentId)
                .isNull(parentId == null, FileEntity::getParentId)
                .eq(FileEntity::getFileName, folderName)
                .eq(FileEntity::getFileType, "folder")
                .eq(FileEntity::getIsDeleted, false));
        if (count > 0) {
            throw new BusinessException(ApiCode.FILE_NAME_CONFLICT);
        }

        FileEntity folder = FileEntity.builder()
                .userId(userId)
                .parentId(parentId)
                .fileName(folderName)
                .fileType("folder")
                .fileSize(0L)
                .isDeleted(false)
                .versionCount(1)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
        fileMapper.insert(folder);
        return folder;
    }

    @Override
    @Transactional
    public FileEntity renameFile(Long userId, Long fileId, String newName) {
        FileEntity file = getFile(userId, fileId);
        file.setFileName(newName);
        file.setUpdatedAt(LocalDateTime.now());
        fileMapper.updateById(file);
        return file;
    }

    @Override
    @Transactional
    public void moveFile(Long userId, Long fileId, Long targetParentId) {
        FileEntity file = getFile(userId, fileId);
        file.setParentId(targetParentId);
        file.setUpdatedAt(LocalDateTime.now());
        fileMapper.updateById(file);
    }

    @Override
    @Transactional
    public void copyFile(Long userId, Long fileId, Long targetParentId) {
        FileEntity source = getFile(userId, fileId);
        FileEntity copy = FileEntity.builder()
                .userId(userId)
                .parentId(targetParentId)
                .fileName(source.getFileName() + " (copy)")
                .fileType(source.getFileType())
                .mimeType(source.getMimeType())
                .fileSize(source.getFileSize())
                .storagePath(source.getStoragePath())
                .md5Hash(source.getMd5Hash())
                .isDeleted(false)
                .versionCount(1)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
        fileMapper.insert(copy);
    }

    @Override
    @Transactional
    public void deleteFiles(Long userId, List<Long> fileIds) {
        for (Long fileId : fileIds) {
            FileEntity file = getFile(userId, fileId);
            file.setIsDeleted(true);
            file.setDeletedAt(LocalDateTime.now());
            fileMapper.updateById(file);
            // Decrement storage used (only files, not folders)
            if ("file".equals(file.getFileType()) && file.getFileSize() > 0) {
                redisTemplate.opsForValue().increment(STORAGE_INCR_PREFIX + userId, -file.getFileSize());
            }
        }
    }

    @Override
    @Transactional
    public void restoreFile(Long userId, Long fileId) {
        FileEntity file = fileMapper.selectById(fileId);
        if (file == null || !file.getUserId().equals(userId) || !file.getIsDeleted()) {
            throw new BusinessException(ApiCode.FILE_NOT_FOUND);
        }
        file.setIsDeleted(false);
        file.setDeletedAt(null);
        fileMapper.updateById(file);
    }

    @Override
    public PageResult<FileEntity> listRecycleBin(Long userId, int page, int size) {
        LambdaQueryWrapper<FileEntity> wrapper = new LambdaQueryWrapper<FileEntity>()
                .eq(FileEntity::getUserId, userId)
                .eq(FileEntity::getIsDeleted, true)
                .orderByDesc(FileEntity::getDeletedAt);
        Page<FileEntity> result = fileMapper.selectPage(new Page<>(page, size), wrapper);
        return PageResult.of(result.getRecords(), result.getTotal(), page, size);
    }

    @Override
    @Transactional
    public void permanentDelete(Long userId, Long fileId) {
        FileEntity file = fileMapper.selectById(fileId);
        if (file == null || !file.getUserId().equals(userId) || !file.getIsDeleted()) {
            throw new BusinessException(ApiCode.FILE_NOT_FOUND);
        }
        fileMapper.deleteById(fileId);
    }

    @Override
    @Transactional
    public void emptyRecycleBin(Long userId) {
        LambdaQueryWrapper<FileEntity> wrapper = new LambdaQueryWrapper<FileEntity>()
                .eq(FileEntity::getUserId, userId)
                .eq(FileEntity::getIsDeleted, true);
        fileMapper.delete(wrapper);
    }

    @Override
    public PageResult<FileEntity> searchFiles(Long userId, String keyword, int page, int size) {
        LambdaQueryWrapper<FileEntity> wrapper = new LambdaQueryWrapper<FileEntity>()
                .eq(FileEntity::getUserId, userId)
                .eq(FileEntity::getIsDeleted, false)
                .like(FileEntity::getFileName, keyword)
                .orderByAsc(FileEntity::getFileType)
                .orderByAsc(FileEntity::getFileName);
        Page<FileEntity> result = fileMapper.selectPage(new Page<>(page, size), wrapper);
        return PageResult.of(result.getRecords(), result.getTotal(), page, size);
    }

    @Override
    public FileEntity adminGetFile(Long fileId) {
        FileEntity file = fileMapper.selectById(fileId);
        if (file == null || file.getIsDeleted()) {
            throw new BusinessException(ApiCode.FILE_NOT_FOUND);
        }
        return file;
    }

    @Override
    @Transactional
    public void adminDeleteFile(Long fileId) {
        FileEntity file = adminGetFile(fileId);
        file.setIsDeleted(true);
        file.setDeletedAt(LocalDateTime.now());
        fileMapper.updateById(file);
    }
}
