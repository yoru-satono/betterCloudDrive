package com.betterclouddrive.mobile.feature.teams

import com.betterclouddrive.mobile.core.localization.MobileString
import com.betterclouddrive.mobile.core.localization.UiMessage
import com.betterclouddrive.mobile.core.model.FileItem
import com.betterclouddrive.mobile.core.model.OfficeConfig
import com.betterclouddrive.mobile.core.model.PageResult
import com.betterclouddrive.mobile.core.model.TeamRole
import com.betterclouddrive.mobile.core.model.TeamSummary
import com.betterclouddrive.mobile.core.model.TextPreview
import com.betterclouddrive.mobile.feature.files.PersonalFileSort
import com.betterclouddrive.mobile.feature.files.SortDirection
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
