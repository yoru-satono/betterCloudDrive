package com.betterclouddrive.mobile.feature.teams

import com.betterclouddrive.mobile.core.localization.MobileString
import com.betterclouddrive.mobile.core.localization.UiMessage
import com.betterclouddrive.mobile.core.model.FileItem
import com.betterclouddrive.mobile.core.model.ApiException
import com.betterclouddrive.mobile.core.model.OfficeConfig
import com.betterclouddrive.mobile.core.model.PageResult
import com.betterclouddrive.mobile.core.model.TeamRole
import com.betterclouddrive.mobile.core.model.TeamSummary
import com.betterclouddrive.mobile.core.model.TeamEffectivePermission
import com.betterclouddrive.mobile.core.model.TextPreview
import com.betterclouddrive.mobile.feature.files.PersonalFileSort
import com.betterclouddrive.mobile.feature.files.SortDirection
import com.betterclouddrive.mobile.core.platform.UploadPickResult
import com.betterclouddrive.mobile.core.platform.PickedDocument
import com.betterclouddrive.mobile.feature.transfers.TeamTransferOperations
import com.betterclouddrive.mobile.feature.transfers.TeamUploadFolder
import com.betterclouddrive.mobile.feature.transfers.TransferTask
import com.betterclouddrive.mobile.feature.transfers.TransferDirection
import com.betterclouddrive.mobile.feature.transfers.TransferScope
import com.betterclouddrive.mobile.feature.transfers.TransferStatus
import com.betterclouddrive.mobile.feature.transfers.UploadEnqueueResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
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
class TeamStoresTest {
    @Test
    fun fileActionsFailClosedUseEffectivePermissionAndResetSensitiveState() = runTest {
        val repository = FakeTeamWorkspace()
        val transfers = FakeTeamTransfers()
        val changes = mutableListOf<Pair<Long, Set<Long?>>>()
        val store = TeamFileActionsStore(repository, transfers, backgroundScope) { teamId, folders ->
            changes += teamId to folders
        }

        store.activate(TEAM.copy(role = TeamRole.EDITOR), TEAM.id, null)
        assertTrue(store.state.value.directoryPermission?.canWrite == true)
        store.requestItemPermission(FILE); runCurrent()
        store.toggleSelection(FILE)
        assertEquals(setOf(FILE.id), store.state.value.selectedIds)

        store.delete(setOf(FILE.id)); runCurrent()
        assertEquals(listOf(FILE.id), repository.deletedIds)
        assertEquals(TEAM.id to setOf<Long?>(null), changes.single())

        store.activate(TEAM.copy(role = TeamRole.VIEWER), TEAM.id, null)
        assertFalse(store.state.value.directoryPermission?.canWrite == true)
        store.reset()
        assertNull(store.state.value.teamId)
        assertTrue(store.state.value.itemPermissions.isEmpty())
    }

    @Test
    fun fileActionsUseTeamTransferScopeEmitSettledRefreshAndInvalidatePermissionOn403() = runTest {
        val repository = FakeTeamWorkspace()
        val transfers = FakeTeamTransfers()
        val changes = mutableListOf<Pair<Long, Set<Long?>>>()
        val store = TeamFileActionsStore(repository, transfers, backgroundScope) { teamId, folders ->
            changes += teamId to folders
        }
        store.activate(TEAM.copy(role = TeamRole.EDITOR), TEAM.id, 12L); runCurrent()

        store.upload(UploadPickResult(listOf(PickedDocument("content://source", "team.bin", 64)))); runCurrent()
        assertEquals(Triple(TEAM.id, 12L, "team.bin"), transfers.uploaded)
        store.download(FILE, "content://target"); runCurrent()
        assertEquals(Triple(TEAM.id, FILE.id, "content://target"), transfers.downloaded)

        transfers.settle(TeamUploadFolder(TEAM.id, 12L)); runCurrent()
        assertEquals(TEAM.id to setOf<Long?>(12L), changes.single())

        repository.effective = TeamEffectivePermission(true, false, false, false, false)
        transfers.revoke(TEAM.id); runCurrent()
        assertFalse(store.state.value.directoryPermission?.canWrite == true)
        assertEquals(UiMessage(MobileString.ErrorPermissionChanged), store.state.value.permissionError)

        store.requestItemPermission(FILE); runCurrent()
        repository.renameFailure = ApiException(403, "error.forbidden")
        store.rename(FILE, "renamed.txt"); runCurrent()
        assertEquals(UiMessage(MobileString.ErrorPermissionChanged), store.state.value.permissionError)
        assertTrue(store.state.value.itemPermissions.isEmpty())
    }

