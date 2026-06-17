package com.betterclouddrive.android.e2e

import androidx.test.platform.app.InstrumentationRegistry

object E2EConfig {
    private const val DEFAULT_SERVER_BASE_URL = "http://10.0.2.2:8080"
    private const val DEFAULT_MAILPIT_BASE_URL = "http://10.0.2.2:8025"

    val serverBaseUrl: String
        get() = argument("bcd.serverBaseUrl") ?: DEFAULT_SERVER_BASE_URL

    val apiBaseUrl: String
        get() = "${serverBaseUrl.trimEnd('/')}/api/v1"

    val mailpitApiBaseUrl: String
        get() = "${(argument("bcd.mailpitBaseUrl") ?: DEFAULT_MAILPIT_BASE_URL).trimEnd('/')}/api/v1"

    private fun argument(name: String): String? {
        return InstrumentationRegistry.getArguments().getString(name)?.trim()?.takeIf { it.isNotEmpty() }
    }
}
