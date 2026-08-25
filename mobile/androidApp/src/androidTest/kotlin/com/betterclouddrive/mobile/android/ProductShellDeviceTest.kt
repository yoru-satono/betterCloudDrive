package com.betterclouddrive.mobile.android

import androidx.activity.ComponentActivity
import android.app.LocaleConfig
import android.app.Notification
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.betterclouddrive.mobile.app.AppPhase
import com.betterclouddrive.mobile.app.AppUiState
import com.betterclouddrive.mobile.app.DirectoryPageState
import com.betterclouddrive.mobile.app.PersonalFilesUiState
import com.betterclouddrive.mobile.app.ProductShell
import com.betterclouddrive.mobile.app.ProductShellActions
import com.betterclouddrive.mobile.app.FileMutationKind
import com.betterclouddrive.mobile.app.FolderPickerState
import com.betterclouddrive.mobile.app.FileMutationFeedback
import com.betterclouddrive.mobile.core.model.OfficeConfig
import com.betterclouddrive.mobile.core.model.FileItem
import com.betterclouddrive.mobile.core.model.UserProfile
import com.betterclouddrive.mobile.core.localization.UiMessage
import com.betterclouddrive.mobile.core.localization.MobileString
import com.betterclouddrive.mobile.core.localization.LocalMobileStrings
import com.betterclouddrive.mobile.core.localization.mobileStringsFor
import com.betterclouddrive.mobile.core.platform.DocumentPicker
import com.betterclouddrive.mobile.core.platform.OfficeRenderer
import com.betterclouddrive.mobile.core.platform.PickedDestination
import com.betterclouddrive.mobile.core.platform.PickedDocument
import com.betterclouddrive.mobile.platform.transferNotification
import com.betterclouddrive.mobile.designsystem.BetterCloudDriveTheme
import com.betterclouddrive.mobile.feature.files.BreadcrumbEntry
import com.betterclouddrive.mobile.feature.files.FileViewMode
import org.junit.Rule
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.Assert.assertEquals
import java.util.Locale

