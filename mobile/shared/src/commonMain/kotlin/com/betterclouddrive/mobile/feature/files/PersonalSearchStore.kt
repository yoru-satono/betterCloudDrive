package com.betterclouddrive.mobile.feature.files

import com.betterclouddrive.mobile.app.ResettableStore
import com.betterclouddrive.mobile.app.uiError
import com.betterclouddrive.mobile.app.storeChildScope
import com.betterclouddrive.mobile.core.localization.UiMessage
import com.betterclouddrive.mobile.core.model.FileItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class PersonalSearchState(
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
) {
    val hasMore: Boolean get() = submittedQuery.isNotEmpty() && page < pages
}

class PersonalSearchStore(
    private val repository: PersonalFilesRepository,
    parentScope: CoroutineScope,
) : ResettableStore {
    private val scope = parentScope.storeChildScope()
    private val mutableState = MutableStateFlow(PersonalSearchState())
    val state: StateFlow<PersonalSearchState> = mutableState.asStateFlow()
    private var job: Job? = null

    fun updateQuery(value: String) {
        job?.cancel()
        if (value.trim().isEmpty()) {
            mutableState.value = PersonalSearchState(query = value)
            return
        }
        mutableState.update { it.copy(query = value, error = null, loadMoreError = null) }
        job = scope.launch { delay(DEBOUNCE_MILLIS); loadPage(value.trim(), 1) }
    }

    fun submit() {
        val query = state.value.query.trim()
        job?.cancel()
        if (query.isEmpty()) {
            mutableState.value = PersonalSearchState(query = state.value.query)
            return
        }
        job = scope.launch { loadPage(query, 1) }
    }

    fun retry() = submit()

    fun loadMore() {
        val current = state.value
        if (!current.hasMore || current.searching || current.loadingMore) return
        job?.cancel()
        job = scope.launch { loadPage(current.submittedQuery, current.page + 1) }
    }

    fun updateScrollAnchor(fileId: Long?) = mutableState.update { it.copy(scrollAnchorId = fileId) }

    override fun reset() {
        job?.cancel()
        mutableState.value = PersonalSearchState()
    }

    private suspend fun loadPage(query: String, page: Int) {
        val normalized = query.trim()
        if (normalized.isEmpty()) return
        val existing = state.value
        mutableState.update { it.copy(
            submittedQuery = normalized, searching = page == 1, loadingMore = page > 1,
            error = if (page == 1) null else it.error, loadMoreError = null,
        ) }
        try {
            val result = repository.searchPersonalFiles(normalized, page, PAGE_SIZE)
            val latest = state.value
            if (latest.query.trim() != normalized || latest.submittedQuery != normalized) return
            val items = if (page == 1) result.records.distinctBy(FileItem::id)
            else (existing.items + result.records).distinctBy(FileItem::id)
            mutableState.update { it.copy(
                items = items, searching = false, loadingMore = false, error = null, loadMoreError = null,
                page = result.page, pages = result.pages.coerceAtLeast(result.page), total = result.total,
                scrollAnchorId = if (page == 1) null else existing.scrollAnchorId,
            ) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            if (state.value.query.trim() != normalized) return
            mutableState.update { it.copy(
                searching = false, loadingMore = false,
                error = if (page == 1) uiError(error) else it.error,
                loadMoreError = if (page > 1) uiError(error) else null,
            ) }
        }
    }

    private companion object {
        const val PAGE_SIZE = 50
        const val DEBOUNCE_MILLIS = 300L
    }
}
