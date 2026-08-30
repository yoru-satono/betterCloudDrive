package com.betterclouddrive.mobile.core.network

import com.betterclouddrive.mobile.core.model.ApiException
import com.betterclouddrive.mobile.core.model.ApiResponse
import com.betterclouddrive.mobile.core.model.AuthTokens
import com.betterclouddrive.mobile.core.model.RefreshRequest
import com.betterclouddrive.mobile.core.model.SessionExpiredException
import com.betterclouddrive.mobile.core.model.SessionInvalidationReason
import com.betterclouddrive.mobile.core.model.SessionStorageInvalidException
import com.betterclouddrive.mobile.core.model.StoredSession
import com.betterclouddrive.mobile.core.platform.SecureSessionStore
import com.betterclouddrive.mobile.core.platform.ServerSettingsStore
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.prepareGet
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

object NetworkDefaults {
    const val DEFAULT_SERVER = "http://10.0.2.2:8080"
    const val DEFAULT_HTTPS_SERVER = "https://drive.example.com"
    const val TOKEN_EXPIRED = 401001
    const val TOKEN_REVOKED = 401002
    const val INSTANT_UPLOAD_MISS = 419010
    const val REFRESH_EARLY_MILLIS = 60_000L
}

class AuthenticatedHttpClient(
    engineClient: HttpClient,
    initialServerBaseUrl: String = NetworkDefaults.DEFAULT_SERVER,
    private val secureSessionStore: SecureSessionStore = MemoryHttpSessionStore(),
    private val serverSettingsStore: ServerSettingsStore = MemoryHttpServerSettingsStore(initialServerBaseUrl),
    private val now: () -> Long = { kotlin.time.Clock.System.now().toEpochMilliseconds() },
    internal val allowCleartextServer: Boolean = true,
) {
    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true; isLenient = true }
    internal val client = engineClient.config {
        expectSuccess = false
        install(ContentNegotiation) { json(json) }
    }
    private val refreshMutex = Mutex()
    private val sessionState = MutableStateFlow<StoredSession?>(null)
    private val invalidations = MutableSharedFlow<SessionInvalidationReason>(replay = 1, extraBufferCapacity = 8)

    val sessionInvalidations: Flow<SessionInvalidationReason> = invalidations.asSharedFlow()
    var serverBaseUrl: String = initialServerBaseUrl
        private set

    suspend fun initialize(): StoredSession? {
        serverBaseUrl = ServerAddress.normalize(serverSettingsStore.read(), allowCleartextServer)
            .getOrElse { if (allowCleartextServer) NetworkDefaults.DEFAULT_SERVER else NetworkDefaults.DEFAULT_HTTPS_SERVER }
        val restored = try {
            secureSessionStore.read(serverBaseUrl)
        } catch (_: SessionStorageInvalidException) {
            invalidations.tryEmit(SessionInvalidationReason.CORRUPT_STORAGE)
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

    internal suspend fun saveTokens(tokens: AuthTokens) {
        val stored = StoredSession(
            accessToken = tokens.accessToken,
            refreshToken = tokens.refreshToken,
            expiresAtEpochMillis = now() + tokens.expiresIn * 1000L,
            serverBaseUrl = serverBaseUrl,
        )
        secureSessionStore.write(stored)
        sessionState.value = stored
    }

    suspend fun clearSession(reason: SessionInvalidationReason = SessionInvalidationReason.EXPIRED) {
        clearSessionSilently()
        invalidations.emit(reason)
    }

    internal suspend fun clearSessionSilently() {
        sessionState.value = null
        secureSessionStore.clear()
    }

    internal suspend fun ensureSession() = ensureFreshSession()

    internal suspend inline fun <reified T> authenticatedEnvelope(
        crossinline request: suspend (HttpClient, String) -> HttpResponse,
    ): T {
        var used = ensureFreshSession()
        var response = request(client, used.accessToken)
        var envelope = response.decodeEnvelope<T>()
        if (envelope.code == NetworkDefaults.TOKEN_EXPIRED) {
            used = refreshAfter(used.accessToken)
            response = request(client, used.accessToken)
            envelope = response.decodeEnvelope()
        }
        if (envelope.code == NetworkDefaults.TOKEN_EXPIRED || envelope.code == NetworkDefaults.TOKEN_REVOKED || envelope.code == 401) {
            clearSession(if (envelope.code == NetworkDefaults.TOKEN_REVOKED) SessionInvalidationReason.REVOKED else SessionInvalidationReason.EXPIRED)
            throw SessionExpiredException()
        }
        return envelope.requireData()
    }

    internal suspend fun authenticatedNoData(request: suspend (HttpClient, String) -> HttpResponse) {
        var used = ensureFreshSession()
        var envelope = request(client, used.accessToken).decodeEnvelope<JsonElement>()
        if (envelope.code == NetworkDefaults.TOKEN_EXPIRED) {
            used = refreshAfter(used.accessToken)
            envelope = request(client, used.accessToken).decodeEnvelope()
        }
        if (envelope.code != 200) {
            if (envelope.code == NetworkDefaults.TOKEN_EXPIRED || envelope.code == NetworkDefaults.TOKEN_REVOKED || envelope.code == 401) {
                clearSession(if (envelope.code == NetworkDefaults.TOKEN_REVOKED) SessionInvalidationReason.REVOKED else SessionInvalidationReason.EXPIRED)
                throw SessionExpiredException()
            }
            throw ApiException(envelope.code, envelope.messageKey, envelope.messageParams, envelope.requestId)
        }
    }

    internal suspend inline fun <reified T> publicEnvelope(
        crossinline request: suspend (HttpClient) -> HttpResponse,
    ): T = request(client).decodeEnvelope<T>().requireData()

    internal suspend fun publicNoData(request: suspend (HttpClient) -> HttpResponse) {
        val envelope = request(client).decodeEnvelope<JsonElement>()
        if (envelope.code != 200) throw ApiException(envelope.code, envelope.messageKey, envelope.messageParams, envelope.requestId)
    }

    internal suspend fun <T> authenticatedStream(
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
                    AuthStreamingAttempt.Unauthorized(response.unauthorizedCode())
                } else {
                    AuthStreamingAttempt.Consumed(consume(response.status, response.headers, response.bodyAsChannel()))
                }
            }
            when (attempt) {
                is AuthStreamingAttempt.Consumed -> return attempt.value
                is AuthStreamingAttempt.Unauthorized -> if (!refreshed && attempt.code == NetworkDefaults.TOKEN_EXPIRED) {
                    used = refreshAfter(used.accessToken); refreshed = true
                } else {
                    clearSession(if (attempt.code == NetworkDefaults.TOKEN_REVOKED) SessionInvalidationReason.REVOKED else SessionInvalidationReason.EXPIRED)
                    throw SessionExpiredException()
                }
            }
        }
    }

    private suspend fun ensureFreshSession(): StoredSession {
        val current = sessionState.value ?: secureSessionStore.read(serverBaseUrl)?.also { sessionState.value = it }
            ?: throw SessionExpiredException()
        return if (current.expiresAtEpochMillis - now() <= NetworkDefaults.REFRESH_EARLY_MILLIS) refreshAfter(current.accessToken) else current
    }

    private suspend fun refreshAfter(failedAccessToken: String): StoredSession = refreshMutex.withLock {
        val current = sessionState.value ?: throw SessionExpiredException()
        if (current.accessToken != failedAccessToken) return@withLock current
        val envelope: ApiResponse<AuthTokens> = try {
            client.post(url("auth/refresh")) { jsonBody(RefreshRequest(current.refreshToken)) }.decodeEnvelope()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            clearSession(SessionInvalidationReason.EXPIRED); throw SessionExpiredException()
        }
        if (envelope.code != 200 || envelope.data == null) {
            clearSession(if (envelope.code == NetworkDefaults.TOKEN_REVOKED) SessionInvalidationReason.REVOKED else SessionInvalidationReason.EXPIRED)
            throw SessionExpiredException()
        }
        val refreshed = StoredSession(
            envelope.data.accessToken, envelope.data.refreshToken,
            now() + envelope.data.expiresIn * 1000L, serverBaseUrl,
        )
        secureSessionStore.write(refreshed)
        sessionState.value = refreshed
        refreshed
    }

    internal suspend inline fun <reified T> HttpResponse.decodeEnvelope(): ApiResponse<T> = try {
        body()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Throwable) {
        val key = when (status.value) {
            401 -> "error.unauthorized"; 403 -> "error.forbidden"; 404 -> "error.notFound"
            409 -> "error.fileNameConflict"; 429 -> "error.rateLimited"; in 500..599 -> "error.internal"
            else -> "error.network"
        }
        throw ApiException(status.value, key)
    }

    private suspend fun HttpResponse.unauthorizedCode(): Int = try {
        decodeEnvelope<JsonElement>().code
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (_: Throwable) { HttpStatusCode.Unauthorized.value }

    internal fun <T> ApiResponse<T>.requireData(): T {
        if (code != 200 || data == null) throw ApiException(code, messageKey, messageParams, requestId)
        return data
    }

    internal fun url(path: String): String = ServerAddress.apiUrl(serverBaseUrl, path, allowCleartextServer)
    internal fun HttpRequestBuilder.bearer(token: String) = header(HttpHeaders.Authorization, "Bearer $token")
    internal inline fun <reified T> HttpRequestBuilder.jsonBody(value: T) {
        header(HttpHeaders.ContentType, ContentType.Application.Json); setBody(value)
    }
}

private sealed interface AuthStreamingAttempt<out T> {
    data class Consumed<T>(val value: T) : AuthStreamingAttempt<T>
    data class Unauthorized(val code: Int) : AuthStreamingAttempt<Nothing>
}

private class MemoryHttpSessionStore : SecureSessionStore {
    private var value: StoredSession? = null
    override suspend fun read(serverBaseUrl: String): StoredSession? = value?.takeIf { it.serverBaseUrl == serverBaseUrl }
    override suspend fun write(session: StoredSession) { value = session }
    override suspend fun clear() { value = null }
}

private class MemoryHttpServerSettingsStore(initial: String) : ServerSettingsStore {
    private val state = MutableStateFlow(initial)
    override val serverBaseUrl: Flow<String> = state
    override suspend fun read(): String = state.value
    override suspend fun write(value: String) { state.value = value }
}
