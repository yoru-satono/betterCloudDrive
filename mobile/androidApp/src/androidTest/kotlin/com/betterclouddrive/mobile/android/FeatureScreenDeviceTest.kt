package com.betterclouddrive.mobile.android

import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
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
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.betterclouddrive.mobile.app.PersonalSpaceScreen
import com.betterclouddrive.mobile.app.PersonalSearchScreen
import com.betterclouddrive.mobile.app.TagFilesScreen
import com.betterclouddrive.mobile.app.TagsScreen
import com.betterclouddrive.mobile.app.TeamBrowserScreen
import com.betterclouddrive.mobile.app.TeamMembersScreen
import com.betterclouddrive.mobile.app.TeamPermissionsOverlay
import com.betterclouddrive.mobile.app.NotificationsScreen
import com.betterclouddrive.mobile.app.TransferQueueScreen
import com.betterclouddrive.mobile.core.localization.LocalMobileStrings
import com.betterclouddrive.mobile.core.localization.MobileString
import com.betterclouddrive.mobile.core.localization.UiMessage
import com.betterclouddrive.mobile.core.localization.mobileStringsFor
import com.betterclouddrive.mobile.core.model.FileItem
import com.betterclouddrive.mobile.core.model.PersonalTag
import com.betterclouddrive.mobile.core.model.TeamEffectivePermission
import com.betterclouddrive.mobile.core.model.TeamFilePermission
import com.betterclouddrive.mobile.core.model.TeamMember
import com.betterclouddrive.mobile.core.model.TeamInvite
import com.betterclouddrive.mobile.core.model.TeamInviteStatus
import com.betterclouddrive.mobile.core.model.NotificationItem
import com.betterclouddrive.mobile.core.model.TeamPermissionSource
import com.betterclouddrive.mobile.core.model.TeamPermissionSubjectType
import com.betterclouddrive.mobile.core.model.TeamRole
import com.betterclouddrive.mobile.core.model.TeamSummary
import com.betterclouddrive.mobile.core.model.UpdateTeamFilePermissionRequest
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
import com.betterclouddrive.mobile.feature.teams.TeamBrowserState
import com.betterclouddrive.mobile.feature.teams.TeamFileActionsState
import com.betterclouddrive.mobile.feature.teams.TeamMembersState
import com.betterclouddrive.mobile.feature.teams.TeamPermissionsState
import com.betterclouddrive.mobile.feature.teams.TeamInvitesState
import com.betterclouddrive.mobile.feature.teams.TeamPeopleTab
import com.betterclouddrive.mobile.feature.teams.NotificationsState
import com.betterclouddrive.mobile.feature.transfers.TransferDirection
import com.betterclouddrive.mobile.feature.transfers.TransferScope
import com.betterclouddrive.mobile.feature.transfers.TransferStatus
import com.betterclouddrive.mobile.feature.transfers.TransferTask
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Locale

