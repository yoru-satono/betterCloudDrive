package com.betterclouddrive.mobile.android

import android.content.res.Configuration
import android.os.LocaleList
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.material3.VerticalDivider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.betterclouddrive.mobile.app.PersonalSpaceScreen
import com.betterclouddrive.mobile.app.PersonalSearchScreen
import com.betterclouddrive.mobile.app.TagFilesScreen
import com.betterclouddrive.mobile.app.TagsScreen
import com.betterclouddrive.mobile.app.TransferQueueScreen
import com.betterclouddrive.mobile.core.localization.LocalMobileStrings
import com.betterclouddrive.mobile.core.localization.mobileStringsFor
import com.betterclouddrive.mobile.core.model.FileItem
import com.betterclouddrive.mobile.core.model.PersonalTag
import com.betterclouddrive.mobile.core.platform.DocumentPicker
import com.betterclouddrive.mobile.core.platform.DownloadRequest
import com.betterclouddrive.mobile.core.platform.PickedDestination
import com.betterclouddrive.mobile.core.platform.UploadPickResult
import com.betterclouddrive.mobile.designsystem.BetterCloudDriveTheme
import com.betterclouddrive.mobile.feature.files.DirectoryPageState
import com.betterclouddrive.mobile.feature.files.FileMutationKind
import com.betterclouddrive.mobile.feature.files.FileViewMode
import com.betterclouddrive.mobile.feature.files.PersonalFilesState
import com.betterclouddrive.mobile.feature.files.PersonalSearchState
import com.betterclouddrive.mobile.feature.organization.FavoritesState
import com.betterclouddrive.mobile.feature.organization.OrganizationFileListState
import com.betterclouddrive.mobile.feature.organization.TagsState
import com.betterclouddrive.mobile.feature.transfers.TransferDirection
import com.betterclouddrive.mobile.feature.transfers.TransferScope
import com.betterclouddrive.mobile.feature.transfers.TransferStatus
import com.betterclouddrive.mobile.feature.transfers.TransferTask
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Locale

@RunWith(AndroidJUnit4::class)
class FeatureScreenDeviceTest {
    @get:Rule val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun transferQueueRetainsTabsProgressActionsAndTerminalCleanup() {
        var cleared = 0
        val tasks = listOf(
            transfer("upload-active", TransferDirection.UPLOAD, TransferStatus.RUNNING, 25, 100),
            transfer("download-done", TransferDirection.DOWNLOAD, TransferStatus.SUCCEEDED, 200, 200),
        )
        composeRule.setContent { themed("en-US") {
            TransferQueueScreen(tasks, {}, {}, {}, {}, { cleared++ })
        } }
        composeRule.onNodeWithTag("transfer-task-upload-active").assertIsDisplayed()
        composeRule.onNodeWithText("25 B / 100 B · 25%").assertIsDisplayed()
        composeRule.onNodeWithTag("transfer-tab-download").performClick()
        composeRule.onNodeWithTag("transfer-task-download-done").assertIsDisplayed()
        composeRule.onNodeWithTag("transfer-clear-finished").performClick()
        composeRule.onNodeWithTag("transfer-clear-confirm").performClick()
        assertEquals(1, cleared)
    }

    @Test
    fun browserRetainsCreateRenameMoveAndSelectionDeleteInteractions() {
        val folder = FileItem(id = 10, fileName = "Docs", fileType = "folder")
        val file = FileItem(id = 11, fileName = "note.txt")
        var pickerRequest: Pair<Long, FileMutationKind>? = null
        setPersonal(
            PersonalFilesState(
                current = DirectoryPageState(items = listOf(folder, file), hasLoaded = true, page = 1),
            ),
            onOpenPicker = { item, kind -> pickerRequest = item.id to kind },
        )
        composeRule.onNodeWithTag("personal-create-folder").performClick()
        composeRule.onNodeWithTag("personal-name-dialog").assertIsDisplayed()
        composeRule.onNodeWithText("Cancel").performClick()
        composeRule.onNodeWithTag("personal-more-11").performClick()
        composeRule.onNodeWithTag("personal-rename-11").performClick()
        composeRule.onNodeWithTag("personal-name-dialog").assertIsDisplayed()
        composeRule.onNodeWithText("Cancel").performClick()
        composeRule.onNodeWithTag("personal-more-11").performClick()
        composeRule.onNodeWithTag("personal-move-11").performClick()
        assertEquals(file.id to FileMutationKind.MOVE, pickerRequest)
    }

