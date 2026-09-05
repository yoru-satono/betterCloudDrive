package com.betterclouddrive.mobile.feature.teams

import com.betterclouddrive.mobile.app.ResettableStore
import com.betterclouddrive.mobile.app.storeChildScope
import com.betterclouddrive.mobile.app.uiError
import com.betterclouddrive.mobile.core.localization.MobileString
import com.betterclouddrive.mobile.core.localization.UiMessage
import com.betterclouddrive.mobile.core.model.FileItem
import com.betterclouddrive.mobile.core.model.TeamEffectivePermission
import com.betterclouddrive.mobile.core.model.TeamFilePermission
import com.betterclouddrive.mobile.core.model.TeamMember
import com.betterclouddrive.mobile.core.model.TeamRole
import com.betterclouddrive.mobile.core.model.UpdateTeamFilePermissionRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class TeamMembersState(
    val teamId: Long? = null,
    val actorRole: TeamRole = TeamRole.VIEWER,
    val items: List<TeamMember> = emptyList(),
    val page: Int = 0,
    val pages: Int = 1,
    val total: Long = 0,
    val loading: Boolean = false,
    val refreshing: Boolean = false,
    val loadingMore: Boolean = false,
    val loadMoreError: UiMessage? = null,
    val error: UiMessage? = null,
    val adding: Boolean = false,
    val addError: UiMessage? = null,
    val submittingUserId: Long? = null,
    val feedback: UiMessage? = null,
) {
    val hasMore: Boolean get() = page < pages
    val canManageMembers: Boolean get() = actorRole == TeamRole.OWNER || actorRole == TeamRole.ADMIN
}

