package com.betterclouddrive.service;

import com.betterclouddrive.common.dto.PageResult;
import com.betterclouddrive.dal.entity.FileEntity;

import java.util.List;

public interface FileService {
    PageResult<FileEntity> listFiles(Long userId, Long parentId, int page, int size, String sortBy, String order);
    FileEntity getFile(Long userId, Long fileId);
    FileEntity createFolder(Long userId, Long parentId, String folderName);
    FileEntity renameFile(Long userId, Long fileId, String newName);
    void moveFile(Long userId, Long fileId, Long targetParentId);
    void copyFile(Long userId, Long fileId, Long targetParentId);
    void deleteFiles(Long userId, List<Long> fileIds);
    void restoreFile(Long userId, Long fileId);
    PageResult<FileEntity> listRecycleBin(Long userId, String keyword, int page, int size);
    void permanentDelete(Long userId, Long fileId);
    void emptyRecycleBin(Long userId);
    /** Scheduler-only cleanup using the same recursive object/version deletion path as manual cleanup. */
    void purgeExpiredRecycleRoot(Long fileId);
    PageResult<FileEntity> searchFiles(Long userId, String keyword, int page, int size);

    PageResult<FileEntity> listTeamFiles(Long userId, Long teamId, Long parentId, int page, int size, String sortBy, String order);
    FileEntity getTeamFile(Long teamId, Long fileId);
    FileEntity createTeamFolder(Long teamId, Long userId, Long parentId, String folderName);
    FileEntity renameTeamFile(Long teamId, Long actorUserId, Long fileId, String newName);
    void moveTeamFile(Long teamId, Long actorUserId, Long fileId, Long targetParentId);
    void deleteTeamFiles(Long teamId, Long actorUserId, List<Long> fileIds);
    void restoreTeamFile(Long teamId, Long actorUserId, Long fileId);
    PageResult<FileEntity> listTeamRecycleBin(Long userId, Long teamId, String keyword, int page, int size);
    void permanentDeleteTeamFile(Long teamId, Long actorUserId, Long fileId);
    void emptyTeamRecycleBin(Long teamId, Long actorUserId);
    PageResult<FileEntity> searchTeamFiles(Long userId, Long teamId, String keyword, int page, int size);

    /** Admin: get file without userId ownership check */
    FileEntity adminGetFile(Long fileId);

    /** Admin: soft-delete file without userId ownership check */
    void adminDeleteFile(Long fileId);

    /** Copy a file or folder tree to the given parent folder under a specific target name (used by WebDAV COPY). */
    FileEntity copyFileTo(Long userId, Long fileId, Long targetParentId, String targetFileName);
}
