package com.betterclouddrive.android.data.repository

import com.betterclouddrive.android.data.remote.ApiService
import com.betterclouddrive.android.data.remote.dto.PageResult
import com.betterclouddrive.android.domain.model.FileItem
import com.betterclouddrive.android.util.NetworkResult
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RecycleBinRepository @Inject constructor(private val api: ApiService) {

    suspend fun listRecycleBin(page: Int = 1, size: Int = 20): NetworkResult<PageResult<FileItem>> = apiCall {
        api.listRecycleBin(page, size)
    }

    suspend fun restoreFile(fileId: Long): NetworkResult<Unit> = apiCall { api.restoreFile(fileId) }

    suspend fun permanentDeleteFile(fileId: Long): NetworkResult<Unit> = apiCall { api.permanentDeleteFile(fileId) }

    suspend fun emptyRecycleBin(): NetworkResult<Unit> = apiCall { api.emptyRecycleBin() }
}
