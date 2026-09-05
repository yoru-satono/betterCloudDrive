package com.betterclouddrive.mobile.feature.teams

import com.betterclouddrive.mobile.core.localization.MobileString
import com.betterclouddrive.mobile.core.model.FileItem
import com.betterclouddrive.mobile.core.model.PageResult
import com.betterclouddrive.mobile.core.model.TeamEffectivePermission
import com.betterclouddrive.mobile.core.model.TeamFilePermission
import com.betterclouddrive.mobile.core.model.TeamMember
import com.betterclouddrive.mobile.core.model.TeamPermissionSubjectType
import com.betterclouddrive.mobile.core.model.TeamRole
import com.betterclouddrive.mobile.core.model.UpdateTeamFilePermissionRequest
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class TeamAuthorizationStoresTest {

    @Test
    fun membersPaginateMutateAndRespectAdminBoundary() = runTest {
        val repository = FakeMembers().apply {
            pages += PageResult(listOf(member(1, TeamRole.OWNER), member(2, TeamRole.ADMIN)), 3, 1, 50, 2)
            pages += PageResult(listOf(member(3, TeamRole.VIEWER)), 3, 2, 50, 2)
        }
        val store = TeamMembersStore(repository, backgroundScope)
        store.activate(10, TeamRole.OWNER); runCurrent()
        store.loadMore(); runCurrent()
        assertEquals(listOf(1L, 2L, 3L), store.state.value.items.map(TeamMember::userId))

        store.updateRole(member(3, TeamRole.VIEWER), TeamRole.EDITOR); runCurrent()
        assertEquals(TeamRole.EDITOR, store.state.value.items.single { it.userId == 3L }.role)
        store.remove(member(3, TeamRole.EDITOR)); runCurrent()
        assertFalse(store.state.value.items.any { it.userId == 3L })

        store.activate(10, TeamRole.ADMIN)
        store.add("other-admin", TeamRole.ADMIN); runCurrent()
        assertEquals(MobileString.ErrorInvalidInput, store.state.value.addError?.key)
        assertTrue(repository.added.isEmpty())
        store.reset()
        assertNull(store.state.value.teamId)
    }

    @Test
    fun permissionsLoadSourcesSaveInvalidateAndResetSensitiveTarget() = runTest {
        val members = FakeMembers().apply {
            pages += PageResult(listOf(member(1, TeamRole.OWNER), member(2, TeamRole.EDITOR)), 2, 1, 100, 1)
            pages += PageResult(listOf(member(1, TeamRole.OWNER), member(2, TeamRole.EDITOR)), 2, 1, 100, 1)
        }
        val permissions = FakePermissions()
        val changed = mutableListOf<Pair<Long, Long>>()
        val store = TeamPermissionsStore(permissions, members, backgroundScope) { teamId, fileId -> changed += teamId to fileId }
        store.open(10, FileItem(7, null, "Docs", "folder")); runCurrent()
        assertTrue(store.state.value.effective?.canManage == true)
        assertEquals(listOf(2L), store.state.value.members.map(TeamMember::userId))

        val request = UpdateTeamFilePermissionRequest(
            TeamPermissionSubjectType.USER, subjectUserId = 2,
            canRead = true, canWrite = false, canShare = false, canDelete = false, canManage = false,
        )
        store.save(request); runCurrent()
        assertEquals(request, permissions.saved.single())
        assertEquals(listOf(10L to 7L), changed)
        assertEquals(MobileString.TeamPermissionSaved, store.state.value.feedback?.key)

        store.reset()
        assertNull(store.state.value.target)
        assertTrue(store.state.value.members.isEmpty())
        assertTrue(store.state.value.direct.isEmpty())
    }

    private class FakeMembers : TeamMembersRepository {
        val pages = ArrayDeque<PageResult<TeamMember>>()
        val added = mutableListOf<Pair<String, TeamRole>>()
        override suspend fun list(teamId: Long, page: Int, size: Int) = pages.removeFirst()
        override suspend fun add(teamId: Long, username: String, role: TeamRole): TeamMember {
            added += username to role; return member(9, role, username)
        }
        override suspend fun update(teamId: Long, userId: Long, role: TeamRole) = member(userId, role)
        override suspend fun remove(teamId: Long, userId: Long) = Unit
    }

    private class FakePermissions : TeamPermissionsRepository {
        val saved = mutableListOf<UpdateTeamFilePermissionRequest>()
        private val effective = TeamEffectivePermission(true, true, true, true, true, "BASE_ROLE")
        override suspend fun effective(teamId: Long, fileId: Long) = effective
        override suspend fun list(teamId: Long, fileId: Long) = listOf(TeamFilePermission(
            id = 4, teamId = teamId, fileId = fileId, subjectType = TeamPermissionSubjectType.ROLE,
            subjectRole = TeamRole.VIEWER, canRead = true,
        ))
        override suspend fun upsert(teamId: Long, fileId: Long, request: UpdateTeamFilePermissionRequest): TeamFilePermission {
            saved += request
            return TeamFilePermission(id = 5, teamId = teamId, fileId = fileId, subjectType = request.subjectType,
                subjectUserId = request.subjectUserId, subjectRole = request.subjectRole, canRead = request.canRead)
        }
        override suspend fun delete(teamId: Long, fileId: Long, permissionId: Long) = Unit
    }

    private companion object {
        fun member(id: Long, role: TeamRole, username: String = "user$id") =
            TeamMember(teamId = 10, userId = id, username = username, email = "$username@example.com", role = role)
    }
}