class TeamMembersStore(
    private val repository: TeamMembersRepository,
    parentScope: CoroutineScope,
) : ResettableStore {
    private val scope = parentScope.storeChildScope()
    private val mutableState = MutableStateFlow(TeamMembersState())
    val state: StateFlow<TeamMembersState> = mutableState.asStateFlow()
    private var listJob: Job? = null
    private var mutationJob: Job? = null

    fun activate(teamId: Long, actorRole: TeamRole) {
        if (state.value.teamId != teamId) {
            listJob?.cancel(); mutationJob?.cancel()
            mutableState.value = TeamMembersState(teamId = teamId, actorRole = actorRole)
        } else mutableState.update { it.copy(actorRole = actorRole) }
        if (state.value.page == 0 && !state.value.loading) load(1, false)
    }

    fun refresh() = load(1, true)
    fun loadMore() {
        val current = state.value
        if (!current.hasMore || current.loading || current.refreshing || current.loadingMore) return
        load(current.page + 1, false)
    }

    fun add(username: String, role: TeamRole) {
        val current = state.value
        val teamId = current.teamId ?: return
        val normalized = username.trim()
        if (!current.canManageMembers || current.adding || current.submittingUserId != null) return
        if (normalized.isEmpty() || normalized.length > 64 || role == TeamRole.OWNER ||
            (current.actorRole == TeamRole.ADMIN && role == TeamRole.ADMIN)
        ) {
            mutableState.update { it.copy(addError = UiMessage(MobileString.ErrorInvalidInput)) }
            return
        }
        mutationJob?.cancel()
        mutableState.update { it.copy(adding = true, addError = null, feedback = null) }
        mutationJob = scope.launch {
            try {
                repository.add(teamId, normalized, role)
                mutableState.update { it.copy(adding = false, feedback = UiMessage(MobileString.TeamMemberAdded)) }
                load(1, true)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Throwable) { mutableState.update { it.copy(adding = false, addError = uiError(error)) } }
        }
    }

    fun updateRole(member: TeamMember, role: TeamRole) {
        val current = state.value
        val teamId = current.teamId ?: return
        if (!canManage(current.actorRole, member) || current.adding || current.submittingUserId != null || role == TeamRole.OWNER ||
            (current.actorRole == TeamRole.ADMIN && role == TeamRole.ADMIN)
        ) return
        mutationJob?.cancel()
        mutableState.update { it.copy(submittingUserId = member.userId, feedback = null) }
        mutationJob = scope.launch {
            try {
                val updated = repository.update(teamId, member.userId, role)
                mutableState.update { state -> state.copy(
                    items = state.items.map { if (it.userId == updated.userId) updated else it },
                    submittingUserId = null, feedback = UiMessage(MobileString.TeamMemberRoleUpdated),
                ) }
                load(1, true)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Throwable) { mutableState.update { it.copy(submittingUserId = null, feedback = uiError(error)) } }
        }
    }

    fun remove(member: TeamMember) {
        val current = state.value
        val teamId = current.teamId ?: return
        if (!canManage(current.actorRole, member) || current.adding || current.submittingUserId != null) return
        mutationJob?.cancel()
        mutableState.update { it.copy(submittingUserId = member.userId, feedback = null) }
        mutationJob = scope.launch {
            try {
                repository.remove(teamId, member.userId)
                mutableState.update { state -> state.copy(
                    items = state.items.filterNot { it.userId == member.userId },
                    total = (state.total - 1).coerceAtLeast(0), submittingUserId = null,
                    feedback = UiMessage(MobileString.TeamMemberRemoved),
                ) }
                load(1, true)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Throwable) { mutableState.update { it.copy(submittingUserId = null, feedback = uiError(error)) } }
        }
    }

    fun clearFeedback() = mutableState.update { it.copy(feedback = null) }
    fun clearAddError() = mutableState.update { it.copy(addError = null) }

    override fun reset() {
        listJob?.cancel(); mutationJob?.cancel(); mutableState.value = TeamMembersState()
    }

    private fun load(page: Int, refresh: Boolean) {
        val teamId = state.value.teamId ?: return
        val existing = state.value.items
        listJob?.cancel()
        mutableState.update { when {
            page > 1 -> it.copy(loadingMore = true, loadMoreError = null)
            refresh && it.items.isNotEmpty() -> it.copy(refreshing = true, error = null)
            else -> it.copy(loading = true, error = null)
        } }
        listJob = scope.launch {
            try {
                val result = repository.list(teamId, page, MEMBER_PAGE_SIZE)
                val merged = (if (page == 1) result.records else existing + result.records).distinctBy(TeamMember::userId)
                mutableState.update { it.copy(
                    items = merged, page = result.page, pages = result.pages.coerceAtLeast(result.page), total = result.total,
                    loading = false, refreshing = false, loadingMore = false, error = null, loadMoreError = null,
                ) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Throwable) { mutableState.update { when {
                page > 1 -> it.copy(loadingMore = false, loadMoreError = uiError(error))
                refresh && existing.isNotEmpty() -> it.copy(refreshing = false, error = uiError(error))
                else -> it.copy(loading = false, error = uiError(error))
            } } }
        }
    }

    private fun canManage(actor: TeamRole, member: TeamMember): Boolean = when (actor) {
        TeamRole.OWNER -> member.role != TeamRole.OWNER
        TeamRole.ADMIN -> member.role == TeamRole.EDITOR || member.role == TeamRole.VIEWER
        else -> false
    }

    private companion object { const val MEMBER_PAGE_SIZE = 50 }
}

data class TeamPermissionsState(
    val teamId: Long? = null,
    val target: FileItem? = null,
    val effective: TeamEffectivePermission? = null,
    val direct: List<TeamFilePermission> = emptyList(),
    val members: List<TeamMember> = emptyList(),
    val loading: Boolean = false,
    val submitting: Boolean = false,
    val error: UiMessage? = null,
    val feedback: UiMessage? = null,
)

class TeamPermissionsStore(
    private val permissions: TeamPermissionsRepository,
    private val members: TeamMembersRepository,
    parentScope: CoroutineScope,
    private val onAuthorizationChanged: (Long, Long) -> Unit = { _, _ -> },
) : ResettableStore {
    private val scope = parentScope.storeChildScope()
    private val mutableState = MutableStateFlow(TeamPermissionsState())
    val state: StateFlow<TeamPermissionsState> = mutableState.asStateFlow()
    private var loadJob: Job? = null
    private var submitJob: Job? = null

    fun open(teamId: Long, target: FileItem) {
        loadJob?.cancel(); submitJob?.cancel()
        mutableState.value = TeamPermissionsState(teamId = teamId, target = target, loading = true)
        loadJob = scope.launch { load(teamId, target.id) }
    }

    fun retry() {
        val current = state.value
        val teamId = current.teamId ?: return
        val fileId = current.target?.id ?: return
        loadJob?.cancel(); mutableState.update { it.copy(loading = true, error = null) }
        loadJob = scope.launch { load(teamId, fileId) }
    }

    fun save(request: UpdateTeamFilePermissionRequest) {
        val current = state.value
        val teamId = current.teamId ?: return
        val fileId = current.target?.id ?: return
        if (current.submitting || !valid(request)) return
        submitJob?.cancel(); mutableState.update { it.copy(submitting = true, error = null, feedback = null) }
        submitJob = scope.launch {
            try {
                permissions.upsert(teamId, fileId, request)
                onAuthorizationChanged(teamId, fileId)
                mutableState.update { it.copy(submitting = false, feedback = UiMessage(MobileString.TeamPermissionSaved)) }
                load(teamId, fileId, preserveFeedback = true)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Throwable) { mutableState.update { it.copy(submitting = false, error = uiError(error)) } }
        }
    }

    fun delete(permission: TeamFilePermission) {
        val current = state.value
        val teamId = current.teamId ?: return
        val fileId = current.target?.id ?: return
        if (current.submitting) return
        submitJob?.cancel(); mutableState.update { it.copy(submitting = true, error = null, feedback = null) }
        submitJob = scope.launch {
            try {
                permissions.delete(teamId, fileId, permission.id)
                onAuthorizationChanged(teamId, fileId)
                mutableState.update { it.copy(submitting = false, feedback = UiMessage(MobileString.TeamPermissionDeleted)) }
                load(teamId, fileId, preserveFeedback = true)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Throwable) { mutableState.update { it.copy(submitting = false, error = uiError(error)) } }
        }
    }

    fun clearFeedback() = mutableState.update { it.copy(feedback = null) }
    fun close() { loadJob?.cancel(); submitJob?.cancel(); mutableState.value = TeamPermissionsState() }
    override fun reset() = close()

    private suspend fun load(teamId: Long, fileId: Long, preserveFeedback: Boolean = false) {
        try {
            val effective = permissions.effective(teamId, fileId)
            val direct = if (effective.canManage) permissions.list(teamId, fileId) else emptyList()
            val allMembers = if (effective.canManage) loadAllMembers(teamId) else emptyList()
            mutableState.update { it.copy(
                effective = effective, direct = direct, members = allMembers.filter { member -> member.role != TeamRole.OWNER },
                loading = false, submitting = false, error = null,
                feedback = it.feedback.takeIf { preserveFeedback },
            ) }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Throwable) { mutableState.update { it.copy(loading = false, submitting = false, error = uiError(error)) } }
    }

    private suspend fun loadAllMembers(teamId: Long): List<TeamMember> {
        val result = mutableListOf<TeamMember>()
        var page = 1
        do {
            val response = members.list(teamId, page, MEMBER_PICKER_PAGE_SIZE)
            result += response.records
            page++
        } while (page <= response.pages)
        return result.distinctBy(TeamMember::userId)
    }

    private fun valid(request: UpdateTeamFilePermissionRequest): Boolean =
        request.canRead || !(request.canWrite || request.canShare || request.canDelete || request.canManage)

    private companion object { const val MEMBER_PICKER_PAGE_SIZE = 100 }
}
