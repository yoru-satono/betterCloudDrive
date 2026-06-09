package com.betterclouddrive.android.data.repository

import com.betterclouddrive.android.data.remote.ApiService
import com.betterclouddrive.android.data.remote.dto.PageResult
import com.betterclouddrive.android.domain.model.FileItem
import com.betterclouddrive.android.util.NetworkResult
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class FavoriteRepository @Inject constructor(private val api: ApiService) {

    suspend fun addFavorite(fileId: Long): NetworkResult<Unit> = apiCall { api.addFavorite(fileId) }

    suspend fun removeFavorite(fileId: Long): NetworkResult<Unit> = apiCall { api.removeFavorite(fileId) }

    suspend fun listFavorites(page: Int = 1, size: Int = 20): NetworkResult<PageResult<FileItem>> = apiCall {
        api.listFavorites(page, size)
    }

    suspend fun getFavoriteStatus(fileId: Long): NetworkResult<Boolean> = apiCall { api.getFavoriteStatus(fileId) }
}