    @Test
    fun teamsPageDeduplicatesCreatesTrimmedNameAndResetClearsSessionState() = runTest {
        val repository = FakeTeamWorkspace().apply {
            teamPages += PageResult(listOf(TEAM, TEAM), 2, 1, 50, 2)
            teamPages += PageResult(listOf(TEAM.copy(id = 2, name = "Beta")), 2, 2, 50, 2)
        }
        val store = TeamsStore(repository, backgroundScope)
        store.activate(); runCurrent()
        assertEquals(listOf(1L), store.state.value.items.map(TeamSummary::id))
        store.loadMore(); runCurrent()
        assertEquals(listOf(1L, 2L), store.state.value.items.map(TeamSummary::id))

        store.create("  Created  "); runCurrent()
        assertEquals("Created", repository.createdNames.single())
        assertEquals(99, store.state.value.createdTeam?.id)
        store.reset()
        assertTrue(store.state.value.items.isEmpty())
        assertNull(store.state.value.createdTeam)
    }

    @Test
    fun browserRebuildsPathRestoresSortOnFailureAndSwitchesWorkspaceWithoutOldCache() = runTest {
        val parent = FileItem(10, null, "parent", "folder")
        val child = FileItem(11, 10, "child", "folder")
        val repository = FakeTeamWorkspace().apply {
            metadata[10] = parent; metadata[11] = child
            filePages += Result.success(PageResult(listOf(FILE), 1, 1, 50, 1))
            filePages += Result.failure(IllegalStateException("offline"))
            filePages += Result.success(PageResult(emptyList(), 0, 1, 50, 1))
        }
        val store = TeamBrowserStore(repository, backgroundScope)
        store.activate(TEAM.id, child.id); runCurrent()
        assertEquals(listOf(null, 10L, 11L), store.state.value.breadcrumb.map { it.id })
        assertEquals(FILE.id, store.state.value.current.items.single().id)

        store.changeSort(PersonalFileSort.SIZE, SortDirection.DESCENDING); runCurrent()
        assertEquals(PersonalFileSort.NAME, store.state.value.sort)
        assertEquals(UiMessage(MobileString.ErrorNetwork), store.state.value.current.error)

        store.activate(2, null); runCurrent()
        assertEquals(2, store.state.value.teamId)
        assertTrue(store.state.value.breadcrumb.size == 1)
        assertTrue(store.state.value.current.items.isEmpty())
    }

    @Test
    fun searchDebouncesLatestQueryPagesWithoutDuplicatesAndResetCancelsIt() = runTest {
        val repository = FakeTeamWorkspace().apply {
            searches += PageResult(listOf(FILE), 2, 1, 50, 2)
            searches += PageResult(listOf(FILE, FILE.copy(id = 8)), 2, 2, 50, 2)
        }
        val store = TeamSearchStore(repository, backgroundScope)
        store.activate(1)
        store.updateQuery("old")
        store.updateQuery(" latest ")
        advanceTimeBy(300); runCurrent()
        assertEquals(listOf("latest"), repository.searchQueries)
        store.loadMore(); runCurrent()
        assertEquals(listOf(7L, 8L), store.state.value.items.map(FileItem::id))
        store.reset()
        assertNull(store.state.value.teamId)
        assertTrue(store.state.value.items.isEmpty())
    }

    @Test
    fun previewKeepsTicketInFeatureStateRetriesOnceAndClearsOnReset() = runTest {
        val repository = FakeTeamPreview()
        val store = TeamPreviewStore(repository, backgroundScope)
        store.open(3, IMAGE.id); runCurrent()
        assertEquals("https://ticket/1", store.state.value.mediaUrl)
        store.reportRendererError(UiMessage(MobileString.ErrorPreviewFailed)); runCurrent()
        assertEquals("https://ticket/2", store.state.value.mediaUrl)
        store.reportRendererError(UiMessage(MobileString.ErrorPreviewFailed)); runCurrent()
        assertNull(store.state.value.mediaUrl)
        assertEquals(2, repository.ticketCalls)

        store.reset()
        assertNull(store.state.value.teamId)
        assertNull(store.state.value.file)
        assertFalse(store.state.value.loading)
    }

