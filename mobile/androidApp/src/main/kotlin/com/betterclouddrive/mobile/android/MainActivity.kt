package com.betterclouddrive.mobile.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.betterclouddrive.mobile.app.BetterCloudDriveApp

class MainActivity : ComponentActivity() {
    private val appContainer get() = (application as BetterCloudDriveApplication).container

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = appContainer
        setContent {
            BetterCloudDriveApp(container, debugToolsEnabled = BuildConfig.DEBUG)
        }
    }

    override fun onResume() {
        super.onResume()
        appContainer.reconcileTransfers()
    }
}
