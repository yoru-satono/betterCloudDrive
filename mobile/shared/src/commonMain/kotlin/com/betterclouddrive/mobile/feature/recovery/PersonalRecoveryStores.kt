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
import com.betterclouddrive.mobile.feature.files.PersonalVersionsRepository
import com.betterclouddrive.mobile.feature.files.RecycleBinRepository
import com.betterclouddrive.mobile.feature.transfers.VersionTransferOperations
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class RecycleBinState(
    val query: String = "",
    val submittedQuery: String = "",
    val items: List<RecycleBinItem> = emptyList(),
    val loading: Boolean = false,
    val refreshing: Boolean = false,
    val loadingMore: Boolean = false,
    val error: UiMessage? = null,
    val loadMoreError: UiMessage? = null,
    val page: Int = 0,
    val pages: Int = 1,
    val total: Long = 0,
    val mutatingFileId: Long? = null,
    val emptying: Boolean = false,
    val feedback: FileMutationFeedback? = null,
) {
    val hasLoaded get() = page > 0
    val hasMore get() = page < pages
}

class PersonalRecycleBinStore(
    private val repository: RecycleBinRepository,
    parentScope: CoroutineScope,
    private val onContentChanged: () -> Unit = {},
) : ResettableStore {
    private val scope = parentScope.storeChildScope()
    private val mutableState = MutableStateFlow(RecycleBinState())
    val state: StateFlow<RecycleBinState> = mutableState.asStateFlow()
    private var listJob: Job? = null
    private var mutationJob: Job? = null
    private var sequence = 0L

    fun activate() { if (!state.value.hasLoaded && !state.value.loading) refresh() }

    fun updateQuery(value: String) {
        listJob?.cancel()
        mutableState.update { it.copy(query = value, error = null, loadMoreError = null) }
        listJob = scope.launch { delay(300); loadPage(value.trim(), 1, false) }
    }

    fun refresh() {
        listJob?.cancel()
        listJob = scope.launch { loadPage(state.value.query.trim(), 1, true) }
    }

    fun loadMore() {
        val current = state.value
        if (!current.hasMore || current.loading || current.loadingMore) return
        listJob?.cancel()
        listJob = scope.launch { loadPage(current.submittedQuery, current.page + 1, false) }
    }

    fun restore(fileId: Long) = mutate(fileId, MobileString.RecycleRestored) { repository.restore(fileId) }
    fun permanentlyDelete(fileId: Long) = mutate(fileId, MobileString.RecyclePermanentlyDeleted) {
        repository.permanentlyDelete(fileId)
    }

    fun empty() {
        if (state.value.emptying || state.value.mutatingFileId != null) return
        mutationJob?.cancel()
        mutationJob = scope.launch {
            mutableState.update { it.copy(emptying = true, feedback = null) }
            try {
                repository.empty(); onContentChanged()
                mutableState.update { it.copy(
                    items = emptyList(), total = 0, page = 1, pages = 1, emptying = false,
                    feedback = FileMutationFeedback(++sequence, UiMessage(MobileString.RecycleEmptied), false),
                ) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Throwable) { mutableState.update { it.copy(
                emptying = false, feedback = FileMutationFeedback(++sequence, uiError(error), true),
            ) } }
        }
    }

    fun consumeFeedback() = mutableState.update { it.copy(feedback = null) }

    private fun mutate(fileId: Long, success: MobileString, action: suspend () -> Unit) {
        if (state.value.mutatingFileId != null || state.value.emptying) return
        mutationJob?.cancel()
        mutationJob = scope.launch {
            mutableState.update { it.copy(mutatingFileId = fileId, feedback = null) }
            try {
                action(); onContentChanged()
                mutableState.update { current -> current.copy(
                    items = current.items.filterNot { it.id == fileId },
                    total = (current.total - 1).coerceAtLeast(0), mutatingFileId = null,
                    feedback = FileMutationFeedback(++sequence, UiMessage(success), false),
                ) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Throwable) { mutableState.update { it.copy(
                mutatingFileId = null, feedback = FileMutationFeedback(++sequence, uiError(error), true),
            ) } }
        }
    }

    private suspend fun loadPage(query: String, page: Int, refresh: Boolean) {
        val existing = state.value
        mutableState.update { it.copy(
            submittedQuery = query, loading = page == 1 && !refresh,
            refreshing = page == 1 && refresh && it.hasLoaded, loadingMore = page > 1,
            error = if (page == 1) null else it.error, loadMoreError = null,
        ) }
        try {
            val result = repository.list(query.ifEmpty { null }, page, 50)
            if (state.value.query.trim() != query || state.value.submittedQuery != query) return
            val merged = if (page == 1) result.records.distinctBy(RecycleBinItem::id)
            else (existing.items + result.records).distinctBy(RecycleBinItem::id)
            mutableState.update { it.copy(
                items = merged, loading = false, refreshing = false, loadingMore = false,
                error = null, loadMoreError = null, page = result.page,
                pages = result.pages.coerceAtLeast(result.page), total = result.total,
            ) }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Throwable) { if (state.value.query.trim() == query) mutableState.update { it.copy(
            loading = false, refreshing = false, loadingMore = false,
            error = if (page == 1) uiError(error) else it.error,
            loadMoreError = if (page > 1) uiError(error) else null,
        ) } }
    }

    override fun reset() {
        listJob?.cancel(); mutationJob?.cancel(); mutableState.value = RecycleBinState()
    }
}

data class PersonalVersionsState(
    val fileId: Long? = null,
    val file: FileItem? = null,
    val versions: List<PersonalFileVersion> = emptyList(),
    val loading: Boolean = false,
    val error: UiMessage? = null,
    val restoringVersion: Int? = null,
    val deletingVersion: Int? = null,
    val downloadingVersion: Int? = null,
    val feedback: FileMutationFeedback? = null,
    val transferFeedback: TransferEnqueueFeedback? = null,
)

class PersonalVersionsStore(
    private val repository: PersonalVersionsRepository,
    private val transfers: VersionTransferOperations,
    parentScope: CoroutineScope,
    private val onContentChanged: () -> Unit = {},
) : ResettableStore {
    private val scope = parentScope.storeChildScope()
    private val mutableState = MutableStateFlow(PersonalVersionsState())
    val state: StateFlow<PersonalVersionsState> = mutableState.asStateFlow()
    private var loadJob: Job? = null
    private var mutationJob: Job? = null
    private var sequence = 0L

    fun open(fileId: Long) {
        if (state.value.fileId == fileId && state.value.file != null) return
        loadJob?.cancel(); mutableState.value = PersonalVersionsState(fileId = fileId, loading = true)
        loadJob = scope.launch { load(fileId) }
    }

    fun retry() { state.value.fileId?.let { fileId -> loadJob?.cancel(); loadJob = scope.launch { load(fileId) } } }

    fun restore(versionNumber: Int) = mutate(versionNumber, restore = true)
    fun delete(versionNumber: Int) = mutate(versionNumber, restore = false)

    fun download(version: PersonalFileVersion, destinationHandle: String) {
        val file = state.value.file ?: return
        if (state.value.downloadingVersion != null) return
        scope.launch {
            mutableState.update { it.copy(downloadingVersion = version.versionNumber, feedback = null) }
            try {
                transfers.enqueueVersionDownload(
                    file.id, version.versionNumber, "v${version.versionNumber}_${file.fileName}",
                    version.fileSize, file.mimeType, destinationHandle, version.md5Hash,
                )
                mutableState.update { it.copy(
                    downloadingVersion = null,
                    transferFeedback = TransferEnqueueFeedback(++sequence, UiMessage(MobileString.VersionDownloadQueued), true),
                ) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Throwable) { mutableState.update { it.copy(
                downloadingVersion = null,
                feedback = FileMutationFeedback(++sequence, uiError(error), true),
            ) } }
        }
    }

    fun consumeFeedback() = mutableState.update { it.copy(feedback = null) }
    fun consumeTransferFeedback() = mutableState.update { it.copy(transferFeedback = null) }

    private suspend fun load(fileId: Long) {
        mutableState.update { it.copy(loading = true, error = null) }
        try {
            val file = repository.file(fileId)
            val versions = repository.list(fileId)
            if (state.value.fileId == fileId) mutableState.update { it.copy(
                file = file, versions = versions, loading = false, error = null,
            ) }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Throwable) { if (state.value.fileId == fileId) mutableState.update { it.copy(
            loading = false, error = uiError(error),
        ) } }
    }

    private fun mutate(versionNumber: Int, restore: Boolean) {
        if (state.value.restoringVersion != null || state.value.deletingVersion != null) return
        val fileId = state.value.fileId ?: return
        mutationJob?.cancel()
        mutationJob = scope.launch {
            mutableState.update { if (restore) it.copy(restoringVersion = versionNumber) else it.copy(deletingVersion = versionNumber) }
            try {
                if (restore) repository.restore(fileId, versionNumber) else repository.delete(fileId, versionNumber)
                onContentChanged()
                val versions = repository.list(fileId)
                val file = repository.file(fileId)
                mutableState.update { it.copy(
                    file = file, versions = versions, restoringVersion = null, deletingVersion = null,
                    feedback = FileMutationFeedback(
                        ++sequence, UiMessage(if (restore) MobileString.VersionRestored else MobileString.VersionDeleted), false,
                    ),
                ) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Throwable) { mutableState.update { it.copy(
                restoringVersion = null, deletingVersion = null,
                feedback = FileMutationFeedback(++sequence, uiError(error), true),
            ) } }
        }
    }

    override fun reset() {
        loadJob?.cancel(); mutationJob?.cancel(); mutableState.value = PersonalVersionsState()
    }
}
