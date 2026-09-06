package com.betterclouddrive.mobile.feature.teams

import com.betterclouddrive.mobile.app.ResettableStore
import com.betterclouddrive.mobile.app.RealtimeSessionPort
import com.betterclouddrive.mobile.app.storeChildScope
import com.betterclouddrive.mobile.app.uiError
import com.betterclouddrive.mobile.core.localization.MobileString
import com.betterclouddrive.mobile.core.localization.UiMessage
import com.betterclouddrive.mobile.core.model.NotificationItem
import com.betterclouddrive.mobile.core.model.TeamInvite
import com.betterclouddrive.mobile.core.model.TeamInviteStatus
import com.betterclouddrive.mobile.core.model.TeamRole
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class TeamPeopleTab { MEMBERS, INVITES }

data class TeamInvitesState(
    val teamId: Long? = null,
    val actorRole: TeamRole = TeamRole.VIEWER,
    val selectedTab: TeamPeopleTab = TeamPeopleTab.MEMBERS,
    val items: List<TeamInvite> = emptyList(),
    val page: Int = 0,
    val pages: Int = 1,
    val total: Long = 0,
    val loading: Boolean = false,
    val refreshing: Boolean = false,
    val loadingMore: Boolean = false,
    val error: UiMessage? = null,
    val loadMoreError: UiMessage? = null,
    val creating: Boolean = false,
    val createError: UiMessage? = null,
    val submittingInviteId: Long? = null,
    val feedback: UiMessage? = null,
) {
    val hasMore get() = page < pages
    val canInvite get() = actorRole == TeamRole.OWNER || actorRole == TeamRole.ADMIN
}