    private class FakeTeamWorkspace : TeamWorkspaceRepository {
        val teamPages = ArrayDeque<PageResult<TeamSummary>>()
        val filePages = ArrayDeque<Result<PageResult<FileItem>>>()
        val searches = ArrayDeque<PageResult<FileItem>>()
        val metadata = mutableMapOf<Long, FileItem>()
        val createdNames = mutableListOf<String>()
        val searchQueries = mutableListOf<String>()
        val deletedIds = mutableListOf<Long>()
        var renameFailure: Throwable? = null
        var effective = TeamEffectivePermission(true, true, true, true, false)

        override suspend fun listTeams(page: Int, size: Int) = teamPages.removeFirst()
        override suspend fun createTeam(name: String): TeamSummary {
            createdNames += name
            return TEAM.copy(id = 99, name = name, role = TeamRole.OWNER)
        }
        override suspend fun getTeam(teamId: Long) = TEAM.copy(id = teamId, name = "Team $teamId")
        override suspend fun listFiles(teamId: Long, parentId: Long?, page: Int, size: Int, sortBy: String, order: String) =
            filePages.removeFirst().getOrThrow()
        override suspend fun getFile(teamId: Long, fileId: Long) = metadata[fileId] ?: FILE.copy(id = fileId)
        override suspend fun search(teamId: Long, query: String, page: Int, size: Int): PageResult<FileItem> {
            searchQueries += query
            return searches.removeFirst()
        }
        override suspend fun effectivePermission(teamId: Long, fileId: Long) = effective
        override suspend fun createFolder(teamId: Long, parentId: Long?, name: String) = FILE.copy(id = 100, parentId = parentId, fileName = name, fileType = "folder")
        override suspend fun rename(teamId: Long, fileId: Long, name: String): FileItem {
            renameFailure?.let { throw it }
            return getFile(teamId, fileId).copy(fileName = name)
        }
        override suspend fun move(teamId: Long, fileId: Long, parentId: Long?) = Unit
        override suspend fun delete(teamId: Long, fileIds: List<Long>) { deletedIds += fileIds }
    }

    private class FakeTeamTransfers : TeamTransferOperations {
        private val settled = MutableSharedFlow<TeamUploadFolder>()
        private val revoked = MutableSharedFlow<Long>()
        var uploaded: Triple<Long, Long?, String>? = null
        var downloaded: Triple<Long, Long, String>? = null
        override val teamUploadFoldersSettled: Flow<TeamUploadFolder> = settled
        override val teamPermissionRevoked: Flow<Long> = revoked
        suspend fun settle(folder: TeamUploadFolder) = settled.emit(folder)
        suspend fun revoke(teamId: Long) = revoked.emit(teamId)
        override suspend fun enqueueTeamUploads(teamId: Long, result: UploadPickResult, parentId: Long?): UploadEnqueueResult {
            uploaded = Triple(teamId, parentId, result.documents.single().displayName)
            return UploadEnqueueResult(emptyList(), result.rejectedCount, result.overflowCount)
        }
        override suspend fun enqueueTeamDownload(
            teamId: Long, fileId: Long, fileName: String, fileSize: Long, mimeType: String?,
            destinationHandle: String, expectedMd5: String?,
        ): TransferTask {
            downloaded = Triple(teamId, fileId, destinationHandle)
            return TransferTask(
                id = "team-download", direction = TransferDirection.DOWNLOAD, status = TransferStatus.QUEUED,
                serverBaseUrl = "https://drive.example.com", scope = TransferScope.Team(teamId), fileName = fileName,
                bytesTotal = fileSize, destinationHandle = destinationHandle, createdAt = 1, updatedAt = 1,
            )
        }
    }

    private class FakeTeamPreview : TeamPreviewRepository {
        var ticketCalls = 0
        override suspend fun getFile(teamId: Long, fileId: Long) = if (fileId == IMAGE.id) IMAGE else FILE
        override suspend fun createTicket(teamId: Long, fileId: Long) = "https://ticket/${++ticketCalls}"
        override suspend fun loadText(teamId: Long, file: FileItem) = TextPreview("text", false)
        override suspend fun loadOffice(teamId: Long, fileId: Long) = OfficeConfig(editable = false)
    }

    private companion object {
        val TEAM = TeamSummary(1, "Alpha", role = TeamRole.VIEWER)
        val FILE = FileItem(7, fileName = "note.txt", mimeType = "text/plain")
        val IMAGE = FileItem(9, fileName = "image.png", mimeType = "image/png")
    }
}
