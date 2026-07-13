package com.betterclouddrive.android.ui

import androidx.compose.runtime.*
import com.betterclouddrive.android.ui.navigation.BCDNavHost
import com.betterclouddrive.android.ui.theme.BCDTheme
import com.betterclouddrive.android.ui.theme.ThemeViewModel
import androidx.hilt.navigation.compose.hiltViewModel

@Composable
fun BCDApp(
    initialTeamInviteToken: String? = null,
    themeViewModel: ThemeViewModel = hiltViewModel(),
) {
    val themeMode by themeViewModel.themeMode.collectAsState()
    BCDTheme(themeMode = themeMode) {
        BCDNavHost(initialTeamInviteToken = initialTeamInviteToken)
    }
}
