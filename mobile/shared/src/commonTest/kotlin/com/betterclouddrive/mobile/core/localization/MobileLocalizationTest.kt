package com.betterclouddrive.mobile.core.localization

import kotlin.test.Test
import kotlin.test.assertEquals

class MobileLocalizationTest {
    @Test
    fun simplifiedChineseRequiresAnExplicitSupportedRegion() {
        listOf("zh-Hans-CN", "zh-CN", "zh_CN").forEach { tag ->
            assertEquals(MobileLocale.SIMPLIFIED_CHINESE_CN, resolveMobileLocale(listOf(tag)), tag)
        }
        listOf("zh-Hans-SG", "zh-SG", "zh_SG").forEach { tag ->
            assertEquals(MobileLocale.SIMPLIFIED_CHINESE_SG, resolveMobileLocale(listOf(tag)), tag)
        }
    }

    @Test
    fun traditionalAndUnqualifiedChineseFallBackToEnglish() {
        listOf("zh", "zh-Hans", "zh-Hant", "zh-TW", "zh-HK", "zh-MO", "zh-Hant-CN").forEach { tag ->
            assertEquals(MobileLocale.ENGLISH_US, resolveMobileLocale(listOf(tag)), tag)
        }
    }

    @Test
    fun unsupportedLanguagesFallBackToEnglishAndPriorityIsPreserved() {
        assertEquals(MobileLocale.ENGLISH_US, resolveMobileLocale(emptyList()))
        assertEquals(MobileLocale.SIMPLIFIED_CHINESE_CN, resolveMobileLocale(listOf("fr-FR", "zh-CN")))
        assertEquals(MobileLocale.SIMPLIFIED_CHINESE_CN, resolveMobileLocale(listOf("zh-CN", "en-US")))
    }

    @Test
    fun bothCatalogsAreCompleteAndFormatArguments() {
        assertEquals(MobileString.entries.size, MobileString.entries.map { it.resourceKey }.distinct().size)
        val english = MobileStrings(
            MobileLocale.ENGLISH_US,
            MobileString.entries.associateWith { if (it == MobileString.ErrorRequestWithId) "Request failed (request ID: {0})" else it.name },
        )
        val chinese = MobileStrings(
            MobileLocale.SIMPLIFIED_CHINESE_CN,
            MobileString.entries.associateWith { if (it == MobileString.ErrorRequestWithId) "请求失败（请求 ID：{0}）" else it.name },
        )
        assertEquals(MobileString.entries.toSet(), english.keys())
        assertEquals(MobileString.entries.toSet(), chinese.keys())
        assertEquals(
            "Request failed (request ID: abc)",
            english.resolve(UiMessage(MobileString.ErrorRequestWithId, listOf("abc"))),
        )
        assertEquals(
            "请求失败（请求 ID：abc）",
            chinese.resolve(UiMessage(MobileString.ErrorRequestWithId, listOf("abc"))),
        )
    }
}
