package com.betterclouddrive.mobile.feature.teams

import com.betterclouddrive.mobile.core.model.FileItem
import com.betterclouddrive.mobile.core.model.PageResult
import com.betterclouddrive.mobile.core.model.TeamTag
import com.betterclouddrive.mobile.core.network.TeamOrganizationApi

interface TeamOrganizationRepository {
    suspend fun listFavorites(teamId: Long, query: String?, page: Int, size: Int): PageResult<FileItem>
    suspend fun favoriteStatus(teamId: Long, fileId: Long): Boolean
    suspend fun addFavorite(teamId: Long, fileId: Long)
    suspend fun removeFavorite(teamId: Long, fileId: Long)
    suspend fun listTags(teamId: Long, query: String? = null): List<TeamTag>
    suspend fun createTag(teamId: Long, name: String, color: String): TeamTag
    suspend fun updateTag(teamId: Long, tagId: Long, name: String, color: String): TeamTag
    suspend fun deleteTag(teamId: Long, tagId: Long)
    suspend fun filesByTag(teamId: Long, tagId: Long, query: String?, page: Int, size: Int): PageResult<FileItem>
    suspend fun fileTags(teamId: Long, fileId: Long): List<TeamTag>
    suspend fun replaceFileTags(teamId: Long, fileId: Long, tagIds: List<Long>): List<TeamTag>
    suspend fun removeFileTag(teamId: Long, tagId: Long, fileId: Long)
}

class TeamOrganizationRepositoryImpl(private val api: TeamOrganizationApi) : TeamOrganizationRepository {
    override suspend fun listFavorites(teamId: Long, query: String?, page: Int, size: Int) =
        api.listFavorites(teamId, query, page, size)
    override suspend fun favoriteStatus(teamId: Long, fileId: Long) = api.favoriteStatus(teamId, fileId)
    override suspend fun addFavorite(teamId: Long, fileId: Long) = api.addFavorite(teamId, fileId)
    override suspend fun removeFavorite(teamId: Long, fileId: Long) = api.removeFavorite(teamId, fileId)
    override suspend fun listTags(teamId: Long, query: String?) = api.listTags(teamId, query)
    override suspend fun createTag(teamId: Long, name: String, color: String) = api.createTag(teamId, name, color)
    override suspend fun updateTag(teamId: Long, tagId: Long, name: String, color: String) =
        api.updateTag(teamId, tagId, name, color)
    override suspend fun deleteTag(teamId: Long, tagId: Long) = api.deleteTag(teamId, tagId)
    override suspend fun filesByTag(teamId: Long, tagId: Long, query: String?, page: Int, size: Int) =
        api.filesByTag(teamId, tagId, query, page, size)
    override suspend fun fileTags(teamId: Long, fileId: Long) = api.fileTags(teamId, fileId)
    override suspend fun replaceFileTags(teamId: Long, fileId: Long, tagIds: List<Long>) =
        api.replaceFileTags(teamId, fileId, tagIds)
    override suspend fun removeFileTag(teamId: Long, tagId: Long, fileId: Long) =
        api.removeFileTag(teamId, tagId, fileId)
}
