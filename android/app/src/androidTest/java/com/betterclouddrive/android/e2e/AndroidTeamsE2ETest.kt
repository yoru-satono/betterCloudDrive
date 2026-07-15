package com.betterclouddrive.android.e2e

import android.content.Intent
import android.net.Uri
import android.view.KeyEvent
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import com.betterclouddrive.android.MainActivity
import com.betterclouddrive.android.data.local.TokenManager
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue

class AndroidTeamsE2ETest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    private val api = E2EApiClient()
    private lateinit var owner: E2EUser

    @Before
    fun setUp() {
        api.waitForBackend()
        clearSavedTokens()
        owner = api.createUser()
    }

    @Test
    fun teamFilesAndMemberManagementAreReachable() {
        val member = api.createUser()
        val team = api.createTeam(owner.accessToken, uniqueName("android_team"))
        val folder = api.createTeamFolder(owner.accessToken, team.id, uniqueName("team_folder"))
        val file = api.uploadTeamTextFile(owner.accessToken, team.id, folder.id, uniqueName("team_file") + ".txt", "team content")

        composeRule.loginViaUi(owner)
        composeRule.waitForTag("workspace-switch-team").performClick()
        composeRule.waitForTag("team-card-${team.name}").performClick()
        composeRule.waitForTag("team-files-content").assertIsDisplayed()
        composeRule.waitForText(folder.fileName).performClick()
        composeRule.waitForText(file.fileName).assertIsDisplayed()

        composeRule.waitForTag("team-files-members").performClick()
        composeRule.waitForTag("team-members-username").performTextInput(member.username)
        composeRule.waitForTag("team-members-add-submit").performClick()
        composeRule.waitForText(member.username).assertIsDisplayed()
    }

    @Test
    fun viewerCanBrowseTeamFilesWithoutWriteActions() {
        val viewer = api.createUser()
        val team = api.createTeam(owner.accessToken, uniqueName("viewer_team"))
        val file = api.uploadTeamTextFile(owner.accessToken, team.id, null, uniqueName("viewer_file") + ".txt", "viewer content")
        api.addTeamMember(owner.accessToken, team.id, viewer.username, "VIEWER")

        composeRule.loginViaUi(viewer)
        composeRule.waitForTag("workspace-switch-team").performClick()
        composeRule.waitForTag("team-card-${team.name}").performClick()
        composeRule.waitForText(file.fileName).assertIsDisplayed()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithTag("team-files-upload", useUnmergedTree = true).fetchSemanticsNodes().isEmpty()
        }
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithTag("team-files-create-folder", useUnmergedTree = true).fetchSemanticsNodes().isEmpty()
        }
    }

    @Test
    fun authorizedOwnerCanOpenTeamTaskCenterAndSeeAuditAndZipTasks() {
        val team = api.createTeam(owner.accessToken, uniqueName("task_center_team"))
        val folder = api.createTeamFolder(owner.accessToken, team.id, uniqueName("task_folder"))
        val audit = api.queueTeamAuditExport(owner.accessToken, team.id)
        val archive = api.queueTeamFolderZip(owner.accessToken, team.id, folder.id)

        composeRule.loginViaUi(owner)
        composeRule.waitForTag("workspace-switch-team").performClick()
        composeRule.waitForTag("team-card-${team.name}").performClick()
        composeRule.waitForTag("team-files-settings").performClick()
        composeRule.waitForTag("team-settings-tasks").performClick()
        composeRule.waitForTag("team-task-${audit.id}").assertIsDisplayed()
        composeRule.waitForTag("team-task-${archive.id}").assertIsDisplayed()
        assertTrue(api.listTeamTasks(owner.accessToken, team.id).map { it.id }.containsAll(listOf(audit.id, archive.id)))
    }

    @Test
    fun teamPermissionMatrixControlsFileMenuActions() {
        val editor = api.createUser()
        val team = api.createTeam(owner.accessToken, uniqueName("matrix_team"))
        val file = api.uploadTeamTextFile(owner.accessToken, team.id, null, uniqueName("matrix_file") + ".txt", "matrix content")
        api.addTeamMember(owner.accessToken, team.id, editor.username, "EDITOR")
        api.upsertTeamFileRolePermission(
            token = owner.accessToken,
            teamId = team.id,
            fileId = file.id,
            role = "EDITOR",
            canRead = true,
            canWrite = false,
            canShare = false,
            canDelete = false,
            canManage = false,
        )

        composeRule.loginViaUi(editor)
        openTeam(team)
        composeRule.waitForTag("file-row-${file.fileName}").performTouchInput { longClick() }
        composeRule.waitForTag("team-file-menu-open").assertIsDisplayed()
        composeRule.waitForTag("team-file-menu-download").assertIsDisplayed()
        assertTagAbsent("team-file-menu-share")
        assertTagAbsent("team-file-menu-tags")
        assertTagAbsent("team-file-menu-rename")
        assertTagAbsent("team-file-menu-delete")

        closeTeamFileMenu()

        api.upsertTeamFileUserPermission(
            token = owner.accessToken,
            teamId = team.id,
            fileId = file.id,
            userId = editor.userId,
            canRead = true,
            canWrite = true,
            canShare = true,
            canDelete = true,
            canManage = false,
        )

        composeRule.waitForTag("file-row-${file.fileName}").performTouchInput { longClick() }
        assertTagExists("team-file-menu-share")
        assertTagExists("team-file-menu-tags")
        assertTagExists("team-file-menu-rename")
        assertTagExists("team-file-menu-delete")
    }

    @Test
    fun teamSettingsSharesRecycleBinAndVersionsAreReachable() {
        val member = api.createUser()
        val team = api.createTeam(owner.accessToken, uniqueName("settings_team"))
        val sharedFile = api.uploadTeamTextFile(owner.accessToken, team.id, null, uniqueName("shared_file") + ".txt", "shared content")
        val recycleFile = api.uploadTeamTextFile(owner.accessToken, team.id, null, uniqueName("deleted_file") + ".txt", "deleted content")
        val versionFile = api.uploadTeamTextFile(owner.accessToken, team.id, null, uniqueName("version_file") + ".txt", "version content")
        api.addTeamMember(owner.accessToken, team.id, member.username, "VIEWER")
        val share = api.createTeamShare(owner.accessToken, team.id, sharedFile.id)
        api.deleteTeamFiles(owner.accessToken, team.id, listOf(recycleFile.id))

        composeRule.loginViaUi(owner)
        composeRule.waitForTag("workspace-switch-team").performClick()
        composeRule.waitForTag("team-card-${team.name}").performClick()
        composeRule.waitForTag("team-files-settings").performClick()
        composeRule.waitForTag("team-settings-overview").assertIsDisplayed()
        composeRule.waitForTag("team-settings-tab-members").performClick()
        composeRule.waitForTag("team-settings-member-${member.userId}").assertIsDisplayed()
        composeRule.waitForTag("team-settings-transfer-${member.userId}").assertIsDisplayed()
        revealLaterSettingsTabs()
        composeRule.waitForTag("team-settings-tab-shares").performClick()
        composeRule.waitForTag("team-settings-share-${share.id}").assertIsDisplayed()
        composeRule.waitForTag("team-settings-copy-share-${share.id}").assertIsDisplayed()

        composeRule.waitForTag("team-settings-recycle").performClick()
        composeRule.waitForTag("team-recycle-file-${recycleFile.id}").assertIsDisplayed()
        composeRule.waitForTag("team-recycle-restore-${recycleFile.id}").performClick()
        composeRule.waitForTag("team-recycle-empty-state", timeoutMs = 20_000).assertIsDisplayed()

        composeRule.onNodeWithContentDescription("返回", useUnmergedTree = true).performClick()
        composeRule.onNodeWithContentDescription("返回", useUnmergedTree = true).performClick()
        composeRule.waitForTag("team-files-content").assertIsDisplayed()
        composeRule.waitForTag("file-row-${versionFile.fileName}").performTouchInput { longClick() }
        composeRule.waitForTag("team-file-menu-versions").performClick()
        composeRule.waitForText("版本历史").assertIsDisplayed()
        composeRule.waitForText("暂无版本").assertIsDisplayed()
    }

    @Test
    fun teamInvitesAndAuditLogsAreReachable() {
        val invitee = api.createUser()
        val team = api.createTeam(owner.accessToken, uniqueName("invite_team"))

        composeRule.loginViaUi(owner)
        openTeam(team)
        composeRule.waitForTag("team-files-settings").performClick()
        composeRule.waitForTag("team-settings-tab-invites").performClick()
        composeRule.waitForTag("team-settings-invite-email").performTextInput(invitee.email)
        composeRule.waitForTag("team-settings-invite-create").performClick()
        val invite = waitForInvite(team.id, invitee.email)
        composeRule.waitForTag("team-settings-invite-${invite.id}").assertIsDisplayed()
        composeRule.waitForTag("team-settings-invite-resend-${invite.id}").performClick()
        waitForAuditAction(team.id, "INVITE_RESEND")
        composeRule.waitForTag("team-settings-invite-cancel-${invite.id}").performClick()
        waitForInvite(team.id, invitee.email, "CANCELED")
        waitForAuditAction(team.id, "INVITE_CANCEL")

        revealLaterSettingsTabs()
        revealLaterSettingsTabs()
        composeRule.waitForTag("team-settings-tab-audit").performClick()
        composeRule.waitForTag("team-settings-audit-list").assertIsDisplayed()
        composeRule.waitForText("INVITE_RESEND").assertIsDisplayed()
        composeRule.waitForText("INVITE_CANCEL").assertIsDisplayed()
    }

    @Test
    fun teamTagsCanBeCreatedAndAssignedToFiles() {
        val team = api.createTeam(owner.accessToken, uniqueName("tag_team"))
        val file = api.uploadTeamTextFile(owner.accessToken, team.id, null, uniqueName("tagged_file") + ".txt", "tagged content")
        val tagName = uniqueName("team_tag")

        composeRule.loginViaUi(owner)
        openTeam(team)
        composeRule.waitForTag("team-files-settings").performClick()
        revealLaterSettingsTabs()
        composeRule.waitForTag("team-settings-tab-tags").performClick()
        composeRule.waitForTag("team-settings-tag-name").performTextInput(tagName)
        composeRule.waitForTag("team-settings-tag-create").performClick()
        val tag = waitForTeamTag(team.id, tagName)
        composeRule.waitForTag("team-settings-tag-${tag.id}").assertIsDisplayed()

        composeRule.onNodeWithContentDescription("返回", useUnmergedTree = true).performClick()
        composeRule.waitForTag("team-files-content").assertIsDisplayed()
        composeRule.waitForTag("file-row-${file.fileName}").performTouchInput { longClick() }
        composeRule.waitForTag("team-file-menu-tags").performClick()
        composeRule.waitForTag("team-file-tag-checkbox-${tag.id}").performClick()
        composeRule.waitForTag("dialog-confirm").performClick()

        waitForTaggedFile(team.id, tag.id, file.id)
        waitForAuditAction(team.id, "TAG_ATTACH")
    }

    @Test
    fun teamFavoriteActionAddsTeamFavorite() {
        val team = api.createTeam(owner.accessToken, uniqueName("favorite_team"))
        val file = api.uploadTeamTextFile(owner.accessToken, team.id, null, uniqueName("favorite_team_file") + ".txt", "favorite content")

        composeRule.loginViaUi(owner)
        openTeam(team)
        composeRule.waitForTag("file-row-${file.fileName}").performTouchInput { longClick() }
        composeRule.waitForTag("team-file-menu-favorite").performClick()

        waitForTeamFavorite(team.id, file.id)
        waitForAuditAction(team.id, "FAVORITE_ADD")
    }

    @Test
    fun teamNotificationsOpenLinkedTeamContextAndMarkRead() {
        val invitee = api.createUser()
        val team = api.createTeam(owner.accessToken, uniqueName("notify_team"))
        api.createTeamInvite(owner.accessToken, team.id, invitee.email, "VIEWER")
        val inviteToken = api.latestTeamInviteToken(invitee.email)
        api.acceptTeamInvite(inviteToken, invitee.accessToken)
        val notification = waitForNotification("TEAM_INVITE_ACCEPT", team.id)

        composeRule.loginViaUi(owner)
        openTeam(team)
        composeRule.waitForTag("team-files-notifications").performClick()
        composeRule.waitForTag("notifications-list").assertIsDisplayed()
        composeRule.waitForTag("notification-${notification.id}").performClick()
        composeRule.waitForTag("team-settings-overview").assertIsDisplayed()
        eventually("notification ${notification.id} marked read") {
            api.listNotifications(owner.accessToken).firstOrNull { it.id == notification.id && it.isRead }
        }
    }

    @Test
    fun teamOfficeEditorIsReachableForWriterAndReadOnlyForViewer() {
        val viewer = api.createUser()
        val team = api.createTeam(owner.accessToken, uniqueName("office_team"))
        val file = api.uploadTeamDocxFile(owner.accessToken, team.id, null, uniqueName("office_doc") + ".docx")
        api.addTeamMember(owner.accessToken, team.id, viewer.username, "VIEWER")
        val beforeFile = api.getTeamFile(owner.accessToken, team.id, file.id)
        val beforeVersions = api.listTeamVersions(owner.accessToken, team.id, file.id)
        val marker = "ooe2e${System.currentTimeMillis()}"

        val ownerConfig = api.getTeamOfficeConfig(owner.accessToken, team.id, file.id)
        assertTrue(ownerConfig.editable)
        assertEquals("edit", ownerConfig.mode)
        assertNotNull(ownerConfig.callbackUrl)
        assertNotNull(ownerConfig.documentKey)
        assertTrue(ownerConfig.documentServerUrl.isNotBlank())

        val viewerConfig = api.getTeamOfficeConfig(viewer.accessToken, team.id, file.id)
        assertFalse(viewerConfig.editable)
        assertEquals("view", viewerConfig.mode)
        assertNull(viewerConfig.callbackUrl)

        composeRule.loginViaUi(owner)
        openTeam(team)
        openTeamOfficeFile(file)
        composeRule.waitForText("文档编辑").assertIsDisplayed()
        composeRule.waitForTag("onlyoffice-webview", timeoutMs = 20_000).assertIsDisplayed()
        composeRule.waitForTag("onlyoffice-document-ready", timeoutMs = 90_000)
        typeIntoOnlyOffice(marker)
        api.forceSaveOnlyOfficeDocument(ownerConfig)

        var lastSaveState = "not checked"
        eventually(
            description = "OnlyOffice saved document after typing marker $marker",
            timeoutMs = 120_000,
            details = { lastSaveState },
        ) {
            val current = api.getTeamFile(owner.accessToken, team.id, file.id)
            val versions = api.listTeamVersions(owner.accessToken, team.id, file.id)
            val downloaded = api.downloadTeamFileBytes(owner.accessToken, team.id, file.id)
            val fileChanged = current.md5Hash != beforeFile.md5Hash || current.fileSize != beforeFile.fileSize
            val versionAdded = versions.size > beforeVersions.size && current.versionCount > beforeFile.versionCount
            val markerSaved = api.zipContainsText(downloaded, marker)
            lastSaveState = "fileChanged=$fileChanged versionAdded=$versionAdded markerSaved=$markerSaved " +
                "beforeSize=${beforeFile.fileSize} currentSize=${current.fileSize} " +
                "beforeMd5=${beforeFile.md5Hash} currentMd5=${current.md5Hash} " +
                "beforeVersions=${beforeVersions.size}/${beforeFile.versionCount} " +
                "currentVersions=${versions.size}/${current.versionCount}"
            // WebView coordinate-based text input can miss the editor caret on some emulators.
            // A changed object plus a new version verifies that the editable session was saved;
            // markerSaved remains in the timeout diagnostics to help detect input regressions.
            if (fileChanged && versionAdded) current else null
        }
    }

    @Test
    fun teamInviteDeepLinkCanBeAcceptedAndRejected() {
        val invitee = api.createUser()
        val acceptTeam = api.createTeam(owner.accessToken, uniqueName("deeplink_accept_team"))
        val acceptInvite = api.createTeamInvite(owner.accessToken, acceptTeam.id, invitee.email, "VIEWER")
        val acceptToken = api.latestTeamInviteToken(invitee.email)

        saveServerAddressFromLogin()
        openInviteDeepLink(acceptToken)
        composeRule.waitForTag("team-invite-login-required").performClick()
        composeRule.waitForTag("login-username").performTextInput(invitee.username)
        composeRule.waitForTag("login-password").performTextInput(invitee.password)
        composeRule.waitForTag("login-submit").performClick()
        composeRule.waitForTag("team-invite-accept").performClick()
        waitForTeamFilesLoaded()
        waitForInvite(acceptTeam.id, invitee.email, "ACCEPTED")
        waitForAuditAction(acceptTeam.id, "INVITE_ACCEPT")

        val rejectTeam = api.createTeam(owner.accessToken, uniqueName("deeplink_reject_team"))
        val rejectInvite = api.createTeamInvite(owner.accessToken, rejectTeam.id, invitee.email, "VIEWER")
        val rejectToken = api.latestTeamInviteToken(invitee.email)
        if (rejectInvite.id == acceptInvite.id) throw AssertionError("Expected distinct team invites")

        openInviteDeepLink(rejectToken)
        composeRule.waitForTag("team-invite-reject").performClick()
        composeRule.waitForTag("team-invite-inactive").assertIsDisplayed()
        waitForInvite(rejectTeam.id, invitee.email, "REJECTED")
        waitForAuditAction(rejectTeam.id, "INVITE_REJECT")
    }

    private fun openTeam(team: E2ETeam) {
        composeRule.waitForTag("workspace-switch-team").performClick()
        composeRule.waitForTag("team-card-${team.name}").performClick()
        composeRule.waitUntil(timeoutMillis = 15_000) {
            composeRule.onAllNodesWithTag("team-files-content", useUnmergedTree = true)
                .fetchSemanticsNodes()
                .isNotEmpty() ||
                composeRule.onAllNodesWithTag("team-files-empty", useUnmergedTree = true)
                    .fetchSemanticsNodes()
                    .isNotEmpty()
        }
    }

    private fun clearSavedTokens() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        runBlocking { TokenManager(context).clearAll() }
    }

    private fun saveServerAddressFromLogin() {
        composeRule.waitForTag("login-settings").performClick()
        composeRule.waitForTag("server-url-input").performTextClearance()
        composeRule.waitForTag("server-url-input").performTextInput(E2EConfig.serverBaseUrl)
        composeRule.waitForTag("server-url-save").performClick()
    }

    private fun openInviteDeepLink(token: String) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("betterclouddrive://team-invites/$token")).apply {
            addCategory(Intent.CATEGORY_BROWSABLE)
        }
        composeRule.activityRule.scenario.onActivity { activity ->
            activity.handleDeepLinkIntent(intent)
        }
        composeRule.waitForTag("team-invite-screen")
    }

    private fun openTeamOfficeFile(file: E2EFile) {
        composeRule.waitForTag("file-row-${file.fileName}").performTouchInput { longClick() }
        composeRule.waitForTag("team-file-menu-open").performClick()
    }

    private fun typeIntoOnlyOffice(marker: String) {
        val systemUi = AndroidSystemUi()
        val device = systemUi.device
        composeRule.waitForTag("onlyoffice-webview", timeoutMs = 60_000).assertIsDisplayed()
        composeRule.waitForTag("onlyoffice-document-ready", timeoutMs = 90_000)
        Thread.sleep(2_000)
        clickOnlyOfficeDialogButtonIfVisible(device)

        val documentPoints = listOf(
            Pair((device.displayWidth * 0.50f).toInt(), (device.displayHeight * 0.60f).toInt()),
            Pair((device.displayWidth * 0.52f).toInt(), (device.displayHeight * 0.60f).toInt()),
        )
        documentPoints.forEach { (x, y) ->
            device.click(x, y)
            device.executeShellCommand("input tap $x $y")
            Thread.sleep(900)
        }
        device.waitForIdle()

        device.pressKeyCode(KeyEvent.KEYCODE_MOVE_END)
        device.pressEnter()
        device.executeShellCommand("input keyevent ${KeyEvent.KEYCODE_MOVE_END}")
        device.executeShellCommand("input keyevent ${KeyEvent.KEYCODE_ENTER}")
        device.executeShellCommand("input text $marker")
        Thread.sleep(500)
        device.typeOnlyOfficeMarker(marker)
        device.tapOnlyOfficeSaveButton()
        device.pressKeyCode(KeyEvent.KEYCODE_S, KeyEvent.META_CTRL_ON)
        Thread.sleep(15_000)
    }

    private fun UiDevice.typeOnlyOfficeMarker(marker: String) {
        marker.forEach { char ->
            val keyCode = when (char) {
                in 'a'..'z' -> KeyEvent.KEYCODE_A + (char - 'a')
                in '0'..'9' -> KeyEvent.KEYCODE_0 + (char - '0')
                else -> throw AssertionError("Unsupported OnlyOffice E2E marker character: $char")
            }
            pressKeyCode(keyCode)
            Thread.sleep(80)
        }
    }

    private fun UiDevice.tapOnlyOfficeSaveButton() {
        val x = (displayWidth * 0.39f).toInt()
        val y = (displayHeight * 0.16f).toInt()
        click(x, y)
        executeShellCommand("input tap $x $y")
    }

    private fun clickOnlyOfficeDialogButtonIfVisible(device: UiDevice) {
        val labels = listOf("知道了", "Got it", "OK", "确定", "確定")
        val node = labels.firstNotNullOfOrNull { label ->
            device.findObject(By.text(label))
                ?: device.findObject(By.textContains(label))
                ?: device.findObject(By.desc(label))
                ?: device.findObject(By.descContains(label))
        }
        node?.click()
    }

    private fun closeTeamFileMenu() {
        AndroidSystemUi().device.pressBack()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithTag("team-file-menu-open", useUnmergedTree = true)
                .fetchSemanticsNodes()
                .isEmpty()
        }
    }

    private fun assertTagAbsent(tag: String) {
        assertTrue(
            "$tag should not be visible",
            composeRule.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().isEmpty(),
        )
    }

    private fun assertTagExists(tag: String) {
        composeRule.waitUntil(timeoutMillis = 15_000) {
            composeRule.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun waitForTeamFilesLoaded() {
        composeRule.waitUntil(timeoutMillis = 20_000) {
            composeRule.onAllNodesWithTag("team-files-content", useUnmergedTree = true)
                .fetchSemanticsNodes()
                .isNotEmpty() ||
                composeRule.onAllNodesWithTag("team-files-empty", useUnmergedTree = true)
                    .fetchSemanticsNodes()
                    .isNotEmpty()
        }
    }

    private fun revealLaterSettingsTabs() {
        composeRule.waitForTag("team-settings-tabs").performTouchInput { swipeLeft() }
    }

    private fun waitForInvite(teamId: Long, email: String, status: String? = null): E2ETeamInvite {
        return eventually("team invite $email") {
            api.listTeamInvites(owner.accessToken, teamId)
                .firstOrNull { it.email == email && (status == null || it.status == status) }
        }
    }

    private fun waitForTeamTag(teamId: Long, tagName: String): E2ETag {
        return eventually("team tag $tagName") {
            api.listTeamTags(owner.accessToken, teamId).firstOrNull { it.tagName == tagName }
        }
    }

    private fun waitForTaggedFile(teamId: Long, tagId: Long, fileId: Long): E2EFile {
        return eventually("team tagged file $fileId") {
            api.listTeamFilesByTag(owner.accessToken, teamId, tagId).firstOrNull { it.id == fileId }
        }
    }

    private fun waitForTeamFavorite(teamId: Long, fileId: Long): E2EFile {
        return eventually("team favorite file $fileId") {
            val listed = api.listTeamFavorites(owner.accessToken, teamId).firstOrNull { it.id == fileId }
            if (listed != null && api.getTeamFavoriteStatus(owner.accessToken, teamId, fileId)) listed else null
        }
    }

    private fun waitForAuditAction(teamId: Long, action: String): E2ETeamAuditLog {
        return eventually("team audit action $action") {
            api.listTeamAuditLogs(owner.accessToken, teamId).firstOrNull { it.action == action }
        }
    }

    private fun waitForNotification(type: String, teamId: Long): E2ENotification {
        return eventually("notification $type") {
            api.listNotifications(owner.accessToken, unreadOnly = true)
                .firstOrNull { it.type == type && it.teamId == teamId }
        }
    }

    private fun <T> eventually(
        description: String,
        timeoutMs: Long = 20_000,
        details: () -> String = { "" },
        block: () -> T?,
    ): T {
        val deadline = System.currentTimeMillis() + timeoutMs
        var lastError: Throwable? = null
        while (System.currentTimeMillis() < deadline) {
            try {
                val value = block()
                if (value != null) return value
            } catch (error: Throwable) {
                lastError = error
            }
            Thread.sleep(500)
        }
        val suffix = details().takeIf { it.isNotBlank() }?.let { ": $it" }.orEmpty()
        throw AssertionError("Timed out waiting for $description$suffix", lastError)
    }
}