@RunWith(AndroidJUnit4::class)
class FeatureScreenDeviceTest {
    @get:Rule val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun invitationDeepLinkIsRegisteredWithAndroid() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(
            "betterclouddrive://team-invites/abcdefghijklmnop",
        )).addCategory(Intent.CATEGORY_BROWSABLE)

        val resolved = context.packageManager.resolveActivity(intent, 0)

        assertNotNull(resolved)
        assertEquals(MainActivity::class.java.name, resolved?.activityInfo?.name)
    }

    @Test
    fun transferQueueRetainsTabsProgressActionsAndTerminalCleanup() {
        var cleared = 0
        val tasks = listOf(
            transfer("upload-active", TransferDirection.UPLOAD, TransferStatus.RUNNING, 25, 100),
            transfer("team-upload", TransferDirection.UPLOAD, TransferStatus.QUEUED, 0, 512, TransferScope.Team(9, 3)),
            transfer("download-done", TransferDirection.DOWNLOAD, TransferStatus.SUCCEEDED, 200, 200),
        )
        composeRule.setContent { themed("en-US") {
            TransferQueueScreen(tasks, {}, {}, {}, {}, { cleared++ })
        } }
        composeRule.onNodeWithTag("transfer-task-upload-active").assertIsDisplayed()
        composeRule.onNodeWithText("25 B / 100 B · 25%").assertIsDisplayed()
        composeRule.onNodeWithTag("transfer-scope-team").performClick()
        composeRule.onNodeWithTag("transfer-task-team-upload").assertIsDisplayed()
        composeRule.onAllNodesWithTag("transfer-task-upload-active").assertCountEquals(0)
        composeRule.onNodeWithTag("transfer-tab-download").performClick()
        composeRule.onNodeWithTag("transfer-scope-all").performClick()
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
    fun teamBrowserFailsClosedAndShowsAuthorizedCollaborationActions() {
        val file = FileItem(id = 41, fileName = "team-note.txt")
        val browser = TeamBrowserState(
            teamId = 9,
            team = TeamSummary(9, "Team", role = TeamRole.EDITOR),
            current = DirectoryPageState(items = listOf(file), hasLoaded = true, page = 1),
        )
        val actions = mutableStateOf(TeamFileActionsState(teamId = 9, folderId = null))
        composeRule.setContent { themed("en-US") {
            teamBrowser(browser, actions.value)
        } }
        composeRule.onAllNodesWithTag("team-create-folder").assertCountEquals(0)
        composeRule.onAllNodesWithTag("team-upload").assertCountEquals(0)
        composeRule.onNodeWithTag("team-more-41").performClick()
        composeRule.onNodeWithText("Download").assertIsDisplayed()
        composeRule.onAllNodesWithText("Rename").assertCountEquals(0)

        val editable = TeamEffectivePermission(true, true, false, true, false)
        composeRule.runOnIdle {
            actions.value = TeamFileActionsState(
                teamId = 9, folderId = null, directoryPermission = editable,
                itemPermissions = mapOf(file.id to editable), selectedIds = setOf(file.id),
            )
        }
        composeRule.onNodeWithTag("team-delete-selected").assertIsDisplayed()
    }

    @Test
    fun teamMembersScreenEnforcesAdminRoleBoundary() {
        val admin = TeamMember(userId = 2, username = "admin-user", role = TeamRole.ADMIN)
        val editor = TeamMember(userId = 3, username = "editor-user", role = TeamRole.EDITOR)
        var changed: Pair<Long, TeamRole>? = null
        composeRule.setContent { themed("en-US") {
            TeamMembersScreen(
                state = TeamMembersState(teamId = 9, actorRole = TeamRole.ADMIN, items = listOf(admin, editor), page = 1),
                wide = false,
                onBack = {}, onRefresh = {}, onLoadMore = {}, onAdd = { _, _ -> },
                onUpdateRole = { member, role -> changed = member.userId to role },
                onRemove = {}, onClearAddError = {}, onClearFeedback = {},
            )
        } }
        composeRule.onAllNodesWithTag("team-member-more-2").assertCountEquals(0)
        composeRule.onNodeWithTag("team-member-more-3").performClick()
        composeRule.onNodeWithTag("team-member-role-3-viewer").performClick()
        composeRule.onNodeWithText("Confirm").performClick()
        composeRule.runOnIdle { assertEquals(editor.userId to TeamRole.VIEWER, changed) }
    }

    @Test
    fun teamMembersScreenClosesAddSheetAfterSuccess() {
        val state = mutableStateOf(TeamMembersState(teamId = 9, actorRole = TeamRole.OWNER, page = 1))
        composeRule.setContent { themed("en-US") {
            TeamMembersScreen(
                state = state.value,
                wide = false,
                onBack = {}, onRefresh = {}, onLoadMore = {}, onAdd = { _, _ -> },
                onUpdateRole = { _, _ -> }, onRemove = {}, onClearAddError = {}, onClearFeedback = {},
            )
        } }
        composeRule.onNodeWithTag("team-member-add").performClick()
        composeRule.onNodeWithTag("team-member-username").assertIsDisplayed()
        composeRule.runOnIdle {
            state.value = state.value.copy(feedback = UiMessage(MobileString.TeamMemberAdded))
        }
        composeRule.onAllNodesWithTag("team-member-username").assertCountEquals(0)
    }

    @Test
    fun teamPeopleTabsShowPendingAndExpiredInvitations() {
        val invites = mutableStateOf(TeamInvitesState(
            teamId = 9,
            actorRole = TeamRole.OWNER,
            selectedTab = TeamPeopleTab.MEMBERS,
            items = listOf(
                TeamInvite(id = 1, teamId = 9, email = "pending@example.com", status = TeamInviteStatus.PENDING),
                TeamInvite(id = 2, teamId = 9, email = "expired@example.com", status = TeamInviteStatus.EXPIRED),
            ),
            page = 1,
        ))
        composeRule.setContent { themed("en-US") {
            TeamMembersScreen(
                state = TeamMembersState(teamId = 9, actorRole = TeamRole.OWNER, page = 1),
                invites = invites.value,
                wide = false,
                onBack = {}, onRefresh = {}, onLoadMore = {}, onAdd = { _, _ -> },
                onUpdateRole = { _, _ -> }, onRemove = {}, onClearAddError = {}, onClearFeedback = {},
                onSelectTab = { invites.value = invites.value.copy(selectedTab = it) },
            )
        } }

        composeRule.onNodeWithTag("team-people-invites-tab").performClick()
        composeRule.onNodeWithTag("team-invites-list").assertIsDisplayed()
        composeRule.onNodeWithText("pending@example.com").assertIsDisplayed()
        composeRule.onNodeWithTag("team-invite-resend-2").assertIsDisplayed()
    }

    @Test
    fun notificationsRenderLocalizedKnownAndGenericMessages() {
        val known = NotificationItem(
            id = 1,
            type = "TEAM_INVITE",
            titleKey = "notification.teamInvite.title",
            contentKey = "notification.teamInvite.content",
        )
        val unknown = NotificationItem(id = 2, type = "UNKNOWN", titleKey = "unknown.key", contentKey = "unknown.content")
        composeRule.setContent { themed("en-US") {
            NotificationsScreen(
                state = NotificationsState(items = listOf(known, unknown), page = 1),
                onRefresh = {}, onLoadMore = {}, onFilter = { _, _ -> }, onOpen = {},
                onMarkAllRead = {}, onClearFeedback = {},
            )
        } }

        composeRule.onNodeWithText("Team invitation").assertIsDisplayed()
        composeRule.onNodeWithText("Notification").assertIsDisplayed()
        composeRule.onAllNodesWithText("unknown.key").assertCountEquals(0)
    }

    @Test
    fun permissionEditorKeepsReadWhenWriteIsEnabled() {
        val member = TeamMember(userId = 3, username = "editor-user", role = TeamRole.EDITOR)
        val effective = TeamEffectivePermission(
            canRead = true, canWrite = true, canShare = false, canDelete = false, canManage = true,
            sourceChain = listOf(TeamPermissionSource(type = "BASE_ROLE", subjectRole = "OWNER", canRead = true, canManage = true)),
        )
        val direct = TeamFilePermission(
            id = 8, teamId = 9, fileId = 41, subjectType = TeamPermissionSubjectType.ROLE,
            subjectRole = TeamRole.EDITOR, canRead = true, canWrite = true,
        )
        var saved: UpdateTeamFilePermissionRequest? = null
        composeRule.setContent { themed("en-US") {
            TeamPermissionsOverlay(
                state = TeamPermissionsState(
                    teamId = 9, target = FileItem(id = 41, fileName = "team-note.txt"), effective = effective,
                    direct = listOf(direct), members = listOf(member),
                ),
                wide = false, actorUserId = 1, actorRole = TeamRole.OWNER,
                onDismiss = {}, onRetry = {}, onSave = { saved = it }, onDelete = {}, onClearFeedback = {},
            )
        } }
        composeRule.onNodeWithTag("team-permissions-sheet").assertIsDisplayed()
        composeRule.onNodeWithTag("team-permission-8").assertIsDisplayed()
        composeRule.onNodeWithTag("team-permission-add").performClick()
        composeRule.onNodeWithTag("team-permission-write").performClick()
        composeRule.onNodeWithTag("team-permission-save").performClick()
        composeRule.runOnIdle {
            assertEquals(true, saved?.canRead)
            assertEquals(true, saved?.canWrite)
        }
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
                                {},
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
                onOpenVersions = {},
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
            onOpenVersions = {},
        )
    }

    @Composable
    private fun teamBrowser(state: TeamBrowserState, actions: TeamFileActionsState) {
        TeamBrowserScreen(
            state = state, actions = actions, permissions = TeamPermissionsState(), wide = false,
            actorUserId = 1, documentPicker = NoopDocumentPicker,
            onBack = {}, onBackToTeams = {}, onBreadcrumb = {}, onOpenFolder = {}, onOpenSearch = {},
            onOpenMembers = {},
            onRetryWorkspace = {}, onRefresh = {}, onLoadMore = {}, onSort = { _, _ -> }, onViewMode = {},
            onSelect = {}, onPreview = {}, onRequestPermission = {}, onToggleSelection = {}, onClearSelection = {},
            onCreateFolder = {}, onRename = { _, _ -> }, onDelete = {}, onOpenMovePicker = {},
            onOpenPickerFolder = {}, onPickerBack = {}, onRetryPicker = {}, onClosePicker = {},
            onConfirmMove = {}, onUpload = {}, onDownload = { _, _ -> }, onConsumeFeedback = {},
            onConsumeTransferFeedback = {}, onClearPermissionError = {}, onOpenTransfers = {},
            onManagePermissions = {}, onRetryPermissions = {}, onSavePermission = {}, onDeletePermission = {},
            onClosePermissions = {}, onClearPermissionsFeedback = {},
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

    private fun transfer(
        id: String, direction: TransferDirection, status: TransferStatus, done: Long, total: Long,
        scope: TransferScope = TransferScope.Personal(),
    ) = TransferTask(
        id = id, direction = direction, status = status, serverBaseUrl = "https://drive.example.com",
        scope = scope, fileName = "$id.bin", bytesDone = done, bytesTotal = total,
        createdAt = 1, updatedAt = 2,
    )
}
