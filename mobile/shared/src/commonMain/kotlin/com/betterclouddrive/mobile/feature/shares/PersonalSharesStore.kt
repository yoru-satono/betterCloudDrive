package com.betterclouddrive.mobile.feature.shares

import com.betterclouddrive.mobile.app.FileMutationFeedback
import com.betterclouddrive.mobile.app.ResettableStore
import com.betterclouddrive.mobile.app.uiError
import com.betterclouddrive.mobile.app.storeChildScope
import com.betterclouddrive.mobile.core.localization.MobileString
import com.betterclouddrive.mobile.core.localization.UiMessage
import com.betterclouddrive.mobile.core.model.CreatePersonalShareRequest
import com.betterclouddrive.mobile.core.model.FileItem
import com.betterclouddrive.mobile.core.model.ShareExpirationFilter
import com.betterclouddrive.mobile.core.model.ShareLink
import com.betterclouddrive.mobile.core.model.UpdatePersonalShareRequest
import com.betterclouddrive.mobile.feature.files.PersonalSharesRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class PersonalSharesState(
    val query: String = "",
    val submittedQuery: String = "",
    val passwordProtected: Boolean? = null,
    val expiration: ShareExpirationFilter = ShareExpirationFilter.ALL,
    val items: List<ShareLink> = emptyList(),
    val loading: Boolean = false,
    val refreshing: Boolean = false,
    val loadingMore: Boolean = false,
    val error: UiMessage? = null,
    val loadMoreError: UiMessage? = null,
    val page: Int = 0,
    val pages: Int = 1,
    val total: Long = 0,
    val selected: ShareLink? = null,
    val detailLoading: Boolean = false,
    val detailError: UiMessage? = null,
    val revealedPassword: String? = null,
    val passwordVisible: Boolean = false,
    val passwordLoading: Boolean = false,
    val createTarget: FileItem? = null,
    val creating: Boolean = false,
    val createError: UiMessage? = null,
    val created: ShareLink? = null,
    val updating: Boolean = false,
    val updateError: UiMessage? = null,
    val canceling: Boolean = false,
    val feedback: FileMutationFeedback? = null,
) {
    val hasLoaded: Boolean get() = page > 0
    val hasMore: Boolean get() = page < pages
}