    @Test
    fun browserSelectionDeleteStillRequiresConfirmation() {
        val folder = FileItem(id = 10, fileName = "Docs", fileType = "folder")
        val file = FileItem(id = 11, fileName = "note.txt")
        setPersonal(PersonalFilesState(
            current = DirectoryPageState(items = listOf(folder, file), hasLoaded = true, page = 1),
            selectedIds = setOf(folder.id, file.id),
        ))
        composeRule.onNodeWithTag("personal-selection-bar").assertIsDisplayed()
        composeRule.onNodeWithTag("personal-selection-delete").performClick()
        composeRule.onNodeWithTag("personal-delete-dialog").assertIsDisplayed()
    }

    @Test
    fun compactBrowserKeepsGridAndReadOnlyDetailSheet() {
        val file = FileItem(id = 7, fileName = "detail.txt", fileSize = 12)
        setPersonal(PersonalFilesState(
            current = DirectoryPageState(items = listOf(file), hasLoaded = true, page = 1),
            viewMode = FileViewMode.GRID,
            selectedFile = file,
        ))
        composeRule.onNodeWithTag("personal-file-grid").assertIsDisplayed()
        composeRule.onNodeWithTag("personal-detail-sheet").assertIsDisplayed()
        composeRule.onNodeWithTag("personal-file-detail-7").assertIsDisplayed()
    }

