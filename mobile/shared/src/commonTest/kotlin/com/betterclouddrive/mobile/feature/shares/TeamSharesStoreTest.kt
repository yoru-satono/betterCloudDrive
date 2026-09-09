package com.betterclouddrive.mobile.feature.shares

import com.betterclouddrive.mobile.core.model.CreateShareRequest
import com.betterclouddrive.mobile.core.model.FileItem
import com.betterclouddrive.mobile.core.model.PageResult
import com.betterclouddrive.mobile.core.model.ShareExpirationFilter
import com.betterclouddrive.mobile.core.model.ShareLink
import com.betterclouddrive.mobile.core.model.TeamEffectivePermission
import com.betterclouddrive.mobile.core.model.TeamFilePermission
import com.betterclouddrive.mobile.core.model.TeamRole
import com.betterclouddrive.mobile.core.model.UpdateShareRequest
import com.betterclouddrive.mobile.core.model.UpdateTeamFilePermissionRequest
import com.betterclouddrive.mobile.feature.teams.TeamPermissionsRepository
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
class TeamSharesStoreTest {
    @Test
    fun pagingDebounceFiltersAndTeamSwitchStayScoped() = runTest {
        val repository = FakeTeamShares().apply {
            pages[Triple(10L, "", 1)] = page(listOf(share(1), share(2)), 1, 3, 105)
            pages[Triple(10L, "", 2)] = page(listOf(share(2), share(3)), 2, 3, 105)
            pages[Triple(10L, "", 3)] = page(listOf(share(3), share(4)), 3, 3, 105)
            pages[Triple(10L, "latest", 1)] = page(listOf(share(5)), 1, 1, 1)
            pages[Triple(20L, "", 1)] = page(listOf(share(20, teamId = 20)), 1, 1, 1)
        }
        val store = TeamSharesStore(repository, FakePermissions(), backgroundScope)

        store.activate(10, 7, TeamRole.EDITOR); runCurrent()
        store.loadMore(); runCurrent(); store.loadMore(); runCurrent()
        assertEquals(listOf(1L, 2L, 3L, 4L), store.state.value.items.map(ShareLink::id))
        assertEquals(listOf(1, 2, 3), repository.requests.take(3).map(ListRequest::page))

        store.updateQuery(" first ")
        store.updateQuery(" latest ")
        advanceTimeBy(301); runCurrent()
        assertEquals("latest", repository.requests.last().query)
        assertEquals(listOf(5L), store.state.value.items.map(ShareLink::id))

        repository.pages[Triple(10L, "latest", 1)] = page(listOf(share(6)), 1, 1, 1)
        store.setFilters(true, ShareExpirationFilter.ACTIVE); runCurrent()
        assertEquals(true, repository.requests.last().passwordProtected)
        assertEquals(ShareExpirationFilter.ACTIVE, repository.requests.last().expiration)

        store.openCreator(FileItem(id = 8, fileName = "old.txt"))
        store.activate(20, 7, TeamRole.VIEWER); runCurrent()
        assertEquals(20L, store.state.value.teamId)
        assertEquals(listOf(20L), store.state.value.items.map(ShareLink::id))
        assertNull(store.state.value.createTarget)
        assertNull(store.state.value.selected)
        assertNull(store.state.value.revealedPassword)
    }

    @Test
    fun detailPermissionMatrixProtectsPasswordEditAndCancel() = runTest {
        val repository = FakeTeamShares().apply {
            details[1] = share(1, owner = 7, hasPassword = true)
            details[2] = share(2, owner = 8, hasPassword = true)
        }
        val permissions = FakePermissions()
        val store = TeamSharesStore(repository, permissions, backgroundScope)

        permissions.value = permission(canShare = true)
        store.bind(10, 7, TeamRole.EDITOR)
        store.openDetail(1); runCurrent()
        assertTrue(store.state.value.selectedCanEdit)
        store.togglePassword(); runCurrent()
        assertEquals("secret", store.state.value.revealedPassword)
        store.bind(20, 7, TeamRole.EDITOR)
        assertNull(store.state.value.revealedPassword)
        store.bind(10, 7, TeamRole.EDITOR)

        store.openDetail(2); runCurrent()
        assertFalse(store.state.value.selectedCanEdit)
        store.togglePassword(); runCurrent()
        assertEquals(1, repository.passwordCalls)

        permissions.value = permission(canShare = true, canManage = true)
        store.openDetail(2); runCurrent()
        assertTrue(store.state.value.selectedCanEdit)
        val clear = UpdateShareRequest(clearPassword = true, clearExpireAt = true, clearMaxVisits = true)
        store.update(clear); runCurrent()
        assertEquals(clear, repository.lastUpdate)

        permissions.value = permission(canShare = false, canManage = true)
        store.openDetail(2); runCurrent()
        assertFalse(store.state.value.selectedCanEdit)

        permissions.value = permission(canShare = true)
        store.bind(10, 99, TeamRole.OWNER)
        store.openDetail(2); runCurrent()
        assertTrue(store.state.value.selectedCanEdit)
        store.cancel(); runCurrent()
        assertEquals(listOf(10L to 2L), repository.cancels)
        assertNull(store.state.value.selected)
    }

