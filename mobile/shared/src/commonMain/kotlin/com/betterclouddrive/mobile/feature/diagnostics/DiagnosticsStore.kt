package com.betterclouddrive.mobile.feature.diagnostics

import com.betterclouddrive.mobile.app.ResettableStore
import com.betterclouddrive.mobile.app.storeChildScope
import com.betterclouddrive.mobile.app.uiError
import com.betterclouddrive.mobile.core.localization.MobileString
import com.betterclouddrive.mobile.core.localization.UiMessage
import com.betterclouddrive.mobile.core.model.OfficeConfig
import com.betterclouddrive.mobile.feature.files.PreviewRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class DiagnosticsState(
    val office: OfficeConfig? = null,
    val status: UiMessage = UiMessage(MobileString.OfficeReady),
)

class DiagnosticsStore(
    private val repository: PreviewRepository,
    parentScope: CoroutineScope,
) : ResettableStore {
    private val scope = parentScope.storeChildScope()
    private val mutableState = MutableStateFlow(DiagnosticsState())
    val state: StateFlow<DiagnosticsState> = mutableState.asStateFlow()
    private var job: Job? = null

    fun loadOffice(teamId: Long, fileId: Long) {
        job?.cancel(); job = scope.launch {
            mutableState.update { it.copy(status = UiMessage(MobileString.OfficeConfigLoading)) }
            try {
                val config = repository.loadTeamOffice(teamId, fileId)
                mutableState.value = DiagnosticsState(config, UiMessage(MobileString.OfficeConfigLoaded))
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Throwable) { mutableState.update { it.copy(status = uiError(error)) } }
        }
    }

    fun updateStatus(value: UiMessage) = mutableState.update { it.copy(status = value) }

    override fun reset() { job?.cancel(); mutableState.value = DiagnosticsState() }
}
