package com.betterclouddrive.mobile.feature.shares

import com.betterclouddrive.mobile.core.model.CreateShareRequest
import com.betterclouddrive.mobile.core.model.PageResult
import com.betterclouddrive.mobile.core.model.ShareExpirationFilter
import com.betterclouddrive.mobile.core.model.ShareLink
import com.betterclouddrive.mobile.core.model.UpdateShareRequest
import com.betterclouddrive.mobile.core.network.TeamSharesApi

interface TeamSharesRepository {
    suspend fun create(teamId: Long, request: CreateShareRequest): ShareLink
    suspend fun list(
        teamId: Long,
        query: String?,
        passwordProtected: Boolean?,
        expiration: ShareExpirationFilter,
        page: Int,
        size: Int,
    ): PageResult<ShareLink>
    suspend fun get(teamId: Long, shareId: Long): ShareLink
    suspend fun password(teamId: Long, shareId: Long): String?
    suspend fun update(teamId: Long, shareId: Long, request: UpdateShareRequest): ShareLink
    suspend fun cancel(teamId: Long, shareId: Long)
}

class TeamSharesRepositoryImpl(private val api: TeamSharesApi) : TeamSharesRepository {
    override suspend fun create(teamId: Long, request: CreateShareRequest) = api.create(teamId, request)
    override suspend fun list(
        teamId: Long,
        query: String?,
        passwordProtected: Boolean?,
        expiration: ShareExpirationFilter,
        page: Int,
        size: Int,
    ) = api.list(teamId, query, passwordProtected, expiration, page, size)
    override suspend fun get(teamId: Long, shareId: Long) = api.get(teamId, shareId)
    override suspend fun password(teamId: Long, shareId: Long) = api.password(teamId, shareId)
    override suspend fun update(teamId: Long, shareId: Long, request: UpdateShareRequest) =
        api.update(teamId, shareId, request)
    override suspend fun cancel(teamId: Long, shareId: Long) = api.cancel(teamId, shareId)
}
