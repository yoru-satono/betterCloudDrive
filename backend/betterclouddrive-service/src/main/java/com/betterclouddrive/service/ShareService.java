package com.betterclouddrive.service;

import com.betterclouddrive.common.dto.PageResult;
import com.betterclouddrive.dal.entity.FileEntity;
import com.betterclouddrive.dal.entity.ShareLinkEntity;

public interface ShareService {
    ShareLinkEntity createShare(Long userId, Long fileId, String password, Long expireAtMs, Integer maxVisits);
    PageResult<ShareLinkEntity> listShares(Long userId, int page, int size);
    ShareLinkEntity getShare(Long userId, Long shareId);
    String getSharePassword(Long userId, Long shareId);
    ShareLinkEntity updateShare(Long userId, Long shareId, String password, Long expireAtMs, Integer maxVisits);
    void cancelShare(Long userId, Long shareId);
    /** Access a shared link: verify code + password, return the shared file info */
    FileEntity accessShare(String shareCode, String password);
    /** Resolve and record a public share download for a file inside the shared scope. */
    FileEntity downloadSharedFile(String shareCode, Long fileId, String password);
    /** Resolve a shared folder for webpage ZIP download without recording download count. */
    FileEntity resolveSharedFolderDownload(String shareCode, Long fileId, String password);
    /** Record a successful public share download. */
    void recordSharedDownload(String shareCode, String password);
    /** Save the shared file or folder, or one item inside it, into the current user's drive. */
    FileEntity saveSharedItem(String shareCode, Long fileId, Long targetParentId, Long userId, String password);
    /** List contents of a shared folder */
    PageResult<FileEntity> listSharedFiles(String shareCode, Long parentId, int page, int size);
}
