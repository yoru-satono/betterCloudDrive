package com.betterclouddrive.android.ui.teams

import android.content.Context
import com.betterclouddrive.android.MainDispatcherRule
import com.betterclouddrive.android.data.remote.ApiService
import com.betterclouddrive.android.data.remote.dto.ApiResponse
import com.betterclouddrive.android.data.remote.dto.PageResult
import com.betterclouddrive.android.domain.model.TeamTask
import com.betterclouddrive.android.util.MessageResolver
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.any
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

@OptIn(ExperimentalCoroutinesApi::class)
class TeamTasksViewModelTest {
    @get:Rule val dispatcherRule = MainDispatcherRule()
    private val api: ApiService = mock()
    private val context: Context = mock()
    private lateinit var viewModel: TeamTasksViewModel

    @Before fun setUp() {
        whenever(context.applicationContext).thenReturn(context)
        whenever(context.getString(any())).thenReturn("Forbidden")
        MessageResolver.initialize(context)
        viewModel = TeamTasksViewModel(api, context)
    }

    @Test fun loadAndFilterPreserveServerPaginationAndSelection() = runTest {
        whenever(api.listTeamTasks(7, null, null, 1, 20)).thenReturn(ApiResponse(data = PageResult(
            records = listOf(task(1, "AUDIT_EXPORT", "SUCCEEDED")), total = 21, page = 1, size = 20, pages = 2,
        )))
        viewModel.load(7)
        advanceUntilIdle()
        assertEquals(21, viewModel.state.value.total)
        assertEquals(2, viewModel.state.value.pages)
        assertFalse(viewModel.state.value.loading)

        whenever(api.listTeamTasks(7, "FAILED", "ONLYOFFICE_SAVE", 1, 20)).thenReturn(ApiResponse(data = PageResult(
            records = listOf(task(2, "ONLYOFFICE_SAVE", "FAILED")), total = 1, page = 1, size = 20, pages = 1,
        )))
        viewModel.filter("FAILED", "ONLYOFFICE_SAVE")
        advanceUntilIdle()
        assertEquals("FAILED", viewModel.state.value.status)
        assertEquals("ONLYOFFICE_SAVE", viewModel.state.value.type)
        assertEquals(2, viewModel.state.value.tasks.single().id)
    }

    @Test fun nextPageAndRetryReloadCurrentPage() = runTest {
        whenever(api.listTeamTasks(7, null, null, 2, 20)).thenReturn(ApiResponse(data = PageResult(
            records = listOf(task(9, "FOLDER_ZIP", "FAILED")), total = 21, page = 2, size = 20, pages = 2,
        )))
        viewModel.load(7, 2)
        advanceUntilIdle()
        whenever(api.retryTeamTask(7, 9)).thenReturn(ApiResponse(data = task(9, "FOLDER_ZIP", "PENDING")))
        viewModel.retry(viewModel.state.value.tasks.single())
        advanceUntilIdle()
        verify(api, times(2)).listTeamTasks(7, null, null, 2, 20)
        verify(api).retryTeamTask(7, 9)
    }

    @Test fun authorizationErrorClearsLoadingAndExposesError() = runTest {
        whenever(api.listTeamTasks(7, null, null, 1, 20)).thenReturn(ApiResponse(code = 403, messageKey = "error.forbidden"))
        viewModel.load(7)
        advanceUntilIdle()
        assertFalse(viewModel.state.value.loading)
        assertTrue(viewModel.state.value.error?.isNotBlank() == true)
    }

    private fun task(id: Long, type: String, status: String) = TeamTask(
        id = id, teamId = 7, taskType = type, status = status,
        resultFileName = if (status == "SUCCEEDED") "result.bin" else null,
    )
}
