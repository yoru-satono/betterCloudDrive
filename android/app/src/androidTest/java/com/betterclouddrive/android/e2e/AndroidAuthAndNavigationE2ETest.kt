package com.betterclouddrive.android.e2e

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import com.betterclouddrive.android.MainActivity
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class AndroidAuthAndNavigationE2ETest {
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
    fun loginAndNavigateMajorPages() {
        composeRule.loginViaUi(user)

        composeRule.waitForTag("files-content").assertIsDisplayed()
        composeRule.waitForTag("nav-shares").performClick()
        composeRule.waitForText("我的分享").assertIsDisplayed()
        composeRule.waitForTag("nav-favorites").performClick()
        composeRule.waitForText("收藏夹").assertIsDisplayed()
        composeRule.waitForTag("nav-tags").performClick()
        composeRule.waitForText("标签管理").assertIsDisplayed()
        composeRule.waitForTag("nav-profile").performClick()
        composeRule.waitForText("个人设置").assertIsDisplayed()
        composeRule.waitForTag("profile-recycle-bin").performClick()
        composeRule.waitForTag("recycle-empty").assertIsDisplayed()
        composeRule.waitForTag("nav-files").performClick()
        composeRule.waitForTag("files-content").assertIsDisplayed()
    }

    @Test
    fun logoutReturnsToLoginScreen() {
        composeRule.loginViaUi(user)

        composeRule.waitForTag("nav-profile").performClick()
        composeRule.waitForTag("profile-logout").performClick()
        composeRule.waitForTag("login-submit").assertIsDisplayed()
    }

    @Test
    fun loginServerSettingsValidateAndSaveAddress() {
        composeRule.waitForTag("login-settings").performClick()
        composeRule.waitForTag("server-url-input").assertIsDisplayed()
        composeRule.waitForTag("server-url-input").performTextClearance()
        composeRule.waitForTag("server-url-input").performTextInput("not-a-url")
        composeRule.waitForTag("server-url-save").performClick()
        composeRule.waitForText("服务器地址格式无效").assertIsDisplayed()

        composeRule.waitForTag("login-settings").performClick()
        composeRule.waitForTag("server-url-input").performTextClearance()
        composeRule.waitForTag("server-url-input").performTextInput("http://10.0.2.2:8080")
        composeRule.waitForTag("server-url-save").performClick()
        composeRule.waitForText("服务器地址已保存，请重新登录").assertIsDisplayed()

        composeRule.loginViaUi(user)
    }
}
