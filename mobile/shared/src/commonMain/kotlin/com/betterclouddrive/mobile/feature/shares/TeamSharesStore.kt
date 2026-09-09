package com.betterclouddrive.mobile.feature.shares

import com.betterclouddrive.mobile.app.FileMutationFeedback
import com.betterclouddrive.mobile.app.ResettableStore
import com.betterclouddrive.mobile.app.storeChildScope
import com.betterclouddrive.mobile.app.uiError
import com.betterclouddrive.mobile.core.localization.MobileString
import com.betterclouddrive.mobile.core.localization.UiMessage
import com.betterclouddrive.mobile.core.model.CreateShareRequest
import com.betterclouddrive.mobile.core.model.FileItem
import com.betterclouddrive.mobile.core.model.ShareExpirationFilter
import com.betterclouddrive.mobile.core.model.ShareLink
import com.betterclouddrive.mobile.core.model.TeamRole
import com.betterclouddrive.mobile.core.model.UpdateShareRequest
import com.betterclouddrive.mobile.feature.teams.TeamPermissionsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

typealias TeamSharesState = ShareManagementState

class TeamSharesStore(
    private val repository: TeamSharesRepository,
    private val permissions: TeamPermissionsRepository,
    parentScope: CoroutineScope,
    private val now: () -> Long = { kotlin.time.Clock.System.now().toEpochMilliseconds() },
) : ResettableStore {
    private val scope = parentScope.storeChildScope()
    private val mutableState = MutableStateFlow(ShareManagementState(selectedCanEdit = false))
    val state: StateFlow<TeamSharesState> = mutableState.asStateFlow()
    private var actorUserId: Long? = null
    private var actorRole: TeamRole = TeamRole.VIEWER
    private var generation = 0L
    private var listJob: Job? = null
    private var detailJob: Job? = null
    private var mutationJob: Job? = null
    private var feedbackSequence = 0L
    private var detailRequest = 0L

    fun activate(teamId: Long, userId: Long?, role: TeamRole) {
        bind(teamId, userId, role)
        if (!state.value.hasLoaded && !state.value.loading) refresh()
    }

    fun bind(teamId: Long, userId: Long?, role: TeamRole) {
        if (state.value.teamId != teamId || actorUserId != userId || actorRole != role) {
            clearJobs()
            generation++
            actorUserId = userId
            actorRole = role
            mutableState.value = ShareManagementState(teamId = teamId, selectedCanEdit = false)
        } else {
            actorRole = role
        }
    }

    fun updateQuery(value: String) {
        val teamId = state.value.teamId ?: return
        val token = generation
        listJob?.cancel()
        mutableState.update { it.copy(query = value, error = null, loadMoreError = null) }
        listJob = scope.launch {
            delay(SEARCH_DEBOUNCE_MILLIS)
            loadPage(teamId, token, value.trim(), 1, refresh = false)
        }
    }

    fun setFilters(passwordProtected: Boolean?, expiration: ShareExpirationFilter) {
        val teamId = state.value.teamId ?: return
        val token = generation
        listJob?.cancel()
        mutableState.update { it.copy(
            passwordProtected = passwordProtected,
            expiration = expiration,
            error = null,
            loadMoreError = null,
        ) }
        listJob = scope.launch { loadPage(teamId, token, state.value.query.trim(), 1, refresh = false) }
    }

    fun refresh() {
        val teamId = state.value.teamId ?: return
        val token = generation
        listJob?.cancel()
        listJob = scope.launch { loadPage(teamId, token, state.value.query.trim(), 1, refresh = true) }
    }

    fun loadMore() {
        val current = state.value
        val teamId = current.teamId ?: return
        if (!current.hasMore || current.loading || current.refreshing || current.loadingMore) return
        val token = generation
        listJob?.cancel()
        listJob = scope.launch { loadPage(teamId, token, current.submittedQuery, current.page + 1, refresh = false) }
    }

    fun openCreator(file: FileItem) {
        if (state.value.teamId == null) return
        mutableState.update { it.copy(createTarget = file, creating = false, createError = null, created = null) }
    }

    fun closeCreator() {
        mutationJob?.cancel()
        mutableState.update { it.copy(createTarget = null, creating = false, createError = null, created = null) }
    }

    fun create(password: String?, expireAt: Long?, maxVisits: Int?) {
        val current = state.value
        val teamId = current.teamId ?: return
        val target = current.createTarget ?: return
        if (current.creating) return
        validate(password, expireAt, maxVisits)?.let { message ->
            mutableState.update { it.copy(createError = message) }
            return
        }
        val token = generation
        mutationJob?.cancel()
        mutableState.update { it.copy(creating = true, createError = null) }
        mutationJob = scope.launch {
            try {
                val created = repository.create(teamId, CreateShareRequest(
                    target.id, password?.trim()?.takeIf(String::isNotEmpty), expireAt, maxVisits,
                ))
                if (!isCurrent(teamId, token)) return@launch
                mutableState.update { value -> value.copy(
                    creating = false,
                    created = created,
                    items = listOf(created) + value.items.filterNot { it.id == created.id },
                    total = value.total + 1,
                ) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                if (isCurrent(teamId, token)) mutableState.update { it.copy(creating = false, createError = uiError(error)) }
            }
        }
    }

    fun openDetail(shareId: Long) {
        val teamId = state.value.teamId ?: return
        val token = generation
        val request = ++detailRequest
        detailJob?.cancel()
        val listed = state.value.items.firstOrNull { it.id == shareId }
        mutableState.update { it.copy(
            selected = listed,
            detailShareId = shareId,
            detailLoading = true,
            detailError = null,
            revealedPassword = null,
            passwordVisible = false,
            passwordLoading = false,
            selectedCanEdit = false,
            permissionLoading = true,
            updateError = null,
        ) }
        detailJob = scope.launch {
            try {
                val detail = repository.get(teamId, shareId)
                val permission = permissions.effective(teamId, detail.fileId)
                if (!isCurrent(teamId, token) || detailRequest != request || state.value.detailShareId != shareId) return@launch
                val ownsShare = actorUserId != null && detail.userId == actorUserId
                val managesShare = actorRole == TeamRole.OWNER || actorRole == TeamRole.ADMIN || permission.canManage
                mutableState.update { it.copy(
                    selected = detail,
                    detailLoading = false,
                    permissionLoading = false,
                    selectedCanEdit = permission.canShare && (ownsShare || managesShare),
                ) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                if (isCurrent(teamId, token) && detailRequest == request && state.value.detailShareId == shareId) mutableState.update { it.copy(
                    detailLoading = false,
                    permissionLoading = false,
                    detailError = uiError(error),
                ) }
            }
        }
    }

    fun closeDetail() {
        detailRequest++
        detailJob?.cancel()
        mutableState.update { it.copy(
            selected = null,
            detailShareId = null,
            detailLoading = false,
            detailError = null,
            revealedPassword = null,
            passwordVisible = false,
            passwordLoading = false,
            selectedCanEdit = false,
            permissionLoading = false,
            updateError = null,
        ) }
    }

    fun togglePassword() {
        val current = state.value
        val teamId = current.teamId ?: return
        val share = current.selected ?: return
        if (!current.selectedCanEdit || !share.hasPassword || current.passwordLoading) return
        if (current.passwordVisible) {
            mutableState.update { it.copy(passwordVisible = false) }
            return
        }
        current.revealedPassword?.let {
            mutableState.update { it.copy(passwordVisible = true) }
            return
        }
        val token = generation
        val request = ++detailRequest
        detailJob?.cancel()
        mutableState.update { it.copy(passwordLoading = true, detailError = null) }
        detailJob = scope.launch {
            try {
                val password = repository.password(teamId, share.id)
                if (!isCurrent(teamId, token) || detailRequest != request || state.value.detailShareId != share.id) return@launch
                mutableState.update { value ->
                    if (value.selected?.id != share.id) value else value.copy(
                        revealedPassword = password,
                        passwordVisible = password != null,
                        passwordLoading = false,
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                if (isCurrent(teamId, token) && detailRequest == request && state.value.detailShareId == share.id) {
                    mutableState.update { it.copy(passwordLoading = false, detailError = uiError(error)) }
                }
            }
        }
    }

    fun update(request: UpdateShareRequest) {
        val current = state.value
        val teamId = current.teamId ?: return
        val share = current.selected ?: return
        if (!current.selectedCanEdit || current.updating) return
        validate(request.password, request.expireAt, request.maxVisits)?.let { message ->
            mutableState.update { it.copy(updateError = message) }
            return
        }
        val token = generation
        mutationJob?.cancel()
        mutableState.update { it.copy(updating = true, updateError = null) }
        mutationJob = scope.launch {
            try {
                val updated = repository.update(
                    teamId,
                    share.id,
                    request.copy(password = request.password?.trim()?.takeIf(String::isNotEmpty)),
                )
                if (!isCurrent(teamId, token) || state.value.detailShareId != share.id) return@launch
                mutableState.update { value -> value.copy(
                    updating = false,
                    selected = updated,
                    revealedPassword = null,
                    passwordVisible = false,
                    items = value.items.map { if (it.id == updated.id) updated else it },
                    feedback = FileMutationFeedback(++feedbackSequence, UiMessage(MobileString.ShareUpdated), false),
                ) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                if (isCurrent(teamId, token) && state.value.detailShareId == share.id) {
                    mutableState.update { it.copy(updating = false, updateError = uiError(error)) }
                }
            }
        }
    }

    fun cancel() {
        val current = state.value
        val teamId = current.teamId ?: return
        val share = current.selected ?: return
        if (!current.selectedCanEdit || current.canceling) return
        val token = generation
        mutationJob?.cancel()
        mutableState.update { it.copy(canceling = true, detailError = null) }
        mutationJob = scope.launch {
            try {
                repository.cancel(teamId, share.id)
                if (!isCurrent(teamId, token) || state.value.detailShareId != share.id) return@launch
                mutableState.update { value -> value.copy(
                    canceling = false,
                    selected = null,
                    detailShareId = null,
                    revealedPassword = null,
                    passwordVisible = false,
                    selectedCanEdit = false,
                    items = value.items.filterNot { it.id == share.id },
                    total = (value.total - 1).coerceAtLeast(0),
                    feedback = FileMutationFeedback(++feedbackSequence, UiMessage(MobileString.ShareCanceled), false),
                ) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                if (isCurrent(teamId, token) && state.value.detailShareId == share.id) {
                    mutableState.update { it.copy(canceling = false, detailError = uiError(error)) }
                }
            }
        }
    }

    fun consumeFeedback() = mutableState.update { it.copy(feedback = null) }

    override fun reset() {
        clearJobs()
        generation++
        detailRequest++
        actorUserId = null
        actorRole = TeamRole.VIEWER
        mutableState.value = ShareManagementState(selectedCanEdit = false)
    }

    private suspend fun loadPage(teamId: Long, token: Long, query: String, page: Int, refresh: Boolean) {
        if (!isCurrent(teamId, token)) return
        val before = state.value
        mutableState.update { it.copy(
            loading = page == 1 && !refresh,
            refreshing = page == 1 && refresh,
            loadingMore = page > 1,
            error = null,
            loadMoreError = null,
        ) }
        try {
            val result = repository.list(
                teamId,
                query.takeIf(String::isNotEmpty),
                before.passwordProtected,
                before.expiration,
                page,
                PAGE_SIZE,
            )
            if (!isCurrent(teamId, token)) return
            mutableState.update { current ->
                if (current.query.trim() != query || current.passwordProtected != before.passwordProtected ||
                    current.expiration != before.expiration
                ) current else {
                    val records = if (page == 1) result.records else current.items + result.records
                    current.copy(
                        submittedQuery = query,
                        items = records.distinctBy(ShareLink::id),
                        loading = false,
                        refreshing = false,
                        loadingMore = false,
                        page = result.page,
                        pages = result.pages.coerceAtLeast(1),
                        total = result.total,
                    )
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            if (isCurrent(teamId, token)) mutableState.update { current -> current.copy(
                loading = false,
                refreshing = false,
                loadingMore = false,
                error = if (page == 1) uiError(error) else current.error,
                loadMoreError = if (page > 1) uiError(error) else null,
            ) }
        }
    }

    private fun isCurrent(teamId: Long, token: Long) = state.value.teamId == teamId && generation == token

    private fun clearJobs() {
        listJob?.cancel()
        detailJob?.cancel()
        mutationJob?.cancel()
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
