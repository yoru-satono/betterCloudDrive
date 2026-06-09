package com.betterclouddrive.android.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "bcd_tokens")

@Singleton
class TokenManager @Inject constructor(private val context: Context) {

    companion object {
        private val KEY_ACCESS_TOKEN = stringPreferencesKey("access_token")
        private val KEY_REFRESH_TOKEN = stringPreferencesKey("refresh_token")
        private val KEY_USER_ID = stringPreferencesKey("user_id")
        private val KEY_USERNAME = stringPreferencesKey("username")
    }

    val accessTokenFlow: Flow<String?> = context.dataStore.data.map { it[KEY_ACCESS_TOKEN] }
    val refreshTokenFlow: Flow<String?> = context.dataStore.data.map { it[KEY_REFRESH_TOKEN] }

    suspend fun getAccessToken(): String? = context.dataStore.data.first()[KEY_ACCESS_TOKEN]
    suspend fun getRefreshToken(): String? = context.dataStore.data.first()[KEY_REFRESH_TOKEN]

    suspend fun saveTokens(accessToken: String, refreshToken: String) {
        context.dataStore.edit {
            it[KEY_ACCESS_TOKEN] = accessToken
            it[KEY_REFRESH_TOKEN] = refreshToken
        }
    }

    suspend fun saveUserInfo(userId: Long, username: String) {
        context.dataStore.edit {
            it[KEY_USER_ID] = userId.toString()
            it[KEY_USERNAME] = username
        }
    }

    suspend fun clearAll() {
        context.dataStore.edit { it.clear() }
    }
}
