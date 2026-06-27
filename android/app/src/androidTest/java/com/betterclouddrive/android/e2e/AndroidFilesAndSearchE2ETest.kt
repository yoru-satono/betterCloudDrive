package com.betterclouddrive.android.e2e

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import com.betterclouddrive.android.MainActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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

    @Test
    fun apiContractsSupportRootMoveCopyAndPasswordShareListing() {
        val sourceFolder = api.createFolder(user.accessToken, uniqueName("contract_source"))
        val file = api.uploadTextFile(user.accessToken, sourceFolder.id, uniqueName("contract_file") + ".txt", "contract content")

        api.copyFile(user.accessToken, file.id, null)
        api.moveFile(user.accessToken, file.id, null)

        val sourceFiles = api.listFiles(user.accessToken, sourceFolder.id)
        val rootFiles = api.listFiles(user.accessToken)
        assertFalse(sourceFiles.any { it.fileName == file.fileName })
        assertTrue(rootFiles.any { it.fileName == file.fileName })
        assertTrue(rootFiles.any { it.fileName == "${file.fileName} (copy)" })

        val sharedFolder = api.createFolder(user.accessToken, uniqueName("shared_contract"))
        val child = api.uploadTextFile(user.accessToken, sharedFolder.id, uniqueName("shared_child") + ".txt", "shared content")
        val share = api.createShare(user.accessToken, sharedFolder.id, password = "abcd")

        val wrongList = api.listSharedFiles(share.shareCode, password = "wrong")
        assertEquals(419003, wrongList.getInt("code"))
        val correctList = api.listSharedFiles(share.shareCode, password = "abcd")
        assertEquals(200, correctList.getInt("code"))
        val records = correctList.getJSONObject("data").getJSONArray("records")
        assertTrue((0 until records.length()).any { index ->
            records.getJSONObject(index).getString("fileName") == child.fileName
        })
    }
}
