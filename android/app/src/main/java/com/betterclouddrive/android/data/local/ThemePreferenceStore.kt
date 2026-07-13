package com.betterclouddrive.android.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.betterclouddrive.android.domain.model.ThemeMode
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.appearanceDataStore: DataStore<Preferences> by preferencesDataStore(name = "bcd_appearance")

@Singleton
class ThemePreferenceStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val themeModeKey = stringPreferencesKey("theme_mode")

    val themeModeFlow: Flow<ThemeMode> = context.appearanceDataStore.data.map { preferences ->
        ThemeMode.fromStorage(preferences[themeModeKey])
    }

    suspend fun setThemeMode(themeMode: ThemeMode) {
        context.appearanceDataStore.edit { preferences ->
            preferences[themeModeKey] = themeMode.storageValue
        }
    }
}
