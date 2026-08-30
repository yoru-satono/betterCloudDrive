package com.betterclouddrive.mobile.android

import android.app.LocaleConfig
import android.app.Notification
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.betterclouddrive.mobile.app.ProductShell
import com.betterclouddrive.mobile.core.localization.LocalMobileStrings
import com.betterclouddrive.mobile.core.localization.mobileStringsFor
import com.betterclouddrive.mobile.designsystem.BetterCloudDriveTheme
import com.betterclouddrive.mobile.feature.transfers.TransferDirection
import com.betterclouddrive.mobile.feature.transfers.TransferScope
import com.betterclouddrive.mobile.feature.transfers.TransferStatus
import com.betterclouddrive.mobile.feature.transfers.TransferTask
import com.betterclouddrive.mobile.core.platform.TransferScheduleSpec
import com.betterclouddrive.mobile.platform.AndroidTransferScheduler
import com.betterclouddrive.mobile.platform.transferNotification
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ProductShellDeviceTest {
    @get:Rule val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Before
    fun setEnglishLocale() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val configuration = Configuration(composeRule.activity.resources.configuration)
            configuration.setLocales(LocaleList.forLanguageTags("en-US"))
            @Suppress("DEPRECATION")
            composeRule.activity.resources.updateConfiguration(configuration, composeRule.activity.resources.displayMetrics)
        }
    }

    @Test
    fun android13LanguageSettingsExposeOnlySupportedLocales() {
        if (Build.VERSION.SDK_INT < 33) return
        val config = LocaleConfig(InstrumentationRegistry.getInstrumentation().targetContext)
        assertEquals(LocaleConfig.STATUS_SUCCESS, config.status)
        val locales = requireNotNull(config.supportedLocales)
        assertEquals(setOf("en-US", "zh-CN", "zh-SG"), (0 until locales.size()).map { locales[it].toLanguageTag() }.toSet())
    }

    @Test
    fun transferNotificationUsesCurrentApplicationLanguage() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val task = TransferTask(
            id = "i18n", direction = TransferDirection.UPLOAD, status = TransferStatus.RUNNING,
            serverBaseUrl = "https://drive.example.com", scope = TransferScope.Personal(), fileName = "file.bin",
            bytesTotal = 1, createdAt = 1, updatedAt = 1,
        )
        fun localized(tag: String) = context.createConfigurationContext(
            Configuration(context.resources.configuration).apply { setLocales(LocaleList.forLanguageTags(tag)) },
        )
        val english = transferNotification(localized("en-US"), "i18n-en", "Progress", task)
        val chinese = transferNotification(localized("zh-CN"), "i18n-zh", "进度", task)
        assertEquals("BetterCloudDrive file transfer", english.extras.getString(Notification.EXTRA_TITLE))
        assertEquals(listOf("Pause", "Cancel"), english.actions.map { it.title.toString() })
        assertEquals("BetterCloudDrive 文件传输", chinese.extras.getString(Notification.EXTRA_TITLE))
        assertEquals(listOf("暂停", "取消"), chinese.actions.map { it.title.toString() })

        val singapore = transferNotification(localized("zh-SG"), "i18n-zh-sg", "进度", task)
        assertEquals("BetterCloudDrive 文件传输", singapore.extras.getString(Notification.EXTRA_TITLE))
        assertEquals(listOf("暂停", "取消"), singapore.actions.map { it.title.toString() })
        listOf("zh", "zh-Hans", "zh-Hant", "zh-TW", "zh-HK", "zh-MO").forEach { tag ->
            val fallback = transferNotification(localized(tag), "i18n-fallback-$tag", "Progress", task)
            assertEquals("BetterCloudDrive file transfer", fallback.extras.getString(Notification.EXTRA_TITLE))
            assertEquals(listOf("Pause", "Cancel"), fallback.actions.map { it.title.toString() })
        }
    }

    @Test
    fun compactShellUsesFeatureStoresForFourTopLevelDestinations() {
        setShellContent(wide = false, debugToolsEnabled = true)
        composeRule.onNodeWithTag("main-navigation-bar").assertIsDisplayed()
        composeRule.onNodeWithTag("nav-teams").performClick()
        composeRule.onNodeWithTag("teams-placeholder").assertIsDisplayed()
        composeRule.runOnUiThread { composeRule.activity.onBackPressedDispatcher.onBackPressed() }
        composeRule.onNodeWithTag("personal-content").assertIsDisplayed()

        composeRule.onNodeWithTag("nav-transfers").performClick()
        composeRule.onNodeWithTag("transfers-empty").assertIsDisplayed()
        composeRule.onNodeWithTag("nav-profile").performClick()
        composeRule.onNodeWithTag("profile-content").assertIsDisplayed()
        composeRule.onNodeWithTag("profile-content").performScrollToNode(hasTestTag("profile-diagnostics"))
        composeRule.onNodeWithTag("profile-diagnostics").performClick()
        composeRule.onNodeWithTag("diagnostics-content").assertIsDisplayed()
        composeRule.onNodeWithTag("main-navigation-bar").assertIsNotDisplayed()
        composeRule.onNodeWithTag("diagnostics-back").performClick()
        composeRule.onNodeWithTag("profile-content").assertIsDisplayed()
    }

    @Test
    fun wideShellUsesNavigationRailWithoutGlobalUiState() {
        setShellContent(wide = true, debugToolsEnabled = false)
        composeRule.onNodeWithTag("main-navigation-rail").assertIsDisplayed()
        composeRule.onNodeWithTag("nav-transfers").performClick()
        composeRule.onNodeWithTag("transfers-content").assertIsDisplayed()
    }

    @Test
    fun releaseConfigurationDoesNotExposeDiagnostics() {
        setShellContent(wide = false, debugToolsEnabled = false)
        composeRule.onNodeWithTag("nav-profile").performClick()
        composeRule.onNodeWithTag("profile-content").performScrollToNode(hasTestTag("profile-logout"))
        composeRule.onNodeWithTag("profile-diagnostics").assertIsNotDisplayed()
        composeRule.onNodeWithTag("diagnostics-content").assertIsNotDisplayed()
    }

    @Test
    fun organizeRoutesRemainInsidePersonalNavigationBranch() {
        setShellContent(wide = false, debugToolsEnabled = false)
        listOf(
            "personal-open-favorites" to "favorites-screen",
            "personal-open-tags" to "tags-screen",
            "personal-open-shares" to "shares-screen",
        ).forEach { (entry, destination) ->
            composeRule.onNodeWithTag("personal-organize").performClick()
            composeRule.onNodeWithTag(entry).performClick()
            composeRule.onNodeWithTag(destination).assertIsDisplayed()
            composeRule.onNodeWithTag("nav-personal").performClick()
            composeRule.onNodeWithTag("personal-content").assertIsDisplayed()
        }
    }

    @Test
    fun api34AcceptsVisibleUserInitiatedBatchJob() = runBlocking {
        if (Build.VERSION.SDK_INT < 34) return@runBlocking
        setShellContent(wide = false, debugToolsEnabled = false)
        composeRule.waitForIdle()
        val scheduler = AndroidTransferScheduler(InstrumentationRegistry.getInstrumentation().targetContext)
        val batchId = "architecture-uidt-${System.nanoTime()}"
        try {
            assertTrue(scheduler.schedule(TransferScheduleSpec(batchId, listOf("one", "two"), 1024, 2048)))
        } finally {
            scheduler.cancelAll()
        }
    }

    private fun setShellContent(wide: Boolean, debugToolsEnabled: Boolean) {
        val container = (composeRule.activity.application as BetterCloudDriveApplication).container
        composeRule.setContent {
            val configuration = Configuration(LocalConfiguration.current).apply { screenWidthDp = if (wide) 700 else 400 }
            CompositionLocalProvider(
                LocalConfiguration provides configuration,
                LocalMobileStrings provides mobileStringsFor(listOf("en-US")),
            ) {
                BetterCloudDriveTheme { ProductShell(container, debugToolsEnabled = debugToolsEnabled, wideLayoutOverride = wide) }
            }
        }
    }
}
