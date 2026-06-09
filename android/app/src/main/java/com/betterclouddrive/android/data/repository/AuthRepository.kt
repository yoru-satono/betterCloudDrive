package com.betterclouddrive.android.data.repository

import com.betterclouddrive.android.data.local.TokenManager
import com.betterclouddrive.android.data.remote.ApiService
import com.betterclouddrive.android.data.remote.dto.*
import com.betterclouddrive.android.domain.model.User
import com.betterclouddrive.android.util.NetworkResult
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AuthRepository @Inject constructor(
    private val api: ApiService,
    private val tokenManager: TokenManager,
) {
    suspend fun register(username: String, password: String, email: String?): NetworkResult<RegisterResult> = apiCall {
        api.register(RegisterRequest(username, password, email))
    }

    suspend fun login(username: String, password: String): NetworkResult<AuthTokens> = apiCall {
        api.login(LoginRequest(username, password))
    }

    suspend fun refresh(): NetworkResult<AuthTokens> {
        val rt = tokenManager.getRefreshToken() ?: return NetworkResult.Error(401, "No refresh token")
        return apiCall { api.refresh(RefreshRequest(rt)) }
    }

    suspend fun logout(): NetworkResult<Unit> = apiCall { api.logout() }

    suspend fun getMe(): NetworkResult<User> = apiCall { api.getMe() }

    suspend fun sendVerificationCode(): NetworkResult<Unit> = apiCall { api.sendVerificationCode() }

    suspend fun verifyEmail(code: String): NetworkResult<Unit> = apiCall { api.verifyEmail(VerifyEmailRequest(code)) }

    suspend fun forgotPassword(email: String): NetworkResult<Unit> = apiCall { api.forgotPassword(ForgotPasswordRequest(email)) }

    suspend fun resetPassword(email: String, code: String, newPassword: String): NetworkResult<Unit> = apiCall {
        api.resetPassword(ResetPasswordRequest(email, code, newPassword))
    }

    suspend fun saveTokens(accessToken: String, refreshToken: String) {
        tokenManager.saveTokens(accessToken, refreshToken)
    }

    suspend fun clearTokens() {
        tokenManager.clearAll()
    }
}
