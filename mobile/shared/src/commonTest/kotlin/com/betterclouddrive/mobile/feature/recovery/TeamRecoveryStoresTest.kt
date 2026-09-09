package com.betterclouddrive.mobile.feature.recovery

import com.betterclouddrive.mobile.core.model.FileItem
import com.betterclouddrive.mobile.core.model.PageResult
import com.betterclouddrive.mobile.core.model.PersonalFileVersion
import com.betterclouddrive.mobile.core.model.RecycleBinItem
import com.betterclouddrive.mobile.core.model.TeamEffectivePermission
import com.betterclouddrive.mobile.core.model.TeamFilePermission
import com.betterclouddrive.mobile.core.model.TeamRole
import com.betterclouddrive.mobile.core.model.UpdateTeamFilePermissionRequest
import com.betterclouddrive.mobile.feature.teams.TeamPermissionsRepository
import com.betterclouddrive.mobile.feature.transfers.TeamVersionTransferOperations
import com.betterclouddrive.mobile.feature.transfers.TransferDirection
import com.betterclouddrive.mobile.feature.transfers.TransferScope
import com.betterclouddrive.mobile.feature.transfers.TransferStatus
import com.betterclouddrive.mobile.feature.transfers.TransferTask
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class TeamRecoveryStoresTest {
    @Test
    fun recycleBinDebouncesDeduplicatesChecksPermissionsAndResetsOnTeamSwitch() = runTest {
        val repository = FakeRecycle().apply {
            pages += PageResult(listOf(RECYCLE), 1, 1, 50, 1)
            pages += PageResult(listOf(RECYCLE), 3, 1, 50, 3)
            pages += PageResult(listOf(RECYCLE, RECYCLE.copy(id = 2, fileName = "two")), 3, 2, 50, 3)
            pages += PageResult(listOf(RECYCLE.copy(id = 2, fileName = "two"), RECYCLE.copy(id = 3, fileName = "three")), 3, 3, 50, 3)
            pages += PageResult(listOf(RECYCLE.copy(id = 3, fileName = "other")), 1, 1, 50, 1)
        }
        val permissions = FakePermissions(TeamEffectivePermission(canRead = true, canWrite = true, canDelete = false))
        val invalidated = mutableListOf<Long>()
        val store = TeamRecycleBinStore(repository, permissions, backgroundScope, invalidated::add)

        store.activate(7, TeamRole.EDITOR); runCurrent()
        store.updateQuery(" old ")
        advanceTimeBy(299); runCurrent()
        assertEquals(1, repository.requests.size)
        advanceTimeBy(1); runCurrent()
        store.loadMore(); runCurrent()
        store.loadMore(); runCurrent()
        assertEquals(listOf(1L, 2L, 3L), store.state.value.items.map { it.id })
        assertEquals("old", repository.requests[1].second)

        store.requestPermission(1); runCurrent()
        store.permanentlyDelete(1); runCurrent()
        assertTrue(repository.deleted.isEmpty())
        store.restore(1); runCurrent()
        assertEquals(listOf(7L to 1L), repository.restored)
        assertEquals(listOf(7L), invalidated)

        store.activate(8, TeamRole.VIEWER); runCurrent()
        assertEquals(8L, store.state.value.teamId)
        assertEquals(listOf(3L), store.state.value.items.map { it.id })
        assertFalse(store.state.value.canEmpty)
    }

    @Test
    fun recycleBinDropsAnOldTeamResponseAfterSwitchingTeams() = runTest {
        val repository = StaleRecycle()
        val store = TeamRecycleBinStore(repository, FakePermissions(ALL), backgroundScope)

        store.activate(7, TeamRole.EDITOR); runCurrent()
        store.activate(8, TeamRole.VIEWER); runCurrent()
        assertEquals(8L, store.state.value.teamId)
        assertEquals(listOf(8L), store.state.value.items.map { it.id })

        repository.releaseOld.complete(Unit); runCurrent()
        assertEquals(8L, store.state.value.teamId)
        assertEquals(listOf(8L), store.state.value.items.map { it.id })
    }

    @Test
    fun ownerCanEmptyAndGlobalResetClearsBoundTeam() = runTest {
        val repository = FakeRecycle().apply { pages += PageResult(listOf(RECYCLE), 1, 1, 50, 1) }
        val store = TeamRecycleBinStore(repository, FakePermissions(ALL), backgroundScope)
        store.activate(7, TeamRole.OWNER); runCurrent()
        assertTrue(store.state.value.canEmpty)
        store.empty(); runCurrent()
        assertEquals(listOf(7L), repository.emptied)
        assertTrue(store.state.value.items.isEmpty())
        store.reset()
        assertNull(store.state.value.teamId)
    }

    @Test
    fun versionsUseTeamScopedTransferAndSeparateWriteDeletePermissions() = runTest {
        val repository = FakeVersions()
        val transfers = FakeTransfers()
        val invalidated = mutableListOf<Pair<Long, Long?>>()
        val store = TeamVersionsStore(
            repository,
            FakePermissions(TeamEffectivePermission(canRead = true, canWrite = true, canDelete = false)),
            transfers,
            backgroundScope,
        ) { teamId, parentId -> invalidated += teamId to parentId }

        store.open(7, 9); runCurrent()
        assertEquals(2, store.state.value.versions.single().versionNumber)
        store.download(VERSION, "content://target"); runCurrent()
        assertEquals(listOf(Triple(7L, 9L, 2)), transfers.requests)
        store.delete(2); runCurrent()
        assertTrue(repository.deleted.isEmpty())
        store.restore(2); runCurrent()
        assertEquals(listOf(Triple(7L, 9L, 2)), repository.restored)
        assertEquals(listOf<Pair<Long, Long?>>(7L to 4L), invalidated)

        store.open(8, 10); runCurrent()
        assertEquals(8L, store.state.value.teamId)
        store.reset()
        assertNull(store.state.value.fileId)
    }

    private class FakeRecycle : TeamRecycleBinRepository {
        val pages = ArrayDeque<PageResult<RecycleBinItem>>()
        val requests = mutableListOf<Triple<Long, String?, Int>>()
        val restored = mutableListOf<Pair<Long, Long>>()
        val deleted = mutableListOf<Pair<Long, Long>>()
        val emptied = mutableListOf<Long>()
        override suspend fun list(teamId: Long, query: String?, page: Int, size: Int): PageResult<RecycleBinItem> {
            requests += Triple(teamId, query, page)
            return pages.removeFirst()
        }
        override suspend fun restore(teamId: Long, fileId: Long) { restored += teamId to fileId }
        override suspend fun permanentlyDelete(teamId: Long, fileId: Long) { deleted += teamId to fileId }
        override suspend fun empty(teamId: Long) { emptied += teamId }
    }

    private class StaleRecycle : TeamRecycleBinRepository {
        val releaseOld = CompletableDeferred<Unit>()
        override suspend fun list(teamId: Long, query: String?, page: Int, size: Int): PageResult<RecycleBinItem> {
            if (teamId == 7L) withContext(NonCancellable) { releaseOld.await() }
            return PageResult(
                listOf(RECYCLE.copy(id = teamId, fileName = "team-$teamId")), 1, 1, size, 1,
            )
        }
        override suspend fun restore(teamId: Long, fileId: Long) = Unit
        override suspend fun permanentlyDelete(teamId: Long, fileId: Long) = Unit
        override suspend fun empty(teamId: Long) = Unit
    }

    private class FakeVersions : TeamVersionsRepository {
        val restored = mutableListOf<Triple<Long, Long, Int>>()
        val deleted = mutableListOf<Triple<Long, Long, Int>>()
        override suspend fun file(teamId: Long, fileId: Long) = FILE.copy(id = fileId)
        override suspend fun list(teamId: Long, fileId: Long) = listOf(VERSION.copy(fileId = fileId))
        override suspend fun restore(teamId: Long, fileId: Long, versionNumber: Int) =
            FILE.copy(id = fileId).also { restored += Triple(teamId, fileId, versionNumber) }
        override suspend fun delete(teamId: Long, fileId: Long, versionNumber: Int) {
            deleted += Triple(teamId, fileId, versionNumber)
        }
    }

    private class FakePermissions(private val permission: TeamEffectivePermission) : TeamPermissionsRepository {
        override suspend fun effective(teamId: Long, fileId: Long) = permission
        override suspend fun list(teamId: Long, fileId: Long): List<TeamFilePermission> = emptyList()
        override suspend fun upsert(teamId: Long, fileId: Long, request: UpdateTeamFilePermissionRequest) = TeamFilePermission()
        override suspend fun delete(teamId: Long, fileId: Long, permissionId: Long) = Unit
    }

    private class FakeTransfers : TeamVersionTransferOperations {
        val requests = mutableListOf<Triple<Long, Long, Int>>()
        override suspend fun enqueueTeamVersionDownload(
            teamId: Long, fileId: Long, versionNumber: Int, fileName: String, fileSize: Long,
            mimeType: String?, destinationHandle: String, expectedMd5: String?,
        ): TransferTask {
            requests += Triple(teamId, fileId, versionNumber)
            return TransferTask(
                "task", TransferDirection.DOWNLOAD, TransferStatus.QUEUED, "https://drive.example.com",
                TransferScope.Team(teamId), remoteFileId = fileId, remoteVersionNumber = versionNumber,
                fileName = fileName, createdAt = 1, updatedAt = 1,
            )
        }
    }

    private companion object {
        val ALL = TeamEffectivePermission(true, true, true, true, true)
        val RECYCLE = RecycleBinItem(id = 1, fileName = "old", deletedAt = "2026-09-09T10:00:00")
        val FILE = FileItem(id = 9, parentId = 4, fileName = "doc.txt", mimeType = "text/plain")
        val VERSION = PersonalFileVersion(id = 20, fileId = 9, versionNumber = 2, fileSize = 8, md5Hash = "abcd")
    }
}
