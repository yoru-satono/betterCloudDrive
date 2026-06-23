package com.betterclouddrive.android.data.remote.interceptor

import com.betterclouddrive.android.data.local.TokenManager
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.Response
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AuthInterceptor @Inject constructor(
    private val tokenManager: TokenManager,
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val originalRequest = chain.request()

        // Skip auth header for public endpoints
        val path = originalRequest.url.encodedPath
        val method = originalRequest.method
        val isPublic = isPublicEndpoint(method, path)

        if (isPublic) {
            return chain.proceed(originalRequest)
        }

        val token = runBlocking { tokenManager.getAccessToken() }
        if (token.isNullOrBlank()) {
            return chain.proceed(originalRequest)
        }

        val authenticatedRequest = originalRequest.newBuilder()
            .header("Authorization", "Bearer $token")
            .build()

        return chain.proceed(authenticatedRequest)
    }

    private fun isPublicEndpoint(method: String, path: String): Boolean {
        if (path.contains("auth/register") ||
            path.contains("auth/register-code/send") ||
            path.contains("auth/login") ||
            path.contains("auth/refresh") ||
            path.contains("auth/forgot-password") ||
            path.contains("auth/reset-password")
        ) {
            return true
        }

        if (!path.contains("shares/access")) {
            return false
        }

        return when {
            method == "POST" && path.contains("/download/") -> true
            method == "GET" && path.endsWith("/files") -> true
            method == "POST" && !path.contains("/save") && !path.contains("/download/") -> true
            else -> false
        }
    }
}
