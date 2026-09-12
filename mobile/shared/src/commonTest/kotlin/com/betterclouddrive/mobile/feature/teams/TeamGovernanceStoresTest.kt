package com.betterclouddrive.mobile.feature.teams

import com.betterclouddrive.mobile.core.localization.MobileString
import com.betterclouddrive.mobile.core.model.PageResult
import com.betterclouddrive.mobile.core.model.TeamOperationLog
import com.betterclouddrive.mobile.core.model.TeamRole
import com.betterclouddrive.mobile.core.model.TeamTask
import com.betterclouddrive.mobile.core.platform.LocalDateTimeService
import com.betterclouddrive.mobile.core.platform.PickedDestination
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class TeamGovernanceStoresTest {
    @Test
    fun auditTeamSwitchDiscardsTheOldTeamsLateResponse() = runTest {
        val repository = DelayedAuditRepository()
        val store = TeamAuditStore(repository, TestDateTime, backgroundScope)

        store.activate(10, TeamRole.ADMIN); runCurrent()
        store.activate(11, TeamRole.ADMIN); runCurrent()
        repository.requests[1].complete(auditPage(teamId = 11, logId = 2)); runCurrent()
        repository.requests[0].complete(auditPage(teamId = 10, logId = 1)); runCurrent()

        assertEquals(11, store.state.value.teamId)
        assertEquals(listOf(2L), store.state.value.logs.map { it.id })
    }

    @Test
    fun auditFiltersValidateApplyAndQueueTheExactExportSnapshot() = runTest {
        val repository = FakeAuditRepository()
        val queued = mutableListOf<Pair<Long, TeamTask>>()
        val store = TeamAuditStore(repository, TestDateTime, backgroundScope) { teamId, task -> queued += teamId to task }

        store.activate(10, TeamRole.OWNER); runCurrent()
        assertEquals(1, store.state.value.logs.size)

        store.updateActorUserId("0")
        store.applyFilters()
        assertEquals(MobileString.AuditActorInvalid, store.state.value.filterError?.key)

        store.updateActorUserId(" 12 ")
        store.updateAction(" file_delete ")
        store.updateTargetType(" file ")
        store.updateResult(0)
        store.updateStartAt(100)
        store.updateEndAt(200)
        store.applyFilters(); runCurrent()
        assertEquals(
            TeamAuditFilter(12, "FILE_DELETE", "FILE", 0, "api-100", "api-200"),
            repository.filters.last(),
        )

        store.export(); runCurrent()
        assertEquals(10L to 77L, queued.single().let { it.first to it.second.id })
        assertEquals(77, store.state.value.queuedTaskId)
        assertEquals(MobileString.AuditExportQueued, store.state.value.feedback?.key)

        store.activate(10, TeamRole.EDITOR)
        assertFalse(store.state.value.canView)
        assertTrue(store.state.value.logs.isEmpty())
        store.reset()
        assertNull(store.state.value.teamId)
    }

    @Test
    fun taskCenterReplacesRetriedTaskDownloadsAndResetsTeamBoundState() = runTest {
        val repository = FakeTasksRepository()
        val store = TeamTasksStore(repository, backgroundScope)

        store.activate(10, TeamRole.VIEWER); runCurrent()
        assertEquals("FAILED", store.state.value.tasks.single().status)
        store.retry(9); runCurrent()
        assertEquals("PENDING", store.state.value.tasks.single().status)
        assertEquals(MobileString.TeamTaskRetryQueued, store.state.value.feedback?.key)

        repository.page = PageResult(listOf(repository.downloadable), 1, 1, 20, 1)
        store.refresh(); runCurrent()
        store.download(8, PickedDestination("content://result", "audit.csv")); runCurrent()
        assertEquals(listOf(10L to 8L), repository.downloads)
        assertEquals(MobileString.TeamTaskDownloaded, store.state.value.feedback?.key)
        assertNull(store.state.value.downloadingTaskId)

        store.activate(11, TeamRole.ADMIN); runCurrent()
        assertEquals(11, store.state.value.teamId)
        store.reset()
        assertTrue(store.state.value.tasks.isEmpty())
    }

    @Test
    fun taskPollingOnlyRunsWhileVisibleWithAnActiveTask() = runTest {
        val repository = PollingTasksRepository()
        val store = TeamTasksStore(repository, backgroundScope)

        store.activate(10, TeamRole.VIEWER); runCurrent()
        store.setVisible(true)
        advanceTimeBy(5_000); runCurrent()
        assertEquals(2, repository.listCalls)

        store.setVisible(false)
        advanceTimeBy(10_000); runCurrent()
        assertEquals(2, repository.listCalls)
    }

    @Test
    fun taskCenterReloadsWhenTeamRoleChanges() = runTest {
        val repository = PollingTasksRepository()
        val store = TeamTasksStore(repository, backgroundScope)

        store.activate(10, TeamRole.VIEWER); runCurrent()
        assertEquals(1, repository.listCalls)
        store.activate(10, TeamRole.ADMIN); runCurrent()

        assertEquals(2, repository.listCalls)
        assertEquals(TeamRole.ADMIN, store.state.value.role)
        assertTrue(store.state.value.tasks.isNotEmpty())
    }

    private class FakeAuditRepository : TeamAuditRepository {
        val filters = mutableListOf<TeamAuditFilter>()
        override suspend fun list(teamId: Long, filter: TeamAuditFilter, page: Int, size: Int): PageResult<TeamOperationLog> {
            filters += filter
            return PageResult(listOf(TeamOperationLog(id = page.toLong(), teamId = teamId, action = "FILE_DELETE", result = 1)), 1, page, size, 1)
        }

        override suspend fun queueExport(teamId: Long, filter: TeamAuditFilter) =
            TeamTask(id = 77, teamId = teamId, taskType = "AUDIT_EXPORT", status = "PENDING")
    }

    private class FakeTasksRepository : TeamTasksRepository {
        val failed = TeamTask(id = 9, teamId = 10, taskType = "FOLDER_ZIP", status = "FAILED")
        val downloadable = TeamTask(
            id = 8, teamId = 10, taskType = "AUDIT_EXPORT", status = "SUCCEEDED",
            resultFileName = "audit.csv", resultSizeBytes = 4,
        )
        var page = PageResult(listOf(failed), 1, 1, 20, 1)
        val downloads = mutableListOf<Pair<Long, Long>>()

        override suspend fun list(teamId: Long, status: String?, type: String?, page: Int, size: Int) = this.page
        override suspend fun retry(teamId: Long, taskId: Long) = failed.copy(status = "PENDING", retryCount = 1)
        override suspend fun download(
            teamId: Long,
            task: TeamTask,
            destination: PickedDestination,
            onProgress: suspend (Long, Long?) -> Unit,
        ): Long {
            downloads += teamId to task.id
            onProgress(4, 4)
            return 4
        }
    }

    private class DelayedAuditRepository : TeamAuditRepository {
        val requests = mutableListOf<CompletableDeferred<PageResult<TeamOperationLog>>>()
        override suspend fun list(teamId: Long, filter: TeamAuditFilter, page: Int, size: Int): PageResult<TeamOperationLog> =
            CompletableDeferred<PageResult<TeamOperationLog>>().also(requests::add).await()
        override suspend fun queueExport(teamId: Long, filter: TeamAuditFilter) = error("not used")
    }

    private class PollingTasksRepository : TeamTasksRepository {
        var listCalls = 0
        override suspend fun list(teamId: Long, status: String?, type: String?, page: Int, size: Int): PageResult<TeamTask> {
            listCalls++
            return PageResult(listOf(TeamTask(id = 1, teamId = teamId, status = "RUNNING")), 1, 1, size, 1)
        }
        override suspend fun retry(teamId: Long, taskId: Long) = error("not used")
        override suspend fun download(
            teamId: Long,
            task: TeamTask,
            destination: PickedDestination,
            onProgress: suspend (Long, Long?) -> Unit,
        ) = error("not used")
    }

    private fun auditPage(teamId: Long, logId: Long) = PageResult(
        listOf(TeamOperationLog(id = logId, teamId = teamId, action = "FILE_DELETE", result = 1)),
        1,
        1,
        20,
        1,
    )

    private object TestDateTime : LocalDateTimeService {
        override fun nowEpochMillis() = 1_000L
        override fun localDateTimeToEpochMillis(selectedUtcDateMillis: Long, hour: Int, minute: Int) = selectedUtcDateMillis
        override fun formatDateTime(epochMillis: Long) = "display-$epochMillis"
        override fun formatApiLocalDateTime(epochMillis: Long) = "api-$epochMillis"
    }
}
