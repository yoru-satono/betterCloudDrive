package com.betterclouddrive.mobile.core.network

import com.betterclouddrive.mobile.core.model.ApiException
import com.betterclouddrive.mobile.core.model.ApiResponse
import com.betterclouddrive.mobile.core.model.AuthTokens
import com.betterclouddrive.mobile.core.model.CompleteResult
import com.betterclouddrive.mobile.core.model.CreatePersonalFolderRequest
import com.betterclouddrive.mobile.core.model.DeletePersonalFilesRequest
import com.betterclouddrive.mobile.core.model.DownloadTicketResponse
import com.betterclouddrive.mobile.core.model.FileItem
import com.betterclouddrive.mobile.core.model.InitUploadRequest
import com.betterclouddrive.mobile.core.model.InitUploadResult
import com.betterclouddrive.mobile.core.model.InstantUploadRequest
import com.betterclouddrive.mobile.core.model.InstantUploadResult
import com.betterclouddrive.mobile.core.model.LoginRequest
import com.betterclouddrive.mobile.core.model.RegisterCodeRequest
import com.betterclouddrive.mobile.core.model.RegisterRequest
import com.betterclouddrive.mobile.core.model.RegistrationResult
import com.betterclouddrive.mobile.core.model.ForgotPasswordRequest
import com.betterclouddrive.mobile.core.model.ResetPasswordRequest
import com.betterclouddrive.mobile.core.model.UpdateLocaleRequest
import com.betterclouddrive.mobile.core.model.OfficeConfig
import com.betterclouddrive.mobile.core.model.PageResult
import com.betterclouddrive.mobile.core.model.PersonalFileDestinationRequest
import com.betterclouddrive.mobile.core.model.PersonalTag
import com.betterclouddrive.mobile.core.model.CreatePersonalTagRequest
import com.betterclouddrive.mobile.core.model.UpdatePersonalTagRequest
import com.betterclouddrive.mobile.core.model.ReplacePersonalFileTagsRequest
import com.betterclouddrive.mobile.core.model.RefreshRequest
import com.betterclouddrive.mobile.core.model.RenamePersonalFileRequest
import com.betterclouddrive.mobile.core.model.SessionExpiredException
import com.betterclouddrive.mobile.core.model.SessionInvalidationReason
import com.betterclouddrive.mobile.core.model.SessionRestoreResult
import com.betterclouddrive.mobile.core.model.SessionStorageInvalidException
import com.betterclouddrive.mobile.core.model.StoredSession
import com.betterclouddrive.mobile.core.model.UploadChunkResult
import com.betterclouddrive.mobile.core.model.UploadStatusResult
import com.betterclouddrive.mobile.core.model.UserProfile
import com.betterclouddrive.mobile.core.platform.SecureSessionStore
import com.betterclouddrive.mobile.core.platform.ServerSettingsStore
import com.betterclouddrive.mobile.feature.transfers.TransferScope
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.forms.MultiPartFormDataContent
import io.ktor.client.request.forms.InputProvider
import io.ktor.client.request.forms.formData
import io.ktor.client.request.get
import io.ktor.client.request.delete
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readAvailable

