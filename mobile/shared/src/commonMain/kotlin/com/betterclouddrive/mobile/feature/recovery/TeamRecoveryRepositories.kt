package com.betterclouddrive.mobile.feature.recovery

import com.betterclouddrive.mobile.core.model.FileItem
import com.betterclouddrive.mobile.core.model.PageResult
import com.betterclouddrive.mobile.core.model.PersonalFileVersion
import com.betterclouddrive.mobile.core.model.RecycleBinItem
import com.betterclouddrive.mobile.core.network.TeamRecoveryApi
import com.betterclouddrive.mobile.core.network.TeamWorkspaceApi

interface TeamRecycleBinRepository {
    suspend fun list(teamId: Long, query: String?, page: Int, size: Int): PageResult<RecycleBinItem>
    suspend fun restore(teamId: Long, fileId: Long)
    suspend fun permanentlyDelete(teamId: Long, fileId: Long)
    suspend fun empty(teamId: Long)
}

interface TeamVersionsRepository {
    suspend fun file(teamId: Long, fileId: Long): FileItem
    suspend fun list(teamId: Long, fileId: Long): List<PersonalFileVersion>
    suspend fun restore(teamId: Long, fileId: Long, versionNumber: Int): FileItem
    suspend fun delete(teamId: Long, fileId: Long, versionNumber: Int)
}

class TeamRecycleBinRepositoryImpl(private val api: TeamRecoveryApi) : TeamRecycleBinRepository {
    override suspend fun list(teamId: Long, query: String?, page: Int, size: Int) =
        api.listRecycleBin(teamId, query, page, size)
    override suspend fun restore(teamId: Long, fileId: Long) = api.restoreRecycleItem(teamId, fileId)
    override suspend fun permanentlyDelete(teamId: Long, fileId: Long) = api.permanentlyDeleteRecycleItem(teamId, fileId)
    override suspend fun empty(teamId: Long) = api.emptyRecycleBin(teamId)
}

class TeamVersionsRepositoryImpl(
    private val workspace: TeamWorkspaceApi,
    private val api: TeamRecoveryApi,
) : TeamVersionsRepository {
    override suspend fun file(teamId: Long, fileId: Long) = workspace.getFile(teamId, fileId)
    override suspend fun list(teamId: Long, fileId: Long) = api.listVersions(teamId, fileId)
    override suspend fun restore(teamId: Long, fileId: Long, versionNumber: Int) =
        api.restoreVersion(teamId, fileId, versionNumber)
    override suspend fun delete(teamId: Long, fileId: Long, versionNumber: Int) =
        api.deleteVersion(teamId, fileId, versionNumber)
}
