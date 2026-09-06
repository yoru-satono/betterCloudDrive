package com.betterclouddrive.mobile.app

import com.betterclouddrive.mobile.core.model.SessionInvalidationReason
import com.betterclouddrive.mobile.feature.auth.AuthDataSource
import com.betterclouddrive.mobile.feature.auth.AuthStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

interface BrowserSessionPort {
    fun loadRoot()
}

interface TransferSessionPort {
    suspend fun resumeWaitingForLogin()
    suspend fun clearForLogout()
    suspend fun waitForLogin()
}

interface RealtimeSessionPort {
    fun startSession()
    fun stopSession()
}

class AppCoordinator(
    val auth: AuthStore,
    private val authSource: AuthDataSource,
    private val browser: BrowserSessionPort,
    private val transfers: TransferSessionPort,
    private val sessionStores: List<ResettableStore>,
    private val incomingInvite: ResettableStore,
    private val realtime: RealtimeSessionPort,
    private val scope: CoroutineScope,
) {
    private var started = false

    init {
        scope.launch { authSource.sessionInvalidations.collectLatest { reason ->
            if (reason !in setOf(
                    SessionInvalidationReason.EXPIRED,
                    SessionInvalidationReason.REVOKED,
                    SessionInvalidationReason.CORRUPT_STORAGE,
                )
            ) return@collectLatest
            auth.invalidate(reason)
            realtime.stopSession()
            resetSessionStores()
            incomingInvite.reset()
            transfers.waitForLogin()
        } }
    }

    fun start() {
        if (started) return
        started = true
        scope.launch { if (auth.restore()) { transfers.resumeWaitingForLogin(); browser.loadRoot(); realtime.startSession() } else resetSessionStores() }
    }

    fun retryRestore() { scope.launch { if (auth.restore()) { transfers.resumeWaitingForLogin(); browser.loadRoot(); realtime.startSession() } } }

    fun discardSession() { scope.launch {
        auth.logout(); realtime.stopSession(); transfers.clearForLogout(); resetSessionStores(); incomingInvite.reset()
    } }

    fun login(server: String, username: String, password: String) { scope.launch {
        val normalized = authSource.normalizeServer(server).getOrNull()
        val changedServer = normalized != null && auth.state.value.server != normalized
        if (changedServer) {
            transfers.clearForLogout()
            incomingInvite.reset()
        }
        if (auth.login(server, username, password)) {
            resetSessionStores(); transfers.resumeWaitingForLogin(); browser.loadRoot(); realtime.startSession()
        }
    } }

    fun logout() { scope.launch {
        auth.logout(); realtime.stopSession(); transfers.clearForLogout(); resetSessionStores(); incomingInvite.reset()
    } }

    fun switchAccountPreservingInvite() { scope.launch {
        auth.logout(); realtime.stopSession(); transfers.clearForLogout(); resetSessionStores()
    } }

    fun confirmServerSwitch() { scope.launch {
        if (!auth.state.value.account.serverSwitchConfirming) return@launch
        if (auth.switchServer { transfers.clearForLogout() }) {
            realtime.stopSession(); resetSessionStores(); incomingInvite.reset()
        }
    } }

    private fun resetSessionStores() { sessionStores.forEach(ResettableStore::reset) }
}
