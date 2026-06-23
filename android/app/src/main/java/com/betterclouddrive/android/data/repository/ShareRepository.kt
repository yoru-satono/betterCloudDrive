package com.betterclouddrive.android.data.repository

import com.betterclouddrive.android.data.remote.ApiService
import com.betterclouddrive.android.data.remote.dto.*
import com.betterclouddrive.android.domain.model.FileItem
import com.betterclouddrive.android.domain.model.ShareLink
import com.betterclouddrive.android.util.NetworkResult
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ShareRepository @Inject constructor(private val api: ApiService) {

    suspend fun createShare(
        fileId: Long,
        password: String? = null,
        expireAt: Long? = null,
        maxVisits: Int? = null,
        notifyEmail: String? = null,
    ): NetworkResult<ShareLink> = apiCall {
        api.createShare(CreateShareRequest(fileId, password, expireAt, maxVisits, notifyEmail))
    }

    suspend fun listShares(page: Int = 1, size: Int = 20): NetworkResult<PageResult<ShareLink>> = apiCall {
        api.listShares(page, size)
    }

    suspend fun getShare(shareId: Long): NetworkResult<ShareLink> = apiCall { api.getShare(shareId) }

    suspend fun getSharePassword(shareId: Long): NetworkResult<SharePasswordResponse> = apiCall {
        api.getSharePassword(shareId)
    }

    suspend fun updateShare(
        shareId: Long,
        password: String? = null,
        expireAt: Long? = null,
        maxVisits: Int? = null,
    ): NetworkResult<ShareLink> = apiCall {
        api.updateShare(shareId, UpdateShareRequest(password, expireAt, maxVisits))
    }

    suspend fun cancelShare(shareId: Long): NetworkResult<Unit> = apiCall { api.cancelShare(shareId) }

    suspend fun accessShare(shareCode: String, password: String? = null): NetworkResult<ShareAccessResult> = apiCall {
        api.accessShare(shareCode, AccessShareRequest(password))
    }

    suspend fun listSharedFiles(
        shareCode: String,
        parentId: Long? = null,
        page: Int = 1,
        size: Int = 20,
    ): NetworkResult<PageResult<FileItem>> = apiCall {
        api.listSharedFiles(shareCode, parentId, page, size)
    }

    suspend fun saveSharedItem(
        shareCode: String,
        fileId: Long? = null,
        targetParentId: Long? = null,
        password: String? = null,
    ): NetworkResult<FileItem> = apiCall {
        api.saveSharedItem(shareCode, SaveSharedItemRequest(fileId, targetParentId, password))
    }

    suspend fun downloadSharedFile(shareCode: String, fileId: Long, password: String? = null) =
        api.downloadSharedFile(shareCode, fileId, AccessShareRequest(password))

    suspend fun downloadSharedFolderZip(shareCode: String, fileId: Long, password: String? = null) =
        api.downloadSharedFolderZip(shareCode, fileId, AccessShareRequest(password))
}
