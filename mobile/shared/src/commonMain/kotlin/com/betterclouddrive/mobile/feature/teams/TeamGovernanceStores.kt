package com.betterclouddrive.mobile.feature.teams

import com.betterclouddrive.mobile.app.ResettableStore
import com.betterclouddrive.mobile.app.storeChildScope
import com.betterclouddrive.mobile.app.uiError
import com.betterclouddrive.mobile.core.localization.MobileString
import com.betterclouddrive.mobile.core.localization.UiMessage
import com.betterclouddrive.mobile.core.model.TeamOperationLog
import com.betterclouddrive.mobile.core.model.TeamRole
import com.betterclouddrive.mobile.core.model.TeamTask
import com.betterclouddrive.mobile.core.platform.LocalDateTimeService
import com.betterclouddrive.mobile.core.platform.PickedDestination
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class TeamAuditDraft(
    val actorUserId: String = "",
    val action: String = "",
    val targetType: String = "",
    val result: Int? = null,
    val startAtEpochMillis: Long? = null,
    val endAtEpochMillis: Long? = null,
)

data class TeamAuditState(
    val teamId: Long? = null,
    val role: TeamRole = TeamRole.VIEWER,
    val draft: TeamAuditDraft = TeamAuditDraft(),
    val applied: TeamAuditFilter = TeamAuditFilter(),
    val logs: List<TeamOperationLog> = emptyList(),
    val page: Int = 0,
    val pages: Int = 1,
    val total: Long = 0,
    val loading: Boolean = false,
    val refreshing: Boolean = false,
    val loadingMore: Boolean = false,
    val exporting: Boolean = false,
    val error: UiMessage? = null,
    val loadMoreError: UiMessage? = null,
    val filterError: UiMessage? = null,
    val feedback: UiMessage? = null,
    val queuedTaskId: Long? = null,
) {
    val canView: Boolean get() = role == TeamRole.OWNER || role == TeamRole.ADMIN
    val hasMore: Boolean get() = page < pages
}

