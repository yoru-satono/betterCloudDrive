package com.betterclouddrive.android

import android.app.Application
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class BCDApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        com.betterclouddrive.android.util.MessageResolver.initialize(this)
    }
}
