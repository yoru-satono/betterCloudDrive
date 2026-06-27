package com.betterclouddrive.android.data.repository

import com.betterclouddrive.android.data.remote.ApiService
import com.betterclouddrive.android.data.remote.dto.*
import com.betterclouddrive.android.domain.model.FileItem
import com.betterclouddrive.android.util.NetworkResult
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class FileRepository @Inject constructor(private val api: ApiService) {

    suspend fun listFiles(
        parentId: Long? = null,
        page: Int = 1,
        size: Int = 20,
        sortBy: String = "fileName",
        order: String = "asc",
    ): NetworkResult<PageResult<FileItem>> = apiCall { api.listFiles(parentId, page, size, sortBy, order) }

    suspend fun getFile(fileId: Long): NetworkResult<FileItem> = apiCall { api.getFile(fileId) }

    suspend fun createFolder(parentId: Long?, folderName: String): NetworkResult<FileItem> = apiCall {
        api.createFolder(CreateFolderRequest(parentId, folderName))
    }

    suspend fun renameFile(fileId: Long, newName: String): NetworkResult<FileItem> = apiCall {
        api.renameFile(fileId, RenameRequest(newName))
    }

    suspend fun moveFile(fileId: Long, targetParentId: Long?): NetworkResult<Unit> = apiCall {
        api.moveFile(fileId, MoveRequest(targetParentId))
    }

    suspend fun copyFile(fileId: Long, targetParentId: Long?): NetworkResult<Unit> = apiCall {
        api.copyFile(fileId, CopyRequest(targetParentId))
    }

    suspend fun deleteFiles(fileIds: List<Long>): NetworkResult<Unit> = apiCall {
        api.deleteFiles(BatchDeleteRequest(fileIds))
    }

    suspend fun searchFiles(query: String, page: Int = 1, size: Int = 20): NetworkResult<PageResult<FileItem>> = apiCall {
        api.searchFiles(query, page, size)
    }
}
