package com.betterclouddrive.service.impl;

import com.betterclouddrive.common.constant.ApiCode;
import com.betterclouddrive.common.exception.BusinessException;
import com.betterclouddrive.dal.entity.FileEntity;
import com.betterclouddrive.dal.entity.FileVersionEntity;
import com.betterclouddrive.dal.mapper.FileMapper;
import com.betterclouddrive.dal.mapper.FileVersionMapper;
import com.betterclouddrive.service.FileVersionService;
import com.betterclouddrive.storage.StorageService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class FileVersionServiceImpl implements FileVersionService {

    private final FileVersionMapper fileVersionMapper;
    private final FileMapper fileMapper;
    private final StorageService storageService;

    @Override
    public List<FileVersionEntity> listVersions(Long userId, Long fileId) {
        FileEntity file = fileMapper.selectById(fileId);
        if (file == null || !file.getUserId().equals(userId)) {
            throw new BusinessException(ApiCode.FILE_NOT_FOUND);
        }
        return fileVersionMapper.selectByFileId(fileId, 10);
    }

    @Override
    @Transactional
    public void deleteVersion(Long userId, Long fileId, int versionNumber) {
        FileEntity file = fileMapper.selectById(fileId);
        if (file == null || !file.getUserId().equals(userId)) {
            throw new BusinessException(ApiCode.FILE_NOT_FOUND);
        }
        List<FileVersionEntity> versions = fileVersionMapper.selectByFileId(fileId, 100);
        FileVersionEntity toDelete = versions.stream()
                .filter(v -> v.getVersionNumber() == versionNumber)
                .findFirst()
                .orElseThrow(() -> new BusinessException(ApiCode.NOT_FOUND, "Version not found"));

        // Don't allow deleting the only version
        if (versions.size() <= 1) {
            throw new BusinessException(ApiCode.CONFLICT, "Cannot delete the only version of a file");
        }

        // Delete from storage
        storageService.deleteObject(toDelete.getStoragePath());
        // Delete from DB
        fileVersionMapper.deleteById(toDelete.getId());
        // Update version count on file
        file.setVersionCount(file.getVersionCount() - 1);
        fileMapper.updateById(file);
    }
}
