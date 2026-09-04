package com.betterclouddrive.mobile.feature.teams

import com.betterclouddrive.mobile.app.ResettableStore
import com.betterclouddrive.mobile.app.storeChildScope
import com.betterclouddrive.mobile.app.uiError
import com.betterclouddrive.mobile.core.localization.MobileString
import com.betterclouddrive.mobile.core.localization.UiMessage
import com.betterclouddrive.mobile.core.model.FileItem
import com.betterclouddrive.mobile.core.model.OfficeConfig
import com.betterclouddrive.mobile.core.model.PreviewKind
import com.betterclouddrive.mobile.core.model.TeamSummary
import com.betterclouddrive.mobile.feature.files.BreadcrumbEntry
import com.betterclouddrive.mobile.feature.files.DirectoryPageState
import com.betterclouddrive.mobile.feature.files.FileViewMode
import com.betterclouddrive.mobile.feature.files.PersonalFileSort
import com.betterclouddrive.mobile.feature.files.SortDirection
import com.betterclouddrive.mobile.feature.files.previewKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class TeamsState(
    val items: List<TeamSummary> = emptyList(),
    val loading: Boolean = false,
    val refreshing: Boolean = false,
    val loadingMore: Boolean = false,
    val error: UiMessage? = null,
    val loadMoreError: UiMessage? = null,
    val page: Int = 0,
    val pages: Int = 1,
    val total: Long = 0,
    val creating: Boolean = false,
    val createError: UiMessage? = null,
    val createdTeam: TeamSummary? = null,
) { val hasMore: Boolean get() = page < pages }

