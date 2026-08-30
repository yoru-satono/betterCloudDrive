package com.betterclouddrive.mobile.feature.files

import com.betterclouddrive.mobile.app.FileMutationFeedback
import com.betterclouddrive.mobile.app.BrowserSessionPort
import com.betterclouddrive.mobile.app.storeChildScope
import com.betterclouddrive.mobile.app.ResettableStore
import com.betterclouddrive.mobile.app.TransferEnqueueFeedback
import com.betterclouddrive.mobile.app.uiError
import com.betterclouddrive.mobile.core.localization.MobileString
import com.betterclouddrive.mobile.core.localization.UiMessage
import com.betterclouddrive.mobile.core.model.FileItem
import com.betterclouddrive.mobile.core.model.PageResult
import com.betterclouddrive.mobile.core.platform.UploadPickResult
import com.betterclouddrive.mobile.feature.transfers.PersonalTransferOperations
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class DirectoryPageState(
    val folderId: Long? = null,
    val items: List<FileItem> = emptyList(),
    val hasLoaded: Boolean = false,
    val initialLoading: Boolean = false,
    val refreshing: Boolean = false,
    val loadingMore: Boolean = false,
    val error: UiMessage? = null,
    val loadMoreError: UiMessage? = null,
    val page: Int = 0,
    val pages: Int = 1,
    val total: Long = 0,
    val scrollAnchorId: Long? = null,
) { val hasMore: Boolean get() = hasLoaded && page < pages }

enum class FileMutationKind { CREATE_FOLDER, RENAME, MOVE, COPY, DELETE }

data class FolderPickerState(
    val kind: FileMutationKind,
    val source: FileItem,
    val breadcrumb: List<BreadcrumbEntry> = listOf(BreadcrumbEntry(null, "")),
    val currentFolderId: Long? = null,
    val folders: List<FileItem> = emptyList(),
    val loading: Boolean = false,
    val error: UiMessage? = null,
) { val canConfirm: Boolean get() = kind == FileMutationKind.COPY || currentFolderId != source.parentId }

data class PersonalFilesState(
    val breadcrumb: List<BreadcrumbEntry> = listOf(BreadcrumbEntry(null, "")),
    val current: DirectoryPageState = DirectoryPageState(),
    val sort: PersonalFileSort = PersonalFileSort.NAME,
    val direction: SortDirection = SortDirection.ASCENDING,
    val viewMode: FileViewMode = FileViewMode.LIST,
    val selectedFile: FileItem? = null,
    val selectedIds: Set<Long> = emptySet(),
    val mutation: FileMutationKind? = null,
    val mutationError: UiMessage? = null,
    val mutationErrorKind: FileMutationKind? = null,
    val feedback: FileMutationFeedback? = null,
    val folderPicker: FolderPickerState? = null,
    val transferFeedback: TransferEnqueueFeedback? = null,
)

