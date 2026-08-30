package com.betterclouddrive.mobile.feature.files

import com.betterclouddrive.mobile.app.ResettableStore
import com.betterclouddrive.mobile.app.storeChildScope
import com.betterclouddrive.mobile.core.localization.MobileString
import com.betterclouddrive.mobile.core.localization.UiMessage
import com.betterclouddrive.mobile.core.model.FileItem
import com.betterclouddrive.mobile.core.model.OfficeConfig
import com.betterclouddrive.mobile.core.model.PreviewKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class PersonalPreviewState(
    val fileId: Long? = null,
    val file: FileItem? = null,
    val kind: PreviewKind? = null,
    val loading: Boolean = false,
    val error: UiMessage? = null,
    val mediaUrl: String? = null,
    val text: String? = null,
    val textTruncated: Boolean = false,
    val office: OfficeConfig? = null,
    val playbackPositionMillis: Long = 0,
    val ticketRetryCount: Int = 0,
)

class PersonalPreviewStore(
    private val repository: PreviewRepository,
    parentScope: CoroutineScope,
) : ResettableStore {
    private val scope = parentScope.storeChildScope()
    private val mutableState = MutableStateFlow(PersonalPreviewState())
    val state: StateFlow<PersonalPreviewState> = mutableState.asStateFlow()
    private var job: Job? = null

    fun open(fileId: Long) {
        val existing = state.value
        if (existing.fileId == fileId && (existing.loading || existing.file != null) && existing.error == null) return
        job?.cancel()
        val position = if (existing.fileId == fileId) existing.playbackPositionMillis else 0
        mutableState.value = PersonalPreviewState(fileId = fileId, loading = true, playbackPositionMillis = position)
        job = scope.launch {
            try {
                load(repository.getPersonalFile(fileId), position, 0)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                fail(fileId, UiMessage(MobileString.ErrorPreviewFailed))
            }
        }
    }

    fun retry() {
        val preview = state.value
        val fileId = preview.fileId ?: return
        job?.cancel()
        mutableState.value = preview.copy(loading = true, error = null, mediaUrl = null)
        job = scope.launch {
            try {
                load(preview.file ?: repository.getPersonalFile(fileId), preview.playbackPositionMillis, 0)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                fail(fileId, UiMessage(MobileString.ErrorPreviewFailed))
            }
        }
    }

    fun reportRendererError(message: UiMessage) {
        val preview = state.value
        val file = preview.file ?: return
        if (preview.kind in setOf(PreviewKind.IMAGE, PreviewKind.VIDEO) && preview.ticketRetryCount == 0) {
            job?.cancel()
            mutableState.value = preview.copy(loading = true, error = null, mediaUrl = null, ticketRetryCount = 1)
            job = scope.launch {
                try {
                    load(file, preview.playbackPositionMillis, 1)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Throwable) {
                    fail(file.id, UiMessage(MobileString.ErrorPreviewFailed))
                }
            }
        } else fail(file.id, message)
    }

    fun updatePosition(positionMillis: Long) {
        mutableState.update { if (it.kind == PreviewKind.VIDEO) it.copy(
            playbackPositionMillis = positionMillis.coerceAtLeast(0),
        ) else it }
    }

    fun close() = reset()

    override fun reset() {
        job?.cancel()
        job = null
        mutableState.value = PersonalPreviewState()
    }

    private suspend fun load(file: FileItem, position: Long, ticketRetryCount: Int) {
        val kind = file.previewKind()
        val base = PersonalPreviewState(
            fileId = file.id, file = file, kind = kind, loading = true,
            playbackPositionMillis = position, ticketRetryCount = ticketRetryCount,
        )
        mutableState.update { if (it.fileId == file.id) base else it }
        val loaded = when (kind) {
            PreviewKind.IMAGE, PreviewKind.VIDEO -> base.copy(
                loading = false, mediaUrl = repository.createPersonalDownloadTicket(file.id),
            )
            PreviewKind.TEXT -> repository.loadPersonalText(file).let {
                base.copy(loading = false, text = it.content, textTruncated = it.truncated)
            }
            PreviewKind.OFFICE -> base.copy(loading = false, office = repository.loadPersonalOffice(file.id))
            PreviewKind.UNSUPPORTED -> base.copy(loading = false)
        }
        mutableState.update { if (it.fileId == file.id) loaded else it }
    }

    private fun fail(fileId: Long, message: UiMessage) {
        mutableState.update { if (it.fileId == fileId) it.copy(
            loading = false, error = message, mediaUrl = null,
        ) else it }
    }
}
