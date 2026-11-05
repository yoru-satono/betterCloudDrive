package com.betterclouddrive.mobile.core.network

import com.betterclouddrive.mobile.core.model.*
import com.betterclouddrive.mobile.feature.transfers.TransferScope
import io.ktor.client.request.delete
import io.ktor.client.request.forms.InputProvider
import io.ktor.client.request.forms.MultiPartFormDataContent
import io.ktor.client.request.forms.formData
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.parameter
import io.ktor.client.request.setBody
import io.ktor.client.plugins.sse.sse
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.collect
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

class AuthApi(private val http: AuthenticatedHttpClient) {
    val sessionInvalidations = http.sessionInvalidations
    val serverBaseUrl get() = http.serverBaseUrl
    fun normalizeServer(input: String) = http.normalizeServer(input)
    suspend fun updateServer(input: String) = http.updateServer(input)

    suspend fun login(username: String, password: String): UserProfile = http.run {
        val tokens: AuthTokens = publicEnvelope { client ->
            client.post(url("auth/login")) { jsonBody(LoginRequest(username, password)) }
        }
        saveTokens(tokens)
        try {
            getMe()
        } catch (cancelled: CancellationException) {
            clearSessionSilently(); throw cancelled
        } catch (error: Throwable) {
            clearSessionSilently(); throw error
        }
    }

    suspend fun restoreUser(): SessionRestoreResult {
        val restored = http.initialize() ?: return SessionRestoreResult.NoSession
        return try {
            http.ensureSession()
            SessionRestoreResult.Restored(getMe())
        } catch (_: SessionExpiredException) {
            SessionRestoreResult.Invalidated(SessionInvalidationReason.EXPIRED)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            SessionRestoreResult.RetryableFailure()
        }
    }

    suspend fun getMe(): UserProfile = http.run {
        authenticatedEnvelope { client, token -> client.get(url("auth/me")) { bearer(token) } }
    }

    suspend fun updatePreferredLocale(locale: String): UserProfile = http.run {
        authenticatedEnvelope { client, token ->
            client.put(url("auth/preferences/locale")) { bearer(token); jsonBody(UpdateLocaleRequest(locale)) }
        }
    }

    suspend fun logout() {
        try {
            http.run { authenticatedNoData { client, token -> client.post(url("auth/logout")) { bearer(token) } } }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            // Local logout remains authoritative.
        } finally {
            http.clearSession(SessionInvalidationReason.LOGOUT)
        }
    }

    suspend fun sendRegistrationCode(email: String, preferredLocale: String) = http.run {
        publicNoData { client ->
            client.post(url("auth/register-code/send")) { jsonBody(RegisterCodeRequest(email, preferredLocale)) }
        }
    }

    suspend fun register(
        username: String,
        password: String,
        email: String,
        verificationCode: String,
        preferredLocale: String,
    ): RegistrationResult = http.run {
        publicEnvelope { client ->
            client.post(url("auth/register")) {
                jsonBody(RegisterRequest(username, password, email, verificationCode, preferredLocale))
            }
        }
    }

    suspend fun forgotPassword(email: String) = http.run {
        publicNoData { client -> client.post(url("auth/forgot-password")) { jsonBody(ForgotPasswordRequest(email)) } }
    }

    suspend fun resetPassword(email: String, code: String, newPassword: String) = http.run {
        publicNoData { client ->
            client.post(url("auth/reset-password")) { jsonBody(ResetPasswordRequest(email, code, newPassword)) }
        }
    }
}

class PersonalFilesApi(private val http: AuthenticatedHttpClient) {
    suspend fun list(parentId: Long?, page: Int, size: Int, sortBy: String, order: String): PageResult<FileItem> = http.run {
        authenticatedEnvelope { client, token -> client.get(url("files")) {
            bearer(token); url {
                parentId?.let { parameters.append("parentId", it.toString()) }
                parameters.append("page", page.toString()); parameters.append("size", size.toString())
                parameters.append("sortBy", sortBy); parameters.append("order", order)
            }
        } }
    }

    suspend fun get(fileId: Long): FileItem = http.run {
        authenticatedEnvelope { client, token -> client.get(url("files/$fileId")) { bearer(token) } }
    }

    suspend fun search(query: String, page: Int, size: Int): PageResult<FileItem> = http.run {
        authenticatedEnvelope { client, token -> client.get(url("files/search")) {
            bearer(token); url {
                parameters.append("q", query); parameters.append("page", page.toString()); parameters.append("size", size.toString())
            }
        } }
    }

    suspend fun createFolder(parentId: Long?, name: String): FileItem = http.run {
        authenticatedEnvelope { client, token -> client.post(url("files/folder")) {
            bearer(token); jsonBody(CreatePersonalFolderRequest(parentId, name))
        } }
    }

    suspend fun rename(fileId: Long, name: String): FileItem = http.run {
        authenticatedEnvelope { client, token -> client.put(url("files/$fileId")) {
            bearer(token); jsonBody(RenamePersonalFileRequest(name))
        } }
    }