    @Test
    fun wideBrowserKeepsReadOnlyDetailPane() {
        val file = FileItem(id = 7, fileName = "detail.txt", fileSize = 12)
        composeRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f)) {
                themed("en-US") { personal(PersonalFilesState(
                    current = DirectoryPageState(items = listOf(file), hasLoaded = true, page = 1),
                    selectedFile = file,
                )) }
            }
        }
        composeRule.onNodeWithTag("personal-detail-pane").assertIsDisplayed()
    }

    @Test
    fun englishResourceEnvironmentRendersDefaultCatalog() = assertEmptyCatalog(
        "en-US", "Personal space is empty", "Upload to current folder",
    )

    @Test
    fun mainlandResourceEnvironmentRendersSimplifiedChineseCatalog() = assertEmptyCatalog(
        "zh-CN", "个人空间为空", "上传到当前目录",
    )

    @Test
    fun singaporeResourceEnvironmentRendersSimplifiedChineseCatalog() = assertEmptyCatalog(
        "zh-SG", "个人空间为空", "上传到当前目录",
    )

    @Test
    fun traditionalChineseResourceEnvironmentFallsBackToEnglish() = assertEmptyCatalog(
        "zh-TW", "Personal space is empty", "Upload to current folder",
    )

    @Test
    fun wideTagsKeepIndependentListAndFilePanes() {
        val tag = PersonalTag(id = 31, tagName = "work", color = "#00d4aa", fileCount = 1)
        val tagged = FileItem(id = 32, fileName = "tagged.txt")
        composeRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f)) {
                themed("en-US") {
                    var state by remember { mutableStateOf(TagsState(tags = listOf(tag))) }
                    Row(Modifier.fillMaxSize()) {
                        Box(Modifier.width(420.dp).fillMaxSize()) {
                            TagsScreen(state, {}, {}, onOpenTag = {
                                state = state.copy(
                                    activeTagId = it.id,
                                    tagFiles = OrganizationFileListState(
                                        items = listOf(tagged), page = 1, pages = 1, total = 1,
                                    ),
                                )
                            }, onCreate = { _, _ -> }, onUpdate = { _, _, _ -> }, onDelete = {}, onConsumeFeedback = {})
                        }
                        VerticalDivider()
                        Box(Modifier.fillMaxSize().testTag("tags-wide-detail")) {
                            TagFilesScreen(
                                tag, state, NoopDocumentPicker, {}, {}, {}, {}, {}, {}, {}, { _, _ -> }, {}, {}, {}, {},
                            )
                        }
                    }
                }
            }
        }
        composeRule.onNodeWithTag("tag-row-31").performClick()
        composeRule.onNodeWithTag("tags-wide-detail").assertIsDisplayed()
        composeRule.onNodeWithTag("organization-file-32").assertIsDisplayed()
    }

    @Test
    fun searchResultDelegatesSupportedFileToPreviewBoundary() {
        val file = FileItem(id = 71, fileName = "search-note.txt", mimeType = "text/plain", fileSize = 5)
        var previewed: Long? = null
        composeRule.setContent { themed("en-US") {
            PersonalSearchScreen(
                state = PersonalSearchState(
                    query = "note", submittedQuery = "note", items = listOf(file), page = 1, pages = 1, total = 1,
                ),
                documentPicker = NoopDocumentPicker,
                onBack = {}, onQueryChanged = {}, onSubmit = {}, onLoadMore = {}, onRetry = {}, onScrollAnchor = {},
                onOpenFolder = {}, onOpenLocation = {}, onPreview = { previewed = it.id }, onDownload = { _, _ -> },
                onCreateShare = {},
            )
        } }
        composeRule.onNodeWithTag("search-result-71").performClick()
        composeRule.runOnIdle { assertEquals(file.id, previewed) }
    }

    private fun assertEmptyCatalog(locale: String, empty: String, upload: String) {
        setPersonal(PersonalFilesState(current = DirectoryPageState(hasLoaded = true, page = 1)), locale)
        composeRule.onNodeWithText(empty).assertIsDisplayed()
        composeRule.onNodeWithText(upload).assertIsDisplayed()
    }

    private fun setPersonal(
        state: PersonalFilesState,
        locale: String = "en-US",
        onOpenPicker: (FileItem, FileMutationKind) -> Unit = { _, _ -> },
    ) {
        composeRule.activityRule.scenario.onActivity { activity ->
            val selected = Locale.forLanguageTag(locale)
            Locale.setDefault(selected)
            val resources = activity.resources
            val configuration = Configuration(resources.configuration).apply { setLocales(LocaleList(selected)) }
            @Suppress("DEPRECATION") resources.updateConfiguration(configuration, resources.displayMetrics)
        }
        composeRule.setContent { themed(locale) { personal(state, onOpenPicker) } }
    }

    @Composable
    private fun personal(
        state: PersonalFilesState,
        onOpenPicker: (FileItem, FileMutationKind) -> Unit = { _, _ -> },
    ) {
        PersonalSpaceScreen(
            state = state,
            favorites = FavoritesState(),
            documentPicker = NoopDocumentPicker,
            onUpload = { _, _ -> }, onDownload = { _, _ -> }, onOpenFolder = {}, onSelectFile = {},
            onLoadMore = {}, onScrollAnchor = {}, onRetry = {}, onGoRoot = {}, onToggleSelection = {},
            onClearSelection = {}, onCreateFolder = {}, onRename = { _, _ -> }, onDelete = {},
            onOpenPicker = onOpenPicker, onOpenPickerFolder = {}, onPickerBack = {}, onRetryPicker = {},
            onClosePicker = {}, onConfirmPicker = {}, onConsumeFeedback = {}, onConsumeTransferFeedback = {},
            onOpenTransfers = {}, onPreview = {}, onLoadFavoriteStatus = {}, onToggleFavorite = {},
            onManageTags = {}, onCreateShare = {},
        )
    }

    @Composable
    private fun themed(locale: String, content: @Composable () -> Unit) {
        val configuration = Configuration(
            InstrumentationRegistry.getInstrumentation().targetContext.resources.configuration,
        ).apply { setLocales(LocaleList.forLanguageTags(locale)) }
        CompositionLocalProvider(LocalConfiguration provides configuration) {
            CompositionLocalProvider(LocalMobileStrings provides mobileStringsFor(
                (0 until configuration.locales.size()).map { configuration.locales[it].toLanguageTag() },
            )) { BetterCloudDriveTheme(content = content) }
        }
    }

    private object NoopDocumentPicker : DocumentPicker {
        @Composable override fun rememberUploadLauncher(maxItems: Int, onPicked: (UploadPickResult) -> Unit): () -> Unit = {}
        @Composable override fun rememberDownloadLauncher(onPicked: (PickedDestination) -> Unit): (DownloadRequest) -> Unit = {}
    }

    private fun transfer(id: String, direction: TransferDirection, status: TransferStatus, done: Long, total: Long) = TransferTask(
        id = id, direction = direction, status = status, serverBaseUrl = "https://drive.example.com",
        scope = TransferScope.Personal(), fileName = "$id.bin", bytesDone = done, bytesTotal = total,
        createdAt = 1, updatedAt = 2,
    )
}
