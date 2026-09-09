package com.betterclouddrive.mobile.core.network

import com.betterclouddrive.mobile.core.model.*
import com.betterclouddrive.mobile.core.platform.SecureSessionStore
import com.betterclouddrive.mobile.core.platform.ServerSettingsStore
import io.ktor.client.HttpClient
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

/** Keeps the pre-refactor contract suite intact while production code uses domain APIs directly. */
class NetworkTestFacade private constructor(
    private val http: AuthenticatedHttpClient,
) : TransferApi(http) {
    constructor(
        engineClient: HttpClient,
        initialServerBaseUrl: String = DEFAULT_SERVER,
        secureSessionStore: SecureSessionStore = TestMemorySessionStore(),
        serverSettingsStore: ServerSettingsStore = TestMemoryServerStore(initialServerBaseUrl),
        now: () -> Long = { kotlin.time.Clock.System.now().toEpochMilliseconds() },
        allowCleartextServer: Boolean = true,
    ) : this(AuthenticatedHttpClient(
        engineClient, initialServerBaseUrl, secureSessionStore, serverSettingsStore, now, allowCleartextServer,
    ))

    private val auth = AuthApi(http)
    private val files = PersonalFilesApi(http)
    private val organization = PersonalOrganizationApi(http)
    private val shares = PersonalSharesApi(http)
    private val teamShares = TeamSharesApi(http)
    private val preview = PreviewApi(http)
    private val recovery = PersonalRecoveryApi(http)
    private val teamRecovery = TeamRecoveryApi(http)
    private val teams = TeamWorkspaceApi(http)
    private val teamPreview = TeamPreviewApi(http)
    private val teamOrganization = TeamOrganizationApi(http)
    private val teamPermissions = TeamPermissionsApi(http)
    private val teamMembers = TeamMembersApi(http)
    private val teamInvites = TeamInvitesApi(http)
    private val notifications = NotificationsApi(http)

    val sessionInvalidations get() = http.sessionInvalidations
    suspend fun initialize() = http.initialize()
    suspend fun updateServer(value: String) = http.updateServer(value)
    fun normalizeServer(value: String) = http.normalizeServer(value)
    suspend fun login(username: String, password: String) = auth.login(username, password)
    suspend fun restoreUser() = auth.restoreUser()
    suspend fun getMe() = auth.getMe()
    suspend fun updatePreferredLocale(locale: String) = auth.updatePreferredLocale(locale)
    suspend fun logout() = auth.logout()
    suspend fun sendRegistrationCode(email: String, locale: String) = auth.sendRegistrationCode(email, locale)
    suspend fun register(username: String, password: String, email: String, code: String, locale: String) =
        auth.register(username, password, email, code, locale)
    suspend fun forgotPassword(email: String) = auth.forgotPassword(email)
    suspend fun resetPassword(email: String, code: String, password: String) = auth.resetPassword(email, code, password)

    suspend fun listPersonalFiles(parentId: Long?, page: Int, size: Int, sortBy: String, order: String) = files.list(parentId, page, size, sortBy, order)
    suspend fun listRootFiles() = files.list(null, 1, 50, "fileName", "asc").records
    suspend fun getPersonalFile(id: Long) = files.get(id)
    suspend fun searchPersonalFiles(query: String, page: Int, size: Int) = files.search(query, page, size)
    suspend fun createPersonalFolder(parentId: Long?, name: String) = files.createFolder(parentId, name)
    suspend fun renamePersonalFile(id: Long, name: String) = files.rename(id, name)
    suspend fun movePersonalFile(id: Long, parentId: Long?) = files.move(id, parentId)
    suspend fun copyPersonalFile(id: Long, parentId: Long?) = files.copy(id, parentId)
    suspend fun deletePersonalFiles(ids: List<Long>) = files.delete(ids)

    suspend fun listPersonalFavorites(query: String?, page: Int, size: Int) = organization.listFavorites(query, page, size)
    suspend fun isPersonalFavorite(id: Long) = organization.favoriteStatus(id)
    suspend fun addPersonalFavorite(id: Long) = organization.addFavorite(id)
    suspend fun removePersonalFavorite(id: Long) = organization.removeFavorite(id)
    suspend fun listPersonalTags(query: String? = null) = organization.listTags(query)
    suspend fun createPersonalTag(name: String, color: String) = organization.createTag(name, color)
    suspend fun updatePersonalTag(id: Long, name: String, color: String) = organization.updateTag(id, name, color)
    suspend fun deletePersonalTag(id: Long) = organization.deleteTag(id)
    suspend fun listPersonalFilesByTag(id: Long, query: String?, page: Int, size: Int) = organization.filesByTag(id, query, page, size)
    suspend fun listPersonalFileTags(id: Long) = organization.fileTags(id)
    suspend fun replacePersonalFileTags(id: Long, tags: List<Long>) = organization.replaceFileTags(id, tags)
    suspend fun removePersonalFileTag(tagId: Long, fileId: Long) = organization.removeFileTag(tagId, fileId)

    suspend fun listTeamFavorites(teamId: Long, query: String?, page: Int, size: Int) = teamOrganization.listFavorites(teamId, query, page, size)
    suspend fun isTeamFavorite(teamId: Long, fileId: Long) = teamOrganization.favoriteStatus(teamId, fileId)
    suspend fun addTeamFavorite(teamId: Long, fileId: Long) = teamOrganization.addFavorite(teamId, fileId)
    suspend fun removeTeamFavorite(teamId: Long, fileId: Long) = teamOrganization.removeFavorite(teamId, fileId)
    suspend fun listTeamTags(teamId: Long, query: String? = null) = teamOrganization.listTags(teamId, query)
    suspend fun createTeamTag(teamId: Long, name: String, color: String) = teamOrganization.createTag(teamId, name, color)
    suspend fun updateTeamTag(teamId: Long, tagId: Long, name: String, color: String) = teamOrganization.updateTag(teamId, tagId, name, color)
    suspend fun deleteTeamTag(teamId: Long, tagId: Long) = teamOrganization.deleteTag(teamId, tagId)
    suspend fun listTeamFilesByTag(teamId: Long, tagId: Long, query: String?, page: Int, size: Int) =
        teamOrganization.filesByTag(teamId, tagId, query, page, size)
    suspend fun listTeamFileTags(teamId: Long, fileId: Long) = teamOrganization.fileTags(teamId, fileId)
    suspend fun replaceTeamFileTags(teamId: Long, fileId: Long, tagIds: List<Long>) =
        teamOrganization.replaceFileTags(teamId, fileId, tagIds)
    suspend fun removeTeamFileTag(teamId: Long, tagId: Long, fileId: Long) = teamOrganization.removeFileTag(teamId, tagId, fileId)

    suspend fun createPersonalShare(request: CreatePersonalShareRequest) = shares.create(request)
    suspend fun listPersonalShares(query: String?, protected: Boolean?, expiration: ShareExpirationFilter, page: Int, size: Int) =
        shares.list(query, protected, expiration, page, size)
    suspend fun getPersonalShare(id: Long) = shares.get(id)
    suspend fun getPersonalSharePassword(id: Long) = shares.password(id)
    suspend fun updatePersonalShare(id: Long, request: UpdatePersonalShareRequest) = shares.update(id, request)
    suspend fun cancelPersonalShare(id: Long) = shares.cancel(id)
    suspend fun createTeamShare(teamId: Long, request: CreateShareRequest) = teamShares.create(teamId, request)
    suspend fun listTeamShares(
        teamId: Long, query: String?, protected: Boolean?, expiration: ShareExpirationFilter, page: Int, size: Int,
    ) = teamShares.list(teamId, query, protected, expiration, page, size)
    suspend fun getTeamShare(teamId: Long, id: Long) = teamShares.get(teamId, id)
    suspend fun getTeamSharePassword(teamId: Long, id: Long) = teamShares.password(teamId, id)
    suspend fun updateTeamShare(teamId: Long, id: Long, request: UpdateShareRequest) = teamShares.update(teamId, id, request)
    suspend fun cancelTeamShare(teamId: Long, id: Long) = teamShares.cancel(teamId, id)

    suspend fun createPersonalDownloadTicket(id: Long) = preview.ticket(id)
    suspend fun loadPersonalOffice(fileId: Long) = preview.personalOffice(fileId)
    suspend fun loadTeamOffice(teamId: Long, fileId: Long) = preview.teamOffice(teamId, fileId)
    suspend fun readPersonalText(id: Long, maxBytes: Int) = preview.text(id, maxBytes)
    suspend fun listRecycleBin(query: String?, page: Int, size: Int) = recovery.listRecycleBin(query, page, size)
    suspend fun restoreRecycleItem(id: Long) = recovery.restoreRecycleItem(id)
    suspend fun permanentlyDeleteRecycleItem(id: Long) = recovery.permanentlyDeleteRecycleItem(id)
    suspend fun emptyRecycleBin() = recovery.emptyRecycleBin()
    suspend fun listVersions(id: Long) = recovery.listVersions(id)
    suspend fun restoreVersion(id: Long, version: Int) = recovery.restoreVersion(id, version)
    suspend fun deleteVersion(id: Long, version: Int) = recovery.deleteVersion(id, version)
    suspend fun listTeamRecycleBin(teamId: Long, query: String?, page: Int, size: Int) =
        teamRecovery.listRecycleBin(teamId, query, page, size)
    suspend fun restoreTeamRecycleItem(teamId: Long, id: Long) = teamRecovery.restoreRecycleItem(teamId, id)
    suspend fun permanentlyDeleteTeamRecycleItem(teamId: Long, id: Long) = teamRecovery.permanentlyDeleteRecycleItem(teamId, id)
    suspend fun emptyTeamRecycleBin(teamId: Long) = teamRecovery.emptyRecycleBin(teamId)
    suspend fun listTeamVersions(teamId: Long, id: Long) = teamRecovery.listVersions(teamId, id)
    suspend fun restoreTeamVersion(teamId: Long, id: Long, version: Int) = teamRecovery.restoreVersion(teamId, id, version)
    suspend fun deleteTeamVersion(teamId: Long, id: Long, version: Int) = teamRecovery.deleteVersion(teamId, id, version)
    suspend fun listTeams(page: Int, size: Int) = teams.listTeams(page, size)
    suspend fun createTeam(name: String) = teams.createTeam(name)
    suspend fun getTeam(id: Long) = teams.getTeam(id)
    suspend fun listTeamFiles(teamId: Long, parentId: Long?, page: Int, size: Int, sortBy: String, order: String) =
        teams.listFiles(teamId, parentId, page, size, sortBy, order)
    suspend fun getTeamFile(teamId: Long, fileId: Long) = teams.getFile(teamId, fileId)
    suspend fun searchTeamFiles(teamId: Long, query: String, page: Int, size: Int) = teams.search(teamId, query, page, size)
    suspend fun getTeamEffectivePermission(teamId: Long, fileId: Long) = teamPermissions.effective(teamId, fileId)
    suspend fun listTeamMembers(teamId: Long, page: Int, size: Int) = teamMembers.list(teamId, page, size)
    suspend fun addTeamMember(teamId: Long, username: String, role: TeamRole) = teamMembers.add(teamId, username, role)
    suspend fun updateTeamMember(teamId: Long, userId: Long, role: TeamRole) = teamMembers.update(teamId, userId, role)
    suspend fun removeTeamMember(teamId: Long, userId: Long) = teamMembers.remove(teamId, userId)
    suspend fun listTeamInvites(teamId: Long, page: Int, size: Int) = teamInvites.list(teamId, page, size)
    suspend fun createTeamInvite(teamId: Long, email: String, role: TeamRole) = teamInvites.create(teamId, email, role)
    suspend fun resendTeamInvite(teamId: Long, inviteId: Long) = teamInvites.resend(teamId, inviteId)
    suspend fun cancelTeamInvite(teamId: Long, inviteId: Long) = teamInvites.cancel(teamId, inviteId)
    suspend fun getIncomingInvite(token: String) = teamInvites.getIncoming(token)
    suspend fun acceptIncomingInvite(token: String) = teamInvites.accept(token)
    suspend fun rejectIncomingInvite(token: String) = teamInvites.reject(token)
    suspend fun listNotifications(unreadOnly: Boolean?, type: String?, page: Int, size: Int) =
        notifications.list(unreadOnly, type, page, size)
    suspend fun notificationUnreadCount() = notifications.unreadCount()
    suspend fun markNotificationRead(id: Long) = notifications.markRead(id)
    suspend fun markAllNotificationsRead() = notifications.markAllRead()
    suspend fun listTeamPermissions(teamId: Long, fileId: Long) = teamPermissions.list(teamId, fileId)
    suspend fun saveTeamPermission(teamId: Long, fileId: Long, request: UpdateTeamFilePermissionRequest) =
        teamPermissions.upsert(teamId, fileId, request)
    suspend fun deleteTeamPermission(teamId: Long, fileId: Long, permissionId: Long) =
        teamPermissions.delete(teamId, fileId, permissionId)
    suspend fun createTeamFolder(teamId: Long, parentId: Long?, name: String) = teams.createFolder(teamId, parentId, name)
    suspend fun renameTeamFile(teamId: Long, fileId: Long, name: String) = teams.rename(teamId, fileId, name)
    suspend fun moveTeamFile(teamId: Long, fileId: Long, parentId: Long?) = teams.move(teamId, fileId, parentId)
    suspend fun deleteTeamFiles(teamId: Long, fileIds: List<Long>) = teams.delete(teamId, fileIds)
    suspend fun createTeamDownloadTicket(teamId: Long, fileId: Long) = teamPreview.ticket(teamId, fileId)
    suspend fun readTeamText(teamId: Long, fileId: Long, maxBytes: Int) = teamPreview.text(teamId, fileId, maxBytes)
    suspend fun loadTeamPreviewOffice(teamId: Long, fileId: Long) = teamPreview.office(teamId, fileId)

    companion object {
        const val DEFAULT_SERVER = NetworkDefaults.DEFAULT_SERVER
        const val DEFAULT_HTTPS_SERVER = NetworkDefaults.DEFAULT_HTTPS_SERVER
        const val TOKEN_EXPIRED = NetworkDefaults.TOKEN_EXPIRED
        const val TOKEN_REVOKED = NetworkDefaults.TOKEN_REVOKED
        const val INSTANT_UPLOAD_MISS = NetworkDefaults.INSTANT_UPLOAD_MISS
    }
}

private class TestMemorySessionStore : SecureSessionStore {
    private var session: StoredSession? = null
    override suspend fun read(serverBaseUrl: String) = session?.takeIf { it.serverBaseUrl == serverBaseUrl }
    override suspend fun write(session: StoredSession) { this.session = session }
    override suspend fun clear() { session = null }
}

private class TestMemoryServerStore(initial: String) : ServerSettingsStore {
    private val state = MutableStateFlow(initial)
    override val serverBaseUrl: Flow<String> = state
    override suspend fun read() = state.value
    override suspend fun write(value: String) { state.value = value }
}
