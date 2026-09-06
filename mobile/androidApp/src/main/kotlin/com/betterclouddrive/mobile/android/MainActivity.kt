package com.betterclouddrive.mobile.android

import android.os.Bundle
import android.content.Intent
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.betterclouddrive.mobile.core.platform.ThemeMode
import com.betterclouddrive.mobile.feature.auth.AppPhase
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import com.betterclouddrive.mobile.app.BetterCloudDriveApp
import com.betterclouddrive.mobile.feature.teams.parseIncomingInviteDeepLink

class MainActivity : AppCompatActivity() {
    private val appContainer get() = (application as BetterCloudDriveApplication).container
    private var inviteIntentJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = appContainer
        handleInviteIntent(intent)
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

    override fun onStart() {
        super.onStart()
        appContainer.notificationsStore.setForeground(true)
    }

    override fun onStop() {
        appContainer.notificationsStore.setForeground(false)
        super.onStop()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleInviteIntent(intent)
    }

    private fun handleInviteIntent(intent: Intent?) {
        val token = parseIncomingInviteDeepLink(intent?.dataString) ?: return
        inviteIntentJob?.cancel()
        inviteIntentJob = lifecycleScope.launch {
            appContainer.authStore.state.first { it.phase != AppPhase.LOADING }
            appContainer.incomingInviteStore.open(token)
        }
    }
}
