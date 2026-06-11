package com.betterclouddrive.service.impl;

import com.betterclouddrive.common.constant.ApiCode;
import com.betterclouddrive.common.exception.BusinessException;
import com.betterclouddrive.dal.entity.FileEntity;
import com.betterclouddrive.dal.entity.FileVersionEntity;
import com.betterclouddrive.dal.repository.FileRepository;
import com.betterclouddrive.dal.repository.FileVersionRepository;
import com.betterclouddrive.service.FileVersionService;
import com.betterclouddrive.storage.StorageService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class FileVersionServiceImpl implements FileVersionService {

    private final FileVersionRepository fileVersionRepository;
    private final FileRepository fileRepository;
    private final StorageService storageService;

    @Override
    public List<FileVersionEntity> listVersions(Long userId, Long fileId) {
        FileEntity file = fileRepository.findById(fileId).orElse(null);
        if (file == null || !file.getUserId().equals(userId)) {
            throw new BusinessException(ApiCode.FILE_NOT_FOUND);
        }
        return fileVersionRepository.findTopVersionsByFileId(fileId, PageRequest.of(0, 10));
    }

    @Override
    @Transactional
    public void deleteVersion(Long userId, Long fileId, int versionNumber) {
        FileEntity file = fileRepository.findById(fileId).orElse(null);
        if (file == null || !file.getUserId().equals(userId)) {
            throw new BusinessException(ApiCode.FILE_NOT_FOUND);
        }
        List<FileVersionEntity> versions = fileVersionRepository.findTopVersionsByFileId(fileId, PageRequest.of(0, 100));
        FileVersionEntity toDelete = versions.stream()
                .filter(v -> v.getVersionNumber() == versionNumber)
                .findFirst()
                .orElseThrow(() -> new BusinessException(ApiCode.NOT_FOUND, "Version not found"));

        if (versions.size() <= 1) {
            throw new BusinessException(ApiCode.CONFLICT, "Cannot delete the only version of a file");
        }

        storageService.deleteObject(toDelete.getStoragePath());
        fileVersionRepository.deleteById(toDelete.getId());
        file.setVersionCount(file.getVersionCount() - 1);
        fileRepository.save(file);
    }
}