    suspend fun move(fileId: Long, parentId: Long?) = destination("files/$fileId/move", parentId)
    suspend fun copy(fileId: Long, parentId: Long?) = destination("files/$fileId/copy", parentId)

    private suspend fun destination(path: String, parentId: Long?) = http.run {
        authenticatedNoData { client, token -> client.post(url(path)) {
            bearer(token); jsonBody(PersonalFileDestinationRequest(parentId))
        } }
    }

    suspend fun delete(fileIds: List<Long>) = http.run {
        authenticatedNoData { client, token -> client.delete(url("files")) {
            bearer(token); jsonBody(DeletePersonalFilesRequest(fileIds.distinct()))
        } }
    }
}

class TeamWorkspaceApi(private val http: AuthenticatedHttpClient) {
    suspend fun listTeams(page: Int, size: Int): PageResult<TeamSummary> = http.run {
        authenticatedEnvelope { client, token -> client.get(url("teams")) {
            bearer(token); url {
                parameters.append("page", page.toString())
                parameters.append("size", size.toString())
            }
        } }
    }

    suspend fun createTeam(name: String): TeamSummary = http.run {
        authenticatedEnvelope { client, token -> client.post(url("teams")) {
            bearer(token); jsonBody(CreateTeamRequest(name))
        } }
    }

    suspend fun getTeam(teamId: Long): TeamSummary = http.run {
        authenticatedEnvelope { client, token -> client.get(url("teams/$teamId")) { bearer(token) } }
    }

    suspend fun listFiles(
        teamId: Long, parentId: Long?, page: Int, size: Int, sortBy: String, order: String,
    ): PageResult<FileItem> = http.run {
        authenticatedEnvelope { client, token -> client.get(url("teams/$teamId/files")) {
            bearer(token); url {
                parentId?.let { parameters.append("parentId", it.toString()) }
                parameters.append("page", page.toString())
                parameters.append("size", size.toString())
                parameters.append("sortBy", sortBy)
                parameters.append("order", order)
            }
        } }
    }

    suspend fun getFile(teamId: Long, fileId: Long): FileItem = http.run {
        authenticatedEnvelope { client, token -> client.get(url("teams/$teamId/files/$fileId")) { bearer(token) } }
    }

    suspend fun search(teamId: Long, query: String, page: Int, size: Int): PageResult<FileItem> = http.run {
        authenticatedEnvelope { client, token -> client.get(url("teams/$teamId/files/search")) {
            bearer(token); url {
                parameters.append("q", query.trim())
                parameters.append("page", page.toString())
                parameters.append("size", size.toString())
            }
        } }
    }

    suspend fun createFolder(teamId: Long, parentId: Long?, name: String): FileItem = http.run {
        authenticatedEnvelope { client, token -> client.post(url("teams/$teamId/files/folder")) {
            bearer(token); jsonBody(CreateTeamFolderRequest(parentId, name))
        } }
    }

    suspend fun rename(teamId: Long, fileId: Long, name: String): FileItem = http.run {
        authenticatedEnvelope { client, token -> client.put(url("teams/$teamId/files/$fileId")) {
            bearer(token); jsonBody(RenameTeamFileRequest(name))
        } }
    }

    suspend fun move(teamId: Long, fileId: Long, parentId: Long?) = http.run {
        authenticatedNoData { client, token -> client.post(url("teams/$teamId/files/$fileId/move")) {
            bearer(token); jsonBody(MoveTeamFileRequest(parentId))
        } }
    }

    suspend fun delete(teamId: Long, fileIds: List<Long>) = http.run {
        authenticatedNoData { client, token -> client.delete(url("teams/$teamId/files")) {
            bearer(token); jsonBody(DeleteTeamFilesRequest(fileIds.distinct()))
        } }
    }
}

class TeamManagementApi(private val http: AuthenticatedHttpClient) {
    suspend fun getTeam(teamId: Long): TeamSummary = http.run {
        authenticatedEnvelope { client, token -> client.get(url("teams/$teamId")) { bearer(token) } }
    }

    suspend fun rename(teamId: Long, name: String): TeamSummary = http.run {
        authenticatedEnvelope { client, token -> client.put(url("teams/$teamId")) {
            bearer(token); jsonBody(UpdateTeamRequest(name))
        } }
    }

    suspend fun leave(teamId: Long) = http.run {
        authenticatedNoData { client, token -> client.post(url("teams/$teamId/leave")) { bearer(token) } }
    }

    suspend fun delete(teamId: Long) = http.run {
        authenticatedNoData { client, token -> client.delete(url("teams/$teamId")) { bearer(token) } }
    }

    suspend fun transferOwner(teamId: Long, userId: Long): TeamSummary = http.run {
        authenticatedEnvelope { client, token -> client.post(url("teams/$teamId/owner")) {
            bearer(token); jsonBody(TransferTeamOwnerRequest(userId))
        } }
    }
}

