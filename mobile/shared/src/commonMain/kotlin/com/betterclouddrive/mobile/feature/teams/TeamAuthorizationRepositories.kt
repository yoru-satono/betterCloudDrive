package com.betterclouddrive.mobile.feature.teams

import com.betterclouddrive.mobile.core.model.PageResult
import com.betterclouddrive.mobile.core.model.TeamEffectivePermission
import com.betterclouddrive.mobile.core.model.TeamFilePermission
import com.betterclouddrive.mobile.core.model.TeamMember
import com.betterclouddrive.mobile.core.model.TeamRole
import com.betterclouddrive.mobile.core.model.UpdateTeamFilePermissionRequest
import com.betterclouddrive.mobile.core.network.TeamMembersApi
import com.betterclouddrive.mobile.core.network.TeamPermissionsApi

interface TeamMembersRepository {
    suspend fun list(teamId: Long, page: Int, size: Int): PageResult<TeamMember>
    suspend fun add(teamId: Long, username: String, role: TeamRole): TeamMember
    suspend fun update(teamId: Long, userId: Long, role: TeamRole): TeamMember
    suspend fun remove(teamId: Long, userId: Long)
}

interface TeamPermissionsRepository {
    suspend fun effective(teamId: Long, fileId: Long): TeamEffectivePermission
    suspend fun list(teamId: Long, fileId: Long): List<TeamFilePermission>
    suspend fun upsert(teamId: Long, fileId: Long, request: UpdateTeamFilePermissionRequest): TeamFilePermission
    suspend fun delete(teamId: Long, fileId: Long, permissionId: Long)
}

class TeamMembersRepositoryImpl(private val api: TeamMembersApi) : TeamMembersRepository {
    override suspend fun list(teamId: Long, page: Int, size: Int) = api.list(teamId, page, size)
    override suspend fun add(teamId: Long, username: String, role: TeamRole) = api.add(teamId, username, role)
    override suspend fun update(teamId: Long, userId: Long, role: TeamRole) = api.update(teamId, userId, role)
    override suspend fun remove(teamId: Long, userId: Long) = api.remove(teamId, userId)
}

class TeamPermissionsRepositoryImpl(private val api: TeamPermissionsApi) : TeamPermissionsRepository {
    override suspend fun effective(teamId: Long, fileId: Long) = api.effective(teamId, fileId)
    override suspend fun list(teamId: Long, fileId: Long) = api.list(teamId, fileId)
    override suspend fun upsert(teamId: Long, fileId: Long, request: UpdateTeamFilePermissionRequest) =
        api.upsert(teamId, fileId, request)
    override suspend fun delete(teamId: Long, fileId: Long, permissionId: Long) =
        api.delete(teamId, fileId, permissionId)
}
