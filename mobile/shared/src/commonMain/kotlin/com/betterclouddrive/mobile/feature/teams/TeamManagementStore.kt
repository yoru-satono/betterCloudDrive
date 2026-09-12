package com.betterclouddrive.mobile.feature.teams

import com.betterclouddrive.mobile.app.ResettableStore
import com.betterclouddrive.mobile.app.storeChildScope
import com.betterclouddrive.mobile.app.uiError
import com.betterclouddrive.mobile.core.localization.MobileString
import com.betterclouddrive.mobile.core.localization.UiMessage
import com.betterclouddrive.mobile.core.model.TeamMember
import com.betterclouddrive.mobile.core.model.TeamRole
import com.betterclouddrive.mobile.core.model.TeamSummary
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class TeamLifecycleOperation { LEAVE, DELETE }

data class TeamManagementFeedback(
    val id: Long,
    val message: UiMessage,
    val isError: Boolean,
)

data class TeamManagementState(
    val teamId: Long? = null,
    val team: TeamSummary? = null,
    val loading: Boolean = false,
    val refreshing: Boolean = false,
    val loadError: UiMessage? = null,
    val savingName: Boolean = false,
    val nameError: UiMessage? = null,
    val lifecycleOperation: TeamLifecycleOperation? = null,
    val transferringUserId: Long? = null,
    val feedback: TeamManagementFeedback? = null,
    val departedTeamId: Long? = null,
) {
    val canRename: Boolean get() = team?.role == TeamRole.OWNER || team?.role == TeamRole.ADMIN
    val canLeave: Boolean get() = team != null && team.role != TeamRole.OWNER
    val canDelete: Boolean get() = team?.role == TeamRole.OWNER
    val busy: Boolean get() = savingName || lifecycleOperation != null || transferringUserId != null
}

