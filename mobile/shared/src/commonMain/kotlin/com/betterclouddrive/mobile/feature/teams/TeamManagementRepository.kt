package com.betterclouddrive.mobile.feature.teams

import com.betterclouddrive.mobile.core.model.TeamSummary
import com.betterclouddrive.mobile.core.network.TeamManagementApi

interface TeamManagementRepository {
    suspend fun getTeam(teamId: Long): TeamSummary
    suspend fun rename(teamId: Long, name: String): TeamSummary
    suspend fun leave(teamId: Long)
    suspend fun delete(teamId: Long)
    suspend fun transferOwner(teamId: Long, userId: Long): TeamSummary
}

class TeamManagementRepositoryImpl(private val api: TeamManagementApi) : TeamManagementRepository {
    override suspend fun getTeam(teamId: Long) = api.getTeam(teamId)
    override suspend fun rename(teamId: Long, name: String) = api.rename(teamId, name)
    override suspend fun leave(teamId: Long) = api.leave(teamId)
    override suspend fun delete(teamId: Long) = api.delete(teamId)
    override suspend fun transferOwner(teamId: Long, userId: Long) = api.transferOwner(teamId, userId)
}