class BackendClient(
    engineClient: HttpClient,
    initialServerBaseUrl: String = DEFAULT_SERVER,
    private val secureSessionStore: SecureSessionStore = MemorySessionStore(),
    private val serverSettingsStore: ServerSettingsStore = MemoryServerSettingsStore(initialServerBaseUrl),
    private val now: () -> Long = { kotlin.time.Clock.System.now().toEpochMilliseconds() },
    private val allowCleartextServer: Boolean = true,
) {
    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        isLenient = true
    }
    private val client = engineClient.config {
        expectSuccess = false
        install(ContentNegotiation) { json(json) }
    }
    private val refreshMutex = Mutex()
    private val sessionState = MutableStateFlow<StoredSession?>(null)
    private val mutableSessionInvalidations = MutableSharedFlow<SessionInvalidationReason>(replay = 1, extraBufferCapacity = 8)

    val sessionInvalidations: Flow<SessionInvalidationReason> = mutableSessionInvalidations.asSharedFlow()
    var serverBaseUrl: String = initialServerBaseUrl
        private set

    suspend fun initialize(): StoredSession? {
        serverBaseUrl = ServerAddress.normalize(serverSettingsStore.read(), allowCleartextServer)
            .getOrElse { if (allowCleartextServer) DEFAULT_SERVER else DEFAULT_HTTPS_SERVER }
        val restored = try {
            secureSessionStore.read(serverBaseUrl)
        } catch (_: SessionStorageInvalidException) {
            mutableSessionInvalidations.tryEmit(SessionInvalidationReason.CORRUPT_STORAGE)
            null
        }
        sessionState.value = restored
        return restored
    }

    suspend fun updateServer(input: String): Result<String> {
        val normalized = normalizeServer(input)
        normalized.onSuccess { value ->
            if (value != serverBaseUrl) {
                clearSession(SessionInvalidationReason.SERVER_CHANGED)
                serverBaseUrl = value
                serverSettingsStore.write(value)
            }
        }
        return normalized
    }

    fun normalizeServer(input: String): Result<String> = ServerAddress.normalize(input, allowCleartextServer)

    suspend fun login(username: String, password: String): UserProfile {
        val envelope: ApiResponse<AuthTokens> = client.post(url("auth/login")) {
            jsonBody(LoginRequest(username, password))
        }.body()
        val tokens = envelope.requireData()
        val stored = StoredSession(
            accessToken = tokens.accessToken,
            refreshToken = tokens.refreshToken,
            expiresAtEpochMillis = now() + tokens.expiresIn * 1000L,
            serverBaseUrl = serverBaseUrl,
        )
        secureSessionStore.write(stored)
        sessionState.value = stored
        return try {
            getMe()
        } catch (cancelled: CancellationException) {
            clearSessionInternal()
            throw cancelled
        } catch (error: Throwable) {
            clearSessionInternal()
            throw error
        }
    }

    suspend fun restoreUser(): SessionRestoreResult {
        val restored = initialize()
        if (restored == null) return SessionRestoreResult.NoSession
        return try {
            ensureFreshSession()
            SessionRestoreResult.Restored(getMe())
        } catch (_: SessionExpiredException) {
            SessionRestoreResult.Invalidated(SessionInvalidationReason.EXPIRED)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            SessionRestoreResult.RetryableFailure()
        }
    }

    suspend fun getMe(): UserProfile = authenticatedEnvelope { token ->
        client.get(url("auth/me")) { bearer(token) }
    }

    suspend fun updatePreferredLocale(locale: String): UserProfile = authenticatedEnvelope { token ->
        client.put(url("auth/preferences/locale")) {
            bearer(token)
            jsonBody(UpdateLocaleRequest(locale))
        }
    }

    suspend fun logout() {
        try {
            authenticatedNoData { token ->
                client.post(url("auth/logout")) { bearer(token) }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            // Local logout is authoritative when the server cannot be reached.
        } finally {
            clearSession(SessionInvalidationReason.LOGOUT)
        }
    }

    suspend fun clearSession(reason: SessionInvalidationReason = SessionInvalidationReason.EXPIRED) {
        clearSessionInternal()
        mutableSessionInvalidations.emit(reason)
    }

    private suspend fun clearSessionInternal() {
        sessionState.value = null
        secureSessionStore.clear()
    }

    suspend fun listPersonalFiles(
        parentId: Long?,
        page: Int,
        size: Int,
        sortBy: String,
        order: String,
    ): PageResult<FileItem> = authenticatedEnvelope { token ->
        client.get(url("files")) {
            bearer(token)
            url {
                parentId?.let { parameters.append("parentId", it.toString()) }
                parameters.append("page", page.toString())
                parameters.append("size", size.toString())
                parameters.append("sortBy", sortBy)
                parameters.append("order", order)
            }
        }
    }

    suspend fun getPersonalFile(fileId: Long): FileItem = authenticatedEnvelope { token ->
        client.get(url("files/$fileId")) { bearer(token) }
    }

    suspend fun createPersonalFolder(parentId: Long?, folderName: String): FileItem = authenticatedEnvelope { token ->
        client.post(url("files/folder")) {
            bearer(token)
            jsonBody(CreatePersonalFolderRequest(parentId, folderName))
        }
    }

    suspend fun renamePersonalFile(fileId: Long, newName: String): FileItem = authenticatedEnvelope { token ->
        client.put(url("files/$fileId")) {
            bearer(token)
            jsonBody(RenamePersonalFileRequest(newName))
        }
    }

    suspend fun movePersonalFile(fileId: Long, targetParentId: Long?) {
        authenticatedNoData { token ->
            client.post(url("files/$fileId/move")) {
                bearer(token)
                jsonBody(PersonalFileDestinationRequest(targetParentId))
            }
        }
    }

    suspend fun copyPersonalFile(fileId: Long, targetParentId: Long?) {
        authenticatedNoData { token ->
            client.post(url("files/$fileId/copy")) {
                bearer(token)
                jsonBody(PersonalFileDestinationRequest(targetParentId))
            }
        }
    }

    suspend fun deletePersonalFiles(fileIds: List<Long>) {
        authenticatedNoData { token ->
            client.delete(url("files")) {
                bearer(token)
                jsonBody(DeletePersonalFilesRequest(fileIds.distinct()))
            }
        }
    }

    suspend fun listPersonalFavorites(query: String?, page: Int, size: Int): PageResult<FileItem> =
        authenticatedEnvelope { token ->
            client.get(url("favorites")) {
                bearer(token)
                url {
                    query?.trim()?.takeIf(String::isNotEmpty)?.let { parameters.append("q", it) }
                    parameters.append("page", page.toString())
                    parameters.append("size", size.toString())
                }
            }
        }

    suspend fun isPersonalFavorite(fileId: Long): Boolean = authenticatedEnvelope { token ->
        client.get(url("favorites/$fileId/status")) { bearer(token) }
    }

    suspend fun addPersonalFavorite(fileId: Long) {
        authenticatedNoData { token -> client.post(url("favorites/$fileId")) { bearer(token) } }
    }

    suspend fun removePersonalFavorite(fileId: Long) {
        authenticatedNoData { token -> client.delete(url("favorites/$fileId")) { bearer(token) } }
    }

    suspend fun listPersonalTags(query: String? = null): List<PersonalTag> = authenticatedEnvelope { token ->
        client.get(url("tags")) {
            bearer(token)
            url { query?.trim()?.takeIf(String::isNotEmpty)?.let { parameters.append("q", it) } }
        }
    }

    suspend fun createPersonalTag(name: String, color: String): PersonalTag = authenticatedEnvelope { token ->
        client.post(url("tags")) { bearer(token); jsonBody(CreatePersonalTagRequest(name, color)) }
    }

    suspend fun updatePersonalTag(tagId: Long, name: String, color: String): PersonalTag = authenticatedEnvelope { token ->
        client.put(url("tags/$tagId")) { bearer(token); jsonBody(UpdatePersonalTagRequest(name, color)) }
    }

    suspend fun deletePersonalTag(tagId: Long) {
        authenticatedNoData { token -> client.delete(url("tags/$tagId")) { bearer(token) } }
    }

    suspend fun listPersonalFilesByTag(
        tagId: Long,
        query: String?,
        page: Int,
        size: Int,
    ): PageResult<FileItem> = authenticatedEnvelope { token ->
        client.get(url("tags/$tagId/files")) {
            bearer(token)
            url {
                query?.trim()?.takeIf(String::isNotEmpty)?.let { parameters.append("q", it) }
                parameters.append("page", page.toString())
                parameters.append("size", size.toString())
            }
        }
    }

    suspend fun listPersonalFileTags(fileId: Long): List<PersonalTag> = authenticatedEnvelope { token ->
        client.get(url("files/$fileId/tags")) { bearer(token) }
    }

    suspend fun replacePersonalFileTags(fileId: Long, tagIds: List<Long>): List<PersonalTag> =
        authenticatedEnvelope { token ->
            client.put(url("files/$fileId/tags")) {
                bearer(token)
                jsonBody(ReplacePersonalFileTagsRequest(tagIds.distinct()))
            }
        }

    suspend fun removePersonalFileTag(tagId: Long, fileId: Long) {
        authenticatedNoData { token -> client.delete(url("tags/$tagId/files/$fileId")) { bearer(token) } }
    }

    suspend fun listRootFiles(): List<FileItem> {
        val page: PageResult<FileItem> = authenticatedEnvelope { token ->
            client.get(url("files")) {
                bearer(token)
                url { parameters.append("page", "1"); parameters.append("size", "50") }
            }
        }
        return page.records
    }

    suspend fun sendRegistrationCode(email: String, preferredLocale: String) {
        publicNoData {
            client.post(url("auth/register-code/send")) {
                jsonBody(RegisterCodeRequest(email, preferredLocale))
            }
        }
    }

    suspend fun register(
        username: String,
        password: String,
        email: String,
        verificationCode: String,
        preferredLocale: String,
    ): RegistrationResult = publicEnvelope {
        client.post(url("auth/register")) {
            jsonBody(RegisterRequest(username, password, email, verificationCode, preferredLocale))
        }
    }

    suspend fun forgotPassword(email: String) {
        publicNoData { client.post(url("auth/forgot-password")) { jsonBody(ForgotPasswordRequest(email)) } }
    }

    suspend fun resetPassword(email: String, code: String, newPassword: String) {
        publicNoData {
            client.post(url("auth/reset-password")) { jsonBody(ResetPasswordRequest(email, code, newPassword)) }
        }
    }

    suspend fun searchPersonalFiles(query: String, page: Int, size: Int): PageResult<FileItem> =
        authenticatedEnvelope { token ->
            client.get(url("files/search")) {
                bearer(token)
                url {
                    parameters.append("q", query)
                    parameters.append("page", page.toString())
                    parameters.append("size", size.toString())
                }
            }
        }

    suspend fun createPersonalDownloadTicket(fileId: Long): String {
        val response: DownloadTicketResponse = authenticatedEnvelope { token ->
            client.post(url("download/$fileId/ticket")) { bearer(token) }
        }
        return when {
            response.url.startsWith("http://") || response.url.startsWith("https://") -> response.url
            response.url.startsWith("/") -> serverBaseUrl.trimEnd('/') + response.url
            else -> ServerAddress.apiUrl(serverBaseUrl, response.url, allowCleartextServer)
        }
    }

    suspend fun loadPersonalOffice(fileId: Long): OfficeConfig = authenticatedEnvelope { token ->
        client.get(url("office/files/$fileId/config")) {
            bearer(token)
            url { parameters.append("mobile", "true") }
        }
    }

    suspend fun readPersonalText(fileId: Long, maxBytes: Int): ByteArray {
        require(maxBytes > 0) { "preview.invalidTextLimit" }
        return authenticatedStream("download/$fileId", "bytes=0-${maxBytes - 1}") { status, headers, channel ->
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

    suspend fun loadTeamOffice(teamId: Long, fileId: Long): OfficeConfig = authenticatedEnvelope { token ->
        client.get(url("teams/$teamId/office/files/$fileId/config")) {
            bearer(token)
            url { parameters.append("mobile", "true") }
        }
    }

    suspend fun instantUpload(scope: TransferScope, fileName: String, fileSize: Long, md5: String): Long {
        val result: InstantUploadResult = authenticatedEnvelope { token ->
            client.post(url(scope.path("upload/instant"))) {
                bearer(token)
                jsonBody(InstantUploadRequest(scope.parentId, fileName, fileSize, md5))
            }
        }
        return result.fileId
    }

    suspend fun initUpload(scope: TransferScope, fileName: String, fileSize: Long, md5: String, totalChunks: Int): InitUploadResult =
        authenticatedEnvelope { token ->
            client.post(url(scope.path("upload/init"))) {
                bearer(token)
                jsonBody(InitUploadRequest(scope.parentId, fileName, fileSize, md5, totalChunks))
            }
        }

    suspend fun uploadStatus(scope: TransferScope, sessionId: String): UploadStatusResult = authenticatedEnvelope { token ->
        client.get(url(scope.path("upload/$sessionId/status"))) { bearer(token) }
    }

    suspend fun uploadChunk(
        scope: TransferScope,
        sessionId: String,
        chunkNumber: Int,
        fileName: String,
        provider: InputProvider,
    ) {
        authenticatedEnvelope<UploadChunkResult> { token ->
            client.post(url(scope.path("upload/$sessionId/chunk"))) {
                bearer(token)
                url { parameters.append("chunkNumber", chunkNumber.toString()) }
                setBody(
                    MultiPartFormDataContent(
                        formData {
                            append(
                                "file",
                                provider,
                                Headers.build {
                                    append(HttpHeaders.ContentType, "application/octet-stream")
                                    append(HttpHeaders.ContentDisposition, "filename=\"$fileName\"")
                                },
                            )
                        },
                    ),
                )
            }
        }
    }

    suspend fun completeUpload(scope: TransferScope, sessionId: String): Long {
        val result: CompleteResult = authenticatedEnvelope { token ->
            client.post(url(scope.path("upload/$sessionId/complete"))) { bearer(token) }
        }
        return result.fileId
    }

    suspend fun cancelUpload(scope: TransferScope, sessionId: String) {
        authenticatedNoData { token ->
            client.post(url(scope.path("upload/$sessionId/cancel"))) { bearer(token) }
        }
    }

    suspend fun <T> download(
        scope: TransferScope,
        fileId: Long,
        rangeStart: Long,
        consume: suspend (HttpStatusCode, Headers, ByteReadChannel) -> T,
    ): T = authenticatedStream(
        scope.downloadPath(fileId),
        if (rangeStart > 0) "bytes=$rangeStart-" else null,
        consume,
    )

    private suspend fun <T> authenticatedStream(
        path: String,
        range: String?,
        consume: suspend (HttpStatusCode, Headers, ByteReadChannel) -> T,
    ): T {
        var used = ensureFreshSession()
        var refreshed = false
        while (true) {
            val attempt = client.prepareGet(url(path)) {
                bearer(used.accessToken)
                range?.let { header(HttpHeaders.Range, it) }
            }.execute { response ->
                if (response.status == HttpStatusCode.Unauthorized) {
                    StreamingAttempt.Unauthorized(response.unauthorizedCode())
                } else {
                    StreamingAttempt.Consumed(
                        consume(response.status, response.headers, response.bodyAsChannel()),
                    )
                }
            }
            when (attempt) {
                is StreamingAttempt.Consumed -> return attempt.value
                is StreamingAttempt.Unauthorized -> {
                    if (!refreshed && attempt.code == TOKEN_EXPIRED) {
                        used = refreshAfter(used.accessToken)
                        refreshed = true
                    } else {
                        clearSession(
                            if (attempt.code == TOKEN_REVOKED) SessionInvalidationReason.REVOKED
                            else SessionInvalidationReason.EXPIRED,
                        )
                        throw SessionExpiredException()
                    }
                }
            }
        }
    }

    private suspend inline fun <reified T> authenticatedEnvelope(
        crossinline request: suspend (String) -> HttpResponse,
    ): T {
        var used = ensureFreshSession()
        var response = request(used.accessToken)
        var envelope = response.decodeEnvelope<T>()
        if (envelope.code == TOKEN_EXPIRED) {
            used = refreshAfter(used.accessToken)
            response = request(used.accessToken)
            envelope = response.decodeEnvelope()
        }
        if (envelope.code == TOKEN_EXPIRED || envelope.code == TOKEN_REVOKED || envelope.code == 401) {
            clearSession(if (envelope.code == TOKEN_REVOKED) SessionInvalidationReason.REVOKED else SessionInvalidationReason.EXPIRED)
            throw SessionExpiredException()
        }
        return envelope.requireData()
    }

    private suspend fun authenticatedNoData(request: suspend (String) -> HttpResponse) {
        var used = ensureFreshSession()
        var envelope = request(used.accessToken).decodeEnvelope<JsonElement>()
        if (envelope.code == TOKEN_EXPIRED) {
            used = refreshAfter(used.accessToken)
            envelope = request(used.accessToken).decodeEnvelope()
        }
        if (envelope.code != 200) {
            if (envelope.code == TOKEN_EXPIRED || envelope.code == TOKEN_REVOKED || envelope.code == 401) {
                clearSession(if (envelope.code == TOKEN_REVOKED) SessionInvalidationReason.REVOKED else SessionInvalidationReason.EXPIRED)
                throw SessionExpiredException()
            }
            throw ApiException(envelope.code, envelope.messageKey, envelope.messageParams, envelope.requestId)
        }
    }

    private suspend inline fun <reified T> publicEnvelope(request: suspend () -> HttpResponse): T =
        request().decodeEnvelope<T>().requireData()

    private suspend fun publicNoData(request: suspend () -> HttpResponse) {
        val envelope = request().decodeEnvelope<JsonElement>()
        if (envelope.code != 200) {
            throw ApiException(envelope.code, envelope.messageKey, envelope.messageParams, envelope.requestId)
        }
    }

    private suspend fun ensureFreshSession(): StoredSession {
        val current = sessionState.value ?: secureSessionStore.read(serverBaseUrl)?.also { sessionState.value = it }
            ?: throw SessionExpiredException()
        return if (current.expiresAtEpochMillis - now() <= REFRESH_EARLY_MILLIS) {
            refreshAfter(current.accessToken)
        } else current
    }

    private suspend fun refreshAfter(failedAccessToken: String): StoredSession = refreshMutex.withLock {
        val current = sessionState.value ?: throw SessionExpiredException()
        if (current.accessToken != failedAccessToken) return@withLock current
        val envelope: ApiResponse<AuthTokens> = try {
            client.post(url("auth/refresh")) {
                jsonBody(RefreshRequest(current.refreshToken))
            }.decodeEnvelope()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            clearSession(SessionInvalidationReason.EXPIRED)
            throw SessionExpiredException()
        }
        if (envelope.code != 200 || envelope.data == null) {
            clearSession(if (envelope.code == TOKEN_REVOKED) SessionInvalidationReason.REVOKED else SessionInvalidationReason.EXPIRED)
            throw SessionExpiredException()
        }
        val refreshed = StoredSession(
            accessToken = envelope.data.accessToken,
            refreshToken = envelope.data.refreshToken,
            expiresAtEpochMillis = now() + envelope.data.expiresIn * 1000L,
            serverBaseUrl = serverBaseUrl,
        )
        secureSessionStore.write(refreshed)
        sessionState.value = refreshed
        refreshed
    }

    private suspend inline fun <reified T> HttpResponse.decodeEnvelope(): ApiResponse<T> = try {
        body()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Throwable) {
        val key = when (status.value) {
            401 -> "error.unauthorized"
            403 -> "error.forbidden"
            404 -> "error.notFound"
            409 -> "error.fileNameConflict"
            429 -> "error.rateLimited"
            in 500..599 -> "error.internal"
            else -> "error.network"
        }
        throw ApiException(status.value, key)
    }

    private suspend fun HttpResponse.unauthorizedCode(): Int = try {
        decodeEnvelope<JsonElement>().code
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Throwable) {
        HttpStatusCode.Unauthorized.value
    }

    private fun <T> ApiResponse<T>.requireData(): T {
        if (code != 200 || data == null) throw ApiException(code, messageKey, messageParams, requestId)
        return data
    }

    private fun url(path: String): String = ServerAddress.apiUrl(serverBaseUrl, path, allowCleartextServer)

    private fun HttpRequestBuilder.bearer(token: String) {
        header(HttpHeaders.Authorization, "Bearer $token")
    }

    private inline fun <reified T> HttpRequestBuilder.jsonBody(value: T) {
        header(HttpHeaders.ContentType, ContentType.Application.Json)
        setBody(value)
    }

    private fun TransferScope.path(suffix: String): String = when (this) {
        is TransferScope.Personal -> suffix
        is TransferScope.Team -> "teams/$teamId/$suffix"
    }

    private fun TransferScope.downloadPath(fileId: Long): String = when (this) {
        is TransferScope.Personal -> "download/$fileId"
        is TransferScope.Team -> "teams/$teamId/download/$fileId"
    }

    companion object {
        const val DEFAULT_SERVER = "http://10.0.2.2:8080"
        const val DEFAULT_HTTPS_SERVER = "https://drive.example.com"
        const val TOKEN_EXPIRED = 401001
        const val TOKEN_REVOKED = 401002
        const val INSTANT_UPLOAD_MISS = 419010
        const val REFRESH_EARLY_MILLIS = 60_000L
    }
}

private sealed interface StreamingAttempt<out T> {
    data class Consumed<T>(val value: T) : StreamingAttempt<T>
    data class Unauthorized(val code: Int) : StreamingAttempt<Nothing>
}

private class MemorySessionStore : SecureSessionStore {
    private var value: StoredSession? = null
    override suspend fun read(serverBaseUrl: String): StoredSession? = value?.takeIf { it.serverBaseUrl == serverBaseUrl }
    override suspend fun write(session: StoredSession) { value = session }
    override suspend fun clear() { value = null }
}

private class MemoryServerSettingsStore(initial: String) : ServerSettingsStore {
    private val state = MutableStateFlow(initial)
    override val serverBaseUrl: Flow<String> = state
    override suspend fun read(): String = state.value
    override suspend fun write(value: String) { state.value = value }
}
