package com.betterclouddrive.android.ui

import androidx.compose.runtime.*
import com.betterclouddrive.android.ui.navigation.BCDNavHost
import com.betterclouddrive.android.ui.theme.BCDTheme

@Composable
fun BCDApp() {
    BCDTheme {
        BCDNavHost()
    }
}
