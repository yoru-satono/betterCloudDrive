package com.betterclouddrive.mobile.feature.teams

import com.betterclouddrive.mobile.core.localization.MobileString
import com.betterclouddrive.mobile.core.model.TeamMember
import com.betterclouddrive.mobile.core.model.TeamRole
import com.betterclouddrive.mobile.core.model.TeamSummary
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class TeamManagementStoreTest {

    @Test
    fun refreshRenameAndTransferPropagateCurrentSummaryAndRole() = runTest {
        val repository = FakeManagement()
        val updated = mutableListOf<TeamSummary>()
        val transferred = mutableListOf<TeamSummary>()
        val store = TeamManagementStore(
            repository,
            backgroundScope,
            onTeamUpdated = updated::add,
            onOwnershipTransferred = transferred::add,
        )

        store.activate(10, OWNER_TEAM); runCurrent()
        assertEquals(OWNER_TEAM, store.state.value.team)
        assertTrue(store.state.value.canDelete)

        store.rename("  Product  "); runCurrent()
        assertEquals("Product", repository.renamed.single())
        assertEquals("Product", store.state.value.team?.name)
        assertEquals(MobileString.TeamRenamed, store.state.value.feedback?.message?.key)

        store.clearFeedback()
        store.transferOwner(MEMBER); runCurrent()
        assertEquals(MEMBER.userId, repository.transferred.single())
        assertEquals(TeamRole.ADMIN, store.state.value.team?.role)
        assertFalse(store.state.value.canDelete)
        assertTrue(store.state.value.canLeave)
        assertEquals(TeamRole.ADMIN, transferred.single().role)
        assertEquals(TeamRole.ADMIN, updated.last().role)
    }

    @Test
    fun validationAndFailuresKeepTeamAndGlobalResetClearsSensitiveState() = runTest {
        val repository = FakeManagement().apply { renameFailure = IllegalStateException("offline") }
        val removed = mutableListOf<Long>()
        val removalFeedback = mutableListOf<MobileString>()
        val store = TeamManagementStore(repository, backgroundScope, onTeamRemoved = { teamId, message ->
            removed += teamId
            removalFeedback += message.key
        })
        store.bind(10, OWNER_TEAM)

        store.rename(" ")
        assertEquals(MobileString.NameRequired, store.state.value.nameError?.key)
        store.rename("x".repeat(81))
        assertEquals(MobileString.TeamNameTooLong, store.state.value.nameError?.key)

        store.rename("new"); runCurrent()
        assertEquals(OWNER_TEAM, store.state.value.team)
        assertFalse(store.state.value.savingName)
        assertEquals(MobileString.ErrorNetwork, store.state.value.nameError?.key)

        store.delete(); runCurrent()
        assertEquals(listOf(10L), repository.deleted)
        assertEquals(listOf(10L), removed)
        assertEquals(listOf(MobileString.TeamDeleted), removalFeedback)
        assertEquals(10, store.state.value.departedTeamId)

        store.reset()
        assertNull(store.state.value.teamId)
        assertNull(store.state.value.team)
        assertNull(store.state.value.feedback)
    }

    @Test
    fun leaveRemovesContextAndLateDetailResponseCannotReplaceNewTeam() = runTest {
        val removed = mutableListOf<Long>()
        val repository = FakeManagement().apply { delayTeamId = 10 }
        val store = TeamManagementStore(repository, backgroundScope, onTeamRemoved = { teamId, _ -> removed += teamId })

        store.activate(10, OWNER_TEAM.copy(role = TeamRole.EDITOR)); runCurrent()
        store.bind(20, OWNER_TEAM.copy(id = 20, name = "Second", role = TeamRole.VIEWER))
        repository.resumeDelayed(OWNER_TEAM.copy(name = "Late")); runCurrent()
        assertEquals(20, store.state.value.teamId)
        assertEquals("Second", store.state.value.team?.name)

        repository.leaveFailure = IllegalStateException("offline")
        store.leave(); runCurrent()
        assertEquals("Second", store.state.value.team?.name)
        assertTrue(store.state.value.feedback?.isError == true)
        repository.leaveFailure = null
        store.clearFeedback()
        store.leave(); runCurrent()
        assertEquals(listOf(20L), repository.left)
        assertEquals(listOf(20L), removed)
        assertEquals(20, store.state.value.departedTeamId)
        assertNull(store.state.value.team)
        store.consumeDeparture()
        assertNull(store.state.value.teamId)
    }

    private class FakeManagement : TeamManagementRepository {
        val renamed = mutableListOf<String>()
        val left = mutableListOf<Long>()
        val deleted = mutableListOf<Long>()
        val transferred = mutableListOf<Long>()
        var renameFailure: Throwable? = null
        var leaveFailure: Throwable? = null
        var delayTeamId: Long? = null
        private var continuation: Continuation<TeamSummary>? = null

        override suspend fun getTeam(teamId: Long): TeamSummary {
            if (delayTeamId == teamId) return suspendCoroutine { continuation = it }
            return OWNER_TEAM.copy(id = teamId)
        }

        override suspend fun rename(teamId: Long, name: String): TeamSummary {
            renameFailure?.let { throw it }
            renamed += name
            return OWNER_TEAM.copy(id = teamId, name = name)
        }

        override suspend fun leave(teamId: Long) {
            leaveFailure?.let { throw it }
            left += teamId
        }
        override suspend fun delete(teamId: Long) { deleted += teamId }
        override suspend fun transferOwner(teamId: Long, userId: Long): TeamSummary {
            transferred += userId
            return OWNER_TEAM.copy(id = teamId, ownerId = userId, role = TeamRole.ADMIN)
        }

        fun resumeDelayed(team: TeamSummary) {
            continuation?.resume(team)
            continuation = null
        }
    }

    private companion object {
        val OWNER_TEAM = TeamSummary(
            id = 10,
            name = "Alpha",
            ownerId = 1,
            storageQuota = 100,
            storageUsed = 25,
            role = TeamRole.OWNER,
        )
        val MEMBER = TeamMember(
            teamId = 10,
            userId = 2,
            username = "successor",
            role = TeamRole.EDITOR,
            status = 1,
        )
    }
}