class TeamAuditApi(private val http: AuthenticatedHttpClient) {
    suspend fun list(
        teamId: Long,
        actorUserId: Long?,
        action: String?,
        targetType: String?,
        result: Int?,
        startAt: String?,
        endAt: String?,
        page: Int,
        size: Int,
    ): PageResult<TeamOperationLog> = http.run {
        authenticatedEnvelope { client, token -> client.get(url("teams/$teamId/audit-logs")) {
            bearer(token); url {
                actorUserId?.let { parameters.append("actorUserId", it.toString()) }
                action?.takeIf(String::isNotBlank)?.let { parameters.append("action", it) }
                targetType?.takeIf(String::isNotBlank)?.let { parameters.append("targetType", it) }
                result?.let { parameters.append("result", it.toString()) }
                startAt?.takeIf(String::isNotBlank)?.let { parameters.append("startAt", it) }
                endAt?.takeIf(String::isNotBlank)?.let { parameters.append("endAt", it) }
                parameters.append("page", page.toString())
                parameters.append("size", size.toString())
            }
        } }
    }

    suspend fun queueExport(
        teamId: Long,
        actorUserId: Long?,
        action: String?,
        targetType: String?,
        result: Int?,
        startAt: String?,
        endAt: String?,
    ): TeamTask = http.run {
        authenticatedEnvelope { client, token -> client.post(url("teams/$teamId/audit-logs/export-tasks")) {
            bearer(token); url {
                actorUserId?.let { parameters.append("actorUserId", it.toString()) }
                action?.takeIf(String::isNotBlank)?.let { parameters.append("action", it) }
                targetType?.takeIf(String::isNotBlank)?.let { parameters.append("targetType", it) }
                result?.let { parameters.append("result", it.toString()) }
                startAt?.takeIf(String::isNotBlank)?.let { parameters.append("startAt", it) }
                endAt?.takeIf(String::isNotBlank)?.let { parameters.append("endAt", it) }
            }
        } }
    }
}

class TeamTasksApi(private val http: AuthenticatedHttpClient) {
    suspend fun list(teamId: Long, status: String?, type: String?, page: Int, size: Int): PageResult<TeamTask> = http.run {
        authenticatedEnvelope { client, token -> client.get(url("teams/$teamId/tasks")) {
            bearer(token); url {
                status?.takeIf(String::isNotBlank)?.let { parameters.append("status", it) }
                type?.takeIf(String::isNotBlank)?.let { parameters.append("type", it) }
                parameters.append("page", page.toString())
                parameters.append("size", size.toString())
            }
        } }
    }

    suspend fun retry(teamId: Long, taskId: Long): TeamTask = http.run {
        authenticatedEnvelope { client, token ->
            client.post(url("teams/$teamId/tasks/$taskId/retry")) { bearer(token) }
        }
    }

    suspend fun <T> download(
        teamId: Long,
        taskId: Long,
        consume: suspend (HttpStatusCode, Headers, ByteReadChannel) -> T,
    ): T = http.authenticatedStream("teams/$teamId/tasks/$taskId/download", null, consume)
}

class TeamOrganizationApi(private val http: AuthenticatedHttpClient) {
    suspend fun listFavorites(teamId: Long, query: String?, page: Int, size: Int): PageResult<FileItem> = http.run {
        authenticatedEnvelope { client, token -> client.get(url("teams/$teamId/favorites")) {
            bearer(token); url {
                query?.trim()?.takeIf(String::isNotEmpty)?.let { parameters.append("q", it) }
                parameters.append("page", page.toString()); parameters.append("size", size.toString())
            }
        } }
    }
    suspend fun favoriteStatus(teamId: Long, fileId: Long): Boolean = http.run {
        authenticatedEnvelope { client, token -> client.get(url("teams/$teamId/favorites/$fileId/status")) { bearer(token) } }
    }
    suspend fun addFavorite(teamId: Long, fileId: Long) = http.run {
        authenticatedNoData { client, token -> client.post(url("teams/$teamId/favorites/$fileId")) { bearer(token) } }
    }
    suspend fun removeFavorite(teamId: Long, fileId: Long) = http.run {
        authenticatedNoData { client, token -> client.delete(url("teams/$teamId/favorites/$fileId")) { bearer(token) } }
    }
    suspend fun listTags(teamId: Long, query: String?): List<TeamTag> = http.run {
        authenticatedEnvelope { client, token -> client.get(url("teams/$teamId/tags")) {
            bearer(token); url { query?.trim()?.takeIf(String::isNotEmpty)?.let { parameters.append("q", it) } }
        } }
    }
    suspend fun createTag(teamId: Long, name: String, color: String): TeamTag = http.run {
        authenticatedEnvelope { client, token -> client.post(url("teams/$teamId/tags")) {
            bearer(token); jsonBody(CreateTeamTagRequest(name, color))
        } }
    }
    suspend fun updateTag(teamId: Long, tagId: Long, name: String, color: String): TeamTag = http.run {
        authenticatedEnvelope { client, token -> client.put(url("teams/$teamId/tags/$tagId")) {
            bearer(token); jsonBody(UpdateTeamTagRequest(name, color))
        } }
    }
    suspend fun deleteTag(teamId: Long, tagId: Long) = http.run {
        authenticatedNoData { client, token -> client.delete(url("teams/$teamId/tags/$tagId")) { bearer(token) } }
    }
    suspend fun filesByTag(teamId: Long, tagId: Long, query: String?, page: Int, size: Int): PageResult<FileItem> = http.run {
        authenticatedEnvelope { client, token -> client.get(url("teams/$teamId/tags/$tagId/files")) {
            bearer(token); url {
                query?.trim()?.takeIf(String::isNotEmpty)?.let { parameters.append("q", it) }
                parameters.append("page", page.toString()); parameters.append("size", size.toString())
            }
        } }
    }
    suspend fun fileTags(teamId: Long, fileId: Long): List<TeamTag> = http.run {
        authenticatedEnvelope { client, token -> client.get(url("teams/$teamId/files/$fileId/tags")) { bearer(token) } }
    }
    suspend fun replaceFileTags(teamId: Long, fileId: Long, ids: List<Long>): List<TeamTag> = http.run {
        authenticatedEnvelope { client, token -> client.put(url("teams/$teamId/files/$fileId/tags")) {
            bearer(token); jsonBody(ReplaceTeamFileTagsRequest(ids.distinct()))
        } }
    }
    suspend fun removeFileTag(teamId: Long, tagId: Long, fileId: Long) = http.run {
        authenticatedNoData { client, token -> client.delete(url("teams/$teamId/tags/$tagId/files/$fileId")) { bearer(token) } }
    }
}

