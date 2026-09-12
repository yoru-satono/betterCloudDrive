package com.betterclouddrive.mobile.feature.recovery

import com.betterclouddrive.mobile.app.FileMutationFeedback
import com.betterclouddrive.mobile.app.ResettableStore
import com.betterclouddrive.mobile.app.TransferEnqueueFeedback
import com.betterclouddrive.mobile.app.storeChildScope
import com.betterclouddrive.mobile.app.uiError
import com.betterclouddrive.mobile.core.localization.MobileString
import com.betterclouddrive.mobile.core.localization.UiMessage
import com.betterclouddrive.mobile.core.model.FileItem
import com.betterclouddrive.mobile.core.model.PersonalFileVersion
import com.betterclouddrive.mobile.core.model.RecycleBinItem
import com.betterclouddrive.mobile.core.model.TeamEffectivePermission
import com.betterclouddrive.mobile.core.model.TeamRole
import com.betterclouddrive.mobile.feature.teams.TeamPermissionsRepository
import com.betterclouddrive.mobile.feature.transfers.TeamVersionTransferOperations
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class TeamRecycleBinState(
    val teamId: Long? = null,
    val role: TeamRole = TeamRole.VIEWER,
    val query: String = "",
    val submittedQuery: String = "",
    val items: List<RecycleBinItem> = emptyList(),
    val permissions: Map<Long, TeamEffectivePermission> = emptyMap(),
    val loadingPermissionIds: Set<Long> = emptySet(),
    val loading: Boolean = false,
    val refreshing: Boolean = false,
    val loadingMore: Boolean = false,
    val error: UiMessage? = null,
    val loadMoreError: UiMessage? = null,
    val permissionError: UiMessage? = null,
    val page: Int = 0,
    val pages: Int = 1,
    val total: Long = 0,
    val mutatingFileId: Long? = null,
    val emptying: Boolean = false,
    val feedback: FileMutationFeedback? = null,
) {
    val hasLoaded get() = page > 0
    val hasMore get() = page < pages
    val canEmpty get() = role == TeamRole.OWNER || role == TeamRole.ADMIN
}

