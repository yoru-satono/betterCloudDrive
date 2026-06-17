package com.betterclouddrive.android.e2e

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import com.betterclouddrive.android.MainActivity
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class AndroidSharesFavoritesRecycleTagsVersionsE2ETest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    private val api = E2EApiClient()
    private lateinit var user: E2EUser

    @Before
    fun setUp() {
        api.waitForBackend()
        user = api.createUser()
    }

    @Test
    fun favoritesSharesAndRecycleReachable() {
        val favoriteFile = api.uploadTextFile(user.accessToken, null, uniqueName("favorite") + ".txt")
        val shareFile = api.uploadTextFile(user.accessToken, null, uniqueName("share") + ".txt")
        val recycleFile = api.uploadTextFile(user.accessToken, null, uniqueName("recycle") + ".txt")
        val share = api.createShare(user.accessToken, shareFile.id, password = "abcd")

        api.addFavorite(user.accessToken, favoriteFile.id)
        api.deleteFiles(user.accessToken, listOf(recycleFile.id))

        composeRule.loginViaUi(user)
        composeRule.waitForTag("nav-favorites").performClick()
        composeRule.waitForTag("file-row-${favoriteFile.fileName}").assertIsDisplayed()

        composeRule.waitForTag("nav-shares").performClick()
        composeRule.waitForTag("share-card-${share.shareCode}").assertIsDisplayed()
        composeRule.waitForTag("share-password-${share.shareCode}").performClick()
        composeRule.waitForText("密码: abcd").assertIsDisplayed()

        composeRule.waitForTag("nav-profile").performClick()
        composeRule.waitForTag("profile-recycle-bin").performClick()
        composeRule.waitForTag("recycle-restore-${recycleFile.fileName}").assertIsDisplayed()
        composeRule.waitForTag("recycle-delete-${recycleFile.fileName}").assertIsDisplayed()
    }

    @Test
    fun tagsListAndTaggedFilesAreReachable() {
        val tagFile = api.uploadTextFile(user.accessToken, null, uniqueName("tag") + ".txt")
        val tag = api.createTag(user.accessToken, uniqueName("tag"))
        api.tagFile(user.accessToken, tag.id, tagFile.id)

        composeRule.loginViaUi(user)

        composeRule.waitForTag("nav-tags").performClick()
        composeRule.waitForTag("tag-row-${tag.tagName}").performClick()
        composeRule.waitForText("标签文件").assertIsDisplayed()
        composeRule.waitForTag("file-row-${tagFile.fileName}").assertIsDisplayed()
    }

    @Test
    fun versionsScreenIsReachableFromFileContextMenu() {
        val versionFile = api.uploadTextFile(user.accessToken, null, uniqueName("version") + ".txt")

        composeRule.loginViaUi(user)
        composeRule.waitForTag("file-row-${versionFile.fileName}").performTouchInput { longClick() }
        composeRule.waitForTag("file-menu-versions").performClick()
        composeRule.waitForText("版本历史").assertIsDisplayed()
    }
}
