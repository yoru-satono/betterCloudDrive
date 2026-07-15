package com.betterclouddrive.android.ui.teams

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import com.betterclouddrive.android.domain.model.TeamTask
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class TeamTasksContentTest {
    @get:Rule val composeRule = createComposeRule()

    @Test fun rendersTaskResultsFailureActionsFiltersAndPagination() {
        var selectedStatus: String? = null
        var retried: TeamTask? = null
        var downloaded: TeamTask? = null
        var previous = 0
        var next = 0
        val audit = task(1, "AUDIT_EXPORT", "SUCCEEDED", "audit.csv")
        val archive = task(2, "FOLDER_ZIP", "SUCCEEDED", "folder.zip")
        val office = task(3, "ONLYOFFICE_SAVE", "FAILED")
        composeRule.setContent {
            MaterialTheme {
                TeamTasksContent(
                    state = TeamTasksUiState(tasks = listOf(audit, archive, office), total = 23, page = 2, pages = 3),
                    onStatusSelected = { selectedStatus = it }, onTypeSelected = {},
                    onRetry = { retried = it }, onDownload = { downloaded = it },
                    onPreviousPage = { previous++ }, onNextPage = { next++ },
                )
            }
        }

        composeRule.onNodeWithTag("team-tasks-filter-status")
            .performScrollToNode(hasTestTag("team-tasks-filter-status-FAILED"))
        composeRule.onNodeWithTag("team-tasks-filter-status-FAILED").performClick()
        assertEquals("FAILED", selectedStatus)
        composeRule.onNodeWithTag("team-task-download-1").performClick()
        assertEquals(audit, downloaded)
        composeRule.onNodeWithTag("team-tasks-list").performScrollToNode(hasTestTag("team-task-retry-3"))
        composeRule.onNodeWithTag("team-task-retry-3").performClick()
        assertEquals(office, retried)
        composeRule.onNodeWithTag("team-task-download-3").assertDoesNotExist()

        composeRule.onNodeWithTag("team-tasks-page").assertIsDisplayed()
        composeRule.onNodeWithTag("team-tasks-previous").assertIsEnabled().performClick()
        composeRule.onNodeWithTag("team-tasks-next").assertIsEnabled().performClick()
        assertEquals(1, previous)
        assertEquals(1, next)
    }

    @Test fun firstAndLastPageDisableUnavailableDirection() {
        composeRule.setContent {
            MaterialTheme {
                TeamTasksContent(
                    state = TeamTasksUiState(page = 1, pages = 1),
                    onStatusSelected = {}, onTypeSelected = {}, onRetry = {}, onDownload = {},
                    onPreviousPage = {}, onNextPage = {},
                )
            }
        }
        composeRule.onNodeWithTag("team-tasks-previous").assertIsNotEnabled()
        composeRule.onNodeWithTag("team-tasks-next").assertIsNotEnabled()
    }

    private fun task(id: Long, type: String, status: String, fileName: String? = null) = TeamTask(
        id = id, teamId = 7, taskType = type, status = status, progress = if (status == "SUCCEEDED") 100 else 40,
        resultFileName = fileName,
    )
}