    @Test
    fun lateResponsesCannotCrossTeamOrActorBoundaries() = runTest {
        val oldList = CompletableDeferred<PageResult<ShareLink>>()
        val oldDetail = CompletableDeferred<ShareLink>()
        val oldCreate = CompletableDeferred<ShareLink>()
        val repository = object : TeamSharesRepository {
            override suspend fun create(teamId: Long, request: CreateShareRequest) =
                withContext(NonCancellable) { oldCreate.await() }

            override suspend fun list(
                teamId: Long,
                query: String?,
                passwordProtected: Boolean?,
                expiration: ShareExpirationFilter,
                page: Int,
                size: Int,
            ) = if (teamId == 10L) withContext(NonCancellable) { oldList.await() }
            else page(listOf(share(20, teamId = teamId)), 1, 1, 1)

            override suspend fun get(teamId: Long, shareId: Long) =
                withContext(NonCancellable) { oldDetail.await() }

            override suspend fun password(teamId: Long, shareId: Long): String? = "old-secret"
            override suspend fun update(teamId: Long, shareId: Long, request: UpdateShareRequest) = share(shareId)
            override suspend fun cancel(teamId: Long, shareId: Long) = Unit
        }
        val store = TeamSharesStore(repository, FakePermissions(), backgroundScope)

        store.activate(10, 7, TeamRole.EDITOR)
        runCurrent()
        store.openCreator(FileItem(id = 8, fileName = "old.txt"))
        store.create(null, null, null)
        runCurrent()
        store.openDetail(1)
        runCurrent()

        store.activate(20, 8, TeamRole.VIEWER)
        runCurrent()
        oldList.complete(page(listOf(share(1)), 1, 1, 1))
        oldCreate.complete(share(100))
        oldDetail.complete(share(1))
        runCurrent()

        assertEquals(20L, store.state.value.teamId)
        assertEquals(listOf(20L), store.state.value.items.map(ShareLink::id))
        assertNull(store.state.value.createTarget)
        assertNull(store.state.value.created)
        assertNull(store.state.value.selected)
        assertNull(store.state.value.revealedPassword)

        store.openCreator(FileItem(id = 9, fileName = "role.txt"))
        store.bind(20, 8, TeamRole.EDITOR)
        assertNull(store.state.value.createTarget)
    }

    private data class ListRequest(
        val teamId: Long,
        val query: String,
        val passwordProtected: Boolean?,
        val expiration: ShareExpirationFilter,
        val page: Int,
        val size: Int,
    )

    private class FakeTeamShares : TeamSharesRepository {
        val pages = mutableMapOf<Triple<Long, String, Int>, PageResult<ShareLink>>()
        val requests = mutableListOf<ListRequest>()
        val details = mutableMapOf<Long, ShareLink>()
        val cancels = mutableListOf<Pair<Long, Long>>()
        var passwordCalls = 0
        var lastUpdate: UpdateShareRequest? = null

        override suspend fun create(teamId: Long, request: CreateShareRequest) =
            share(100, teamId = teamId).copy(fileId = request.fileId)

        override suspend fun list(
            teamId: Long,
            query: String?,
            passwordProtected: Boolean?,
            expiration: ShareExpirationFilter,
            page: Int,
            size: Int,
        ): PageResult<ShareLink> {
            val normalized = query.orEmpty()
            requests += ListRequest(teamId, normalized, passwordProtected, expiration, page, size)
            return pages.getValue(Triple(teamId, normalized, page))
        }

        override suspend fun get(teamId: Long, shareId: Long) = details.getValue(shareId)
        override suspend fun password(teamId: Long, shareId: Long): String? {
            passwordCalls++
            return "secret"
        }

        override suspend fun update(teamId: Long, shareId: Long, request: UpdateShareRequest): ShareLink {
            lastUpdate = request
            return details.getValue(shareId).copy(hasPassword = !request.clearPassword).also { details[shareId] = it }
        }

        override suspend fun cancel(teamId: Long, shareId: Long) {
            cancels += teamId to shareId
        }
    }

    private class FakePermissions : TeamPermissionsRepository {
        var value = permission(canShare = true)
        override suspend fun effective(teamId: Long, fileId: Long) = value
        override suspend fun list(teamId: Long, fileId: Long): List<TeamFilePermission> = emptyList()
        override suspend fun upsert(teamId: Long, fileId: Long, request: UpdateTeamFilePermissionRequest) = TeamFilePermission()
        override suspend fun delete(teamId: Long, fileId: Long, permissionId: Long) = Unit
    }

    private companion object {
        fun share(id: Long, teamId: Long = 10, owner: Long = 7, hasPassword: Boolean = false) = ShareLink(
            id = id,
            userId = owner,
            teamId = teamId,
            fileId = id + 100,
            shareCode = "code$id",
            shareUrl = "https://drive/s/code$id",
            hasPassword = hasPassword,
        )

        fun page(records: List<ShareLink>, page: Int, pages: Int, total: Long) =
            PageResult(records, total, page, 50, pages)

        fun permission(canShare: Boolean, canManage: Boolean = false) = TeamEffectivePermission(
            canRead = true,
            canShare = canShare,
            canManage = canManage,
        )
    }
}
