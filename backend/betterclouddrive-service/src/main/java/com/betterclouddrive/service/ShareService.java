package com.betterclouddrive.service;

import com.betterclouddrive.common.dto.PageResult;
import com.betterclouddrive.dal.entity.FileEntity;
import com.betterclouddrive.dal.entity.ShareLinkEntity;

import java.util.List;

public interface ShareService {
    ShareLinkEntity createShare(Long userId, Long fileId, String password, Long expireAtMs, Integer maxDownloads);
    PageResult<ShareLinkEntity> listShares(Long userId, int page, int size);
    ShareLinkEntity getShare(Long userId, Long shareId);
    ShareLinkEntity updateShare(Long userId, Long shareId, String password, Long expireAtMs, Integer maxDownloads);
    void cancelShare(Long userId, Long shareId);
    /** Access a shared link: verify code + password, return the shared file info */
    FileEntity accessShare(String shareCode, String password);
    /** List contents of a shared folder */
    PageResult<FileEntity> listSharedFiles(String shareCode, Long parentId, int page, int size);
}
