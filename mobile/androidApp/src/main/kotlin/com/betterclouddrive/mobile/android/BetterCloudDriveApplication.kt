package com.betterclouddrive.mobile.android

import android.app.Application
import com.betterclouddrive.mobile.app.AppContainer
import com.betterclouddrive.mobile.platform.AndroidAppContainerFactory
import com.betterclouddrive.mobile.platform.AndroidRuntime

class BetterCloudDriveApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AndroidAppContainerFactory.create(this)
        AndroidRuntime.install(container)
    }
}
