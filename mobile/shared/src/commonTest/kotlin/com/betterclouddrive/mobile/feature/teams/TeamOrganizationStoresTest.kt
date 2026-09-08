package com.betterclouddrive.mobile.feature.teams

import com.betterclouddrive.mobile.core.model.FileItem
import com.betterclouddrive.mobile.core.model.PageResult
import com.betterclouddrive.mobile.core.model.PersonalTag
import com.betterclouddrive.mobile.core.model.TeamRole
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
class TeamOrganizationStoresTest {
    @Test
    fun favoritesDebouncePageToggleAndTeamSwitchWithoutLeakingState() = runTest {
        val repository = FakeTeamOrganization().apply {
            favoritePages += PageResult(listOf(FILE, FILE), 2, 1, 50, 2)
            favoritePages += PageResult(listOf(FILE, FILE.copy(id = 8)), 2, 2, 50, 2)
            statuses[FILE.id] = true
        }
        val store = TeamFavoritesStore(repository, backgroundScope)
        store.bind(TEAM_ID)
        store.updateQuery("old")
        store.updateQuery(" latest ")
        advanceTimeBy(300); runCurrent()
        assertEquals(listOf(Triple<Long, String?, Int>(TEAM_ID, "latest", 1)), repository.favoriteQueries)
        store.loadMore(); runCurrent()
        assertEquals(listOf(7L, 8L), store.state.value.files.items.map(FileItem::id))

        store.toggle(FILE); runCurrent()
        assertEquals(TEAM_ID to FILE.id, repository.removedFavorite)
        assertFalse(store.state.value.favoriteStatus.getValue(FILE.id))

        store.bind(2L)
        assertTrue(store.state.value.files.items.isEmpty())
        assertTrue(store.state.value.favoriteStatus.isEmpty())
    }

    @Test
    fun tagsRespectManagerRoleAndSaveEditorWithOneAtomicReplacement() = runTest {
        val repository = FakeTeamOrganization().apply {
            tags += TAG
            assigned += TAG
        }
        val store = TeamTagsStore(repository, backgroundScope)
        store.activate(TEAM_ID, TeamRole.EDITOR); runCurrent()
        assertFalse(store.canManage.value)
        store.create("blocked", "#00d4aa"); runCurrent()
        assertTrue(repository.createdNames.isEmpty())

        store.bind(TEAM_ID, TeamRole.ADMIN)
        assertTrue(store.canManage.value)
        store.openEditor(FILE); runCurrent()
        assertEquals(setOf(TAG.id), store.editor.value?.selectedIds)
        store.toggleEditorTag(TAG.id)
        store.saveEditor(); runCurrent()
        assertEquals(Triple(TEAM_ID, FILE.id, emptyList()), repository.replaced)
        assertNull(store.editor.value)
    }

    @Test
    fun tagFilesDebouncePageAndResetClearTeamScopedState() = runTest {
        val repository = FakeTeamOrganization().apply {
            tags += TAG
            tagFilePages += PageResult(listOf(FILE), 2, 1, 50, 2)
            tagFilePages += PageResult(listOf(FILE, FILE.copy(id = 8)), 2, 2, 50, 2)
        }
        val store = TeamTagsStore(repository, backgroundScope)
        store.activate(TEAM_ID, TeamRole.OWNER); runCurrent()
        store.activateFiles(TAG.id); runCurrent()
        store.updateFilesQuery("old")
        store.updateFilesQuery(" report ")
        advanceTimeBy(300); runCurrent()
        store.loadMoreFiles(); runCurrent()
        assertEquals(listOf(7L, 8L), store.state.value.tagFiles.items.map(FileItem::id))
        assertEquals("report", repository.tagFileQueries.last().third)

        store.reset()
        assertTrue(store.state.value.tags.isEmpty())
        assertNull(store.editor.value)
        assertFalse(store.canManage.value)
    }

    private class FakeTeamOrganization : TeamOrganizationRepository {
        val favoritePages = ArrayDeque<PageResult<FileItem>>()
        val tagFilePages = ArrayDeque<PageResult<FileItem>>()
        val favoriteQueries = mutableListOf<Triple<Long, String?, Int>>()
        val tagFileQueries = mutableListOf<Triple<Long, Long, String?>>()
        val statuses = mutableMapOf<Long, Boolean>()
        val tags = mutableListOf<PersonalTag>()
        val assigned = mutableListOf<PersonalTag>()
        val createdNames = mutableListOf<String>()
        var removedFavorite: Pair<Long, Long>? = null
        var replaced: Triple<Long, Long, List<Long>>? = null

        override suspend fun listFavorites(teamId: Long, query: String?, page: Int, size: Int): PageResult<FileItem> {
            favoriteQueries += Triple(teamId, query, page)
            return favoritePages.removeFirst()
        }
        override suspend fun favoriteStatus(teamId: Long, fileId: Long) = statuses[fileId] ?: false
        override suspend fun addFavorite(teamId: Long, fileId: Long) { statuses[fileId] = true }
        override suspend fun removeFavorite(teamId: Long, fileId: Long) {
            removedFavorite = teamId to fileId; statuses[fileId] = false
        }
        override suspend fun listTags(teamId: Long, query: String?) = tags.toList()
        override suspend fun createTag(teamId: Long, name: String, color: String): PersonalTag {
            createdNames += name
            return PersonalTag(99, teamId = teamId, tagName = name, color = color)
        }
        override suspend fun updateTag(teamId: Long, tagId: Long, name: String, color: String) =
            PersonalTag(tagId, teamId = teamId, tagName = name, color = color)
        override suspend fun deleteTag(teamId: Long, tagId: Long) = Unit
        override suspend fun filesByTag(
            teamId: Long, tagId: Long, query: String?, page: Int, size: Int,
        ): PageResult<FileItem> {
            tagFileQueries += Triple(teamId, tagId, query)
            return tagFilePages.removeFirst()
        }
        override suspend fun fileTags(teamId: Long, fileId: Long) = assigned.toList()
        override suspend fun replaceFileTags(teamId: Long, fileId: Long, tagIds: List<Long>): List<PersonalTag> {
            replaced = Triple(teamId, fileId, tagIds)
            return tags.filter { it.id in tagIds }
        }
        override suspend fun removeFileTag(teamId: Long, tagId: Long, fileId: Long) = Unit
    }

    private companion object {
        const val TEAM_ID = 1L
        val FILE = FileItem(7, null, "report.txt", "file")
        val TAG = PersonalTag(3, teamId = TEAM_ID, tagName = "important", color = "#00d4aa")
    }
}
