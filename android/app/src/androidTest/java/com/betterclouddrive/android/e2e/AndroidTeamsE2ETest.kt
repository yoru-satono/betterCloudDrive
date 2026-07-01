package com.betterclouddrive.android.e2e

import android.content.Intent
import android.net.Uri
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
import com.betterclouddrive.android.MainActivity
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
        owner = api.createUser()
    }

    @Test
    fun teamFilesAndMemberManagementAreReachable() {
        val member = api.createUser()
        val team = api.createTeam(owner.accessToken, uniqueName("android_team"))
        val folder = api.createTeamFolder(owner.accessToken, team.id, uniqueName("team_folder"))
        val file = api.uploadTeamTextFile(owner.accessToken, team.id, folder.id, uniqueName("team_file") + ".txt", "team content")

        composeRule.loginViaUi(owner)
        composeRule.waitForTag("nav-teams").performClick()
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
        composeRule.waitForTag("nav-teams").performClick()
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
        composeRule.waitForTag("nav-teams").performClick()
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
        composeRule.waitForText("INVITE_CREATE").assertIsDisplayed()
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
    fun teamOfficeEditorIsReachableForWriterAndReadOnlyForViewer() {
        val viewer = api.createUser()
        val team = api.createTeam(owner.accessToken, uniqueName("office_team"))
        val file = api.uploadTeamDocxFile(owner.accessToken, team.id, null, uniqueName("office_doc") + ".docx")
        api.addTeamMember(owner.accessToken, team.id, viewer.username, "VIEWER")

        val ownerConfig = api.getTeamOfficeConfig(owner.accessToken, team.id, file.id)
        assertTrue(ownerConfig.editable)
        assertEquals("edit", ownerConfig.mode)
        assertNotNull(ownerConfig.callbackUrl)
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
        composeRule.waitForTag("nav-teams").performClick()
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

    private fun <T> eventually(description: String, timeoutMs: Long = 20_000, block: () -> T?): T {
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
        throw AssertionError("Timed out waiting for $description", lastError)
    }
}