class TeamInvitesStore(
    private val repository: TeamInvitesRepository,
    parentScope: CoroutineScope,
) : ResettableStore {
    private val scope = parentScope.storeChildScope()
    private val mutableState = MutableStateFlow(TeamInvitesState())
    val state: StateFlow<TeamInvitesState> = mutableState.asStateFlow()
    private var listJob: Job? = null
    private var mutationJob: Job? = null

    fun activate(teamId: Long, actorRole: TeamRole) {
        if (state.value.teamId != teamId) {
            listJob?.cancel(); mutationJob?.cancel()
            mutableState.value = TeamInvitesState(teamId = teamId, actorRole = actorRole)
        } else mutableState.update { it.copy(actorRole = actorRole) }
    }

    fun selectTab(tab: TeamPeopleTab) {
        mutableState.update { it.copy(selectedTab = tab) }
        if (tab == TeamPeopleTab.INVITES && state.value.page == 0) load(1, false)
    }

    fun refresh() = load(1, true)
    fun loadMore() {
        val current = state.value
        if (!current.hasMore || current.loading || current.refreshing || current.loadingMore) return
        load(current.page + 1, false)
    }

    fun create(email: String, role: TeamRole) {
        val current = state.value
        val teamId = current.teamId ?: return
        val normalized = email.trim()
        if (!current.canInvite || current.creating || current.submittingInviteId != null) return
        if (!EMAIL.matches(normalized) || role == TeamRole.OWNER || current.actorRole == TeamRole.ADMIN && role == TeamRole.ADMIN) {
            mutableState.update { it.copy(createError = UiMessage(MobileString.ErrorInvalidInput)) }
            return
        }
        mutationJob?.cancel()
        mutableState.update { it.copy(creating = true, createError = null, feedback = null) }
        mutationJob = scope.launch {
            try {
                repository.create(teamId, normalized, role)
                mutableState.update { it.copy(creating = false, feedback = UiMessage(MobileString.InviteCreated)) }
                load(1, true)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Throwable) { mutableState.update { it.copy(creating = false, createError = uiError(error)) } }
        }
    }

    fun resend(invite: TeamInvite) = mutate(invite, MobileString.InviteResent) {
        repository.resend(it.teamId, it.id)
    }

    fun cancel(invite: TeamInvite) = mutate(invite, MobileString.InviteCanceled) {
        repository.cancel(it.teamId, it.id)
    }

    fun clearFeedback() = mutableState.update { it.copy(feedback = null) }
    fun clearCreateError() = mutableState.update { it.copy(createError = null) }

    override fun reset() {
        listJob?.cancel(); mutationJob?.cancel(); mutableState.value = TeamInvitesState()
    }

    private fun mutate(invite: TeamInvite, success: MobileString, block: suspend (TeamInvite) -> Unit) {
        val current = state.value
        if (!current.canInvite || current.submittingInviteId != null || current.creating) return
        if (invite.status !in setOf(TeamInviteStatus.PENDING, TeamInviteStatus.EXPIRED)) return
        mutationJob?.cancel()
        mutableState.update { it.copy(submittingInviteId = invite.id, feedback = null) }
        mutationJob = scope.launch {
            try {
                block(invite)
                mutableState.update { it.copy(submittingInviteId = null, feedback = UiMessage(success)) }
                load(1, true)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Throwable) { mutableState.update { it.copy(submittingInviteId = null, feedback = uiError(error)) } }
        }
    }

    private fun load(page: Int, refresh: Boolean) {
        val teamId = state.value.teamId ?: return
        val existing = state.value.items
        listJob?.cancel()
        mutableState.update { when {
            page > 1 -> it.copy(loadingMore = true, loadMoreError = null)
            refresh && existing.isNotEmpty() -> it.copy(refreshing = true, error = null)
            else -> it.copy(loading = true, error = null)
        } }
        listJob = scope.launch {
            try {
                val result = repository.list(teamId, page, PAGE_SIZE)
                val merged = (if (page == 1) result.records else existing + result.records).distinctBy(TeamInvite::id)
                mutableState.update { it.copy(
                    items = merged, page = result.page, pages = result.pages.coerceAtLeast(result.page), total = result.total,
                    loading = false, refreshing = false, loadingMore = false, error = null, loadMoreError = null,
                ) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Throwable) { mutableState.update { when {
                page > 1 -> it.copy(loadingMore = false, loadMoreError = uiError(error))
                refresh && existing.isNotEmpty() -> it.copy(refreshing = false, error = uiError(error))
                else -> it.copy(loading = false, error = uiError(error))
            } } }
        }
    }

    private companion object {
        const val PAGE_SIZE = 50
        val EMAIL = Regex("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$")
    }
}

data class IncomingInviteState(
    val active: Boolean = false,
    val loading: Boolean = false,
    val invite: TeamInvite? = null,
    val error: UiMessage? = null,
    val submitting: Boolean = false,
    val awaitingAuthentication: Boolean = false,
    val acceptedTeamId: Long? = null,
    val rejected: Boolean = false,
)

class IncomingInviteStore(
    private val repository: TeamInvitesRepository,
    parentScope: CoroutineScope,
    private val onAccepted: (Long) -> Unit = {},
) : ResettableStore {
    private val scope = parentScope.storeChildScope()
    private val mutableState = MutableStateFlow(IncomingInviteState())
    val state: StateFlow<IncomingInviteState> = mutableState.asStateFlow()
    private var token: String? = null
    private var job: Job? = null

    fun open(rawToken: String) {
        val normalized = rawToken.trim()
        if (!TOKEN.matches(normalized)) return
        token = normalized
        job?.cancel()
        mutableState.value = IncomingInviteState(active = true, loading = true)
        job = scope.launch { load(normalized) }
    }

    fun requestAuthentication() = mutableState.update { it.copy(awaitingAuthentication = true) }
    fun resumeAfterAuthentication() = mutableState.update { it.copy(awaitingAuthentication = false) }

    fun accept(accountEmail: String?) = submit(accountEmail, accepted = true)
    fun reject(accountEmail: String?) = submit(accountEmail, accepted = false)

    fun retry() {
        val currentToken = token ?: return
        job?.cancel(); mutableState.update { it.copy(loading = true, error = null) }
        job = scope.launch { load(currentToken) }
    }

    fun consumeAcceptedTeam() = mutableState.update { it.copy(acceptedTeamId = null, active = false) }.also {
        token = null
    }

    fun finish() {
        token = null
        mutableState.update { it.copy(active = false) }
    }

    fun close() = reset()

    override fun reset() {
        job?.cancel(); token = null; mutableState.value = IncomingInviteState()
    }

    private suspend fun load(currentToken: String) {
        try {
            val invite = repository.getIncoming(currentToken)
            if (token == currentToken) mutableState.value = IncomingInviteState(active = true, invite = invite)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Throwable) {
            if (token == currentToken) mutableState.update { it.copy(loading = false, error = uiError(error)) }
        }
    }

    private fun submit(accountEmail: String?, accepted: Boolean) {
        val current = state.value
        val currentToken = token ?: return
        val invite = current.invite ?: return
        if (current.submitting || invite.status != TeamInviteStatus.PENDING) return
        if (!accountEmail.equals(invite.email, ignoreCase = true)) {
            mutableState.update { it.copy(error = UiMessage(MobileString.InviteEmailMismatch)) }
            return
        }
        job?.cancel(); mutableState.update { it.copy(submitting = true, error = null) }
        job = scope.launch {
            try {
                if (accepted) repository.accept(currentToken) else repository.reject(currentToken)
                if (accepted) {
                    mutableState.update { it.copy(submitting = false, acceptedTeamId = invite.teamId) }
                    onAccepted(invite.teamId)
                } else mutableState.update { it.copy(submitting = false, rejected = true) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Throwable) { mutableState.update { it.copy(submitting = false, error = uiError(error)) } }
        }
    }

    private companion object { val TOKEN = Regex("^[A-Za-z0-9_-]{16,256}$") }
}

enum class NotificationReadFilter { ALL, UNREAD }

sealed interface NotificationTarget {
    data class Invite(val token: String) : NotificationTarget
    data class TeamRoot(val teamId: Long) : NotificationTarget
    data class TeamFolder(val teamId: Long, val folderId: Long) : NotificationTarget
    data class TeamMembers(val teamId: Long) : NotificationTarget
    data object Unsupported : NotificationTarget
}

fun parseNotificationTarget(link: String?): NotificationTarget {
    val path = link?.substringBefore('?')?.trim()?.removePrefix("/") ?: return NotificationTarget.Unsupported
    Regex("^(?:api/v1/)?team-invites/([A-Za-z0-9_-]{16,256})$").matchEntire(path)?.let {
        return NotificationTarget.Invite(it.groupValues[1])
    }
    Regex("^teams/(\\d+)/settings/?$").matchEntire(path)?.let {
        return NotificationTarget.TeamMembers(it.groupValues[1].toLong())
    }
    Regex("^teams/(\\d+)/files(?:/(\\d+))?/?$").matchEntire(path)?.let {
        val teamId = it.groupValues[1].toLong()
        val folder = it.groupValues[2].toLongOrNull()
        return folder?.let { id -> NotificationTarget.TeamFolder(teamId, id) } ?: NotificationTarget.TeamRoot(teamId)
    }
    return NotificationTarget.Unsupported
}

fun parseIncomingInviteDeepLink(value: String?): String? {
    val match = Regex("^betterclouddrive://team-invites/([A-Za-z0-9_-]{16,256})/?$")
        .matchEntire(value?.trim().orEmpty()) ?: return null
    return match.groupValues[1]
}

data class NotificationsState(
    val items: List<NotificationItem> = emptyList(),
    val page: Int = 0,
    val pages: Int = 1,
    val total: Long = 0,
    val unreadCount: Long = 0,
    val readFilter: NotificationReadFilter = NotificationReadFilter.ALL,
    val typeFilter: String? = null,
    val loading: Boolean = false,
    val refreshing: Boolean = false,
    val loadingMore: Boolean = false,
    val error: UiMessage? = null,
    val loadMoreError: UiMessage? = null,
    val submittingId: Long? = null,
    val markingAll: Boolean = false,
    val streamConnected: Boolean = false,
    val feedback: UiMessage? = null,
) { val hasMore get() = page < pages }

class NotificationsStore(
    private val repository: NotificationsRepository,
    parentScope: CoroutineScope,
    private val wait: suspend (Long) -> Unit = { delay(it) },
) : ResettableStore, RealtimeSessionPort {
    private val scope = parentScope.storeChildScope()
    private val mutableState = MutableStateFlow(NotificationsState())
    val state: StateFlow<NotificationsState> = mutableState.asStateFlow()
    private var sessionActive = false
    private var foreground = false
    private var listJob: Job? = null
    private var streamJob: Job? = null
    private var mutationJob: Job? = null

    override fun startSession() {
        sessionActive = true
        refreshUnread()
        updateStream()
    }

    override fun stopSession() {
        sessionActive = false
        streamJob?.cancel(); streamJob = null
        mutableState.update { it.copy(streamConnected = false) }
    }

    fun setForeground(value: Boolean) {
        if (foreground == value) return
        foreground = value
        if (value && sessionActive) refreshUnread()
        updateStream()
    }

    fun activate() { if (state.value.page == 0) load(1, false) }
    fun refresh() = load(1, true)
    fun loadMore() {
        val current = state.value
        if (!current.hasMore || current.loading || current.refreshing || current.loadingMore) return
        load(current.page + 1, false)
    }

    fun filter(read: NotificationReadFilter, type: String?) {
        if (read == state.value.readFilter && type == state.value.typeFilter) return
        listJob?.cancel(); mutableState.update { it.copy(readFilter = read, typeFilter = type, items = emptyList(), page = 0, pages = 1) }
        load(1, false)
    }

    fun markRead(item: NotificationItem, onNavigate: (NotificationTarget) -> Unit) {
        val target = parseNotificationTarget(item.link)
        val unsupportedFeedback = UiMessage(MobileString.NotificationUnsupportedLink)
            .takeIf { target == NotificationTarget.Unsupported }
        if (unsupportedFeedback != null) mutableState.update { it.copy(feedback = unsupportedFeedback) }
        onNavigate(target)
        if (item.isRead || state.value.submittingId != null) return
        mutationJob?.cancel(); mutableState.update { it.copy(submittingId = item.id, feedback = unsupportedFeedback) }
        mutationJob = scope.launch {
            try {
                repository.markRead(item.id)
                mutableState.update { state -> state.copy(
                    items = state.items.map { if (it.id == item.id) it.copy(isRead = true) else it },
                    unreadCount = (state.unreadCount - 1).coerceAtLeast(0), submittingId = null,
                ) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Throwable) { mutableState.update { it.copy(submittingId = null, feedback = uiError(error)) } }
        }
    }

    fun markAllRead() {
        if (state.value.markingAll) return
        mutationJob?.cancel(); mutableState.update { it.copy(markingAll = true, feedback = null) }
        mutationJob = scope.launch {
            try {
                repository.markAllRead()
                mutableState.update { state -> state.copy(
                    items = state.items.map { it.copy(isRead = true) }, unreadCount = 0, markingAll = false,
                ) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Throwable) { mutableState.update { it.copy(markingAll = false, feedback = uiError(error)) } }
        }
    }

    fun clearFeedback() = mutableState.update { it.copy(feedback = null) }

    override fun reset() {
        sessionActive = false
        listJob?.cancel(); streamJob?.cancel(); mutationJob?.cancel()
        mutableState.value = NotificationsState()
    }

    private fun refreshUnread() {
        scope.launch {
            try { mutableState.update { it.copy(unreadCount = repository.unreadCount()) } }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Throwable) { }
        }
    }

    private fun updateStream() {
        streamJob?.cancel(); streamJob = null
        if (!sessionActive || !foreground) {
            mutableState.update { it.copy(streamConnected = false) }
            return
        }
        streamJob = scope.launch {
            val delays = longArrayOf(1_000, 2_000, 5_000, 10_000, 30_000)
            var failure = 0
            while (sessionActive && foreground) {
                try {
                    repository.stream { unread ->
                        failure = 0
                        mutableState.update { it.copy(unreadCount = unread, streamConnected = true) }
                        refreshLoaded()
                    }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Throwable) { mutableState.update { it.copy(streamConnected = false) } }
                if (!sessionActive || !foreground) break
                wait(delays[failure.coerceAtMost(delays.lastIndex)])
                failure++
            }
        }
    }

    private fun refreshLoaded() {
        if (state.value.page > 0 && listJob?.isActive != true) load(1, true)
    }

    private fun load(page: Int, refresh: Boolean) {
        val current = state.value
        val existing = current.items
        listJob?.cancel()
        mutableState.update { when {
            page > 1 -> it.copy(loadingMore = true, loadMoreError = null)
            refresh && existing.isNotEmpty() -> it.copy(refreshing = true, error = null)
            else -> it.copy(loading = true, error = null)
        } }
        listJob = scope.launch {
            try {
                val snapshot = state.value
                val result = repository.list(
                    unreadOnly = true.takeIf { snapshot.readFilter == NotificationReadFilter.UNREAD },
                    type = snapshot.typeFilter, page = page, size = PAGE_SIZE,
                )
                val merged = (if (page == 1) result.records else existing + result.records).distinctBy(NotificationItem::id)
                mutableState.update { it.copy(
                    items = merged, page = result.page, pages = result.pages.coerceAtLeast(result.page), total = result.total,
                    loading = false, refreshing = false, loadingMore = false, error = null, loadMoreError = null,
                ) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Throwable) { mutableState.update { when {
                page > 1 -> it.copy(loadingMore = false, loadMoreError = uiError(error))
                refresh && existing.isNotEmpty() -> it.copy(refreshing = false, error = uiError(error))
                else -> it.copy(loading = false, error = uiError(error))
            } } }
        }
    }

    private companion object { const val PAGE_SIZE = 50 }
}
