package com.betterclouddrive.mobile.android

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.betterclouddrive.mobile.core.platform.ThemeMode
import kotlinx.coroutines.launch
import com.betterclouddrive.mobile.app.BetterCloudDriveApp

class MainActivity : AppCompatActivity() {
    private val appContainer get() = (application as BetterCloudDriveApplication).container

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = appContainer
        setContent {
            BetterCloudDriveApp(container, debugToolsEnabled = BuildConfig.DEBUG)
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                container.themePreferences.themeMode.collect { mode ->
                    val dark = when (mode) {
                        ThemeMode.SYSTEM -> (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
                            android.content.res.Configuration.UI_MODE_NIGHT_YES
                        ThemeMode.LIGHT -> false
                        ThemeMode.DARK -> true
                    }
                    WindowCompat.getInsetsController(window, window.decorView).apply {
                        isAppearanceLightStatusBars = !dark
                        isAppearanceLightNavigationBars = !dark
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        appContainer.reconcileTransfers()
    }
}
