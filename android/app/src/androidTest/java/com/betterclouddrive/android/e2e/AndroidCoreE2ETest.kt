package com.betterclouddrive.android.e2e

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.betterclouddrive.android.MainActivity
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class AndroidCoreE2ETest {
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
    fun loginShowsFilesScreen() {
        loginViaUi(user)
        composeRule.waitForTag("files-content").assertIsDisplayed()
        composeRule.waitForText("此目录为空").assertIsDisplayed()
    }

    @Test
    fun listsAndOpensApiSeededFolderAndFile() {
        val folderName = uniqueName("android_folder")
        val fileName = uniqueName("android_file") + ".txt"
        val fileContent = "sample for $fileName"
        val folder = api.createFolder(user.accessToken, folderName)
        api.uploadTextFile(user.accessToken, folder.id, fileName, fileContent)

        loginViaUi(user)
        composeRule.waitForText(folderName).performClick()
        composeRule.waitForText(fileName).assertIsDisplayed()
    }

    @Test
    fun searchOpensFileAndFolderLocations() {
        val folderName = uniqueName("search_folder")
        val nestedName = uniqueName("search_nested") + ".txt"
        val rootFileName = uniqueName("search_root") + ".txt"
        val folder = api.createFolder(user.accessToken, folderName)
        api.uploadTextFile(user.accessToken, folder.id, nestedName, "nested")
        api.uploadTextFile(user.accessToken, null, rootFileName, "root")

        loginViaUi(user)
        composeRule.waitForTag("files-search").performClick()
        composeRule.waitForTag("search-input").performTextInput("search_")
        composeRule.waitForText(folderName).assertIsDisplayed()
        composeRule.waitForText(rootFileName).assertIsDisplayed()

        composeRule.waitForText(folderName).performClick()
        composeRule.waitForText(nestedName).assertIsDisplayed()
    }

    @Test
    fun favoriteShareAndRecycleFlowsAreReachable() {
        val favoriteFileName = uniqueName("favorite_file") + ".txt"
        val shareFileName = uniqueName("share_file") + ".txt"
        val favoriteFile = api.uploadTextFile(user.accessToken, null, favoriteFileName, "favorite")
        val shareFile = api.uploadTextFile(user.accessToken, null, shareFileName, "share")
        api.addFavorite(user.accessToken, favoriteFile.id)
        api.createShare(user.accessToken, shareFile.id)

        loginViaUi(user)
        composeRule.waitForTag("nav-favorites").performClick()
        composeRule.waitForText(favoriteFileName).assertIsDisplayed()

        composeRule.waitForTag("nav-shares").performClick()
        composeRule.waitForText("我的分享").assertIsDisplayed()

        composeRule.waitForTag("nav-files").performClick()
        composeRule.waitForTag("files-transfers").performClick()
        composeRule.waitForText("传输队列").assertIsDisplayed()
        composeRule.waitForText("暂无上传任务").assertIsDisplayed()
    }

    private fun loginViaUi(user: E2EUser) {
        composeRule.waitForTag("login-username").performTextInput(user.username)
        composeRule.waitForTag("login-password").performTextInput(user.password)
        composeRule.waitForTag("login-submit").performClick()
        composeRule.waitForTag("files-content", timeoutMs = 20_000)
    }

    private fun uniqueName(prefix: String): String {
        return "${prefix}_${System.currentTimeMillis()}"
    }
}