class TeamMembersApi(private val http: AuthenticatedHttpClient) {
    suspend fun list(teamId: Long, page: Int, size: Int): PageResult<TeamMember> = http.run {
        authenticatedEnvelope { client, token -> client.get(url("teams/$teamId/members")) {
            bearer(token); url {
                parameters.append("page", page.toString())
                parameters.append("size", size.toString())
            }
        } }
    }

    suspend fun add(teamId: Long, username: String, role: TeamRole): TeamMember = http.run {
        authenticatedEnvelope { client, token -> client.post(url("teams/$teamId/members")) {
            bearer(token); jsonBody(AddTeamMemberRequest(username, role))
        } }
    }

    suspend fun update(teamId: Long, userId: Long, role: TeamRole): TeamMember = http.run {
        authenticatedEnvelope { client, token -> client.put(url("teams/$teamId/members/$userId")) {
            bearer(token); jsonBody(UpdateTeamMemberRequest(role))
        } }
    }

    suspend fun remove(teamId: Long, userId: Long) = http.run {
        authenticatedNoData { client, token -> client.delete(url("teams/$teamId/members/$userId")) { bearer(token) } }
    }
}

class TeamInvitesApi(private val http: AuthenticatedHttpClient) {
    suspend fun list(teamId: Long, page: Int, size: Int): PageResult<TeamInvite> = http.run {
        authenticatedEnvelope { client, token -> client.get(url("teams/$teamId/invites")) {
            bearer(token)
            url {
                parameters.append("page", page.toString())
                parameters.append("size", size.toString())
            }
        } }
    }

    suspend fun create(teamId: Long, email: String, role: TeamRole): TeamInvite = http.run {
        authenticatedEnvelope { client, token -> client.post(url("teams/$teamId/invites")) {
            bearer(token); jsonBody(CreateTeamInviteRequest(email, role))
        } }
    }

    suspend fun resend(teamId: Long, inviteId: Long): TeamInvite = http.run {
        authenticatedEnvelope { client, token -> client.post(url("teams/$teamId/invites/$inviteId/resend")) { bearer(token) } }
    }

    suspend fun cancel(teamId: Long, inviteId: Long) = http.run {
        authenticatedNoData { client, token -> client.delete(url("teams/$teamId/invites/$inviteId")) { bearer(token) } }
    }

    suspend fun getIncoming(token: String): TeamInvite = http.run {
        publicEnvelope { client -> client.get(url("team-invites/$token")) }
    }

    suspend fun accept(token: String) = http.run {
        authenticatedNoData { client, accessToken -> client.post(url("team-invites/$token/accept")) { bearer(accessToken) } }
    }

    suspend fun reject(token: String) = http.run {
        authenticatedNoData { client, accessToken -> client.post(url("team-invites/$token/reject")) { bearer(accessToken) } }
    }
}

class NotificationsApi(private val http: AuthenticatedHttpClient) {
    suspend fun list(unreadOnly: Boolean?, type: String?, page: Int, size: Int): PageResult<NotificationItem> = http.run {
        authenticatedEnvelope { client, token -> client.get(url("notifications")) {
            bearer(token)
            url {
                unreadOnly?.let { parameters.append("unreadOnly", it.toString()) }
                type?.takeIf(String::isNotBlank)?.let { parameters.append("type", it) }
                parameters.append("page", page.toString())
                parameters.append("size", size.toString())
            }
        } }
    }

