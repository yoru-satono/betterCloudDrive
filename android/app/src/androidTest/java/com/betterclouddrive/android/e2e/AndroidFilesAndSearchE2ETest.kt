package com.betterclouddrive.android.e2e

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import com.betterclouddrive.android.MainActivity
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class AndroidFilesAndSearchE2ETest {
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
    fun browseOpenFolderAndPreviewFromSearch() {
        val folder = api.createFolder(user.accessToken, uniqueName("folder"))
        val nested = api.uploadTextFile(user.accessToken, folder.id, uniqueName("nested") + ".txt", "nested content")
        val rootFile = api.uploadTextFile(user.accessToken, null, uniqueName("root") + ".txt", "root content")

        composeRule.loginViaUi(user)
        composeRule.waitForText(folder.fileName).performClick()
        composeRule.waitForText(nested.fileName).assertIsDisplayed()

        composeRule.waitForTag("files-search").performClick()
        composeRule.waitForTag("search-input").performTextInput("root_")
        composeRule.waitForText(rootFile.fileName).assertIsDisplayed()
        composeRule.waitForText(rootFile.fileName).performClick()
        composeRule.waitForTag("text-preview-content").assertIsDisplayed()
    }

    @Test
    fun fileContextMenuAndDetailActionsAreReachable() {
        val file = api.uploadTextFile(user.accessToken, null, uniqueName("detail") + ".txt", "detail content")

        composeRule.loginViaUi(user)
        composeRule.waitForTag("file-row-${file.fileName}").performTouchInput { longClick() }
        composeRule.waitForTag("file-menu-preview").performClick()
        composeRule.waitForTag("text-preview-content").assertIsDisplayed()
    }
}
