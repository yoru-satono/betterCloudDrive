package com.betterclouddrive.android.e2e

import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.rules.ActivityScenarioRule
import com.betterclouddrive.android.MainActivity

typealias BCDComposeRule = AndroidComposeTestRule<ActivityScenarioRule<MainActivity>, MainActivity>

fun BCDComposeRule.waitForTag(tag: String, timeoutMs: Long = 15_000): SemanticsNodeInteraction {
    waitUntil(timeoutMs) {
        onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
    }
    return onNodeWithTag(tag, useUnmergedTree = true)
}

fun BCDComposeRule.waitForText(text: String, timeoutMs: Long = 15_000): SemanticsNodeInteraction {
    waitUntil(timeoutMs) {
        onAllNodesWithText(text, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
    }
    return onNodeWithText(text, useUnmergedTree = true)
}

fun BCDComposeRule.loginViaUi(user: E2EUser) {
    waitForTag("login-username").performTextInput(user.username)
    waitForTag("login-password").performTextInput(user.password)
    waitForTag("login-submit").performClick()
    waitForTag("files-content", timeoutMs = 20_000)
}

fun uniqueName(prefix: String): String = "${prefix}_${System.currentTimeMillis()}"