    suspend fun unreadCount(): Long = http.run {
        authenticatedEnvelope { client, token -> client.get(url("notifications/unread-count")) { bearer(token) } }
    }

    suspend fun markRead(notificationId: Long) = http.run {
        authenticatedNoData { client, token -> client.post(url("notifications/$notificationId/read")) { bearer(token) } }
    }

    suspend fun markAllRead() = http.run {
        authenticatedNoData { client, token -> client.post(url("notifications/read-all")) { bearer(token) } }
    }

    suspend fun stream(onUnreadCount: suspend (Long) -> Unit) {
        val ticket: NotificationStreamTicket = http.run {
            authenticatedEnvelope { client, token -> client.post(url("notifications/stream-ticket")) { bearer(token) } }
        }
        http.run {
            client.sse(
                urlString = url("notifications/stream"),
                request = { parameter("ticket", ticket.ticket) },
            ) {
                incoming.collect { event ->
                    val data = event.data ?: return@collect
                    val unread = runCatching {
                        Json.parseToJsonElement(data).jsonObject["unreadCount"]?.jsonPrimitive?.longOrNull
                    }.getOrNull()
                    if (unread != null) onUnreadCount(unread)
                }
            }
        }
    }
}

class TeamPermissionsApi(private val http: AuthenticatedHttpClient) {
    suspend fun effective(teamId: Long, fileId: Long): TeamEffectivePermission = http.run {
        authenticatedEnvelope { client, token ->
            client.get(url("teams/$teamId/files/$fileId/effective-permission")) { bearer(token) }
        }
    }

    suspend fun list(teamId: Long, fileId: Long): List<TeamFilePermission> = http.run {
        authenticatedEnvelope { client, token ->
            client.get(url("teams/$teamId/files/$fileId/permissions")) { bearer(token) }
        }
    }

    suspend fun upsert(
        teamId: Long, fileId: Long, request: UpdateTeamFilePermissionRequest,
    ): TeamFilePermission = http.run {
        authenticatedEnvelope { client, token -> client.put(url("teams/$teamId/files/$fileId/permissions")) {
            bearer(token); jsonBody(request)
        } }
    }

    suspend fun delete(teamId: Long, fileId: Long, permissionId: Long) = http.run {
        authenticatedNoData { client, token ->
            client.delete(url("teams/$teamId/files/$fileId/permissions/$permissionId")) { bearer(token) }
        }
    }
}

class TeamPreviewApi(private val http: AuthenticatedHttpClient) {
    suspend fun ticket(teamId: Long, fileId: Long): String = http.run {
        val response: DownloadTicketResponse = authenticatedEnvelope { client, token ->
            client.post(url("teams/$teamId/download/$fileId/ticket")) { bearer(token) }
        }
        when {
            response.url.startsWith("http://") || response.url.startsWith("https://") -> response.url
            response.url.startsWith("/") -> serverBaseUrl.trimEnd('/') + response.url
            else -> ServerAddress.apiUrl(serverBaseUrl, response.url, allowCleartextServer)
        }
    }

    suspend fun office(teamId: Long, fileId: Long): OfficeConfig = http.run {
        authenticatedEnvelope { client, token -> client.get(url("teams/$teamId/office/files/$fileId/config")) {
            bearer(token); url { parameters.append("mobile", "true") }
        } }
    }

    suspend fun text(teamId: Long, fileId: Long, maxBytes: Int): ByteArray {
        require(maxBytes > 0) { "preview.invalidTextLimit" }
        return http.authenticatedStream(
            "teams/$teamId/download/$fileId", "bytes=0-${maxBytes - 1}",
        ) { status, headers, channel ->
            if (status == HttpStatusCode.RequestedRangeNotSatisfiable &&
                headers[HttpHeaders.ContentRange]?.endsWith("/0") == true
            ) return@authenticatedStream ByteArray(0)
            if (status != HttpStatusCode.OK && status != HttpStatusCode.PartialContent) {
                throw ApiException(status.value, "error.previewFailed")
            }
            val output = ByteArray(maxBytes)
            var offset = 0
            while (offset < output.size) {
                val count = channel.readAvailable(output, offset, output.size - offset)
                if (count < 0) break
                offset += count
            }
            output.copyOf(offset)
        }
    }
}

