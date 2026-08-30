package com.betterclouddrive.mobile.feature.organization

import com.betterclouddrive.mobile.app.FileMutationFeedback
import com.betterclouddrive.mobile.app.ResettableStore
import com.betterclouddrive.mobile.app.uiError
import com.betterclouddrive.mobile.app.storeChildScope
import com.betterclouddrive.mobile.core.localization.MobileString
import com.betterclouddrive.mobile.core.localization.UiMessage
import com.betterclouddrive.mobile.core.model.FileItem
import com.betterclouddrive.mobile.core.model.PersonalTag
import com.betterclouddrive.mobile.feature.files.FavoritesRepository
import com.betterclouddrive.mobile.feature.files.TagsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class OrganizationFileListState(
    val query: String = "",
    val submittedQuery: String = "",
    val items: List<FileItem> = emptyList(),
    val loading: Boolean = false,
    val refreshing: Boolean = false,
    val loadingMore: Boolean = false,
    val error: UiMessage? = null,
    val loadMoreError: UiMessage? = null,
    val page: Int = 0,
    val pages: Int = 1,
    val total: Long = 0,
    val scrollAnchorId: Long? = null,
) {
    val hasLoaded: Boolean get() = page > 0
    val hasMore: Boolean get() = page < pages
}

data class FavoritesState(
    val files: OrganizationFileListState = OrganizationFileListState(),
    val favoriteStatus: Map<Long, Boolean> = emptyMap(),
    val statusLoading: Set<Long> = emptySet(),
    val mutatingFileId: Long? = null,
    val feedback: FileMutationFeedback? = null,
)

