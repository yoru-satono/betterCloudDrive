package com.betterclouddrive.android.util

import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import java.util.Locale

object LocaleController {
    const val ZH_CN = "zh-CN"
    const val EN_US = "en-US"

    fun current(): String = AppCompatDelegate.getApplicationLocales().toLanguageTags()
        .takeIf { it == ZH_CN || it == EN_US }
        ?: if (Locale.getDefault().language == "zh") ZH_CN else EN_US

    fun set(locale: String) {
        val normalized = if (locale == EN_US) EN_US else ZH_CN
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(normalized))
    }
}