class PersonalOrganizationApi(private val http: AuthenticatedHttpClient) {
    suspend fun listFavorites(query: String?, page: Int, size: Int): PageResult<FileItem> = http.run {
        authenticatedEnvelope { client, token -> client.get(url("favorites")) {
            bearer(token); url {
                query?.trim()?.takeIf(String::isNotEmpty)?.let { parameters.append("q", it) }
                parameters.append("page", page.toString()); parameters.append("size", size.toString())
            }
        } }
    }
    suspend fun favoriteStatus(fileId: Long): Boolean = http.run {
        authenticatedEnvelope { client, token -> client.get(url("favorites/$fileId/status")) { bearer(token) } }
    }
    suspend fun addFavorite(fileId: Long) = http.run {
        authenticatedNoData { client, token -> client.post(url("favorites/$fileId")) { bearer(token) } }
    }
    suspend fun removeFavorite(fileId: Long) = http.run {
        authenticatedNoData { client, token -> client.delete(url("favorites/$fileId")) { bearer(token) } }
    }
    suspend fun listTags(query: String?): List<PersonalTag> = http.run {
        authenticatedEnvelope { client, token -> client.get(url("tags")) {
            bearer(token); url { query?.trim()?.takeIf(String::isNotEmpty)?.let { parameters.append("q", it) } }
        } }
    }
    suspend fun createTag(name: String, color: String): PersonalTag = http.run {
        authenticatedEnvelope { client, token -> client.post(url("tags")) { bearer(token); jsonBody(CreatePersonalTagRequest(name, color)) } }
    }
    suspend fun updateTag(id: Long, name: String, color: String): PersonalTag = http.run {
        authenticatedEnvelope { client, token -> client.put(url("tags/$id")) { bearer(token); jsonBody(UpdatePersonalTagRequest(name, color)) } }
    }
    suspend fun deleteTag(id: Long) = http.run {
        authenticatedNoData { client, token -> client.delete(url("tags/$id")) { bearer(token) } }
    }
    suspend fun filesByTag(id: Long, query: String?, page: Int, size: Int): PageResult<FileItem> = http.run {
        authenticatedEnvelope { client, token -> client.get(url("tags/$id/files")) {
            bearer(token); url {
                query?.trim()?.takeIf(String::isNotEmpty)?.let { parameters.append("q", it) }
                parameters.append("page", page.toString()); parameters.append("size", size.toString())
            }
        } }
    }
    suspend fun fileTags(fileId: Long): List<PersonalTag> = http.run {
        authenticatedEnvelope { client, token -> client.get(url("files/$fileId/tags")) { bearer(token) } }
    }
    suspend fun replaceFileTags(fileId: Long, ids: List<Long>): List<PersonalTag> = http.run {
        authenticatedEnvelope { client, token -> client.put(url("files/$fileId/tags")) {
            bearer(token); jsonBody(ReplacePersonalFileTagsRequest(ids.distinct()))
        } }
    }
    suspend fun removeFileTag(tagId: Long, fileId: Long) = http.run {
        authenticatedNoData { client, token -> client.delete(url("tags/$tagId/files/$fileId")) { bearer(token) } }
    }
}

class PersonalSharesApi(private val http: AuthenticatedHttpClient) {
    suspend fun create(request: CreatePersonalShareRequest): ShareLink = http.run {
        authenticatedEnvelope { client, token -> client.post(url("shares")) { bearer(token); jsonBody(request) } }
    }
    suspend fun list(
        query: String?, passwordProtected: Boolean?, expiration: ShareExpirationFilter, page: Int, size: Int,
    ): PageResult<ShareLink> = http.run {
        authenticatedEnvelope { client, token -> client.get(url("shares")) {
            bearer(token); url {
                query?.trim()?.takeIf(String::isNotEmpty)?.let { parameters.append("q", it) }
                passwordProtected?.let { parameters.append("passwordProtected", it.toString()) }
                expiration.apiValue?.let { parameters.append("expirationStatus", it) }
                parameters.append("page", page.toString()); parameters.append("size", size.toString())
            }
        } }
    }
    suspend fun get(id: Long): ShareLink = http.run {
        authenticatedEnvelope { client, token -> client.get(url("shares/$id")) { bearer(token) } }
    }
    suspend fun password(id: Long): String? = http.run {
        authenticatedEnvelope<SharePasswordResponse> { client, token -> client.get(url("shares/$id/password")) { bearer(token) } }.password
    }
    suspend fun update(id: Long, request: UpdatePersonalShareRequest): ShareLink = http.run {
        authenticatedEnvelope { client, token -> client.put(url("shares/$id")) { bearer(token); jsonBody(request) } }
    }
    suspend fun cancel(id: Long) = http.run {
        authenticatedNoData { client, token -> client.delete(url("shares/$id")) { bearer(token) } }
    }
}

class TeamSharesApi(private val http: AuthenticatedHttpClient) {
    suspend fun create(teamId: Long, request: CreateShareRequest): ShareLink = http.run {
        authenticatedEnvelope { client, token -> client.post(url("teams/$teamId/shares")) {
            bearer(token); jsonBody(request)
        } }
    }

    suspend fun list(
        teamId: Long,
        query: String?,
        passwordProtected: Boolean?,
        expiration: ShareExpirationFilter,
        page: Int,
        size: Int,
    ): PageResult<ShareLink> = http.run {
        authenticatedEnvelope { client, token -> client.get(url("teams/$teamId/shares")) {
            bearer(token); url {
                query?.trim()?.takeIf(String::isNotEmpty)?.let { parameters.append("q", it) }
                passwordProtected?.let { parameters.append("passwordProtected", it.toString()) }
                expiration.apiValue?.let { parameters.append("expirationStatus", it) }
                parameters.append("page", page.toString())
                parameters.append("size", size.toString())
            }
        } }
    }

