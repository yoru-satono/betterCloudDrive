package com.betterclouddrive.mobile.app

import com.betterclouddrive.mobile.core.localization.MobileString
import com.betterclouddrive.mobile.core.localization.UiMessage
import com.betterclouddrive.mobile.core.model.ApiException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob

interface ResettableStore {
    fun reset()
}

internal fun CoroutineScope.storeChildScope(): CoroutineScope =
    CoroutineScope(coroutineContext + SupervisorJob(coroutineContext[Job]))

data class TransferEnqueueFeedback(
    val id: Long,
    val message: UiMessage,
    val canOpenTransfers: Boolean,
)

data class FileMutationFeedback(
    val id: Long,
    val message: UiMessage,
    val isError: Boolean,
)

internal fun uiError(error: Throwable): UiMessage = when (error) {
    is CancellationException -> throw error
    is ApiException -> when (error.messageKey) {
        "error.unauthorized", "error.invalidCredentials" -> UiMessage(MobileString.ErrorCredentials)
        "error.storageQuotaExceeded" -> UiMessage(MobileString.ErrorQuota)
        "error.fileNameExists", "error.fileNameConflict" -> UiMessage(MobileString.ErrorNameConflict)
        "error.tagNameConflict" -> UiMessage(MobileString.ErrorTagNameConflict)
        "error.cannotDeleteOnlyVersion" -> UiMessage(MobileString.ErrorCannotDeleteOnlyVersion)
        "error.invalidTagName" -> UiMessage(MobileString.ErrorInvalidTagName)
        "error.invalidTagColor" -> UiMessage(MobileString.ErrorInvalidTagColor)
        "error.invalidFileDestination" -> UiMessage(MobileString.ErrorInvalidDestination)
        "error.invalidFileName", "error.validation", "error.invalidParameter" -> UiMessage(MobileString.ErrorInvalidFileName)
        "error.fileNotFound", "error.notFound" -> UiMessage(MobileString.ErrorNotFound)
        "error.rateLimited" -> UiMessage(MobileString.ErrorRateLimited)
        "error.emailCodeExpired" -> UiMessage(MobileString.ErrorVerificationExpired)
        "error.emailCodeMismatch", "error.emailVerificationFailed" -> UiMessage(MobileString.ErrorVerificationCode)
        "error.usernameExists", "error.emailExists", "error.accountConflict" -> UiMessage(MobileString.ErrorAccountConflict)
        else -> error.requestId?.takeIf(String::isNotBlank)
            ?.let { UiMessage(MobileString.ErrorRequestWithId, listOf(it)) }
            ?: UiMessage(MobileString.ErrorRequest)
    }
    is IllegalArgumentException -> when (error.message) {
        "server.empty" -> UiMessage(MobileString.ErrorServerEmpty)
        "server.scheme" -> UiMessage(MobileString.ErrorServerScheme)
        "server.httpsRequired" -> UiMessage(MobileString.ErrorServerHttps)
        "server.hostMissing" -> UiMessage(MobileString.ErrorServerHost)
        else -> UiMessage(MobileString.ErrorInvalidInput)
    }
    else -> UiMessage(MobileString.ErrorNetwork)
}