@RunWith(AndroidJUnit4::class)
class ProductShellDeviceTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun android13LanguageSettingsExposeOnlySupportedLocales() {
        if (Build.VERSION.SDK_INT < 33) return
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val config = LocaleConfig(context)
        assertEquals(LocaleConfig.STATUS_SUCCESS, config.status)
        val locales = requireNotNull(config.supportedLocales)
        assertEquals(setOf("en-US", "zh-CN", "zh-SG"), (0 until locales.size()).map { locales[it].toLanguageTag() }.toSet())
    }

    @Test
    fun transferNotificationUsesCurrentApplicationLanguage() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        fun localized(tag: String) = context.createConfigurationContext(
            Configuration(context.resources.configuration).apply { setLocales(LocaleList.forLanguageTags(tag)) },
        )

        val english = transferNotification(localized("en-US"), "i18n-en", "Progress")
        assertEquals("BetterCloudDrive file transfer", english.extras.getString(Notification.EXTRA_TITLE))
        assertEquals(listOf("Pause", "Cancel"), english.actions.map { it.title.toString() })

        val chinese = transferNotification(localized("zh-CN"), "i18n-zh", "进度")
        assertEquals("BetterCloudDrive 文件传输", chinese.extras.getString(Notification.EXTRA_TITLE))
        assertEquals(listOf("暂停", "取消"), chinese.actions.map { it.title.toString() })

        val singapore = transferNotification(localized("zh-SG"), "i18n-zh-sg", "进度")
        assertEquals("BetterCloudDrive 文件传输", singapore.extras.getString(Notification.EXTRA_TITLE))
        assertEquals(listOf("暂停", "取消"), singapore.actions.map { it.title.toString() })

        listOf("zh", "zh-Hans", "zh-Hant", "zh-TW", "zh-HK", "zh-MO").forEach { tag ->
            val fallback = transferNotification(localized(tag), "i18n-fallback-$tag", "Progress")
            assertEquals("BetterCloudDrive file transfer", fallback.extras.getString(Notification.EXTRA_TITLE))
            assertEquals(listOf("Pause", "Cancel"), fallback.actions.map { it.title.toString() })
        }
    }

    @Test
    fun compactShellNavigatesFourDestinationsAndDebugDetail() {
        setShell(debugToolsEnabled = true)

        composeRule.onNodeWithTag("main-navigation-bar").assertIsDisplayed()
        composeRule.onNodeWithTag("personal-empty").assertIsDisplayed()

        composeRule.onNodeWithTag("nav-teams").performClick()
        composeRule.onNodeWithTag("teams-placeholder").assertIsDisplayed()
        composeRule.runOnUiThread { composeRule.activity.onBackPressedDispatcher.onBackPressed() }
        composeRule.onNodeWithTag("personal-empty").assertIsDisplayed()

        composeRule.onNodeWithTag("nav-transfers").performClick()
        composeRule.onNodeWithTag("transfers-empty").assertIsDisplayed()
        composeRule.onNodeWithTag("nav-profile").performClick()
        composeRule.onNodeWithTag("profile-content").assertIsDisplayed()

        composeRule.onNodeWithTag("profile-diagnostics").performClick()
        composeRule.onNodeWithTag("diagnostics-content").assertIsDisplayed()
        composeRule.onNodeWithTag("main-navigation-bar").assertIsNotDisplayed()
        composeRule.onNodeWithTag("diagnostics-back").performClick()
        composeRule.onNodeWithTag("profile-content").assertIsDisplayed()
    }

    @Test
    fun releaseConfigurationDoesNotExposeDiagnostics() {
        setShell(debugToolsEnabled = false)
        composeRule.onNodeWithTag("nav-profile").performClick()
        composeRule.onNodeWithTag("profile-diagnostics").assertIsNotDisplayed()
        composeRule.onNodeWithTag("diagnostics-content").assertIsNotDisplayed()
    }

    @Test
    fun wideShellUsesNavigationRail() {
        val file = FileItem(id = 70, fileName = "wide-detail.txt", fileSize = 24)
        composeRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f)) {
                BetterCloudDriveTheme { TestShell(debugToolsEnabled = false, files = listOf(file)) }
            }
        }
        composeRule.onNodeWithTag("main-navigation-rail").assertIsDisplayed()
        composeRule.onNodeWithTag("main-navigation-bar").assertIsNotDisplayed()
        composeRule.onNodeWithTag("personal-file-70").performClick()
        composeRule.onNodeWithTag("personal-detail-pane").assertIsDisplayed()
        composeRule.onNodeWithTag("personal-file-detail-70").assertIsDisplayed()
    }

    @Test
    fun personalScrollPositionSurvivesTopLevelNavigation() {
        val files = (1L..40L).map { id ->
            FileItem(id = id, fileName = "file-$id.txt", fileSize = id)
        }
        composeRule.setContent {
            BetterCloudDriveTheme { TestShell(debugToolsEnabled = false, files = files) }
        }

        composeRule.onNodeWithTag("personal-content")
        composeRule.onNodeWithTag("personal-file-list")
            .performScrollToNode(hasTestTag("personal-file-40"))
        composeRule.onNodeWithTag("personal-file-40").assertIsDisplayed()
        composeRule.onNodeWithTag("nav-teams").performClick()
        composeRule.onNodeWithTag("nav-personal").performClick()
        composeRule.onNodeWithTag("personal-file-40").assertIsDisplayed()
    }

    @Test
    fun compactBrowserSwitchesViewAndShowsFileDetailSheet() {
        val file = FileItem(id = 7, fileName = "detail.txt", fileSize = 12)
        composeRule.setContent { BetterCloudDriveTheme { TestShell(false, listOf(file)) } }

        composeRule.onNodeWithTag("personal-view-mode").performClick()
        composeRule.onNodeWithTag("personal-file-grid").assertIsDisplayed()
        composeRule.onNodeWithTag("personal-file-7").performClick()
        composeRule.onNodeWithTag("personal-detail-sheet").assertIsDisplayed()
        composeRule.onNodeWithTag("personal-file-detail-7").assertIsDisplayed()
    }

    @Test
    fun fileManagementExposesCreateRenameMoveAndBatchDelete() {
        val folder = FileItem(id = 10, fileName = "Docs", fileType = "folder")
        val file = FileItem(id = 11, fileName = "note.txt")
        composeRule.setContent { BetterCloudDriveTheme { TestShell(false, listOf(folder, file)) } }

        composeRule.onNodeWithTag("personal-create-folder").performClick()
        composeRule.onNodeWithTag("personal-name-dialog").assertIsDisplayed()
        composeRule.onNodeWithText("Cancel").performClick()

        composeRule.onNodeWithTag("personal-more-11").performClick()
        composeRule.onNodeWithTag("personal-rename-11").performClick()
        composeRule.onNodeWithTag("personal-name-dialog").assertIsDisplayed()
        composeRule.onNodeWithText("Cancel").performClick()

        composeRule.onNodeWithTag("personal-more-11").performClick()
        composeRule.onNodeWithTag("personal-move-11").performClick()
        composeRule.onNodeWithTag("personal-destination-sheet").assertIsDisplayed()
        composeRule.onNodeWithTag("personal-destination-confirm").assertIsDisplayed()
        composeRule.onNodeWithText("Cancel").performClick()

        composeRule.onNodeWithTag("personal-file-10").performTouchInput { longClick() }
        composeRule.onNodeWithTag("personal-file-11").performClick()
        composeRule.onNodeWithTag("personal-selection-bar").assertIsDisplayed()
        composeRule.onNodeWithTag("personal-selection-delete").performClick()
        composeRule.onNodeWithTag("personal-delete-dialog").assertIsDisplayed()
    }

    @Test
    fun folderRouteShowsParentNavigationAndReturnsToRoot() {
        val folder = FileItem(id = 10, fileName = "一级", fileType = "folder")
        val child = FileItem(id = 11, parentId = 10, fileName = "二级", fileType = "folder")
        val grandchild = FileItem(id = 12, parentId = 11, fileName = "三级", fileType = "folder")
        val tree = mapOf<Long?, List<FileItem>>(
            null to listOf(folder),
            10L to listOf(child),
            11L to listOf(grandchild),
            12L to emptyList(),
        )
        composeRule.setContent { BetterCloudDriveTheme { TestShell(false, tree = tree) } }

        composeRule.onNodeWithTag("personal-file-10").performClick()
        composeRule.onNodeWithTag("personal-file-11").performClick()
        composeRule.onNodeWithTag("personal-file-12").performClick()
        composeRule.onNodeWithTag("personal-parent").assertIsDisplayed()
        composeRule.onNodeWithTag("personal-breadcrumb-12").assertIsDisplayed()
        composeRule.onNodeWithTag("personal-parent").performClick()
        composeRule.onNodeWithTag("personal-folder-11").assertIsDisplayed()
        composeRule.onNodeWithTag("nav-personal").performClick()
        composeRule.onNodeWithTag("personal-file-10").assertIsDisplayed()
    }

    @Test
    fun englishCatalogRendersWithoutSimplifiedChineseFallback() {
        setActivityLocale("en-US")
        composeRule.setContent { BetterCloudDriveTheme { TestShell(false) } }
        composeRule.onNodeWithText("Personal").assertIsDisplayed()
        composeRule.onNodeWithText("Personal space is empty").assertIsDisplayed()
    }

    @Test
    fun simplifiedChineseCatalogRendersForMainlandLocale() {
        setActivityLocale("zh-CN")
        composeRule.setContent { BetterCloudDriveTheme { TestShell(false) } }
        composeRule.onNodeWithText("个人").assertIsDisplayed()
        composeRule.onNodeWithText("个人空间为空").assertIsDisplayed()
    }

    @Test
    fun simplifiedChineseCatalogRendersForSingaporeLocale() {
        setActivityLocale("zh-SG")
        composeRule.setContent { BetterCloudDriveTheme { TestShell(false) } }
        composeRule.onNodeWithText("个人").assertIsDisplayed()
        composeRule.onNodeWithText("个人空间为空").assertIsDisplayed()
    }

    @Test
    fun simplifiedChineseFileManagementControlsUseLocalizedResources() {
        setActivityLocale("zh-CN")
        val file = FileItem(id = 81, fileName = "notes.txt")
        composeRule.setContent { BetterCloudDriveTheme { TestShell(false, listOf(file)) } }

        composeRule.onNodeWithText("新建文件夹").assertIsDisplayed()
        composeRule.onNodeWithTag("personal-create-folder").performClick()
        composeRule.onNodeWithText("文件夹名称").assertIsDisplayed()
        composeRule.onNodeWithText("取消").performClick()

        composeRule.onNodeWithTag("personal-more-81").performClick()
        composeRule.onNodeWithText("重命名").assertIsDisplayed()
        composeRule.onNodeWithText("移动").assertIsDisplayed()
        composeRule.onNodeWithText("复制").assertIsDisplayed()
        composeRule.onNodeWithText("移入回收站").assertIsDisplayed()
    }

    @Test
    fun traditionalChineseCatalogFallsBackToEnglish() {
        setActivityLocale("zh-TW")
        composeRule.setContent { BetterCloudDriveTheme { TestShell(false) } }
        composeRule.onNodeWithText("Personal").assertIsDisplayed()
        composeRule.onNodeWithText("Personal space is empty").assertIsDisplayed()
    }

    @After
    fun resetActivityLocale() {
        setActivityLocale("en-US")
    }

    @Suppress("DEPRECATION")
    private fun setActivityLocale(languageTag: String) {
        composeRule.activityRule.scenario.onActivity { activity ->
            val locale = Locale.forLanguageTag(languageTag)
            Locale.setDefault(locale)
            val configuration = Configuration(activity.resources.configuration).apply {
                setLocales(LocaleList(locale))
            }
            activity.resources.updateConfiguration(configuration, activity.resources.displayMetrics)
        }
    }

    private fun setShell(debugToolsEnabled: Boolean) {
        composeRule.setContent {
            BetterCloudDriveTheme { TestShell(debugToolsEnabled) }
        }
    }
}