    suspend fun get(teamId: Long, shareId: Long): ShareLink = http.run {
        authenticatedEnvelope { client, token -> client.get(url("teams/$teamId/shares/$shareId")) { bearer(token) } }
    }

    suspend fun password(teamId: Long, shareId: Long): String? = http.run {
        authenticatedEnvelope<SharePasswordResponse> { client, token ->
            client.get(url("teams/$teamId/shares/$shareId/password")) { bearer(token) }
        }.password
    }

    suspend fun update(teamId: Long, shareId: Long, request: UpdateShareRequest): ShareLink = http.run {
        authenticatedEnvelope { client, token -> client.put(url("teams/$teamId/shares/$shareId")) {
            bearer(token); jsonBody(request)
        } }
    }

    suspend fun cancel(teamId: Long, shareId: Long) = http.run {
        authenticatedNoData { client, token -> client.delete(url("teams/$teamId/shares/$shareId")) { bearer(token) } }
    }
}

class PersonalRecoveryApi(private val http: AuthenticatedHttpClient) {
    suspend fun listRecycleBin(query: String?, page: Int, size: Int): PageResult<RecycleBinItem> = http.run {
        authenticatedEnvelope { client, token -> client.get(url("recycle-bin")) {
            bearer(token); url {
                query?.trim()?.takeIf(String::isNotEmpty)?.let { parameters.append("q", it) }
                parameters.append("page", page.toString())
                parameters.append("size", size.toString())
            }
        } }
    }

    suspend fun restoreRecycleItem(fileId: Long) = http.run {
        authenticatedNoData { client, token -> client.post(url("recycle-bin/$fileId/restore")) { bearer(token) } }
    }

    suspend fun permanentlyDeleteRecycleItem(fileId: Long) = http.run {
        authenticatedNoData { client, token -> client.delete(url("recycle-bin/$fileId")) { bearer(token) } }
    }

    suspend fun emptyRecycleBin() = http.run {
        authenticatedNoData { client, token -> client.delete(url("recycle-bin")) { bearer(token) } }
    }

    suspend fun listVersions(fileId: Long): List<PersonalFileVersion> = http.run {
        authenticatedEnvelope { client, token -> client.get(url("files/$fileId/versions")) { bearer(token) } }
    }

    suspend fun restoreVersion(fileId: Long, versionNumber: Int): FileItem = http.run {
        authenticatedEnvelope { client, token -> client.post(url("files/$fileId/versions/$versionNumber/restore")) { bearer(token) } }
    }

    suspend fun deleteVersion(fileId: Long, versionNumber: Int) = http.run {
        authenticatedNoData { client, token -> client.delete(url("files/$fileId/versions/$versionNumber")) { bearer(token) } }
    }
}

class TeamRecoveryApi(private val http: AuthenticatedHttpClient) {
    suspend fun listRecycleBin(teamId: Long, query: String?, page: Int, size: Int): PageResult<RecycleBinItem> = http.run {
        authenticatedEnvelope { client, token -> client.get(url("teams/$teamId/recycle-bin")) {
            bearer(token); url {
                query?.trim()?.takeIf(String::isNotEmpty)?.let { parameters.append("q", it) }
                parameters.append("page", page.toString())
                parameters.append("size", size.toString())
            }
        } }
    }

    suspend fun restoreRecycleItem(teamId: Long, fileId: Long) = http.run {
        authenticatedNoData { client, token -> client.post(url("teams/$teamId/recycle-bin/$fileId/restore")) { bearer(token) } }
    }

    suspend fun permanentlyDeleteRecycleItem(teamId: Long, fileId: Long) = http.run {
        authenticatedNoData { client, token -> client.delete(url("teams/$teamId/recycle-bin/$fileId")) { bearer(token) } }
    }

    suspend fun emptyRecycleBin(teamId: Long) = http.run {
        authenticatedNoData { client, token -> client.delete(url("teams/$teamId/recycle-bin")) { bearer(token) } }
    }

    suspend fun listVersions(teamId: Long, fileId: Long): List<PersonalFileVersion> = http.run {
        authenticatedEnvelope { client, token -> client.get(url("teams/$teamId/files/$fileId/versions")) { bearer(token) } }
    }

    suspend fun restoreVersion(teamId: Long, fileId: Long, versionNumber: Int): FileItem = http.run {
        authenticatedEnvelope { client, token -> client.post(
            url("teams/$teamId/files/$fileId/versions/$versionNumber/restore"),
        ) { bearer(token) } }
    }

    suspend fun deleteVersion(teamId: Long, fileId: Long, versionNumber: Int) = http.run {
        authenticatedNoData { client, token -> client.delete(
            url("teams/$teamId/files/$fileId/versions/$versionNumber"),
        ) { bearer(token) } }
    }
}