class TeamManagementStore(
    private val repository: TeamManagementRepository,
    parentScope: CoroutineScope,
    private val onTeamUpdated: (TeamSummary) -> Unit = {},
    private val onTeamRemoved: (Long, UiMessage) -> Unit = { _, _ -> },
    private val onOwnershipTransferred: (TeamSummary) -> Unit = {},
) : ResettableStore {
    private val scope = parentScope.storeChildScope()
    private val mutableState = MutableStateFlow(TeamManagementState())
    val state: StateFlow<TeamManagementState> = mutableState.asStateFlow()
    private var loadJob: Job? = null
    private var mutationJob: Job? = null
    private var generation = 0L
    private var feedbackSequence = 0L

    fun bind(teamId: Long, summary: TeamSummary?) {
        if (state.value.teamId != teamId) {
            loadJob?.cancel()
            mutationJob?.cancel()
            generation++
            mutableState.value = TeamManagementState(teamId = teamId, team = summary?.takeIf { it.id == teamId })
        } else if (summary?.id == teamId && !state.value.busy) {
            mutableState.update { it.copy(team = summary) }
        }
    }

    fun activate(teamId: Long, summary: TeamSummary?) {
        bind(teamId, summary)
        refresh()
    }

    fun refresh() {
        val teamId = state.value.teamId ?: return
        if (state.value.busy) return
        val requestGeneration = ++generation
        loadJob?.cancel()
        mutableState.update {
            if (it.team == null) it.copy(loading = true, loadError = null)
            else it.copy(refreshing = true, loadError = null)
        }
        loadJob = scope.launch {
            try {
                val team = repository.getTeam(teamId)
                if (!matches(teamId, requestGeneration)) return@launch
                mutableState.update { it.copy(team = team, loading = false, refreshing = false, loadError = null) }
                onTeamUpdated(team)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                if (!matches(teamId, requestGeneration)) return@launch
                mutableState.update { it.copy(loading = false, refreshing = false, loadError = uiError(error)) }
            }
        }
    }

    fun rename(name: String) {
        val current = state.value
        val teamId = current.teamId ?: return
        if (!current.canRename || current.busy) return
        val normalized = name.trim()
        if (normalized.isEmpty() || normalized.length > MAX_TEAM_NAME) {
            mutableState.update { it.copy(nameError = UiMessage(
                if (normalized.isEmpty()) MobileString.NameRequired else MobileString.TeamNameTooLong,
            )) }
            return
        }
        val requestGeneration = ++generation
        loadJob?.cancel()
        mutationJob?.cancel()
        mutableState.update { it.copy(savingName = true, nameError = null, feedback = null) }
        mutationJob = scope.launch {
            try {
                val updated = repository.rename(teamId, normalized)
                if (!matches(teamId, requestGeneration)) return@launch
                mutableState.update { it.copy(
                    team = updated,
                    savingName = false,
                    feedback = success(MobileString.TeamRenamed),
                ) }
                onTeamUpdated(updated)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                if (!matches(teamId, requestGeneration)) return@launch
                mutableState.update { it.copy(savingName = false, nameError = uiError(error)) }
            }
        }
    }

    fun leave() = lifecycle(TeamLifecycleOperation.LEAVE, MobileString.TeamLeft) { repository.leave(it) }

    fun delete() = lifecycle(TeamLifecycleOperation.DELETE, MobileString.TeamDeleted) { repository.delete(it) }

    fun transferOwner(member: TeamMember) {
        val current = state.value
        val teamId = current.teamId ?: return
        if (current.team?.role != TeamRole.OWNER || member.teamId != teamId || member.role == TeamRole.OWNER ||
            member.status != ACTIVE_MEMBER || current.busy
        ) return
        val requestGeneration = ++generation
        loadJob?.cancel()
        mutationJob?.cancel()
        mutableState.update { it.copy(transferringUserId = member.userId, feedback = null) }
        mutationJob = scope.launch {
            try {
                val updated = repository.transferOwner(teamId, member.userId)
                if (!matches(teamId, requestGeneration)) return@launch
                mutableState.update { it.copy(
                    team = updated,
                    transferringUserId = null,
                    feedback = success(MobileString.TeamOwnershipTransferred),
                ) }
                onTeamUpdated(updated)
                onOwnershipTransferred(updated)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                if (!matches(teamId, requestGeneration)) return@launch
                mutableState.update { it.copy(
                    transferringUserId = null,
                    feedback = failure(error),
                ) }
            }
        }
    }

    fun clearNameError() = mutableState.update { it.copy(nameError = null) }
    fun clearFeedback() = mutableState.update { it.copy(feedback = null) }
    fun consumeDeparture() = reset()

    override fun reset() {
        loadJob?.cancel()
        mutationJob?.cancel()
        generation++
        mutableState.value = TeamManagementState()
    }

    private fun lifecycle(
        operation: TeamLifecycleOperation,
        successMessage: MobileString,
        request: suspend (Long) -> Unit,
    ) {
        val current = state.value
        val teamId = current.teamId ?: return
        val allowed = operation == TeamLifecycleOperation.LEAVE && current.canLeave ||
            operation == TeamLifecycleOperation.DELETE && current.canDelete
        if (!allowed || current.busy) return
        val requestGeneration = ++generation
        loadJob?.cancel()
        mutationJob?.cancel()
        mutableState.update { it.copy(lifecycleOperation = operation, feedback = null) }
        mutationJob = scope.launch {
            try {
                request(teamId)
                if (!matches(teamId, requestGeneration)) return@launch
                val feedback = success(successMessage)
                onTeamRemoved(teamId, feedback.message)
                if (!matches(teamId, requestGeneration)) return@launch
                mutableState.update { it.copy(
                    team = null,
                    lifecycleOperation = null,
                    feedback = feedback,
                    departedTeamId = teamId,
                ) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                if (!matches(teamId, requestGeneration)) return@launch
                mutableState.update { it.copy(lifecycleOperation = null, feedback = failure(error)) }
            }
        }
    }

    private fun matches(teamId: Long, requestGeneration: Long): Boolean =
        state.value.teamId == teamId && generation == requestGeneration

    private fun success(key: MobileString) =
        TeamManagementFeedback(++feedbackSequence, UiMessage(key), false)

    private fun failure(error: Throwable) =
        TeamManagementFeedback(++feedbackSequence, uiError(error), true)

    private companion object {
        const val MAX_TEAM_NAME = 80
        const val ACTIVE_MEMBER = 1
    }
}
