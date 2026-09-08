package com.betterclouddrive.mobile.feature.teams

import com.betterclouddrive.mobile.app.FileMutationFeedback
import com.betterclouddrive.mobile.app.ResettableStore
import com.betterclouddrive.mobile.app.storeChildScope
import com.betterclouddrive.mobile.app.uiError
import com.betterclouddrive.mobile.core.localization.MobileString
import com.betterclouddrive.mobile.core.localization.UiMessage
import com.betterclouddrive.mobile.core.model.FileItem
import com.betterclouddrive.mobile.core.model.PersonalTag
import com.betterclouddrive.mobile.core.model.TeamRole
import com.betterclouddrive.mobile.feature.organization.FavoritesState
import com.betterclouddrive.mobile.feature.organization.FileTagEditorState
import com.betterclouddrive.mobile.feature.organization.OrganizationFileListState
import com.betterclouddrive.mobile.feature.organization.TagMutationKind
import com.betterclouddrive.mobile.feature.organization.TagsState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class TeamFavoritesStore(
    private val repository: TeamOrganizationRepository,
    parentScope: CoroutineScope,
) : ResettableStore {
    private val scope = parentScope.storeChildScope()
    private val mutableState = MutableStateFlow(FavoritesState())
    val state: StateFlow<FavoritesState> = mutableState.asStateFlow()
    private var teamId: Long? = null
    private var listJob: Job? = null
    private var mutationJob: Job? = null
    private var feedbackSequence = 0L

    fun bind(teamId: Long) {
        if (this.teamId == teamId) return
        listJob?.cancel(); mutationJob?.cancel()
        this.teamId = teamId
        mutableState.value = FavoritesState()
    }

    fun activate(teamId: Long) {
        bind(teamId)
        if (!state.value.files.hasLoaded && !state.value.files.loading) refresh()
    }

    fun updateQuery(value: String) {
        listJob?.cancel()
        mutableState.update { it.copy(files = it.files.copy(query = value, error = null, loadMoreError = null)) }
        listJob = scope.launch { delay(DEBOUNCE_MILLIS); loadPage(value.trim(), 1, false) }
    }

    fun refresh() {
        listJob?.cancel()
        listJob = scope.launch { loadPage(state.value.files.query.trim(), 1, true) }
    }

    fun loadMore() {
        val current = state.value.files
        if (!current.hasMore || current.loading || current.loadingMore) return
        listJob?.cancel(); listJob = scope.launch { loadPage(current.submittedQuery, current.page + 1, false) }
    }

    fun updateScrollAnchor(fileId: Long?) = mutableState.update { it.copy(files = it.files.copy(scrollAnchorId = fileId)) }

    fun loadStatus(fileId: Long) {
        val boundTeam = teamId ?: return
        if (fileId in state.value.statusLoading || fileId in state.value.favoriteStatus) return
        scope.launch {
            mutableState.update { it.copy(statusLoading = it.statusLoading + fileId) }
            try {
                val favorite = repository.favoriteStatus(boundTeam, fileId)
                if (teamId == boundTeam) mutableState.update { it.copy(
                    favoriteStatus = it.favoriteStatus + (fileId to favorite), statusLoading = it.statusLoading - fileId,
                ) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Throwable) { if (teamId == boundTeam) mutableState.update { it.copy(statusLoading = it.statusLoading - fileId) } }
        }
    }

    fun loadStatus(file: FileItem) = loadStatus(file.id)

    fun toggle(file: FileItem) {
        val boundTeam = teamId ?: return
        if (state.value.mutatingFileId != null) return
        mutationJob?.cancel(); mutationJob = scope.launch {
            mutableState.update { it.copy(mutatingFileId = file.id, feedback = null) }
            try {
                val wasFavorite = state.value.favoriteStatus[file.id] ?: repository.favoriteStatus(boundTeam, file.id)
                if (wasFavorite) repository.removeFavorite(boundTeam, file.id) else repository.addFavorite(boundTeam, file.id)
                if (teamId == boundTeam) mutableState.update { current -> current.copy(
                    files = if (wasFavorite) current.files.copy(
                        items = current.files.items.filterNot { it.id == file.id },
                        total = (current.files.total - if (current.files.items.any { it.id == file.id }) 1 else 0).coerceAtLeast(0),
                    ) else current.files,
                    favoriteStatus = current.favoriteStatus + (file.id to !wasFavorite), mutatingFileId = null,
                    feedback = FileMutationFeedback(++feedbackSequence, UiMessage(
                        if (wasFavorite) MobileString.FavoriteRemoved else MobileString.FavoriteAdded,
                    ), false),
                ) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Throwable) { if (teamId == boundTeam) mutableState.update { it.copy(
                mutatingFileId = null, feedback = FileMutationFeedback(++feedbackSequence, uiError(error), true),
            ) } }
        }
    }

    fun consumeFeedback() = mutableState.update { it.copy(feedback = null) }
    fun invalidate(changedTeamId: Long) {
        if (teamId == changedTeamId) mutableState.update { it.copy(files = OrganizationFileListState(query = it.files.query)) }
    }
    override fun reset() {
        listJob?.cancel(); mutationJob?.cancel(); teamId = null; mutableState.value = FavoritesState()
    }

    private suspend fun loadPage(query: String, page: Int, refresh: Boolean) {
        val boundTeam = teamId ?: return
        val existing = state.value.files
        mutableState.update { it.copy(files = it.files.copy(
            submittedQuery = query, loading = page == 1 && !refresh,
            refreshing = page == 1 && refresh && it.files.hasLoaded, loadingMore = page > 1,
            error = if (page == 1) null else it.files.error, loadMoreError = null,
        )) }
        try {
            val result = repository.listFavorites(boundTeam, query.ifEmpty { null }, page, PAGE_SIZE)
            if (teamId != boundTeam || state.value.files.query.trim() != query || state.value.files.submittedQuery != query) return
            val merged = if (page == 1) result.records.distinctBy(FileItem::id)
            else (existing.items + result.records).distinctBy(FileItem::id)
            mutableState.update { it.copy(
                files = it.files.copy(
                    items = merged, loading = false, refreshing = false, loadingMore = false,
                    error = null, loadMoreError = null, page = result.page,
                    pages = result.pages.coerceAtLeast(result.page), total = result.total,
                    scrollAnchorId = if (page == 1) null else existing.scrollAnchorId,
                ), favoriteStatus = it.favoriteStatus + merged.associate { file -> file.id to true },
            ) }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Throwable) {
            if (teamId != boundTeam || state.value.files.query.trim() != query) return
            mutableState.update { it.copy(files = it.files.copy(
                loading = false, refreshing = false, loadingMore = false,
                error = if (page == 1) uiError(error) else it.files.error,
                loadMoreError = if (page > 1) uiError(error) else null,
            )) }
        }
    }

    private companion object { const val PAGE_SIZE = 50; const val DEBOUNCE_MILLIS = 300L }
}

class TeamTagsStore(
    private val repository: TeamOrganizationRepository,
    parentScope: CoroutineScope,
) : ResettableStore {
    private val scope = parentScope.storeChildScope()
    private val mutableState = MutableStateFlow(TagsState())
    val state: StateFlow<TagsState> = mutableState.asStateFlow()
    private val mutableEditor = MutableStateFlow<FileTagEditorState?>(null)
    val editor: StateFlow<FileTagEditorState?> = mutableEditor.asStateFlow()
    private val mutableCanManage = MutableStateFlow(false)
    val canManage: StateFlow<Boolean> = mutableCanManage.asStateFlow()
    private var teamId: Long? = null
    private var tagsJob: Job? = null
    private var filesJob: Job? = null
    private var mutationJob: Job? = null
    private var editorJob: Job? = null
    private var feedbackSequence = 0L

    fun bind(teamId: Long, role: TeamRole) {
        val changed = this.teamId != teamId
        if (changed) {
            tagsJob?.cancel(); filesJob?.cancel(); mutationJob?.cancel(); editorJob?.cancel()
            this.teamId = teamId; mutableState.value = TagsState(); mutableEditor.value = null
        }
        mutableCanManage.value = role == TeamRole.OWNER || role == TeamRole.ADMIN
    }

    fun activate(teamId: Long, role: TeamRole) {
        bind(teamId, role)
        if (state.value.tags.isEmpty() && !state.value.loading) refresh()
    }

    fun updateQuery(value: String) {
        tagsJob?.cancel(); mutableState.update { it.copy(query = value, error = null) }
        tagsJob = scope.launch { delay(DEBOUNCE_MILLIS); loadTags(value.trim()) }
    }
    fun refresh() { tagsJob?.cancel(); tagsJob = scope.launch { loadTags(state.value.query.trim()) } }

    fun create(name: String, color: String) {
        if (!canManage.value) return
        mutate(TagMutationKind.CREATE) {
            val boundTeam = teamId ?: return@mutate
            val created = repository.createTag(boundTeam, name, color)
            mutableState.update { it.copy(
                tags = (it.tags + created).distinctBy(PersonalTag::id).sortedBy { tag -> tag.tagName.lowercase() },
                mutation = null, mutationError = null,
                feedback = FileMutationFeedback(++feedbackSequence, UiMessage(MobileString.TagCreated), false),
            ) }
        }
    }

    fun update(tag: PersonalTag, name: String, color: String) {
        if (!canManage.value) return
        mutate(TagMutationKind.UPDATE) {
            val boundTeam = teamId ?: return@mutate
            val updated = repository.updateTag(boundTeam, tag.id, name, color)
            mutableState.update { it.copy(
                tags = it.tags.map { value -> if (value.id == updated.id) updated else value }
                    .sortedBy { value -> value.tagName.lowercase() },
                mutation = null, mutationError = null,
                feedback = FileMutationFeedback(++feedbackSequence, UiMessage(MobileString.TagUpdated), false),
            ) }
        }
    }

    fun delete(tag: PersonalTag) {
        if (!canManage.value) return
        mutate(TagMutationKind.DELETE) {
            val boundTeam = teamId ?: return@mutate
            repository.deleteTag(boundTeam, tag.id)
            mutableState.update { it.copy(
                tags = it.tags.filterNot { value -> value.id == tag.id },
                activeTagId = it.activeTagId.takeUnless { id -> id == tag.id },
                tagFiles = if (it.activeTagId == tag.id) OrganizationFileListState() else it.tagFiles,
                mutation = null, mutationError = null,
                feedback = FileMutationFeedback(++feedbackSequence, UiMessage(MobileString.TagDeleted), false),
            ) }
        }
    }

    fun activateFiles(tagId: Long) {
        if (state.value.activeTagId != tagId) {
            filesJob?.cancel(); mutableState.update { it.copy(activeTagId = tagId, tagFiles = OrganizationFileListState()) }
        }
        if (!state.value.tagFiles.hasLoaded && !state.value.tagFiles.loading) refreshFiles()
    }
    fun updateFilesQuery(value: String) {
        filesJob?.cancel(); mutableState.update { it.copy(tagFiles = it.tagFiles.copy(query = value, error = null, loadMoreError = null)) }
        filesJob = scope.launch { delay(DEBOUNCE_MILLIS); loadFilesPage(value.trim(), 1, false) }
    }
    fun refreshFiles() { filesJob?.cancel(); filesJob = scope.launch { loadFilesPage(state.value.tagFiles.query.trim(), 1, true) } }
    fun loadMoreFiles() {
        val current = state.value.tagFiles
        if (!current.hasMore || current.loading || current.loadingMore) return
        filesJob?.cancel(); filesJob = scope.launch { loadFilesPage(current.submittedQuery, current.page + 1, false) }
    }
    fun updateFilesScrollAnchor(fileId: Long?) = mutableState.update { it.copy(tagFiles = it.tagFiles.copy(scrollAnchorId = fileId)) }

    fun removeFile(file: FileItem) = mutate(TagMutationKind.REMOVE_FILE) {
        val boundTeam = teamId ?: return@mutate
        val tagId = state.value.activeTagId ?: return@mutate
        repository.removeFileTag(boundTeam, tagId, file.id)
        mutableState.update { it.copy(
            tagFiles = it.tagFiles.copy(
                items = it.tagFiles.items.filterNot { value -> value.id == file.id },
                total = (it.tagFiles.total - 1).coerceAtLeast(0),
            ), mutation = null,
            feedback = FileMutationFeedback(++feedbackSequence, UiMessage(MobileString.FileTagRemoved), false),
        ) }
    }

    fun openEditor(file: FileItem) {
        val boundTeam = teamId ?: return
        editorJob?.cancel(); mutableEditor.value = FileTagEditorState(file)
        editorJob = scope.launch {
            try {
                val (available, assigned) = coroutineScope {
                    val availableRequest = async { repository.listTags(boundTeam) }
                    val assignedRequest = async { repository.fileTags(boundTeam, file.id) }
                    availableRequest.await() to assignedRequest.await()
                }
                if (teamId == boundTeam) mutableEditor.update { current -> if (current?.file?.id != file.id) current else current.copy(
                    tags = available, selectedIds = assigned.map(PersonalTag::id).toSet(), loading = false,
                ) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Throwable) { if (teamId == boundTeam) mutableEditor.update { current -> current?.copy(
                loading = false, error = uiError(error),
            ) } }
        }
    }

    fun toggleEditorTag(tagId: Long) = mutableEditor.update { current -> current?.let {
        val selected = it.selectedIds.toMutableSet().apply { if (!add(tagId)) remove(tagId) }
        it.copy(selectedIds = selected, error = null)
    } }

    fun saveEditor() {
        val boundTeam = teamId ?: return
        val current = editor.value ?: return
        if (current.loading || current.saving) return
        editorJob?.cancel(); mutableEditor.update { it?.copy(saving = true, error = null) }
        editorJob = scope.launch {
            try {
                repository.replaceFileTags(boundTeam, current.file.id, current.selectedIds.toList())
                if (teamId == boundTeam) { mutableEditor.value = null; invalidateFiles(boundTeam) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Throwable) { if (teamId == boundTeam) mutableEditor.update { it?.copy(saving = false, error = uiError(error)) } }
        }
    }

    fun createInEditor(name: String, color: String) {
        val boundTeam = teamId ?: return
        if (!canManage.value || editor.value?.creating == true || editor.value?.saving == true) return
        editorJob?.cancel(); mutableEditor.update { it?.copy(creating = true, error = null) }
        editorJob = scope.launch {
            try {
                val created = repository.createTag(boundTeam, name, color)
                if (teamId == boundTeam) mutableEditor.update { value -> value?.copy(
                    tags = (value.tags + created).distinctBy(PersonalTag::id).sortedBy { it.tagName.lowercase() },
                    selectedIds = value.selectedIds + created.id, creating = false,
                ) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Throwable) { if (teamId == boundTeam) mutableEditor.update { it?.copy(creating = false, error = uiError(error)) } }
        }
    }

    fun closeEditor() { editorJob?.cancel(); editorJob = null; mutableEditor.value = null }
    fun consumeFeedback() = mutableState.update { it.copy(feedback = null, mutationError = null) }
    fun invalidateFiles(changedTeamId: Long) {
        if (teamId == changedTeamId) mutableState.update { it.copy(tagFiles = OrganizationFileListState(query = it.tagFiles.query)) }
    }
    override fun reset() {
        tagsJob?.cancel(); filesJob?.cancel(); mutationJob?.cancel(); editorJob?.cancel()
        teamId = null; mutableCanManage.value = false; mutableState.value = TagsState(); mutableEditor.value = null
    }

    private suspend fun loadTags(query: String) {
        val boundTeam = teamId ?: return
        mutableState.update { it.copy(submittedQuery = query, loading = true, error = null) }
        try {
            val result = repository.listTags(boundTeam, query.ifEmpty { null })
            if (teamId == boundTeam && state.value.query.trim() == query) mutableState.update {
                it.copy(tags = result, loading = false, error = null)
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Throwable) { if (teamId == boundTeam && state.value.query.trim() == query) mutableState.update {
            it.copy(loading = false, error = uiError(error))
        } }
    }

    private suspend fun loadFilesPage(query: String, page: Int, refresh: Boolean) {
        val boundTeam = teamId ?: return
        val tagId = state.value.activeTagId ?: return
        val existing = state.value.tagFiles
        mutableState.update { it.copy(tagFiles = it.tagFiles.copy(
            submittedQuery = query, loading = page == 1 && !refresh,
            refreshing = page == 1 && refresh && it.tagFiles.hasLoaded, loadingMore = page > 1,
            error = if (page == 1) null else it.tagFiles.error, loadMoreError = null,
        )) }
        try {
            val result = repository.filesByTag(boundTeam, tagId, query.ifEmpty { null }, page, PAGE_SIZE)
            val latest = state.value
            if (teamId != boundTeam || latest.activeTagId != tagId || latest.tagFiles.query.trim() != query ||
                latest.tagFiles.submittedQuery != query) return
            val merged = if (page == 1) result.records.distinctBy(FileItem::id)
            else (existing.items + result.records).distinctBy(FileItem::id)
            mutableState.update { it.copy(tagFiles = it.tagFiles.copy(
                items = merged, loading = false, refreshing = false, loadingMore = false,
                error = null, loadMoreError = null, page = result.page,
                pages = result.pages.coerceAtLeast(result.page), total = result.total,
                scrollAnchorId = if (page == 1) null else existing.scrollAnchorId,
            )) }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Throwable) {
            if (teamId != boundTeam || state.value.activeTagId != tagId) return
            mutableState.update { it.copy(tagFiles = it.tagFiles.copy(
                loading = false, refreshing = false, loadingMore = false,
                error = if (page == 1) uiError(error) else it.tagFiles.error,
                loadMoreError = if (page > 1) uiError(error) else null,
            )) }
        }
    }

    private fun mutate(kind: TagMutationKind, block: suspend () -> Unit) {
        if (state.value.mutation != null) return
        mutationJob?.cancel(); mutationJob = scope.launch {
            mutableState.update { it.copy(mutation = kind, mutationError = null, feedback = null) }
            try { block() } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Throwable) {
                val message = uiError(error)
                mutableState.update { it.copy(
                    mutation = null, mutationError = message,
                    feedback = FileMutationFeedback(++feedbackSequence, message, true),
                ) }
            }
        }
    }

    private companion object { const val PAGE_SIZE = 50; const val DEBOUNCE_MILLIS = 300L }
}
