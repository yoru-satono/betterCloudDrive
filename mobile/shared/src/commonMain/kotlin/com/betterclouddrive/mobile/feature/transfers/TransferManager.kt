package com.betterclouddrive.mobile.feature.transfers

import com.betterclouddrive.mobile.core.network.BackendClient
import com.betterclouddrive.mobile.core.platform.PickedDocument
import com.betterclouddrive.mobile.core.platform.TransferFileAccess
import com.betterclouddrive.mobile.core.platform.TransferScheduler
import com.betterclouddrive.mobile.core.platform.TransferScheduleSpec
import kotlinx.coroutines.flow.Flow

class TransferManager(
    private val backend: BackendClient,
    private val repository: TransferRepository,
    private val scheduler: TransferScheduler,
    private val files: TransferFileAccess,
) {
    val tasks: Flow<List<TransferTask>> = repository.tasks

    suspend fun enqueueUpload(document: PickedDocument, scope: TransferScope = TransferScope.Personal()): TransferTask {
        files.validateReadable(document.handle)
        val task = repository.enqueueUpload(backend.serverBaseUrl, document, scope)
        schedule(task)
        return task
    }

    suspend fun enqueueDownload(
        fileId: Long,
        fileName: String,
        fileSize: Long,
        mimeType: String?,
        destinationHandle: String,
        scope: TransferScope = TransferScope.Personal(),
        expectedMd5: String? = null,
    ): TransferTask {
        files.validateWritable(destinationHandle)
        val task = repository.enqueueDownload(
            backend.serverBaseUrl, fileId, fileName, fileSize, destinationHandle, scope, mimeType,
        )
        val enriched = if (expectedMd5 == null) task else repository.update(task.id) { it.copy(md5Hash = expectedMd5) } ?: task
        schedule(enriched)
        return enriched
    }

    suspend fun pause(taskId: String) {
        repository.update(taskId) {
            if (it.status in setOf(TransferStatus.QUEUED, TransferStatus.PREPARING, TransferStatus.RUNNING)) {
                it.copy(status = TransferStatus.PAUSED, errorKey = null)
            } else it
        }
        scheduler.cancel(taskId)
    }

    suspend fun resume(taskId: String) {
        val task = repository.update(taskId) {
            if (it.status in setOf(TransferStatus.PAUSED, TransferStatus.FAILED, TransferStatus.WAITING_FOR_LOGIN)) {
                it.copy(status = TransferStatus.QUEUED, errorKey = null)
            } else it
        } ?: return
        if (task.status != TransferStatus.QUEUED) return
        schedule(task)
    }

    suspend fun cancel(taskId: String) {
        val task = repository.update(taskId) {
            if (it.status in setOf(TransferStatus.SUCCEEDED, TransferStatus.CANCELED)) it
            else it.copy(status = TransferStatus.CANCELED, errorKey = null)
        } ?: return
        if (task.status != TransferStatus.CANCELED) return
        scheduler.cancel(taskId)
        files.deleteTemp(taskId)
        if (task.direction == TransferDirection.UPLOAD && task.sessionId != null) {
            runCatching { backend.cancelUpload(task.scope, task.sessionId) }
        }
    }

    suspend fun retry(taskId: String) = resume(taskId)

    suspend fun resumeWaitingForLogin() {
        repository.resumeWaitingForLogin().forEach { schedule(it) }
    }

    suspend fun clearForLogout() {
        scheduler.cancelAll()
        files.deleteAllTemps()
        repository.clear()
    }

    suspend fun reconcile() {
        val scheduled = scheduler.scheduledTaskIds()
        val tasks = repository.all()
        val byId = tasks.associateBy(TransferTask::id)
        scheduled.filter { id -> byId[id]?.status !in SCHEDULABLE }.forEach { scheduler.cancel(it) }
        tasks.forEach { task ->
            val current = if (task.status in setOf(TransferStatus.PREPARING, TransferStatus.RUNNING) && task.id !in scheduled) {
                repository.update(task.id) { it.copy(status = TransferStatus.QUEUED, errorKey = "error.interrupted") } ?: task
            } else task
            if (current.status == TransferStatus.QUEUED && current.id !in scheduled) schedule(current)
        }
    }

    suspend fun waitForLogin() = repository.markActiveWaitingForLogin()

    private suspend fun schedule(task: TransferTask) {
        scheduler.schedule(
            TransferScheduleSpec(
                taskId = task.id,
                direction = task.direction,
                remainingBytes = (task.bytesTotal - task.bytesDone).coerceAtLeast(0),
            ),
        )
    }

    private companion object {
        val SCHEDULABLE = setOf(
            TransferStatus.QUEUED,
            TransferStatus.PREPARING,
            TransferStatus.RUNNING,
        )
    }
}
