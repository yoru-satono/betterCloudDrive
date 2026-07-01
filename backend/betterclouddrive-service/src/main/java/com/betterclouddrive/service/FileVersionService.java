package com.betterclouddrive.service;

import com.betterclouddrive.dal.entity.FileVersionEntity;
import com.betterclouddrive.dal.entity.FileEntity;

import java.util.List;

public interface FileVersionService {
    List<FileVersionEntity> listVersions(Long userId, Long fileId);
    FileVersionEntity getVersion(Long userId, Long fileId, int versionNumber);
    void deleteVersion(Long userId, Long fileId, int versionNumber);
    FileEntity restoreVersion(Long userId, Long fileId, int versionNumber);
    List<FileVersionEntity> listTeamVersions(Long teamId, Long fileId);
    FileVersionEntity getTeamVersion(Long teamId, Long fileId, int versionNumber);
    void deleteTeamVersion(Long teamId, Long userId, Long fileId, int versionNumber);
    FileEntity restoreTeamVersion(Long teamId, Long userId, Long fileId, int versionNumber);
    FileVersionEntity saveTeamEditedVersion(Long teamId, Long userId, Long fileId, byte[] content);
}
