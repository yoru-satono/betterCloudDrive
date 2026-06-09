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
        expireAt: String? = null,
        maxDownloads: Int? = null,
    ): NetworkResult<ShareLink> = apiCall {
        api.createShare(CreateShareRequest(fileId, password, expireAt, maxDownloads))
    }

    suspend fun listShares(page: Int = 1, size: Int = 20): NetworkResult<PageResult<ShareLink>> = apiCall {
        api.listShares(page, size)
    }

    suspend fun getShare(shareId: Long): NetworkResult<ShareLink> = apiCall { api.getShare(shareId) }

    suspend fun updateShare(
        shareId: Long,
        password: String? = null,
        expireAt: String? = null,
        maxDownloads: Int? = null,
    ): NetworkResult<ShareLink> = apiCall {
        api.updateShare(shareId, UpdateShareRequest(password, expireAt, maxDownloads))
    }

    suspend fun cancelShare(shareId: Long): NetworkResult<Unit> = apiCall { api.cancelShare(shareId) }

    suspend fun accessShare(shareCode: String, password: String? = null): NetworkResult<ShareAccessResult> = apiCall {
        api.accessShare(shareCode, AccessShareRequest(password))
    }

    suspend fun listSharedFiles(
        shareCode: String,
        page: Int = 1,
        size: Int = 20,
    ): NetworkResult<PageResult<FileItem>> = apiCall {
        api.listSharedFiles(shareCode, page, size)
    }
}