class TeamRecycleBinStore(
    private val repository: TeamRecycleBinRepository,
    private val permissions: TeamPermissionsRepository,
    parentScope: CoroutineScope,
    private val onContentChanged: (Long) -> Unit = {},
) : ResettableStore {
    private val scope = parentScope.storeChildScope()
    private val mutableState = MutableStateFlow(TeamRecycleBinState())
    val state: StateFlow<TeamRecycleBinState> = mutableState.asStateFlow()
    private var listJob: Job? = null
    private var mutationJob: Job? = null
    private val permissionJobs = mutableMapOf<Long, Job>()
    private var generation = 0L
    private var feedbackSequence = 0L

    fun activate(teamId: Long, role: TeamRole) {
        if (state.value.teamId != teamId) {
            listJob?.cancel(); mutationJob?.cancel(); cancelPermissionJobs(); generation++
            mutableState.value = TeamRecycleBinState(teamId = teamId, role = role, loading = true)
            listJob = scope.launch { loadPage(teamId, "", 1, false, generation) }
        } else {
            mutableState.update { it.copy(role = role) }
            if (!state.value.hasLoaded && !state.value.loading) refresh()
        }
    }

    fun updateRole(teamId: Long, role: TeamRole) {
        mutableState.update { current -> if (current.teamId == teamId) current.copy(role = role) else current }
    }

    fun updateQuery(value: String) {
        val teamId = state.value.teamId ?: return
        listJob?.cancel()
        val requestGeneration = ++generation
        mutableState.update { it.copy(query = value, error = null, loadMoreError = null) }
        listJob = scope.launch { delay(300); loadPage(teamId, value.trim(), 1, false, requestGeneration) }
    }

    fun refresh() {
        val current = state.value
        val teamId = current.teamId ?: return
        listJob?.cancel()
        val requestGeneration = ++generation
        listJob = scope.launch { loadPage(teamId, current.query.trim(), 1, true, requestGeneration) }
    }

    fun loadMore() {
        val current = state.value
        val teamId = current.teamId ?: return
        if (!current.hasMore || current.loading || current.refreshing || current.loadingMore) return
        listJob?.cancel()
        val requestGeneration = ++generation
        listJob = scope.launch { loadPage(teamId, current.submittedQuery, current.page + 1, false, requestGeneration) }
    }

    fun requestPermission(fileId: Long) {
        val current = state.value
        val teamId = current.teamId ?: return
        if (fileId in current.permissions || fileId in current.loadingPermissionIds) return
        mutableState.update { it.copy(loadingPermissionIds = it.loadingPermissionIds + fileId, permissionError = null) }
        val job = scope.launch {
            try {
                val permission = permissions.effective(teamId, fileId)
                mutableState.update { state -> if (state.teamId == teamId) state.copy(
                    permissions = state.permissions + (fileId to permission),
                    loadingPermissionIds = state.loadingPermissionIds - fileId,
                ) else state }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Throwable) { mutableState.update { state -> if (state.teamId == teamId) state.copy(
                loadingPermissionIds = state.loadingPermissionIds - fileId,
                permissionError = uiError(error),
            ) else state } }
        }
        permissionJobs[fileId] = job
        job.invokeOnCompletion { if (permissionJobs[fileId] === job) permissionJobs.remove(fileId) }
    }

    fun restore(fileId: Long) {
        if (state.value.permissions[fileId]?.canWrite != true) return
        mutate(fileId, MobileString.RecycleRestored) { teamId -> repository.restore(teamId, fileId) }
    }

    fun permanentlyDelete(fileId: Long) {
        if (state.value.permissions[fileId]?.canDelete != true) return
        mutate(fileId, MobileString.RecyclePermanentlyDeleted) { teamId -> repository.permanentlyDelete(teamId, fileId) }
    }

    fun empty() {
        val current = state.value
        val teamId = current.teamId ?: return
        if (!current.canEmpty || current.emptying || current.mutatingFileId != null) return
        mutationJob?.cancel()
        mutationJob = scope.launch {
            mutableState.update { it.copy(emptying = true, feedback = null) }
            try {
                repository.empty(teamId)
                if (state.value.teamId != teamId) return@launch
                onContentChanged(teamId)
                mutableState.update { it.copy(
                    items = emptyList(), permissions = emptyMap(), total = 0, page = 1, pages = 1, emptying = false,
                    feedback = FileMutationFeedback(++feedbackSequence, UiMessage(MobileString.RecycleEmptied), false),
                ) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Throwable) { mutableState.update { state -> if (state.teamId == teamId) state.copy(
                emptying = false, feedback = FileMutationFeedback(++feedbackSequence, uiError(error), true),
            ) else state } }
        }
    }

    fun consumeFeedback() = mutableState.update { it.copy(feedback = null) }
    fun clearPermissionError() = mutableState.update { it.copy(permissionError = null) }

    private fun mutate(fileId: Long, success: MobileString, action: suspend (Long) -> Unit) {
        val teamId = state.value.teamId ?: return
        if (state.value.mutatingFileId != null || state.value.emptying) return
        mutationJob?.cancel()
        mutationJob = scope.launch {
            mutableState.update { it.copy(mutatingFileId = fileId, feedback = null) }
            try {
                action(teamId)
                if (state.value.teamId != teamId) return@launch
                onContentChanged(teamId)
                mutableState.update { current -> current.copy(
                    items = current.items.filterNot { it.id == fileId },
                    permissions = current.permissions - fileId,
                    total = (current.total - 1).coerceAtLeast(0), mutatingFileId = null,
                    feedback = FileMutationFeedback(++feedbackSequence, UiMessage(success), false),
                ) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Throwable) { mutableState.update { state -> if (state.teamId == teamId) state.copy(
                mutatingFileId = null, feedback = FileMutationFeedback(++feedbackSequence, uiError(error), true),
            ) else state } }
        }
    }

    private suspend fun loadPage(teamId: Long, query: String, page: Int, refresh: Boolean, requestGeneration: Long) {
        val existing = state.value
        mutableState.update { it.copy(
            submittedQuery = query, loading = page == 1 && !refresh,
            refreshing = page == 1 && refresh && it.hasLoaded, loadingMore = page > 1,
            error = if (page == 1) null else it.error, loadMoreError = null,
        ) }
        try {
            val result = repository.list(teamId, query.ifEmpty { null }, page, PAGE_SIZE)
            if (state.value.teamId != teamId || generation != requestGeneration || state.value.query.trim() != query) return
            val merged = if (page == 1) result.records.distinctBy(RecycleBinItem::id)
            else (existing.items + result.records).distinctBy(RecycleBinItem::id)
            val visibleIds = merged.mapTo(mutableSetOf(), RecycleBinItem::id)
            mutableState.update { it.copy(
                items = merged, permissions = it.permissions.filterKeys(visibleIds::contains),
                loading = false, refreshing = false, loadingMore = false, error = null, loadMoreError = null,
                page = result.page, pages = result.pages.coerceAtLeast(result.page), total = result.total,
            ) }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Throwable) {
            if (state.value.teamId == teamId && generation == requestGeneration) mutableState.update { it.copy(
                loading = false, refreshing = false, loadingMore = false,
                error = if (page == 1) uiError(error) else it.error,
                loadMoreError = if (page > 1) uiError(error) else null,
            ) }
        }
    }

    override fun reset() {
        listJob?.cancel(); mutationJob?.cancel(); cancelPermissionJobs(); generation++
        mutableState.value = TeamRecycleBinState()
    }

    private fun cancelPermissionJobs() {
        permissionJobs.values.toList().forEach(Job::cancel)
        permissionJobs.clear()
    }

    private companion object { const val PAGE_SIZE = 50 }
}

data class TeamVersionsState(
    val teamId: Long? = null,
    val fileId: Long? = null,
    val file: FileItem? = null,
    val permission: TeamEffectivePermission? = null,
    val versions: List<PersonalFileVersion> = emptyList(),
    val loading: Boolean = false,
    val error: UiMessage? = null,
    val restoringVersion: Int? = null,
    val deletingVersion: Int? = null,
    val downloadingVersion: Int? = null,
    val feedback: FileMutationFeedback? = null,
    val transferFeedback: TransferEnqueueFeedback? = null,
)

class TeamVersionsStore(
    private val repository: TeamVersionsRepository,
    private val permissions: TeamPermissionsRepository,
    private val transfers: TeamVersionTransferOperations,
    parentScope: CoroutineScope,
    private val onContentChanged: (Long, Long?) -> Unit = { _, _ -> },
) : ResettableStore {
    private val scope = parentScope.storeChildScope()
    private val mutableState = MutableStateFlow(TeamVersionsState())
    val state: StateFlow<TeamVersionsState> = mutableState.asStateFlow()
    private var loadJob: Job? = null
    private var mutationJob: Job? = null
    private var transferJob: Job? = null
    private var generation = 0L
    private var sequence = 0L

    fun open(teamId: Long, fileId: Long) {
        if (state.value.teamId == teamId && state.value.fileId == fileId && state.value.file != null) return
        loadJob?.cancel(); mutationJob?.cancel(); transferJob?.cancel()
        val requestGeneration = ++generation
        mutableState.value = TeamVersionsState(teamId = teamId, fileId = fileId, loading = true)
        loadJob = scope.launch { load(teamId, fileId, requestGeneration) }
    }

    fun retry() {
        val current = state.value
        val teamId = current.teamId ?: return
        val fileId = current.fileId ?: return
        loadJob?.cancel()
        val requestGeneration = ++generation
        loadJob = scope.launch { load(teamId, fileId, requestGeneration) }
    }

    fun restore(versionNumber: Int) {
        if (state.value.permission?.canWrite != true) return
        mutate(versionNumber, true)
    }

    fun delete(versionNumber: Int) {
        if (state.value.permission?.canDelete != true) return
        mutate(versionNumber, false)
    }

    fun download(version: PersonalFileVersion, destinationHandle: String) {
        val current = state.value
        val teamId = current.teamId ?: return
        val file = current.file ?: return
        if (current.permission?.canRead != true || current.downloadingVersion != null) return
        transferJob?.cancel()
        transferJob = scope.launch {
            mutableState.update { it.copy(downloadingVersion = version.versionNumber, feedback = null) }
            try {
                transfers.enqueueTeamVersionDownload(
                    teamId, file.id, version.versionNumber, "v${version.versionNumber}_${file.fileName}",
                    version.fileSize, file.mimeType, destinationHandle, version.md5Hash,
                )
                mutableState.update { state -> if (state.teamId == teamId && state.fileId == file.id) state.copy(
                    downloadingVersion = null,
                    transferFeedback = TransferEnqueueFeedback(++sequence, UiMessage(MobileString.VersionDownloadQueued), true),
                ) else state }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Throwable) { mutableState.update { state -> if (state.teamId == teamId && state.fileId == file.id) state.copy(
                downloadingVersion = null,
                feedback = FileMutationFeedback(++sequence, uiError(error), true),
            ) else state } }
        }
    }

    fun consumeFeedback() = mutableState.update { it.copy(feedback = null) }
    fun consumeTransferFeedback() = mutableState.update { it.copy(transferFeedback = null) }

    private suspend fun load(teamId: Long, fileId: Long, requestGeneration: Long) {
        mutableState.update { it.copy(loading = true, error = null) }
        try {
            val file = repository.file(teamId, fileId)
            val permission = permissions.effective(teamId, fileId)
            val versions = repository.list(teamId, fileId)
            if (matches(teamId, fileId, requestGeneration)) mutableState.update { it.copy(
                file = file, permission = permission, versions = versions,
                loading = false, error = null,
            ) }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Throwable) { if (matches(teamId, fileId, requestGeneration)) mutableState.update { it.copy(
            loading = false, error = uiError(error), permission = null,
        ) } }
    }

    private fun mutate(versionNumber: Int, restore: Boolean) {
        val current = state.value
        val teamId = current.teamId ?: return
        val fileId = current.fileId ?: return
        if (current.restoringVersion != null || current.deletingVersion != null) return
        mutationJob?.cancel()
        mutationJob = scope.launch {
            mutableState.update { if (restore) it.copy(restoringVersion = versionNumber) else it.copy(deletingVersion = versionNumber) }
            try {
                if (restore) repository.restore(teamId, fileId, versionNumber) else repository.delete(teamId, fileId, versionNumber)
                if (state.value.teamId != teamId || state.value.fileId != fileId) return@launch
                val versions = repository.list(teamId, fileId)
                val file = repository.file(teamId, fileId)
                val permission = permissions.effective(teamId, fileId)
                onContentChanged(teamId, file.parentId)
                mutableState.update { it.copy(
                    file = file, permission = permission, versions = versions,
                    restoringVersion = null, deletingVersion = null,
                    feedback = FileMutationFeedback(
                        ++sequence, UiMessage(if (restore) MobileString.VersionRestored else MobileString.VersionDeleted), false,
                    ),
                ) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Throwable) { mutableState.update { state -> if (state.teamId == teamId && state.fileId == fileId) state.copy(
                restoringVersion = null, deletingVersion = null,
                feedback = FileMutationFeedback(++sequence, uiError(error), true),
            ) else state } }
        }
    }

    private fun matches(teamId: Long, fileId: Long, requestGeneration: Long) =
        state.value.teamId == teamId && state.value.fileId == fileId && generation == requestGeneration

    override fun reset() {
        loadJob?.cancel(); mutationJob?.cancel(); transferJob?.cancel(); generation++
        mutableState.value = TeamVersionsState()
    }
}