class TeamsStore(
    private val repository: TeamWorkspaceRepository,
    parentScope: CoroutineScope,
) : ResettableStore {
    private val scope = parentScope.storeChildScope()
    private val mutableState = MutableStateFlow(TeamsState())
    val state: StateFlow<TeamsState> = mutableState.asStateFlow()
    private var listJob: Job? = null
    private var createJob: Job? = null

    fun activate() {
        if (state.value.loading || state.value.page > 0) return
        load(1, refresh = false)
    }

    fun refresh() = load(1, refresh = true)

    fun loadMore() {
        val current = state.value
        if (!current.hasMore || current.loading || current.loadingMore || current.refreshing) return
        load(current.page + 1, refresh = false)
    }

    fun create(name: String) {
        if (state.value.creating) return
        val normalized = name.trim()
        if (normalized.isEmpty() || normalized.length > MAX_TEAM_NAME) {
            mutableState.update { it.copy(createError = UiMessage(
                if (normalized.isEmpty()) MobileString.NameRequired else MobileString.NameTooLong,
            )) }
            return
        }
        createJob?.cancel()
        mutableState.update { it.copy(creating = true, createError = null) }
        createJob = scope.launch {
            try {
                val created = repository.createTeam(normalized)
                mutableState.update { current -> current.copy(
                    items = (current.items + created).distinctBy(TeamSummary::id)
                        .sortedWith(compareBy<TeamSummary> { it.name.lowercase() }.thenBy { it.id }),
                    total = maxOf(current.total + 1, current.items.size.toLong() + 1),
                    creating = false,
                    createdTeam = created,
                ) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Throwable) { mutableState.update { it.copy(creating = false, createError = uiError(error)) } }
        }
    }

    fun consumeCreatedTeam() = mutableState.update { it.copy(createdTeam = null) }
    fun clearCreateError() = mutableState.update { it.copy(createError = null) }

    override fun reset() {
        listJob?.cancel(); createJob?.cancel()
        mutableState.value = TeamsState()
    }

    private fun load(page: Int, refresh: Boolean) {
        listJob?.cancel()
        val existing = state.value
        mutableState.update { when {
            page > 1 -> it.copy(loadingMore = true, loadMoreError = null)
            refresh && it.items.isNotEmpty() -> it.copy(refreshing = true, error = null)
            else -> it.copy(loading = true, error = null)
        } }
        listJob = scope.launch {
            try {
                val result = repository.listTeams(page, PAGE_SIZE)
                val merged = if (page == 1) result.records.distinctBy(TeamSummary::id)
                else (existing.items + result.records).distinctBy(TeamSummary::id)
                mutableState.update { it.copy(
                    items = merged, loading = false, refreshing = false, loadingMore = false,
                    error = null, loadMoreError = null, page = result.page,
                    pages = result.pages.coerceAtLeast(result.page), total = result.total,
                ) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Throwable) { mutableState.update { when {
                page > 1 -> it.copy(loadingMore = false, loadMoreError = uiError(error))
                refresh && existing.items.isNotEmpty() -> it.copy(refreshing = false, error = uiError(error))
                else -> it.copy(loading = false, error = uiError(error))
            } } }
        }
    }

    private companion object {
        const val PAGE_SIZE = 50
        const val MAX_TEAM_NAME = 80
    }
}

data class TeamBrowserState(
    val teamId: Long? = null,
    val team: TeamSummary? = null,
    val workspaceLoading: Boolean = false,
    val workspaceError: UiMessage? = null,
    val breadcrumb: List<BreadcrumbEntry> = listOf(BreadcrumbEntry(null, "")),
    val current: DirectoryPageState = DirectoryPageState(),
    val sort: PersonalFileSort = PersonalFileSort.NAME,
    val direction: SortDirection = SortDirection.ASCENDING,
    val viewMode: FileViewMode = FileViewMode.LIST,
    val selectedFile: FileItem? = null,
)

class TeamBrowserStore(
    private val repository: TeamWorkspaceRepository,
    parentScope: CoroutineScope,
) : ResettableStore {
    private val scope = parentScope.storeChildScope()
    private val mutableState = MutableStateFlow(TeamBrowserState())
    val state: StateFlow<TeamBrowserState> = mutableState.asStateFlow()
    private val cache = mutableMapOf<Long?, DirectoryPageState>()
    private var directoryJob: Job? = null
    private var detailJob: Job? = null

    fun activate(teamId: Long, folderId: Long?) {
        val current = state.value
        if (current.teamId != teamId) resetWorkspace(teamId)
        val latest = state.value
        if (latest.current.folderId == folderId &&
            (latest.current.hasLoaded || latest.current.initialLoading) && latest.workspaceError == null
        ) return
        directoryJob?.cancel()
        mutableState.update { it.copy(workspaceLoading = it.team == null, workspaceError = null) }
        directoryJob = scope.launch {
            try {
                val team = state.value.team ?: repository.getTeam(teamId)
                if (state.value.teamId != teamId) return@launch
                mutableState.update { it.copy(team = team, workspaceLoading = false) }
                val path = when {
                    folderId == null -> listOf(BreadcrumbEntry(null, ""))
                    state.value.breadcrumb.any { it.id == folderId } ->
                        state.value.breadcrumb.takeWhile { it.id != folderId } + state.value.breadcrumb.first { it.id == folderId }
                    else -> rebuildPath(teamId, folderId)
                }
                cache.keys.retainAll(path.map(BreadcrumbEntry::id).toSet())
                showDirectory(folderId, path)
                if (!state.value.current.hasLoaded) loadPage(teamId, folderId, 1, false)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Throwable) { workspaceUnavailable(teamId) }
        }
    }

    fun openFolder(file: FileItem) {
        val teamId = state.value.teamId ?: return
        if (!file.isFolder) return
        val path = state.value.breadcrumb + BreadcrumbEntry(file.id, file.fileName)
        showDirectory(file.id, path)
        directoryJob?.cancel(); directoryJob = scope.launch { loadPage(teamId, file.id, 1, false) }
    }

    fun refresh() {
        val teamId = state.value.teamId ?: return
        directoryJob?.cancel(); directoryJob = scope.launch { loadPage(teamId, state.value.current.folderId, 1, true) }
    }

    fun loadMore() {
        val teamId = state.value.teamId ?: return
        val current = state.value.current
        if (!current.hasMore || current.loadingMore || current.initialLoading || current.refreshing) return
        directoryJob?.cancel(); directoryJob = scope.launch { loadPage(teamId, current.folderId, current.page + 1, false) }
    }

    fun changeSort(sort: PersonalFileSort, direction: SortDirection) {
        val teamId = state.value.teamId ?: return
        val previous = state.value
        if (previous.sort == sort && previous.direction == direction) return
        directoryJob?.cancel()
        mutableState.value = previous.copy(
            sort = sort, direction = direction,
            current = previous.current.copy(refreshing = previous.current.hasLoaded, error = null),
            selectedFile = null,
        )
        directoryJob = scope.launch {
            try {
                val result = repository.listFiles(
                    teamId, previous.current.folderId, 1, PAGE_SIZE, sort.apiValue, direction.apiValue,
                )
                if (state.value.teamId != teamId || state.value.sort != sort || state.value.direction != direction) return@launch
                cache.clear()
                val snapshot = result.snapshot(previous.current.folderId)
                cache[previous.current.folderId] = snapshot
                mutableState.update { it.copy(current = snapshot) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Throwable) { mutableState.value = previous.copy(
                current = previous.current.copy(error = uiError(error)),
            ) }
        }
    }

    fun changeViewMode(mode: FileViewMode) = mutableState.update { it.copy(viewMode = mode) }
    fun selectFile(file: FileItem?) {
        val teamId = state.value.teamId ?: return
        detailJob?.cancel(); mutableState.update { it.copy(selectedFile = file) }
        if (file == null || file.isFolder) return
        detailJob = scope.launch {
            try {
                val detail = repository.getFile(teamId, file.id)
                mutableState.update { if (it.teamId == teamId && it.selectedFile?.id == file.id) it.copy(selectedFile = detail) else it }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Throwable) { }
        }
    }

    fun updateScrollAnchor(fileId: Long?) = updateCurrent { it.copy(scrollAnchorId = fileId) }
    fun prepareOpenLocation(file: FileItem) {
        cache[file.parentId]?.let { snapshot ->
            if (snapshot.items.any { it.id == file.id }) cache[file.parentId] = snapshot.copy(scrollAnchorId = file.id)
        }
    }

    fun invalidate(teamId: Long, folderIds: Set<Long?>) {
        if (state.value.teamId != teamId) return
        folderIds.forEach(cache::remove)
        if (state.value.current.folderId in folderIds) refresh()
    }

    override fun reset() {
        directoryJob?.cancel(); detailJob?.cancel(); cache.clear()
        mutableState.value = TeamBrowserState()
    }

    private fun resetWorkspace(teamId: Long) {
        directoryJob?.cancel(); detailJob?.cancel(); cache.clear()
        mutableState.value = TeamBrowserState(teamId = teamId, workspaceLoading = true)
    }

    private fun workspaceUnavailable(teamId: Long) {
        if (state.value.teamId != teamId) return
        cache.clear(); mutableState.value = TeamBrowserState(
            teamId = teamId, workspaceError = UiMessage(MobileString.TeamWorkspaceUnavailable),
        )
    }

    private fun showDirectory(folderId: Long?, path: List<BreadcrumbEntry>) {
        val snapshot = cache[folderId] ?: DirectoryPageState(folderId = folderId)
        mutableState.update { it.copy(breadcrumb = path, current = snapshot, selectedFile = null) }
    }

    private suspend fun loadPage(teamId: Long, folderId: Long?, page: Int, refresh: Boolean) {
        if (state.value.teamId != teamId || state.value.current.folderId != folderId) return
        val before = state.value
        val existing = before.current
        updateCurrent { when {
            page > 1 -> it.copy(loadingMore = true, loadMoreError = null)
            refresh && it.hasLoaded -> it.copy(refreshing = true, error = null)
            else -> it.copy(initialLoading = true, error = null)
        } }
        try {
            val result = repository.listFiles(teamId, folderId, page, PAGE_SIZE, before.sort.apiValue, before.direction.apiValue)
            if (state.value.teamId != teamId || state.value.current.folderId != folderId ||
                state.value.sort != before.sort || state.value.direction != before.direction
            ) return
            val merged = if (page == 1) result.records.distinctBy(FileItem::id)
            else (existing.items + result.records).distinctBy(FileItem::id)
            val snapshot = result.snapshot(folderId, merged, if (page == 1) null else existing.scrollAnchorId)
            cache[folderId] = snapshot; mutableState.update { it.copy(current = snapshot) }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Throwable) { updateCurrent { when {
            page > 1 -> it.copy(loadingMore = false, loadMoreError = uiError(error))
            refresh && it.hasLoaded -> it.copy(refreshing = false, error = uiError(error))
            else -> it.copy(initialLoading = false, error = uiError(error))
        } } }
    }

    private suspend fun rebuildPath(teamId: Long, folderId: Long): List<BreadcrumbEntry> {
        val seen = mutableSetOf<Long>()
        val reversed = mutableListOf<BreadcrumbEntry>()
        var currentId: Long? = folderId
        while (currentId != null) {
            check(seen.add(currentId)) { "team.folderCycle" }
            val item = repository.getFile(teamId, currentId)
            check(item.isFolder) { "team.routeNotFolder" }
            reversed += BreadcrumbEntry(item.id, item.fileName)
            currentId = item.parentId
        }
        return listOf(BreadcrumbEntry(null, "")) + reversed.asReversed()
    }

    private fun updateCurrent(transform: (DirectoryPageState) -> DirectoryPageState) {
        val folderId = state.value.current.folderId
        mutableState.update { current ->
            val updated = transform(current.current)
            cache[folderId] = updated
            current.copy(current = updated)
        }
    }

    private fun com.betterclouddrive.mobile.core.model.PageResult<FileItem>.snapshot(
        folderId: Long?, items: List<FileItem> = records.distinctBy(FileItem::id), anchor: Long? = null,
    ) = DirectoryPageState(
        folderId = folderId, items = items, hasLoaded = true, page = page,
        pages = pages.coerceAtLeast(page), total = total, scrollAnchorId = anchor,
    )

    private companion object { const val PAGE_SIZE = 50 }
}

data class TeamSearchState(
    val teamId: Long? = null,
    val query: String = "",
    val submittedQuery: String = "",
    val items: List<FileItem> = emptyList(),
    val searching: Boolean = false,
    val loadingMore: Boolean = false,
    val error: UiMessage? = null,
    val loadMoreError: UiMessage? = null,
    val page: Int = 0,
    val pages: Int = 1,
    val total: Long = 0,
    val scrollAnchorId: Long? = null,
) { val hasMore get() = submittedQuery.isNotEmpty() && page < pages }

class TeamSearchStore(
    private val repository: TeamWorkspaceRepository,
    parentScope: CoroutineScope,
) : ResettableStore {
    private val scope = parentScope.storeChildScope()
    private val mutableState = MutableStateFlow(TeamSearchState())
    val state: StateFlow<TeamSearchState> = mutableState.asStateFlow()
    private var job: Job? = null

    fun activate(teamId: Long) {
        if (state.value.teamId != teamId) { job?.cancel(); mutableState.value = TeamSearchState(teamId = teamId) }
    }
    fun updateQuery(value: String) {
        val teamId = state.value.teamId ?: return
        job?.cancel()
        if (value.trim().isEmpty()) { mutableState.value = TeamSearchState(teamId = teamId, query = value); return }
        mutableState.update { it.copy(query = value, error = null, loadMoreError = null) }
        job = scope.launch { delay(DEBOUNCE_MILLIS); load(teamId, value.trim(), 1) }
    }
    fun submit() {
        val state = state.value; val teamId = state.teamId ?: return; val query = state.query.trim()
        job?.cancel(); if (query.isEmpty()) { mutableState.value = TeamSearchState(teamId = teamId, query = state.query); return }
        job = scope.launch { load(teamId, query, 1) }
    }
    fun retry() = submit()
    fun loadMore() {
        val state = state.value; val teamId = state.teamId ?: return
        if (!state.hasMore || state.searching || state.loadingMore) return
        job?.cancel(); job = scope.launch { load(teamId, state.submittedQuery, state.page + 1) }
    }
    fun updateScrollAnchor(fileId: Long?) = mutableState.update { it.copy(scrollAnchorId = fileId) }
    override fun reset() { job?.cancel(); mutableState.value = TeamSearchState() }

    private suspend fun load(teamId: Long, query: String, page: Int) {
        val existing = state.value
        mutableState.update { it.copy(
            submittedQuery = query, searching = page == 1, loadingMore = page > 1,
            error = if (page == 1) null else it.error, loadMoreError = null,
        ) }
        try {
            val result = repository.search(teamId, query, page, PAGE_SIZE)
            if (state.value.teamId != teamId || state.value.query.trim() != query || state.value.submittedQuery != query) return
            val items = if (page == 1) result.records.distinctBy(FileItem::id)
            else (existing.items + result.records).distinctBy(FileItem::id)
            mutableState.update { it.copy(
                items = items, searching = false, loadingMore = false, error = null, loadMoreError = null,
                page = result.page, pages = result.pages.coerceAtLeast(result.page), total = result.total,
                scrollAnchorId = if (page == 1) null else existing.scrollAnchorId,
            ) }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Throwable) { if (state.value.teamId == teamId && state.value.query.trim() == query) {
            mutableState.update { it.copy(
                searching = false, loadingMore = false,
                error = if (page == 1) uiError(error) else it.error,
                loadMoreError = if (page > 1) uiError(error) else null,
            ) }
        } }
    }

    private companion object { const val PAGE_SIZE = 50; const val DEBOUNCE_MILLIS = 300L }
}

data class TeamPreviewState(
    val teamId: Long? = null,
    val fileId: Long? = null,
    val file: FileItem? = null,
    val kind: PreviewKind? = null,
    val loading: Boolean = false,
    val error: UiMessage? = null,
    val mediaUrl: String? = null,
    val text: String? = null,
    val textTruncated: Boolean = false,
    val office: OfficeConfig? = null,
    val playbackPositionMillis: Long = 0,
    val ticketRetryCount: Int = 0,
)

class TeamPreviewStore(
    private val repository: TeamPreviewRepository,
    parentScope: CoroutineScope,
) : ResettableStore {
    private val scope = parentScope.storeChildScope()
    private val mutableState = MutableStateFlow(TeamPreviewState())
    val state: StateFlow<TeamPreviewState> = mutableState.asStateFlow()
    private var job: Job? = null

    fun open(teamId: Long, fileId: Long) {
        val current = state.value
        if (current.teamId == teamId && current.fileId == fileId &&
            (current.loading || current.file != null) && current.error == null
        ) return
        job?.cancel()
        val position = if (current.teamId == teamId && current.fileId == fileId) current.playbackPositionMillis else 0
        mutableState.value = TeamPreviewState(teamId = teamId, fileId = fileId, loading = true, playbackPositionMillis = position)
        job = scope.launch {
            try { load(teamId, repository.getFile(teamId, fileId), position, 0) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Throwable) { fail(teamId, fileId, UiMessage(MobileString.ErrorPreviewFailed)) }
        }
    }
    fun retry() {
        val state = state.value; val teamId = state.teamId ?: return; val fileId = state.fileId ?: return
        job?.cancel(); mutableState.update { it.copy(loading = true, error = null, mediaUrl = null) }
        job = scope.launch {
            try { load(teamId, state.file ?: repository.getFile(teamId, fileId), state.playbackPositionMillis, 0) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Throwable) { fail(teamId, fileId, UiMessage(MobileString.ErrorPreviewFailed)) }
        }
    }
    fun reportRendererError(message: UiMessage) {
        val state = state.value; val teamId = state.teamId ?: return; val file = state.file ?: return
        if (state.kind in setOf(PreviewKind.IMAGE, PreviewKind.VIDEO) && state.ticketRetryCount == 0) {
            job?.cancel(); mutableState.update { it.copy(loading = true, error = null, mediaUrl = null, ticketRetryCount = 1) }
            job = scope.launch {
                try { load(teamId, file, state.playbackPositionMillis, 1) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Throwable) { fail(teamId, file.id, UiMessage(MobileString.ErrorPreviewFailed)) }
            }
        } else fail(teamId, file.id, message)
    }
    fun updatePosition(position: Long) = mutableState.update {
        if (it.kind == PreviewKind.VIDEO) it.copy(playbackPositionMillis = position.coerceAtLeast(0)) else it
    }
    fun close() = reset()
    override fun reset() { job?.cancel(); mutableState.value = TeamPreviewState() }

    private suspend fun load(teamId: Long, file: FileItem, position: Long, retryCount: Int) {
        val kind = file.previewKind()
        val base = TeamPreviewState(
            teamId = teamId, fileId = file.id, file = file, kind = kind, loading = true,
            playbackPositionMillis = position, ticketRetryCount = retryCount,
        )
        mutableState.update { if (it.teamId == teamId && it.fileId == file.id) base else it }
        val loaded = when (kind) {
            PreviewKind.IMAGE, PreviewKind.VIDEO -> base.copy(
                loading = false, mediaUrl = repository.createTicket(teamId, file.id),
            )
            PreviewKind.TEXT -> repository.loadText(teamId, file).let {
                base.copy(loading = false, text = it.content, textTruncated = it.truncated)
            }
            PreviewKind.OFFICE -> base.copy(loading = false, office = repository.loadOffice(teamId, file.id))
            PreviewKind.UNSUPPORTED -> base.copy(loading = false)
        }
        mutableState.update { if (it.teamId == teamId && it.fileId == file.id) loaded else it }
    }
    private fun fail(teamId: Long, fileId: Long, message: UiMessage) = mutableState.update {
        if (it.teamId == teamId && it.fileId == fileId) it.copy(loading = false, error = message, mediaUrl = null) else it
    }
}
