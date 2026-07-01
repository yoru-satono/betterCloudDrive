package com.betterclouddrive.android.ui

import androidx.compose.runtime.*
import com.betterclouddrive.android.ui.navigation.BCDNavHost
import com.betterclouddrive.android.ui.theme.BCDTheme

@Composable
fun BCDApp(initialTeamInviteToken: String? = null) {
    BCDTheme {
        BCDNavHost(initialTeamInviteToken = initialTeamInviteToken)
    }
}