@Composable
private fun TestShell(
    debugToolsEnabled: Boolean,
    files: List<FileItem> = emptyList(),
    tree: Map<Long?, List<FileItem>> = emptyMap(),
) {
    val rootItems = tree[null] ?: files
    val root = remember(rootItems) {
        DirectoryPageState(items = rootItems, hasLoaded = true, page = 1, pages = 1, total = rootItems.size.toLong())
    }
    var personal by remember(files) { mutableStateOf(PersonalFilesUiState(current = root)) }
    val locales = LocalConfiguration.current.locales
    val strings = mobileStringsFor((0 until locales.size()).map { locales[it].toLanguageTag() })
    CompositionLocalProvider(LocalMobileStrings provides strings) {
      ProductShell(
        state = AppUiState(
            phase = AppPhase.LOGGED_IN,
            server = "https://drive.example.com",
            user = UserProfile(id = 1, username = "test-user", email = "test@example.com"),
            personalFiles = personal,
        ),
        documentPicker = TestDocumentPicker,
        officeRenderer = TestOfficeRenderer,
        roomTaskCount = { 0 },
        actions = ProductShellActions(
            activatePersonalRoute = { folderId ->
                if (folderId == null) {
                    personal = personal.copy(
                        breadcrumb = listOf(BreadcrumbEntry(null, "根目录")),
                        current = root,
                        selectedFile = null,
                    )
                } else {
                    val index = personal.breadcrumb.indexOfFirst { it.id == folderId }
                    personal = personal.copy(
                        breadcrumb = if (index >= 0) personal.breadcrumb.take(index + 1) else personal.breadcrumb,
                        current = DirectoryPageState(
                            folderId = folderId,
                            items = tree[folderId].orEmpty(),
                            hasLoaded = true,
                            page = 1,
                            pages = 1,
                            total = tree[folderId].orEmpty().size.toLong(),
                        ),
                        selectedFile = null,
                    )
                }
            },
            prepareOpenFolder = { folder ->
                val children = tree[folder.id].orEmpty()
                personal = personal.copy(
                    breadcrumb = personal.breadcrumb + BreadcrumbEntry(folder.id, folder.fileName),
                    current = DirectoryPageState(
                        folderId = folder.id,
                        items = children,
                        hasLoaded = true,
                        page = 1,
                        pages = 1,
                        total = children.size.toLong(),
                    ),
                    selectedFile = null,
                )
            },
            refreshFiles = {},
            loadMoreFiles = {},
            changeFileSort = { sort, direction -> personal = personal.copy(sort = sort, direction = direction) },
            changeFileViewMode = { personal = personal.copy(viewMode = it) },
            selectPersonalFile = { personal = personal.copy(selectedFile = it) },
            togglePersonalSelection = { id ->
                personal = personal.copy(selectedIds = personal.selectedIds.toMutableSet().apply {
                    if (!add(id)) remove(id)
                })
            },
            clearPersonalSelection = { personal = personal.copy(selectedIds = emptySet()) },
            clearPersonalTransientUi = { personal = personal.copy(selectedIds = emptySet(), folderPicker = null) },
            createPersonalFolder = { name ->
                val created = FileItem(id = 999, parentId = personal.current.folderId, fileName = name.trim(), fileType = "folder")
                personal = personal.copy(
                    current = personal.current.copy(items = personal.current.items + created),
                    feedback = FileMutationFeedback(1, UiMessage(MobileString.FolderCreated), false),
                )
            },
            renamePersonalFile = { file, name ->
                personal = personal.copy(
                    current = personal.current.copy(items = personal.current.items.map { if (it.id == file.id) it.copy(fileName = name.trim()) else it }),
                    feedback = FileMutationFeedback(2, UiMessage(MobileString.FileRenamed), false),
                )
            },
            deletePersonalFiles = { ids ->
                personal = personal.copy(
                    current = personal.current.copy(items = personal.current.items.filterNot { it.id in ids }),
                    selectedIds = emptySet(),
                    feedback = FileMutationFeedback(3, UiMessage(MobileString.FilesMovedToTrash), false),
                )
            },
            openFolderPicker = { source, kind ->
                personal = personal.copy(folderPicker = FolderPickerState(
                    kind = kind,
                    source = source,
                    folders = rootItems.filter { it.isFolder && it.id != source.id },
                ))
            },
            openPickerFolder = {},
            pickerBack = {},
            retryFolderPicker = {},
            closeFolderPicker = { personal = personal.copy(folderPicker = null) },
            confirmFolderPicker = { personal = personal.copy(folderPicker = null) },
            consumeFileFeedback = { personal = personal.copy(feedback = null) },
            updatePersonalScrollAnchor = {},
            upload = {},
            download = { _, _ -> },
            pause = {},
            resume = {},
            cancel = {},
            retry = {},
            logout = {},
            loadDiagnosticOffice = { _, _ -> },
            updateDiagnosticStatus = {},
        ),
        debugToolsEnabled = debugToolsEnabled,
      )
    }
}

private object TestDocumentPicker : DocumentPicker {
    @Composable
    override fun UploadButton(enabled: Boolean, onPicked: (PickedDocument) -> Unit) {
        Button(onClick = {}, enabled = enabled) { Text("选择上传文件") }
    }

    @Composable
    override fun DownloadButton(
        fileName: String,
        enabled: Boolean,
        onPicked: (PickedDestination) -> Unit,
    ) {
        Button(onClick = {}, enabled = enabled) { Text("下载") }
    }
}

private object TestOfficeRenderer : OfficeRenderer {
    @Composable
    override fun Render(config: OfficeConfig, modifier: Modifier, onStatus: (UiMessage) -> Unit) {
        Text("OnlyOffice", modifier)
    }
}