class FavoritesStore(
    private val repository: FavoritesRepository,
    parentScope: CoroutineScope,
) : ResettableStore {
    private val scope = parentScope.storeChildScope()
    private val mutableState = MutableStateFlow(FavoritesState())
    val state: StateFlow<FavoritesState> = mutableState.asStateFlow()
    private var listJob: Job? = null
    private var mutationJob: Job? = null
    private var feedbackSequence = 0L

    fun activate() {
        val current = state.value.files
        if (!current.hasLoaded && !current.loading) refresh()
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
        listJob?.cancel()
        listJob = scope.launch { loadPage(current.submittedQuery, current.page + 1, false) }
    }

    fun updateScrollAnchor(fileId: Long?) = mutableState.update { it.copy(files = it.files.copy(scrollAnchorId = fileId)) }

    fun loadStatus(fileId: Long) {
        if (fileId in state.value.statusLoading) return
        scope.launch {
            mutableState.update { it.copy(statusLoading = it.statusLoading + fileId) }
            try {
                val favorite = repository.isPersonalFavorite(fileId)
                mutableState.update { it.copy(
                    favoriteStatus = it.favoriteStatus + (fileId to favorite), statusLoading = it.statusLoading - fileId,
                ) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                mutableState.update { it.copy(statusLoading = it.statusLoading - fileId) }
            }
        }
    }

    fun toggle(file: FileItem) {
        if (state.value.mutatingFileId != null) return
        mutationJob?.cancel()
        mutationJob = scope.launch {
            mutableState.update { it.copy(mutatingFileId = file.id, feedback = null) }
            try {
                val wasFavorite = state.value.favoriteStatus[file.id] ?: repository.isPersonalFavorite(file.id)
                if (wasFavorite) repository.removePersonalFavorite(file.id) else repository.addPersonalFavorite(file.id)
                mutableState.update { current ->
                    val listed = current.files.items.any { it.id == file.id }
                    current.copy(
                        files = if (wasFavorite && listed) current.files.copy(
                            items = current.files.items.filterNot { it.id == file.id },
                            total = (current.files.total - 1).coerceAtLeast(0),
                        ) else current.files,
                        favoriteStatus = current.favoriteStatus + (file.id to !wasFavorite),
                        mutatingFileId = null,
                        feedback = FileMutationFeedback(
                            ++feedbackSequence,
                            UiMessage(if (wasFavorite) MobileString.FavoriteRemoved else MobileString.FavoriteAdded),
                            false,
                        ),
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                mutableState.update { it.copy(
                    mutatingFileId = null, feedback = FileMutationFeedback(++feedbackSequence, uiError(error), true),
                ) }
            }
        }
    }

    fun consumeFeedback() = mutableState.update { it.copy(feedback = null) }

    fun invalidate() {
        val query = state.value.files.query
        mutableState.update { it.copy(files = OrganizationFileListState(query = query)) }
    }

    override fun reset() {
        listJob?.cancel(); mutationJob?.cancel()
        mutableState.value = FavoritesState()
    }

    private suspend fun loadPage(query: String, page: Int, refresh: Boolean) {
        val existing = state.value.files
        mutableState.update { it.copy(files = it.files.copy(
            submittedQuery = query, loading = page == 1 && !refresh,
            refreshing = page == 1 && refresh && it.files.hasLoaded, loadingMore = page > 1,
            error = if (page == 1) null else it.files.error, loadMoreError = null,
        )) }
        try {
            val result = repository.listPersonalFavorites(query.ifEmpty { null }, page, PAGE_SIZE)
            val latest = state.value.files
            if (latest.query.trim() != query || latest.submittedQuery != query) return
            val merged = if (page == 1) result.records.distinctBy(FileItem::id)
            else (existing.items + result.records).distinctBy(FileItem::id)
            mutableState.update { it.copy(
                files = it.files.copy(
                    items = merged, loading = false, refreshing = false, loadingMore = false,
                    error = null, loadMoreError = null, page = result.page,
                    pages = result.pages.coerceAtLeast(result.page), total = result.total,
                    scrollAnchorId = if (page == 1) null else existing.scrollAnchorId,
                ),
                favoriteStatus = it.favoriteStatus + merged.associate { file -> file.id to true },
            ) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            if (state.value.files.query.trim() != query) return
            mutableState.update { it.copy(files = it.files.copy(
                loading = false, refreshing = false, loadingMore = false,
                error = if (page == 1) uiError(error) else it.files.error,
                loadMoreError = if (page > 1) uiError(error) else null,
            )) }
        }
    }

    private companion object { const val PAGE_SIZE = 50; const val DEBOUNCE_MILLIS = 300L }
}

enum class TagMutationKind { CREATE, UPDATE, DELETE, ASSIGN, REMOVE_FILE }

data class TagsState(
    val query: String = "",
    val submittedQuery: String = "",
    val tags: List<PersonalTag> = emptyList(),
    val loading: Boolean = false,
    val error: UiMessage? = null,
    val mutation: TagMutationKind? = null,
    val mutationError: UiMessage? = null,
    val feedback: FileMutationFeedback? = null,
    val activeTagId: Long? = null,
    val tagFiles: OrganizationFileListState = OrganizationFileListState(),
)

data class FileTagEditorState(
    val file: FileItem,
    val tags: List<PersonalTag> = emptyList(),
    val selectedIds: Set<Long> = emptySet(),
    val loading: Boolean = true,
    val saving: Boolean = false,
    val creating: Boolean = false,
    val error: UiMessage? = null,
)

class TagsStore(
    private val repository: TagsRepository,
    parentScope: CoroutineScope,
) : ResettableStore {
    private val scope = parentScope.storeChildScope()
    private val mutableState = MutableStateFlow(TagsState())
    val state: StateFlow<TagsState> = mutableState.asStateFlow()
    private val mutableEditor = MutableStateFlow<FileTagEditorState?>(null)
    val editor: StateFlow<FileTagEditorState?> = mutableEditor.asStateFlow()
    private var tagsJob: Job? = null
    private var filesJob: Job? = null
    private var mutationJob: Job? = null
    private var editorJob: Job? = null
    private var feedbackSequence = 0L

    fun activate() { if (state.value.tags.isEmpty() && !state.value.loading) refresh() }
    fun updateQuery(value: String) {
        tagsJob?.cancel(); mutableState.update { it.copy(query = value, error = null) }
        tagsJob = scope.launch { delay(DEBOUNCE_MILLIS); loadTags(value.trim()) }
    }
    fun refresh() { tagsJob?.cancel(); tagsJob = scope.launch { loadTags(state.value.query.trim()) } }

    fun create(name: String, color: String) = mutate(TagMutationKind.CREATE) {
        val created = repository.createPersonalTag(name, color)
        mutableState.update { it.copy(
            tags = (it.tags + created).distinctBy(PersonalTag::id).sortedBy { tag -> tag.tagName.lowercase() },
            mutation = null, mutationError = null,
            feedback = FileMutationFeedback(++feedbackSequence, UiMessage(MobileString.TagCreated), false),
        ) }
    }

    fun update(tag: PersonalTag, name: String, color: String) = mutate(TagMutationKind.UPDATE) {
        val updated = repository.updatePersonalTag(tag.id, name, color)
        mutableState.update { it.copy(
            tags = it.tags.map { value -> if (value.id == updated.id) updated else value }.sortedBy { value -> value.tagName.lowercase() },
            mutation = null, mutationError = null,
            feedback = FileMutationFeedback(++feedbackSequence, UiMessage(MobileString.TagUpdated), false),
        ) }
    }

    fun delete(tag: PersonalTag) = mutate(TagMutationKind.DELETE) {
        repository.deletePersonalTag(tag.id)
        mutableState.update { it.copy(
            tags = it.tags.filterNot { value -> value.id == tag.id },
            activeTagId = it.activeTagId.takeUnless { id -> id == tag.id },
            tagFiles = if (it.activeTagId == tag.id) OrganizationFileListState() else it.tagFiles,
            mutation = null, mutationError = null,
            feedback = FileMutationFeedback(++feedbackSequence, UiMessage(MobileString.TagDeleted), false),
        ) }
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
        val tagId = state.value.activeTagId ?: return@mutate
        repository.removePersonalFileTag(tagId, file.id)
        mutableState.update { it.copy(
            tags = it.tags.map { tag -> if (tag.id == tagId) tag.copy(fileCount = (tag.fileCount - 1).coerceAtLeast(0)) else tag },
            tagFiles = it.tagFiles.copy(
                items = it.tagFiles.items.filterNot { value -> value.id == file.id },
                total = (it.tagFiles.total - 1).coerceAtLeast(0),
            ),
            mutation = null,
            feedback = FileMutationFeedback(++feedbackSequence, UiMessage(MobileString.FileTagRemoved), false),
        ) }
    }

    fun openEditor(file: FileItem) {
        editorJob?.cancel(); mutableEditor.value = FileTagEditorState(file)
        editorJob = scope.launch {
            try {
                val available = repository.listPersonalTags()
                val assigned = repository.listPersonalFileTags(file.id)
                mutableEditor.update { current -> if (current?.file?.id != file.id) current else current.copy(
                    tags = available, selectedIds = assigned.map(PersonalTag::id).toSet(), loading = false,
                ) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                mutableEditor.update { current -> if (current?.file?.id != file.id) current else current.copy(
                    loading = false, error = uiError(error),
                ) }
            }
        }
    }

    fun toggleEditorTag(tagId: Long) = mutableEditor.update { current -> current?.let {
        val selected = it.selectedIds.toMutableSet().apply { if (!add(tagId)) remove(tagId) }
        it.copy(selectedIds = selected, error = null)
    } }

    fun saveEditor() {
        val current = editor.value ?: return
        if (current.loading || current.saving) return
        editorJob?.cancel(); mutableEditor.update { it?.copy(saving = true, error = null) }
        editorJob = scope.launch {
            try {
                repository.replacePersonalFileTags(current.file.id, current.selectedIds.toList())
                mutableEditor.value = null
                mutableState.update { it.copy(tags = emptyList(), tagFiles = OrganizationFileListState()) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                mutableEditor.update { it?.copy(saving = false, error = uiError(error)) }
            }
        }
    }

    fun createInEditor(name: String, color: String) {
        val current = editor.value ?: return
        if (current.creating || current.saving) return
        editorJob?.cancel(); mutableEditor.update { it?.copy(creating = true, error = null) }
        editorJob = scope.launch {
            try {
                val created = repository.createPersonalTag(name, color)
                mutableEditor.update { value -> value?.copy(
                    tags = (value.tags + created).distinctBy(PersonalTag::id).sortedBy { it.tagName.lowercase() },
                    selectedIds = value.selectedIds + created.id, creating = false,
                ) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                mutableEditor.update { it?.copy(creating = false, error = uiError(error)) }
            }
        }
    }

    fun closeEditor() { editorJob?.cancel(); editorJob = null; mutableEditor.value = null }
    fun consumeFeedback() = mutableState.update { it.copy(feedback = null, mutationError = null) }
    fun invalidateFiles() = mutableState.update { it.copy(tagFiles = OrganizationFileListState(query = it.tagFiles.query)) }

    override fun reset() {
        tagsJob?.cancel(); filesJob?.cancel(); mutationJob?.cancel(); editorJob?.cancel()
        mutableState.value = TagsState(); mutableEditor.value = null
    }

    private suspend fun loadTags(query: String) {
        mutableState.update { it.copy(submittedQuery = query, loading = true, error = null) }
        try {
            val result = repository.listPersonalTags(query.ifEmpty { null })
            if (state.value.query.trim() == query) mutableState.update { it.copy(tags = result, loading = false, error = null) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            if (state.value.query.trim() == query) mutableState.update { it.copy(loading = false, error = uiError(error)) }
        }
    }

    private suspend fun loadFilesPage(query: String, page: Int, refresh: Boolean) {
        val tagId = state.value.activeTagId ?: return
        val existing = state.value.tagFiles
        mutableState.update { it.copy(tagFiles = it.tagFiles.copy(
            submittedQuery = query, loading = page == 1 && !refresh,
            refreshing = page == 1 && refresh && it.tagFiles.hasLoaded, loadingMore = page > 1,
            error = if (page == 1) null else it.tagFiles.error, loadMoreError = null,
        )) }
        try {
            val result = repository.listPersonalFilesByTag(tagId, query.ifEmpty { null }, page, PAGE_SIZE)
            val latest = state.value
            if (latest.activeTagId != tagId || latest.tagFiles.query.trim() != query || latest.tagFiles.submittedQuery != query) return
            val merged = if (page == 1) result.records.distinctBy(FileItem::id)
            else (existing.items + result.records).distinctBy(FileItem::id)
            mutableState.update { it.copy(tagFiles = it.tagFiles.copy(
                items = merged, loading = false, refreshing = false, loadingMore = false,
                error = null, loadMoreError = null, page = result.page,
                pages = result.pages.coerceAtLeast(result.page), total = result.total,
                scrollAnchorId = if (page == 1) null else existing.scrollAnchorId,
            )) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            if (state.value.activeTagId != tagId) return
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
            try { block() } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
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