class PersonalSharesStore(
    private val repository: PersonalSharesRepository,
    parentScope: CoroutineScope,
    private val now: () -> Long = { kotlin.time.Clock.System.now().toEpochMilliseconds() },
) : ResettableStore {
    private val scope = parentScope.storeChildScope()
    private val mutableState = MutableStateFlow(PersonalSharesState())
    val state: StateFlow<PersonalSharesState> = mutableState.asStateFlow()
    private var listJob: Job? = null
    private var detailJob: Job? = null
    private var mutationJob: Job? = null
    private var feedbackSequence = 0L

    fun activate() {
        if (!state.value.hasLoaded && !state.value.loading) refresh()
    }

    fun updateQuery(value: String) {
        listJob?.cancel()
        mutableState.update { it.copy(query = value, error = null, loadMoreError = null) }
        listJob = scope.launch {
            delay(SEARCH_DEBOUNCE_MILLIS)
            loadPage(value.trim(), 1, refresh = false)
        }
    }

    fun setFilters(passwordProtected: Boolean?, expiration: ShareExpirationFilter) {
        listJob?.cancel()
        mutableState.update { it.copy(passwordProtected = passwordProtected, expiration = expiration, error = null, loadMoreError = null) }
        listJob = scope.launch { loadPage(state.value.query.trim(), 1, refresh = false) }
    }

    fun refresh() {
        listJob?.cancel()
        listJob = scope.launch { loadPage(state.value.query.trim(), 1, refresh = true) }
    }

    fun loadMore() {
        val current = state.value
        if (!current.hasMore || current.loading || current.loadingMore) return
        listJob?.cancel()
        listJob = scope.launch { loadPage(current.submittedQuery, current.page + 1, refresh = false) }
    }

    fun openCreator(file: FileItem) {
        mutableState.update { it.copy(createTarget = file, creating = false, createError = null, created = null) }
    }

    fun closeCreator() {
        mutationJob?.cancel()
        mutableState.update { it.copy(createTarget = null, creating = false, createError = null, created = null) }
    }

    fun create(password: String?, expireAt: Long?, maxVisits: Int?) {
        val target = state.value.createTarget ?: return
        if (state.value.creating) return
        validate(password, expireAt, maxVisits)?.let { message ->
            mutableState.update { it.copy(createError = message) }
            return
        }
        mutationJob?.cancel()
        mutableState.update { it.copy(creating = true, createError = null) }
        mutationJob = scope.launch {
            try {
                val created = repository.createPersonalShare(CreatePersonalShareRequest(
                    target.id, password?.trim()?.takeIf(String::isNotEmpty), expireAt, maxVisits,
                ))
                mutableState.update { current -> current.copy(
                    creating = false,
                    created = created,
                    items = listOf(created) + current.items.filterNot { it.id == created.id },
                    total = current.total + 1,
                ) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                mutableState.update { it.copy(creating = false, createError = uiError(error)) }
            }
        }
    }

    fun openDetail(shareId: Long) {
        detailJob?.cancel()
        val listed = state.value.items.firstOrNull { it.id == shareId }
        mutableState.update { it.copy(
            selected = listed, detailLoading = true, detailError = null, revealedPassword = null,
            passwordVisible = false, passwordLoading = false, updateError = null,
        ) }
        detailJob = scope.launch {
            try {
                val detail = repository.getPersonalShare(shareId)
                mutableState.update { current ->
                    if (current.selected?.id != shareId && listed != null) current
                    else current.copy(selected = detail, detailLoading = false)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                mutableState.update { it.copy(detailLoading = false, detailError = uiError(error)) }
            }
        }
    }

    fun closeDetail() {
        detailJob?.cancel()
        mutableState.update { it.copy(
            selected = null, detailLoading = false, detailError = null, revealedPassword = null,
            passwordVisible = false, passwordLoading = false, updateError = null,
        ) }
    }

    fun togglePassword() {
        val share = state.value.selected ?: return
        val current = state.value
        if (!share.hasPassword || current.passwordLoading) return
        if (current.passwordVisible) {
            mutableState.update { it.copy(passwordVisible = false) }
            return
        }
        current.revealedPassword?.let {
            mutableState.update { it.copy(passwordVisible = true) }
            return
        }
        detailJob?.cancel()
        mutableState.update { it.copy(passwordLoading = true, detailError = null) }
        detailJob = scope.launch {
            try {
                val password = repository.getPersonalSharePassword(share.id)
                mutableState.update { value -> if (value.selected?.id != share.id) value else value.copy(
                    revealedPassword = password, passwordVisible = password != null, passwordLoading = false,
                ) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                mutableState.update { it.copy(passwordLoading = false, detailError = uiError(error)) }
            }
        }
    }

    fun update(request: UpdatePersonalShareRequest) {
        val share = state.value.selected ?: return
        if (state.value.updating) return
        validate(request.password, request.expireAt, request.maxVisits)?.let { message ->
            mutableState.update { it.copy(updateError = message) }
            return
        }
        mutationJob?.cancel()
        mutableState.update { it.copy(updating = true, updateError = null) }
        mutationJob = scope.launch {
            try {
                val updated = repository.updatePersonalShare(
                    share.id, request.copy(password = request.password?.trim()?.takeIf(String::isNotEmpty)),
                )
                mutableState.update { current -> current.copy(
                    updating = false, selected = updated, revealedPassword = null, passwordVisible = false,
                    items = current.items.map { if (it.id == updated.id) updated else it },
                    feedback = FileMutationFeedback(++feedbackSequence, UiMessage(MobileString.ShareUpdated), false),
                ) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                mutableState.update { it.copy(updating = false, updateError = uiError(error)) }
            }
        }
    }

    fun cancel() {
        val share = state.value.selected ?: return
        if (state.value.canceling) return
        mutationJob?.cancel()
        mutableState.update { it.copy(canceling = true, detailError = null) }
        mutationJob = scope.launch {
            try {
                repository.cancelPersonalShare(share.id)
                mutableState.update { current -> current.copy(
                    canceling = false, selected = null, revealedPassword = null, passwordVisible = false,
                    items = current.items.filterNot { it.id == share.id }, total = (current.total - 1).coerceAtLeast(0),
                    feedback = FileMutationFeedback(++feedbackSequence, UiMessage(MobileString.ShareCanceled), false),
                ) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                mutableState.update { it.copy(canceling = false, detailError = uiError(error)) }
            }
        }
    }

    fun consumeFeedback() = mutableState.update { it.copy(feedback = null) }

    override fun reset() {
        listJob?.cancel(); detailJob?.cancel(); mutationJob?.cancel()
        mutableState.value = PersonalSharesState()
    }

    private suspend fun loadPage(query: String, page: Int, refresh: Boolean) {
        val before = state.value
        mutableState.update { it.copy(
            loading = page == 1 && !refresh, refreshing = page == 1 && refresh,
            loadingMore = page > 1, error = null, loadMoreError = null,
        ) }
        try {
            val result = repository.listPersonalShares(
                query.takeIf(String::isNotEmpty), before.passwordProtected, before.expiration, page, PAGE_SIZE,
            )
            mutableState.update { current ->
                if (current.query.trim() != query || current.passwordProtected != before.passwordProtected ||
                    current.expiration != before.expiration
                ) current else {
                    val records = if (page == 1) result.records else current.items + result.records
                    current.copy(
                        submittedQuery = query, items = records.distinctBy(ShareLink::id), loading = false,
                        refreshing = false, loadingMore = false, page = result.page, pages = result.pages.coerceAtLeast(1),
                        total = result.total,
                    )
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            mutableState.update { current -> current.copy(
                loading = false, refreshing = false, loadingMore = false,
                error = if (page == 1) uiError(error) else current.error,
                loadMoreError = if (page > 1) uiError(error) else null,
            ) }
        }
    }

    private fun validate(password: String?, expireAt: Long?, maxVisits: Int?): UiMessage? {
        val normalized = password?.trim().orEmpty()
        return when {
            normalized.isNotEmpty() && normalized.length !in 4..16 -> UiMessage(MobileString.ErrorSharePasswordLength)
            maxVisits != null && maxVisits < 1 -> UiMessage(MobileString.ErrorShareMaxVisits)
            expireAt != null && expireAt <= now() -> UiMessage(MobileString.ErrorShareExpiryPast)
            else -> null
        }
    }

    private companion object {
        const val PAGE_SIZE = 50
        const val SEARCH_DEBOUNCE_MILLIS = 300L
    }
}
