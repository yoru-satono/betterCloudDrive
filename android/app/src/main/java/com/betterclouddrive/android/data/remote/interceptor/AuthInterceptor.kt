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

    // Public endpoints that don't need Authorization header
    private val publicPaths = setOf(
        "auth/register",
        "auth/login",
        "auth/refresh",
        "auth/forgot-password",
        "auth/reset-password",
        "shares/access",
    )

    override fun intercept(chain: Interceptor.Chain): Response {
        val originalRequest = chain.request()

        // Skip auth header for public endpoints
        val path = originalRequest.url.encodedPath
        val isPublic = publicPaths.any { path.contains(it) }

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
}
