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
    private val preview = PreviewApi(http)

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

    suspend fun createPersonalShare(request: CreatePersonalShareRequest) = shares.create(request)
    suspend fun listPersonalShares(query: String?, protected: Boolean?, expiration: ShareExpirationFilter, page: Int, size: Int) =
        shares.list(query, protected, expiration, page, size)
    suspend fun getPersonalShare(id: Long) = shares.get(id)
    suspend fun getPersonalSharePassword(id: Long) = shares.password(id)
    suspend fun updatePersonalShare(id: Long, request: UpdatePersonalShareRequest) = shares.update(id, request)
    suspend fun cancelPersonalShare(id: Long) = shares.cancel(id)

    suspend fun createPersonalDownloadTicket(id: Long) = preview.ticket(id)
    suspend fun loadPersonalOffice(fileId: Long) = preview.personalOffice(fileId)
    suspend fun loadTeamOffice(teamId: Long, fileId: Long) = preview.teamOffice(teamId, fileId)
    suspend fun readPersonalText(id: Long, maxBytes: Int) = preview.text(id, maxBytes)

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
