package com.betterclouddrive.android.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.betterclouddrive.android.util.Constants
import com.betterclouddrive.android.util.ServerUrlUtil
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.serverSettingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "bcd_settings")

@Singleton
class ServerConfigStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    companion object {
        private val KEY_SERVER_BASE_URL = stringPreferencesKey("server_base_url")
    }

    val serverBaseUrlFlow: Flow<String> = context.serverSettingsDataStore.data.map { preferences ->
        preferences[KEY_SERVER_BASE_URL] ?: Constants.DEFAULT_SERVER_BASE_URL
    }

    suspend fun getServerBaseUrl(): String {
        return context.serverSettingsDataStore.data.first()[KEY_SERVER_BASE_URL] ?: Constants.DEFAULT_SERVER_BASE_URL
    }

    suspend fun saveServerBaseUrl(input: String): Result<String> {
        val normalized = ServerUrlUtil.normalizeBaseUrl(input)
        if (normalized.isFailure) return Result.failure(normalized.exceptionOrNull() ?: IllegalArgumentException("服务器地址无效"))
        val baseUrl = normalized.getOrThrow()
        context.serverSettingsDataStore.edit { preferences ->
            preferences[KEY_SERVER_BASE_URL] = baseUrl
        }
        return Result.success(baseUrl)
    }
}