class PreviewApi(private val http: AuthenticatedHttpClient) {
    suspend fun ticket(fileId: Long): String = http.run {
        val response: DownloadTicketResponse = authenticatedEnvelope { client, token ->
            client.post(url("download/$fileId/ticket")) { bearer(token) }
        }
        when {
            response.url.startsWith("http://") || response.url.startsWith("https://") -> response.url
            response.url.startsWith("/") -> serverBaseUrl.trimEnd('/') + response.url
            else -> ServerAddress.apiUrl(serverBaseUrl, response.url, allowCleartextServer)
        }
    }
    suspend fun personalOffice(fileId: Long): OfficeConfig = http.run {
        authenticatedEnvelope { client, token -> client.get(url("office/files/$fileId/config")) {
            bearer(token); url { parameters.append("mobile", "true") }
        } }
    }
    suspend fun teamOffice(teamId: Long, fileId: Long): OfficeConfig = http.run {
        authenticatedEnvelope { client, token -> client.get(url("teams/$teamId/office/files/$fileId/config")) {
            bearer(token); url { parameters.append("mobile", "true") }
        } }
    }
    suspend fun text(fileId: Long, maxBytes: Int): ByteArray {
        require(maxBytes > 0) { "preview.invalidTextLimit" }
        return http.authenticatedStream("download/$fileId", "bytes=0-${maxBytes - 1}") { status, headers, channel ->
            if (status == HttpStatusCode.RequestedRangeNotSatisfiable && headers[HttpHeaders.ContentRange]?.endsWith("/0") == true) {
                return@authenticatedStream ByteArray(0)
            }
            if (status != HttpStatusCode.OK && status != HttpStatusCode.PartialContent) throw ApiException(status.value, "error.previewFailed")
            val output = ByteArray(maxBytes); var offset = 0
            while (offset < output.size) {
                val count = channel.readAvailable(output, offset, output.size - offset)
                if (count < 0) break
                offset += count
            }
            output.copyOf(offset)
        }
    }
}

open class TransferApi(private val http: AuthenticatedHttpClient) {
    val serverBaseUrl get() = http.serverBaseUrl

    suspend fun initUpload(scope: TransferScope, fileName: String, fileSize: Long, md5: String): InitUploadResult = http.run {
        val result = try {
            authenticatedEnvelope<InitUploadResult> { client, token -> client.post(url(scope.path("upload/init"))) {
                bearer(token); jsonBody(InitUploadRequest(scope.parentId, fileName, fileSize, md5))
            } }
        } catch (_: kotlinx.serialization.SerializationException) {
            throw IllegalArgumentException("transfer.invalidInitResponse")
        }
        result.validate(fileSize)
    }
    suspend fun uploadStatus(scope: TransferScope, sessionId: String): UploadStatusResult = http.run {
        authenticatedEnvelope { client, token -> client.get(url(scope.path("upload/$sessionId/status"))) { bearer(token) } }
    }
    suspend fun uploadChunk(scope: TransferScope, sessionId: String, number: Int, fileName: String, chunkMd5: String, provider: InputProvider) = http.run {
        authenticatedEnvelope<UploadChunkResult> { client, token -> client.post(url(scope.path("upload/$sessionId/chunk"))) {
            bearer(token); url { parameters.append("chunkNumber", number.toString()); parameters.append("chunkMd5", chunkMd5) }
            setBody(MultiPartFormDataContent(formData { append("file", provider, Headers.build {
                append(HttpHeaders.ContentType, "application/octet-stream")
                append(HttpHeaders.ContentDisposition, "filename=\"$fileName\"")
            }) }))
        } }
    }
    suspend fun completeUpload(scope: TransferScope, sessionId: String): Long = http.run {
        authenticatedEnvelope<CompleteResult> { client, token -> client.post(url(scope.path("upload/$sessionId/complete"))) { bearer(token) } }.fileId
    }
    suspend fun cancelUpload(scope: TransferScope, sessionId: String) = http.run {
        authenticatedNoData { client, token -> client.post(url(scope.path("upload/$sessionId/cancel"))) { bearer(token) } }
    }
    suspend fun <T> download(
        scope: TransferScope, fileId: Long, rangeStart: Long, versionNumber: Int? = null,
        consume: suspend (HttpStatusCode, Headers, ByteReadChannel) -> T,
    ): T = http.authenticatedStream(
        scope.downloadPath(fileId, versionNumber), if (rangeStart > 0) "bytes=$rangeStart-" else null, consume,
    )

    private fun TransferScope.path(suffix: String) = when (this) {
        is TransferScope.Personal -> suffix
        is TransferScope.Team -> "teams/$teamId/$suffix"
    }
    private fun TransferScope.downloadPath(fileId: Long, versionNumber: Int?) = when (this) {
        is TransferScope.Personal -> versionNumber?.let { "files/$fileId/versions/$it/download" } ?: "download/$fileId"
        is TransferScope.Team -> versionNumber?.let { "teams/$teamId/files/$fileId/versions/$it/download" }
            ?: "teams/$teamId/download/$fileId"
    }
}
