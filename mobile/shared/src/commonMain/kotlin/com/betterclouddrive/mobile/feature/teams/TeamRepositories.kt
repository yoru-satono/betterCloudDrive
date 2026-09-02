package com.betterclouddrive.mobile.feature.teams

import com.betterclouddrive.mobile.core.model.FileItem
import com.betterclouddrive.mobile.core.model.OfficeConfig
import com.betterclouddrive.mobile.core.model.PageResult
import com.betterclouddrive.mobile.core.model.TeamSummary
import com.betterclouddrive.mobile.core.model.TextPreview
import com.betterclouddrive.mobile.core.network.TeamPreviewApi
import com.betterclouddrive.mobile.core.network.TeamWorkspaceApi
import com.betterclouddrive.mobile.feature.files.decodePersonalTextPreview

interface TeamWorkspaceRepository {
    suspend fun listTeams(page: Int, size: Int): PageResult<TeamSummary>
    suspend fun createTeam(name: String): TeamSummary
    suspend fun getTeam(teamId: Long): TeamSummary
    suspend fun listFiles(
        teamId: Long, parentId: Long?, page: Int, size: Int, sortBy: String, order: String,
    ): PageResult<FileItem>
    suspend fun getFile(teamId: Long, fileId: Long): FileItem
    suspend fun search(teamId: Long, query: String, page: Int, size: Int): PageResult<FileItem>
}

interface TeamPreviewRepository {
    suspend fun getFile(teamId: Long, fileId: Long): FileItem
    suspend fun createTicket(teamId: Long, fileId: Long): String
    suspend fun loadText(teamId: Long, file: FileItem): TextPreview
    suspend fun loadOffice(teamId: Long, fileId: Long): OfficeConfig
}

class TeamWorkspaceRepositoryImpl(private val api: TeamWorkspaceApi) : TeamWorkspaceRepository {
    override suspend fun listTeams(page: Int, size: Int) = api.listTeams(page, size)
    override suspend fun createTeam(name: String) = api.createTeam(name)
    override suspend fun getTeam(teamId: Long) = api.getTeam(teamId)
    override suspend fun listFiles(
        teamId: Long, parentId: Long?, page: Int, size: Int, sortBy: String, order: String,
    ) = api.listFiles(teamId, parentId, page, size, sortBy, order)
    override suspend fun getFile(teamId: Long, fileId: Long) = api.getFile(teamId, fileId)
    override suspend fun search(teamId: Long, query: String, page: Int, size: Int) =
        api.search(teamId, query, page, size)
}

class TeamPreviewRepositoryImpl(
    private val workspace: TeamWorkspaceApi,
    private val preview: TeamPreviewApi,
) : TeamPreviewRepository {
    override suspend fun getFile(teamId: Long, fileId: Long) = workspace.getFile(teamId, fileId)
    override suspend fun createTicket(teamId: Long, fileId: Long) = preview.ticket(teamId, fileId)
    override suspend fun loadOffice(teamId: Long, fileId: Long) = preview.office(teamId, fileId)
    override suspend fun loadText(teamId: Long, file: FileItem): TextPreview {
        if (file.fileSize == 0L) return TextPreview("", false)
        val bytes = preview.text(teamId, file.id, MAX_TEXT_PREVIEW_BYTES)
        return decodePersonalTextPreview(bytes, file.fileSize)
    }

    private companion object {
        const val MAX_TEXT_PREVIEW_BYTES = 512 * 1024
    }
}
