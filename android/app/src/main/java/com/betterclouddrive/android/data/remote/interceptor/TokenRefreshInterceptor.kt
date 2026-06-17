package com.betterclouddrive.android.data.remote.interceptor

import com.betterclouddrive.android.data.local.TokenManager
import com.betterclouddrive.android.data.local.ServerConfigStore
import com.betterclouddrive.android.data.remote.dto.ApiResponse
import com.betterclouddrive.android.data.remote.dto.AuthTokens
import com.betterclouddrive.android.data.remote.dto.RefreshRequest
import com.betterclouddrive.android.util.ServerUrlUtil
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TokenRefreshInterceptor @Inject constructor(
    private val tokenManager: TokenManager,
    private val serverConfigStore: ServerConfigStore,
    private val json: Json,
) : Interceptor {

    private val mutex = Mutex()

    override fun intercept(chain: Interceptor.Chain): Response {
        val originalRequest = chain.request()
        val response = chain.proceed(originalRequest)

        // Only handle 401001 (token expired)
        if (response.code != 401) return response
        val bodyString = response.peekBody(Long.MAX_VALUE).string()
        val code = try {
            json.decodeFromString<ApiResponse<Unit>>(bodyString).code
        } catch (e: Exception) {
            null
        }

        if (code != 401001) return response

        // Close the error response
        response.close()

        return runBlocking {
            mutex.withLock {
                val refreshToken = tokenManager.getRefreshToken()
                if (refreshToken.isNullOrBlank()) return@runBlocking response

                val refreshRequest = RefreshRequest(refreshToken)
                val refreshBody = json.encodeToString(RefreshRequest.serializer(), refreshRequest)
                val refreshUrl = ServerUrlUtil.apiBaseUrl(serverConfigStore.getServerBaseUrl())
                    .newBuilder()
                    .addPathSegments("auth/refresh")
                    .build()
                val refreshCall = Request.Builder()
                    .url(refreshUrl)
                    .post(refreshBody.toRequestBody("application/json".toMediaType()))
                    .build()

                try {
                    val refreshResponse = chain.proceed(refreshCall)
                    if (refreshResponse.isSuccessful) {
                        val respBody = refreshResponse.body?.string() ?: return@runBlocking response
                        refreshResponse.close()

                        val apiResp = json.decodeFromString<ApiResponse<AuthTokens>>(respBody)
                        apiResp.data?.let { tokens ->
                            tokenManager.saveTokens(tokens.accessToken, tokens.refreshToken)

                            // Retry original request with new token
                            val newRequest = originalRequest.newBuilder()
                                .header("Authorization", "Bearer ${tokens.accessToken}")
                                .build()
                            return@runBlocking chain.proceed(newRequest)
                        }
                    }
                    refreshResponse.close()
                } catch (e: Exception) {
                    // Refresh failed — clear tokens, caller should navigate to login
                    tokenManager.clearAll()
                }
                response
            }
        }
    }
}
