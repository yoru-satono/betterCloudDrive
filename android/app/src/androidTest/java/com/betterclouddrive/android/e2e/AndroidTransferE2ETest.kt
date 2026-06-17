package com.betterclouddrive.android.e2e

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.performClick
import com.betterclouddrive.android.MainActivity
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class AndroidTransferE2ETest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    private val api = E2EApiClient()
    private val systemUi = AndroidSystemUi()
    private lateinit var user: E2EUser

    @Before
    fun setUp() {
        api.waitForBackend()
        user = api.createUser()
    }

    @Test
    fun uploadAndDownloadQueueAreReachableOnSimulator() {
        val uploadSeed = systemUi.seedDownloadFile(uniqueName("upload_seed") + ".txt", "upload payload")
        val remoteFile = api.uploadTextFile(user.accessToken, null, uniqueName("download_target") + ".txt", "remote payload")

        composeRule.loginViaUi(user)
        composeRule.waitForTag("files-upload").performClick()
        systemUi.pickFileFromSystemPicker(uploadSeed.name)
        systemUi.grantPermissionIfPrompted()
        composeRule.waitForTag("files-transfers").performClick()
        composeRule.waitForTag("transfer-tab-上传").performClick()
        composeRule.waitForText(uploadSeed.name).assertIsDisplayed()

        composeRule.waitForTag("nav-files").performClick()
        composeRule.waitForTag("file-row-${remoteFile.fileName}").performClick()
        composeRule.waitForTag("file-detail-download").performClick()
        systemUi.acceptCreateDocument(remoteFile.fileName)
        composeRule.waitForTag("files-transfers").performClick()
        composeRule.waitForTag("transfer-tab-下载").performClick()
        composeRule.waitForText(remoteFile.fileName).assertIsDisplayed()
    }
}
