package com.betterclouddrive.android.data.repository

import com.betterclouddrive.android.data.remote.ApiService
import com.betterclouddrive.android.data.remote.dto.*
import com.betterclouddrive.android.domain.model.FileItem
import com.betterclouddrive.android.domain.model.Tag as TagModel
import com.betterclouddrive.android.util.NetworkResult
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TagRepository @Inject constructor(private val api: ApiService) {

    suspend fun createTag(tagName: String, color: String = "#1890ff"): NetworkResult<TagModel> = apiCall {
        api.createTag(CreateTagRequest(tagName, color))
    }

    suspend fun listTags(): NetworkResult<List<TagModel>> = apiCall { api.listTags() }

    suspend fun updateTag(tagId: Long, tagName: String? = null, color: String? = null): NetworkResult<TagModel> = apiCall {
        api.updateTag(tagId, UpdateTagRequest(tagName, color))
    }

    suspend fun deleteTag(tagId: Long): NetworkResult<Unit> = apiCall { api.deleteTag(tagId) }

    suspend fun tagFiles(tagId: Long, fileIds: List<Long>): NetworkResult<Unit> = apiCall {
        api.tagFiles(tagId, TagFilesRequest(fileIds))
    }

    suspend fun untagFile(tagId: Long, fileId: Long): NetworkResult<Unit> = apiCall {
        api.untagFile(tagId, fileId)
    }

    suspend fun listFilesByTag(
        tagId: Long,
        page: Int = 1,
        size: Int = 20,
    ): NetworkResult<PageResult<FileItem>> = apiCall {
        api.listFilesByTag(tagId, page, size)
    }
}
