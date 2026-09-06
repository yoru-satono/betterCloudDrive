package com.betterclouddrive.mobile.feature.teams

import com.betterclouddrive.mobile.core.model.NotificationItem
import com.betterclouddrive.mobile.core.model.PageResult
import com.betterclouddrive.mobile.core.model.TeamInvite
import com.betterclouddrive.mobile.core.model.TeamRole
import com.betterclouddrive.mobile.core.network.NotificationsApi
import com.betterclouddrive.mobile.core.network.TeamInvitesApi

interface TeamInvitesRepository {
    suspend fun list(teamId: Long, page: Int, size: Int): PageResult<TeamInvite>
    suspend fun create(teamId: Long, email: String, role: TeamRole): TeamInvite
    suspend fun resend(teamId: Long, inviteId: Long): TeamInvite
    suspend fun cancel(teamId: Long, inviteId: Long)
    suspend fun getIncoming(token: String): TeamInvite
    suspend fun accept(token: String)
    suspend fun reject(token: String)
}

class TeamInvitesRepositoryImpl(private val api: TeamInvitesApi) : TeamInvitesRepository {
    override suspend fun list(teamId: Long, page: Int, size: Int) = api.list(teamId, page, size)
    override suspend fun create(teamId: Long, email: String, role: TeamRole) = api.create(teamId, email, role)
    override suspend fun resend(teamId: Long, inviteId: Long) = api.resend(teamId, inviteId)
    override suspend fun cancel(teamId: Long, inviteId: Long) = api.cancel(teamId, inviteId)
    override suspend fun getIncoming(token: String) = api.getIncoming(token)
    override suspend fun accept(token: String) = api.accept(token)
    override suspend fun reject(token: String) = api.reject(token)
}

interface NotificationsRepository {
    suspend fun list(unreadOnly: Boolean?, type: String?, page: Int, size: Int): PageResult<NotificationItem>
    suspend fun unreadCount(): Long
    suspend fun markRead(notificationId: Long)
    suspend fun markAllRead()
    suspend fun stream(onUnreadCount: suspend (Long) -> Unit)
}

class NotificationsRepositoryImpl(private val api: NotificationsApi) : NotificationsRepository {
    override suspend fun list(unreadOnly: Boolean?, type: String?, page: Int, size: Int) =
        api.list(unreadOnly, type, page, size)
    override suspend fun unreadCount() = api.unreadCount()
    override suspend fun markRead(notificationId: Long) = api.markRead(notificationId)
    override suspend fun markAllRead() = api.markAllRead()
    override suspend fun stream(onUnreadCount: suspend (Long) -> Unit) = api.stream(onUnreadCount)
}