class PersonalBrowserStore(
    private val repository: PersonalFilesRepository,
    private val transfers: PersonalTransferOperations,
    parentScope: CoroutineScope,
    private val onContentChanged: () -> Unit = {},
) : ResettableStore, BrowserSessionPort {
    private val scope = parentScope.storeChildScope()
    private val mutableState = MutableStateFlow(PersonalFilesState())
    val state: StateFlow<PersonalFilesState> = mutableState.asStateFlow()
    private val cache = mutableMapOf<Long?, DirectoryPageState>()
    private var directoryJob: Job? = null
    private var detailJob: Job? = null
    private var mutationJob: Job? = null
    private var pickerJob: Job? = null
    private var uploadRefreshJob: Job? = null
    private var feedbackSequence = 0L

    init {
        scope.launch {
            transfers.uploadFoldersSettled.collect { folderId ->
                cache.remove(folderId)
                if (state.value.current.folderId == folderId) {
                    uploadRefreshJob?.cancel()
                    uploadRefreshJob = scope.launch {
                        try { reloadLoadedPages() } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (error: Throwable) {
                            updateCurrent(folderId) { it.copy(error = uiError(error)) }
                        }
                    }
                }
            }
        }
    }

    fun activate(folderId: Long?) {
        val personal = state.value
        if (personal.current.folderId == folderId && (personal.current.hasLoaded || personal.current.initialLoading)) return
        directoryJob?.cancel()
        if (folderId == null) {
            val path = listOf(BreadcrumbEntry(null, "")); retainPathCache(path); showDirectory(null, path); loadIfNeeded(null); return
        }
        val existingIndex = personal.breadcrumb.indexOfFirst { it.id == folderId }
        if (existingIndex >= 0) {
            val path = personal.breadcrumb.take(existingIndex + 1); retainPathCache(path)
            showDirectory(folderId, path); loadIfNeeded(folderId); return
        }
        showDirectory(folderId, listOf(BreadcrumbEntry(null, ""), BreadcrumbEntry(folderId, "")),
            DirectoryPageState(folderId = folderId, initialLoading = true))
        directoryJob = scope.launch {
            try {
                val path = rebuildPath(folderId)
                if (state.value.current.folderId != folderId) return@launch
                retainPathCache(path); mutableState.update { it.copy(breadcrumb = path, selectedFile = null) }
                loadDirectoryPage(folderId, 1, false)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Throwable) { updateCurrent(folderId) { it.copy(initialLoading = false, error = uiError(error)) } }
        }
    }

    fun openFolder(folder: FileItem) {
        if (!folder.isFolder) return
        val path = state.value.breadcrumb + BreadcrumbEntry(folder.id, folder.fileName)
        retainPathCache(path); showDirectory(folder.id, path); loadIfNeeded(folder.id)
    }

    fun refresh() { clearSelection(); directoryJob?.cancel(); directoryJob = scope.launch { loadDirectoryPage(state.value.current.folderId, 1, true) } }
    fun loadMore() {
        val current = state.value.current
        if (!current.hasMore || current.loadingMore || current.initialLoading || current.refreshing) return
        directoryJob?.cancel(); directoryJob = scope.launch { loadDirectoryPage(current.folderId, current.page + 1, false) }
    }

    fun changeSort(sort: PersonalFileSort, direction: SortDirection) {
        val previous = state.value
        if (previous.sort == sort && previous.direction == direction) return
        val previousCache = cache.toMap(); val folderId = previous.current.folderId
        directoryJob?.cancel(); mutableState.value = previous.copy(
            sort = sort, direction = direction,
            current = previous.current.copy(refreshing = previous.current.hasLoaded, error = null),
            selectedFile = null, selectedIds = emptySet(),
        )
        directoryJob = scope.launch {
            try {
                val result = repository.listPersonalFiles(folderId, 1, PAGE_SIZE, sort.apiValue, direction.apiValue)
                val latest = state.value
                if (latest.current.folderId != folderId || latest.sort != sort || latest.direction != direction) return@launch
                cache.clear(); val snapshot = result.toSnapshot(folderId); cache[folderId] = snapshot
                mutableState.update { it.copy(current = snapshot, selectedFile = null) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Throwable) {
                cache.clear(); cache.putAll(previousCache); mutableState.value = previous.copy(current = previous.current.copy(error = uiError(error)))
            }
        }
    }

    fun changeViewMode(mode: FileViewMode) = mutableState.update { it.copy(viewMode = mode) }
    fun toggleSelection(fileId: Long) {
        if (state.value.current.items.none { it.id == fileId }) return
        val selected = state.value.selectedIds.toMutableSet().apply { if (!add(fileId)) remove(fileId) }
        mutableState.update { it.copy(selectedIds = selected, selectedFile = null) }
    }
    fun clearSelection() = mutableState.update { it.copy(selectedIds = emptySet()) }
    fun clearTransientUi() { pickerJob?.cancel(); mutableState.update { it.copy(
        selectedIds = emptySet(), mutationError = null, mutationErrorKind = null, folderPicker = null,
    ) } }

    fun createFolder(name: String) = launchMutation(FileMutationKind.CREATE_FOLDER) {
        repository.createPersonalFolder(state.value.current.folderId, name); MobileString.FolderCreated
    }
    fun rename(file: FileItem, name: String) = launchMutation(FileMutationKind.RENAME) {
        repository.renamePersonalFile(file.id, name); MobileString.FileRenamed
    }
    fun delete(fileIds: Set<Long>) {
        if (fileIds.isEmpty()) return
        launchMutation(FileMutationKind.DELETE) { repository.deletePersonalFiles(fileIds.toList()); MobileString.FilesMovedToTrash }
    }

    fun openFolderPicker(file: FileItem, kind: FileMutationKind) {
        if (kind != FileMutationKind.MOVE && kind != FileMutationKind.COPY) return
        pickerJob?.cancel(); mutableState.update { it.copy(
            mutationError = null, mutationErrorKind = null,
            folderPicker = FolderPickerState(kind = kind, source = file, loading = true),
        ) }; loadPickerFolder(null, listOf(BreadcrumbEntry(null, "")))
    }
    fun openPickerFolder(folder: FileItem) {
        if (!folder.isFolder) return
        val picker = state.value.folderPicker ?: return
        loadPickerFolder(folder.id, picker.breadcrumb + BreadcrumbEntry(folder.id, folder.fileName))
    }
    fun pickerBack() {
        val picker = state.value.folderPicker ?: return
        if (picker.breadcrumb.size <= 1) return
        val path = picker.breadcrumb.dropLast(1); loadPickerFolder(path.last().id, path)
    }
    fun retryFolderPicker() { val picker = state.value.folderPicker ?: return; loadPickerFolder(picker.currentFolderId, picker.breadcrumb) }
    fun closeFolderPicker() { pickerJob?.cancel(); mutableState.update { it.copy(
        folderPicker = null, mutationError = null, mutationErrorKind = null,
    ) } }
    fun confirmFolderPicker() {
        val picker = state.value.folderPicker ?: return
        if (!picker.canConfirm) return
        val target = picker.currentFolderId
        launchMutation(picker.kind) {
            when (picker.kind) {
                FileMutationKind.MOVE -> repository.movePersonalFile(picker.source.id, target)
                FileMutationKind.COPY -> repository.copyPersonalFile(picker.source.id, target)
                else -> error("unsupported file destination operation")
            }
            if (target != state.value.current.folderId) cache.remove(target)
            if (picker.kind == FileMutationKind.MOVE) MobileString.FileMoved else MobileString.FileCopied
        }
    }

    fun consumeFeedback() = mutableState.update { it.copy(feedback = null) }
    fun selectFile(file: FileItem?) {
        detailJob?.cancel(); mutableState.update { it.copy(selectedFile = file) }
        if (file == null || file.isFolder) return
        detailJob = scope.launch {
            try {
                val metadata = repository.getPersonalFile(file.id)
                mutableState.update { if (it.selectedFile?.id == file.id) it.copy(selectedFile = metadata) else it }
            } catch (cancelled: CancellationException) { throw cancelled } catch (_: Throwable) { }
        }
    }
    fun updateScrollAnchor(fileId: Long?) { updateCurrent(state.value.current.folderId) { it.copy(scrollAnchorId = fileId) } }
    fun prepareOpenLocation(file: FileItem) {
        val parentId = file.parentId
        cache[parentId]?.let { snapshot -> if (snapshot.items.any { it.id == file.id }) cache[parentId] = snapshot.copy(scrollAnchorId = file.id) }
    }

    fun upload(parentId: Long?, result: UploadPickResult) = scope.launch {
        val outcome = try { transfers.enqueueUploads(result, parentId) } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            mutableState.update { it.copy(transferFeedback = TransferEnqueueFeedback(++feedbackSequence, uiError(error), false)) }
            return@launch
        }
        val message = if (outcome.tasks.isEmpty()) UiMessage(
            MobileString.UploadNothingQueued, listOf(outcome.rejectedCount, outcome.overflowCount),
        ) else UiMessage(MobileString.UploadQueuedSummary, listOf(outcome.tasks.size, outcome.rejectedCount, outcome.overflowCount))
        mutableState.update { it.copy(transferFeedback = TransferEnqueueFeedback(
            ++feedbackSequence, message, outcome.tasks.isNotEmpty(),
        )) }
    }
    fun consumeTransferFeedback() = mutableState.update { it.copy(transferFeedback = null) }
    fun download(file: FileItem, destinationHandle: String) = scope.launch {
        try { transfers.enqueueDownload(
            file.id, file.fileName, file.fileSize, file.mimeType, destinationHandle, file.md5Hash,
        ) } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Throwable) { updateCurrent(state.value.current.folderId) { it.copy(error = uiError(error)) } }
    }

    override fun loadRoot() { activate(null) }

    override fun reset() {
        directoryJob?.cancel(); detailJob?.cancel(); mutationJob?.cancel(); pickerJob?.cancel(); uploadRefreshJob?.cancel()
        cache.clear(); mutableState.value = PersonalFilesState()
    }

    private fun loadIfNeeded(folderId: Long?) { val current = state.value.current; if (!current.hasLoaded && !current.initialLoading) {
        directoryJob?.cancel(); directoryJob = scope.launch { loadDirectoryPage(folderId, 1, false) }
    } }

    private suspend fun loadDirectoryPage(folderId: Long?, page: Int, refresh: Boolean) {
        val personal = state.value; if (personal.current.folderId != folderId) return
        val existing = personal.current
        updateCurrent(folderId) { when {
            page > 1 -> it.copy(loadingMore = true, loadMoreError = null)
            refresh && it.hasLoaded -> it.copy(refreshing = true, error = null)
            else -> it.copy(initialLoading = true, error = null)
        } }
        try {
            val result = repository.listPersonalFiles(folderId, page, PAGE_SIZE, personal.sort.apiValue, personal.direction.apiValue)
            val latest = state.value
            if (latest.current.folderId != folderId || latest.sort != personal.sort || latest.direction != personal.direction) return
            val merged = if (page == 1) result.records.distinctBy(FileItem::id) else (existing.items + result.records).distinctBy(FileItem::id)
            val snapshot = DirectoryPageState(
                folderId = folderId, items = merged, hasLoaded = true, page = result.page,
                pages = result.pages.coerceAtLeast(result.page), total = result.total,
                scrollAnchorId = if (page == 1) null else existing.scrollAnchorId,
            )
            cache[folderId] = snapshot; mutableState.update { it.copy(current = snapshot) }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Throwable) { updateCurrent(folderId) { when {
            page > 1 -> it.copy(loadingMore = false, loadMoreError = uiError(error))
            refresh && it.hasLoaded -> it.copy(refreshing = false, error = uiError(error))
            else -> it.copy(initialLoading = false, error = uiError(error))
        } } }
    }

    private suspend fun rebuildPath(folderId: Long): List<BreadcrumbEntry> {
        val seen = mutableSetOf<Long>(); val reversed = mutableListOf<BreadcrumbEntry>(); var currentId: Long? = folderId
        while (currentId != null) {
            if (!seen.add(currentId) || seen.size > MAX_BREADCRUMB_DEPTH) throw IllegalArgumentException("invalid folder path")
            val item = repository.getPersonalFile(currentId)
            if (!item.isFolder) throw IllegalArgumentException("target is not a folder")
            reversed += BreadcrumbEntry(item.id, item.fileName); currentId = item.parentId
        }
        return listOf(BreadcrumbEntry(null, "")) + reversed.asReversed()
    }

    private fun showDirectory(folderId: Long?, path: List<BreadcrumbEntry>, fallback: DirectoryPageState = DirectoryPageState(folderId = folderId)) {
        mutableState.update { it.copy(
            breadcrumb = path, current = cache[folderId] ?: fallback, selectedFile = null, selectedIds = emptySet(),
            mutationError = null, mutationErrorKind = null, folderPicker = null,
        ) }
    }
    private fun updateCurrent(folderId: Long?, transform: (DirectoryPageState) -> DirectoryPageState) {
        if (state.value.current.folderId != folderId) return
        val updated = transform(state.value.current); cache[folderId] = updated; mutableState.update { it.copy(current = updated) }
    }
    private fun retainPathCache(path: List<BreadcrumbEntry>) { cache.keys.retainAll(path.map(BreadcrumbEntry::id).toSet()) }
    private fun PageResult<FileItem>.toSnapshot(folderId: Long?) = DirectoryPageState(
        folderId = folderId, items = records.distinctBy(FileItem::id), hasLoaded = true,
        page = page, pages = pages.coerceAtLeast(page), total = total,
    )

    private fun launchMutation(kind: FileMutationKind, block: suspend () -> MobileString) {
        if (state.value.mutation != null) return
        mutationJob = scope.launch {
            mutableState.update { it.copy(mutation = kind, mutationError = null, mutationErrorKind = null) }
            try {
                val success = block(); onContentChanged()
                val refreshError = try { reloadLoadedPages(); null } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Throwable) { uiError(error) }
                mutableState.update { it.copy(
                    mutation = null, mutationError = null, mutationErrorKind = null, selectedIds = emptySet(),
                    selectedFile = null, folderPicker = null,
                    feedback = FileMutationFeedback(++feedbackSequence, UiMessage(success), false),
                    current = if (refreshError == null) it.current else it.current.copy(error = refreshError),
                ) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Throwable) {
                val message = uiError(error); mutableState.update { it.copy(
                    mutation = null, mutationError = message, mutationErrorKind = kind,
                    feedback = FileMutationFeedback(++feedbackSequence, message, true),
                ) }
            }
        }
    }

    private suspend fun reloadLoadedPages() {
        val personal = state.value; val folderId = personal.current.folderId; val pageCount = personal.current.page.coerceAtLeast(1)
        val accumulated = mutableListOf<FileItem>(); var last: PageResult<FileItem>? = null
        for (page in 1..pageCount) {
            last = repository.listPersonalFiles(folderId, page, PAGE_SIZE, personal.sort.apiValue, personal.direction.apiValue)
            accumulated += last.records; if (page >= last.pages) break
        }
        val result = checkNotNull(last); val snapshot = DirectoryPageState(
            folderId = folderId, items = accumulated.distinctBy(FileItem::id), hasLoaded = true,
            page = minOf(pageCount, result.pages.coerceAtLeast(1)), pages = result.pages.coerceAtLeast(1),
            total = result.total, scrollAnchorId = personal.current.scrollAnchorId,
        )
        cache[folderId] = snapshot; mutableState.update { it.copy(current = snapshot) }
    }

    private fun loadPickerFolder(folderId: Long?, breadcrumb: List<BreadcrumbEntry>) {
        pickerJob?.cancel(); pickerJob = scope.launch {
            val original = state.value.folderPicker ?: return@launch
            mutableState.update { it.copy(folderPicker = original.copy(
                breadcrumb = breadcrumb, currentFolderId = folderId, loading = true, error = null, folders = emptyList(),
            )) }
            try {
                val folders = mutableListOf<FileItem>(); var page = 1
                while (true) {
                    val result = repository.listPersonalFiles(folderId, page, PICKER_PAGE_SIZE, "fileName", "asc")
                    val firstFile = result.records.indexOfFirst { !it.isFolder }
                    folders += (if (firstFile >= 0) result.records.take(firstFile) else result.records).filterNot { it.id == original.source.id }
                    if (firstFile >= 0 || page >= result.pages) break; page++
                }
                mutableState.update { current -> val picker = current.folderPicker
                    if (picker?.source?.id != original.source.id || picker.currentFolderId != folderId) current
                    else current.copy(folderPicker = picker.copy(folders = folders.distinctBy(FileItem::id), loading = false))
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Throwable) { mutableState.update { current -> val picker = current.folderPicker
                if (picker?.source?.id != original.source.id || picker.currentFolderId != folderId) current
                else current.copy(folderPicker = picker.copy(loading = false, error = uiError(error)))
            } }
        }
    }

    private companion object { const val PAGE_SIZE = 50; const val PICKER_PAGE_SIZE = 100; const val MAX_BREADCRUMB_DEPTH = 128 }
}