class TeamAuditStore(
    private val repository: TeamAuditRepository,
    private val dateTime: LocalDateTimeService,
    parentScope: CoroutineScope,
    private val onTaskQueued: (Long, TeamTask) -> Unit = { _, _ -> },
) : ResettableStore {
    private val scope = parentScope.storeChildScope()
    private val mutableState = MutableStateFlow(TeamAuditState())
    val state: StateFlow<TeamAuditState> = mutableState.asStateFlow()
    private var listJob: Job? = null
    private var exportJob: Job? = null
    private var generation = 0L

    fun activate(teamId: Long, role: TeamRole) {
        if (state.value.teamId != teamId) {
            cancelJobs(); generation++
            mutableState.value = TeamAuditState(teamId = teamId, role = role)
            if (mutableState.value.canView) loadPage(teamId, TeamAuditFilter(), 1, false, generation)
        } else {
            val lostAccess = state.value.canView && role !in MANAGER_ROLES
            mutableState.update { it.copy(role = role) }
            if (lostAccess) {
                listJob?.cancel(); exportJob?.cancel(); generation++
                mutableState.update { it.copy(logs = emptyList(), page = 0, total = 0, loading = false, exporting = false) }
            } else if (state.value.canView && state.value.page == 0 && !state.value.loading) refresh()
        }
    }

    fun updateActorUserId(value: String) = updateDraft { copy(actorUserId = value.filter(Char::isDigit)) }
    fun updateAction(value: String) = updateDraft { copy(action = value) }
    fun updateTargetType(value: String) = updateDraft { copy(targetType = value) }
    fun updateResult(value: Int?) = updateDraft { copy(result = value) }
    fun updateStartAt(value: Long?) = updateDraft { copy(startAtEpochMillis = value) }
    fun updateEndAt(value: Long?) = updateDraft { copy(endAtEpochMillis = value) }

    fun applyFilters() {
        val current = state.value
        val teamId = current.teamId ?: return
        if (!current.canView) return
        val actor = current.draft.actorUserId.trim().takeIf(String::isNotEmpty)?.toLongOrNull()
        if (current.draft.actorUserId.isNotBlank() && (actor == null || actor <= 0)) {
            mutableState.update { it.copy(filterError = UiMessage(MobileString.AuditActorInvalid)) }
            return
        }
        val start = current.draft.startAtEpochMillis
        val end = current.draft.endAtEpochMillis
        if (start != null && end != null && start > end) {
            mutableState.update { it.copy(filterError = UiMessage(MobileString.AuditRangeInvalid)) }
            return
        }
        val filter = TeamAuditFilter(
            actorUserId = actor,
            action = current.draft.action.trim().uppercase().takeIf(String::isNotEmpty),
            targetType = current.draft.targetType.trim().uppercase().takeIf(String::isNotEmpty),
            result = current.draft.result,
            startAt = start?.let(dateTime::formatApiLocalDateTime),
            endAt = end?.let(dateTime::formatApiLocalDateTime),
        )
        val requestGeneration = ++generation
        listJob?.cancel()
        mutableState.update { it.copy(applied = filter, filterError = null, error = null, loadMoreError = null) }
        loadPage(teamId, filter, 1, false, requestGeneration)
    }

    fun resetFilters() {
        mutableState.update { it.copy(draft = TeamAuditDraft(), filterError = null) }
        applyFilters()
    }

    fun refresh() {
        val current = state.value
        val teamId = current.teamId ?: return
        if (!current.canView || current.exporting) return
        val requestGeneration = ++generation
        listJob?.cancel()
        loadPage(teamId, current.applied, 1, true, requestGeneration)
    }

    fun loadMore() {
        val current = state.value
        val teamId = current.teamId ?: return
        if (!current.canView || !current.hasMore || current.loading || current.refreshing || current.loadingMore || current.exporting) return
        val requestGeneration = ++generation
        listJob?.cancel()
        loadPage(teamId, current.applied, current.page + 1, false, requestGeneration)
    }

    fun export() {
        val current = state.value
        val teamId = current.teamId ?: return
        if (!current.canView || current.exporting) return
        val requestGeneration = generation
        exportJob?.cancel()
        mutableState.update { it.copy(exporting = true, feedback = null) }
        exportJob = scope.launch {
            try {
                val task = repository.queueExport(teamId, current.applied)
                if (!matches(teamId, requestGeneration)) return@launch
                onTaskQueued(teamId, task)
                mutableState.update { it.copy(
                    exporting = false,
                    feedback = UiMessage(MobileString.AuditExportQueued),
                    queuedTaskId = task.id,
                ) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Throwable) {
                if (matches(teamId, requestGeneration)) mutableState.update {
                    it.copy(exporting = false, feedback = uiError(error))
                }
            }
        }
    }

    fun consumeFeedback() = mutableState.update { it.copy(feedback = null) }
    fun consumeQueuedTask() = mutableState.update { it.copy(queuedTaskId = null) }
    fun clearFilterError() = mutableState.update { it.copy(filterError = null) }

    override fun reset() {
        cancelJobs(); generation++
        mutableState.value = TeamAuditState()
    }

    private fun updateDraft(change: TeamAuditDraft.() -> TeamAuditDraft) = mutableState.update {
        it.copy(draft = it.draft.change(), filterError = null)
    }

    private fun loadPage(teamId: Long, filter: TeamAuditFilter, page: Int, refresh: Boolean, requestGeneration: Long) {
        mutableState.update {
            when {
                page > 1 -> it.copy(loadingMore = true, loadMoreError = null)
                refresh && it.logs.isNotEmpty() -> it.copy(refreshing = true, error = null)
                else -> it.copy(loading = true, error = null, logs = if (page == 1) emptyList() else it.logs)
            }
        }
        listJob = scope.launch {
            try {
                val result = repository.list(teamId, filter, page, PAGE_SIZE)
                if (!matches(teamId, requestGeneration)) return@launch
                mutableState.update { state -> state.copy(
                    logs = if (page == 1) result.records else (state.logs + result.records).distinctBy { it.id },
                    page = result.page,
                    pages = result.pages.coerceAtLeast(1),
                    total = result.total,
                    loading = false,
                    refreshing = false,
                    loadingMore = false,
                    error = null,
                    loadMoreError = null,
                ) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Throwable) {
                if (!matches(teamId, requestGeneration)) return@launch
                mutableState.update { if (page > 1) it.copy(
                    loadingMore = false, loadMoreError = uiError(error),
                ) else it.copy(loading = false, refreshing = false, error = uiError(error)) }
            }
        }
    }

    private fun matches(teamId: Long, requestGeneration: Long) =
        state.value.teamId == teamId && generation == requestGeneration

    private fun cancelJobs() { listJob?.cancel(); exportJob?.cancel() }

    private companion object {
        const val PAGE_SIZE = 20
        val MANAGER_ROLES = setOf(TeamRole.OWNER, TeamRole.ADMIN)
    }
}

data class TeamTasksState(
    val teamId: Long? = null,
    val role: TeamRole = TeamRole.VIEWER,
    val status: String? = null,
    val type: String? = null,
    val tasks: List<TeamTask> = emptyList(),
    val page: Int = 1,
    val pages: Int = 1,
    val total: Long = 0,
    val loading: Boolean = false,
    val refreshing: Boolean = false,
    val error: UiMessage? = null,
    val operatingTaskId: Long? = null,
    val downloadingTaskId: Long? = null,
    val downloadedBytes: Long = 0,
    val downloadTotalBytes: Long? = null,
    val highlightedTaskId: Long? = null,
    val feedback: UiMessage? = null,
)

class TeamTasksStore(
    private val repository: TeamTasksRepository,
    parentScope: CoroutineScope,
) : ResettableStore {
    private val scope = parentScope.storeChildScope()
    private val mutableState = MutableStateFlow(TeamTasksState())
    val state: StateFlow<TeamTasksState> = mutableState.asStateFlow()
    private var listJob: Job? = null
    private var operationJob: Job? = null
    private var downloadJob: Job? = null
    private var pollingJob: Job? = null
    private var generation = 0L
    private var visible = false

    fun activate(teamId: Long, role: TeamRole, highlightedTaskId: Long? = null) {
        if (state.value.teamId != teamId) {
            cancelJobs(); generation++
            mutableState.value = TeamTasksState(teamId = teamId, role = role, highlightedTaskId = highlightedTaskId, loading = true)
            loadPage(teamId, null, null, 1, false, generation)
        } else {
            val roleChanged = state.value.role != role
            if (roleChanged) {
                cancelJobs()
                generation++
                mutableState.value = state.value.copy(
                    role = role,
                    highlightedTaskId = highlightedTaskId ?: state.value.highlightedTaskId,
                    tasks = emptyList(),
                    page = 1,
                    pages = 1,
                    total = 0,
                    loading = true,
                    refreshing = false,
                    error = null,
                )
                loadPage(teamId, state.value.status, state.value.type, 1, false, generation)
            } else {
                mutableState.update { it.copy(role = role, highlightedTaskId = highlightedTaskId ?: it.highlightedTaskId) }
                if (state.value.tasks.isEmpty() && !state.value.loading) refresh()
            }
        }
        updatePolling()
    }

    fun acceptQueued(teamId: Long, task: TeamTask) {
        if (state.value.teamId == null) mutableState.value = TeamTasksState(
            teamId = teamId,
            tasks = listOf(task),
            total = 1,
            highlightedTaskId = task.id,
            feedback = UiMessage(MobileString.AuditExportQueued),
        )
        else if (state.value.teamId == teamId) mutableState.update { state -> state.copy(
            tasks = listOf(task) + state.tasks.filterNot { it.id == task.id },
            total = maxOf(state.total, (state.tasks.size + 1).toLong()),
            highlightedTaskId = task.id,
            feedback = UiMessage(MobileString.AuditExportQueued),
        ) }
        updatePolling()
    }

    fun setVisible(value: Boolean) { visible = value; updatePolling() }

    fun setStatus(value: String?) = setFilters(value, state.value.type)
    fun setType(value: String?) = setFilters(state.value.status, value)

    private fun setFilters(status: String?, type: String?) {
        val teamId = state.value.teamId ?: return
        val requestGeneration = ++generation
        listJob?.cancel()
        mutableState.update { it.copy(status = status, type = type, page = 1, highlightedTaskId = null) }
        loadPage(teamId, status, type, 1, false, requestGeneration)
    }

    fun refresh() = refresh(silent = false)

    private fun refresh(silent: Boolean) {
        val current = state.value
        val teamId = current.teamId ?: return
        if (current.loading || current.refreshing || current.downloadingTaskId != null || current.operatingTaskId != null) return
        val requestGeneration = ++generation
        listJob?.cancel()
        loadPage(teamId, current.status, current.type, current.page, !silent, requestGeneration)
    }

    fun previousPage() = moveTo(state.value.page - 1)
    fun nextPage() = moveTo(state.value.page + 1)

    private fun moveTo(page: Int) {
        val current = state.value
        val teamId = current.teamId ?: return
        if (page !in 1..current.pages || current.loading || current.refreshing) return
        val requestGeneration = ++generation
        listJob?.cancel()
        loadPage(teamId, current.status, current.type, page, false, requestGeneration)
    }

    fun retry(taskId: Long) {
        val current = state.value
        val teamId = current.teamId ?: return
        val task = current.tasks.firstOrNull { it.id == taskId } ?: return
        if (!task.canRetry || current.operatingTaskId != null) return
        val requestGeneration = generation
        mutableState.update { it.copy(operatingTaskId = taskId, feedback = null) }
        operationJob?.cancel()
        operationJob = scope.launch {
            try {
                val updated = repository.retry(teamId, taskId)
                if (!matches(teamId, requestGeneration)) return@launch
                mutableState.update { state -> state.copy(
                    tasks = state.tasks.map { if (it.id == taskId) updated else it },
                    operatingTaskId = null,
                    feedback = UiMessage(MobileString.TeamTaskRetryQueued),
                ) }
                updatePolling()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Throwable) {
                if (matches(teamId, requestGeneration)) mutableState.update {
                    it.copy(operatingTaskId = null, feedback = uiError(error))
                }
            }
        }
    }

    fun download(taskId: Long, destination: PickedDestination) {
        val current = state.value
        val teamId = current.teamId ?: return
        val task = current.tasks.firstOrNull { it.id == taskId } ?: return
        if (!task.canDownload || current.downloadingTaskId != null) return
        val requestGeneration = generation
        mutableState.update { it.copy(
            downloadingTaskId = taskId, downloadedBytes = 0, downloadTotalBytes = task.resultSizeBytes, feedback = null,
        ) }
        downloadJob?.cancel()
        downloadJob = scope.launch {
            try {
                repository.download(teamId, task, destination) { downloaded, total ->
                    if (matches(teamId, requestGeneration)) mutableState.update {
                        it.copy(downloadedBytes = downloaded, downloadTotalBytes = total)
                    }
                }
                if (!matches(teamId, requestGeneration)) return@launch
                mutableState.update { it.copy(
                    downloadingTaskId = null,
                    feedback = UiMessage(MobileString.TeamTaskDownloaded),
                ) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Throwable) {
                if (matches(teamId, requestGeneration)) mutableState.update {
                    it.copy(downloadingTaskId = null, feedback = uiError(error))
                }
            }
        }
    }

    fun consumeFeedback() = mutableState.update { it.copy(feedback = null) }
    fun clearHighlight() = mutableState.update { it.copy(highlightedTaskId = null) }

    override fun reset() {
        visible = false; cancelJobs(); generation++
        mutableState.value = TeamTasksState()
    }

    private fun loadPage(
        teamId: Long, status: String?, type: String?, page: Int, refresh: Boolean, requestGeneration: Long,
    ) {
        mutableState.update { if (refresh && it.tasks.isNotEmpty()) it.copy(refreshing = true, error = null)
            else it.copy(loading = true, error = null) }
        listJob = scope.launch {
            try {
                val result = repository.list(teamId, status, type, page, PAGE_SIZE)
                if (!matches(teamId, requestGeneration)) return@launch
                mutableState.update { it.copy(
                    tasks = result.records,
                    page = result.page,
                    pages = result.pages.coerceAtLeast(1),
                    total = result.total,
                    loading = false,
                    refreshing = false,
                    error = null,
                ) }
                updatePolling()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Throwable) {
                if (matches(teamId, requestGeneration)) mutableState.update {
                    it.copy(loading = false, refreshing = false, error = uiError(error))
                }
            }
        }
    }

    private fun updatePolling() {
        val shouldPoll = visible && state.value.tasks.any(TeamTask::isActive)
        if (!shouldPoll) { pollingJob?.cancel(); pollingJob = null; return }
        if (pollingJob?.isActive == true) return
        pollingJob = scope.launch {
            while (true) {
                delay(POLL_INTERVAL_MILLIS)
                refresh(silent = true)
            }
        }
    }

    private fun matches(teamId: Long, requestGeneration: Long) =
        state.value.teamId == teamId && generation == requestGeneration

    private fun cancelJobs() {
        listJob?.cancel(); operationJob?.cancel(); downloadJob?.cancel(); pollingJob?.cancel(); pollingJob = null
    }

    private companion object {
        const val PAGE_SIZE = 20
        const val POLL_INTERVAL_MILLIS = 5_000L
    }
}
