package com.betterclouddrive.mobile.feature.auth

import com.betterclouddrive.mobile.core.model.SessionRestoreResult
import com.betterclouddrive.mobile.core.model.UserProfile
import com.betterclouddrive.mobile.core.model.RegistrationResult
import com.betterclouddrive.mobile.core.model.SessionInvalidationReason
import com.betterclouddrive.mobile.core.network.AuthApi
import kotlinx.coroutines.flow.Flow

interface AuthDataSource {
    val serverBaseUrl: String
    val sessionInvalidations: Flow<SessionInvalidationReason>
    suspend fun restore(): SessionRestoreResult
    suspend fun switchServer(value: String): Result<String>
    fun normalizeServer(value: String): Result<String>
    suspend fun login(username: String, password: String): UserProfile
    suspend fun sendRegistrationCode(email: String, preferredLocale: String)
    suspend fun register(
        username: String, password: String, email: String, verificationCode: String, preferredLocale: String,
    ): RegistrationResult
    suspend fun forgotPassword(email: String)
    suspend fun resetPassword(email: String, code: String, newPassword: String)
    suspend fun getCurrentUser(): UserProfile
    suspend fun updatePreferredLocale(locale: String): UserProfile
    suspend fun logout()
}

class AuthRepository(private val api: AuthApi) : AuthDataSource {
    override val serverBaseUrl: String get() = api.serverBaseUrl
    override val sessionInvalidations: Flow<SessionInvalidationReason> get() = api.sessionInvalidations

    override suspend fun restore(): SessionRestoreResult = api.restoreUser()
    override suspend fun switchServer(value: String): Result<String> = api.updateServer(value)
    override fun normalizeServer(value: String): Result<String> = api.normalizeServer(value)
    override suspend fun login(username: String, password: String): UserProfile = api.login(username, password)
    override suspend fun sendRegistrationCode(email: String, preferredLocale: String) =
        api.sendRegistrationCode(email, preferredLocale)
    override suspend fun register(
        username: String, password: String, email: String, verificationCode: String, preferredLocale: String,
    ): RegistrationResult = api.register(username, password, email, verificationCode, preferredLocale)
    override suspend fun forgotPassword(email: String) = api.forgotPassword(email)
    override suspend fun resetPassword(email: String, code: String, newPassword: String) =
        api.resetPassword(email, code, newPassword)
    override suspend fun getCurrentUser(): UserProfile = api.getMe()
    override suspend fun updatePreferredLocale(locale: String): UserProfile = api.updatePreferredLocale(locale)
    override suspend fun logout() = api.logout()
}
