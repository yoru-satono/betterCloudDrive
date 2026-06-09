package com.betterclouddrive.service;

import com.betterclouddrive.dal.entity.FileVersionEntity;

import java.util.List;

public interface FileVersionService {
    List<FileVersionEntity> listVersions(Long userId, Long fileId);
    void deleteVersion(Long userId, Long fileId, int versionNumber);
}
