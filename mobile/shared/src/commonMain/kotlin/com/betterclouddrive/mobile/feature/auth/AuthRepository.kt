package com.betterclouddrive.mobile.feature.auth

import com.betterclouddrive.mobile.core.model.SessionRestoreResult
import com.betterclouddrive.mobile.core.model.UserProfile
import com.betterclouddrive.mobile.core.model.SessionInvalidationReason
import com.betterclouddrive.mobile.core.network.BackendClient
import kotlinx.coroutines.flow.Flow

class AuthRepository(private val backend: BackendClient) {
    val serverBaseUrl: String get() = backend.serverBaseUrl
    val sessionInvalidations: Flow<SessionInvalidationReason> get() = backend.sessionInvalidations

    suspend fun restore(): SessionRestoreResult = backend.restoreUser()
    suspend fun switchServer(value: String): Result<String> = backend.updateServer(value)
    fun normalizeServer(value: String): Result<String> = backend.normalizeServer(value)
    suspend fun login(username: String, password: String): UserProfile = backend.login(username, password)
    suspend fun logout() = backend.logout()
}
