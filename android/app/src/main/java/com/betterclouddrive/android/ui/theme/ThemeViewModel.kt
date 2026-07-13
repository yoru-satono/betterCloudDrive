package com.betterclouddrive.android.ui.theme

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.betterclouddrive.android.data.local.ThemePreferenceStore
import com.betterclouddrive.android.domain.model.ThemeMode
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ThemeViewModel @Inject constructor(
    private val preferenceStore: ThemePreferenceStore,
) : ViewModel() {
    val themeMode: StateFlow<ThemeMode> = preferenceStore.themeModeFlow.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = ThemeMode.SYSTEM,
    )

    fun setThemeMode(themeMode: ThemeMode) {
        viewModelScope.launch { preferenceStore.setThemeMode(themeMode) }
    }
}
