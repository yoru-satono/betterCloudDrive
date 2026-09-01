package com.betterclouddrive.mobile.feature.transfers

import com.betterclouddrive.mobile.core.network.TransferApi
import com.betterclouddrive.mobile.core.platform.PickedDocument
import com.betterclouddrive.mobile.core.platform.TransferFileAccess
import com.betterclouddrive.mobile.core.platform.TransferScheduler
import com.betterclouddrive.mobile.core.platform.TransferScheduleSpec
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.random.Random

class TransferManager(
    private val backend: TransferApi,
    private val repository: TransferRepository,
    private val scheduler: TransferScheduler,
    private val files: TransferFileAccess,
) {
    private val schedulingMutex = Mutex()
    val tasks: Flow<List<TransferTask>> = repository.tasks

    suspend fun find(taskId: String): TransferTask? = repository.find(taskId)

    suspend fun enqueueUpload(document: PickedDocument, scope: TransferScope = TransferScope.Personal()): TransferTask {
        return enqueueUploads(listOf(document), scope).tasks.single()
    }

    suspend fun enqueueUploads(
        documents: List<PickedDocument>,
        scope: TransferScope = TransferScope.Personal(),
        rejectedCount: Int = 0,
        overflowCount: Int = 0,
    ): UploadEnqueueResult {
        val accepted = mutableListOf<PickedDocument>()
        var rejected = rejectedCount
        documents.take(MAX_UPLOAD_SELECTION).forEach { document ->
            try {
                files.validateReadable(document.handle)
                accepted += document
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                runCatching { files.releaseReadable(document.handle) }
                rejected++
            }
        }
        val overflow = overflowCount + (documents.size - MAX_UPLOAD_SELECTION).coerceAtLeast(0)
        val created = if (accepted.isEmpty()) emptyList()
        else repository.enqueueUploads(backend.serverBaseUrl, accepted, scope)
        if (created.isNotEmpty()) scheduleBatch(created)
        return UploadEnqueueResult(created, rejected, overflow)
    }

    suspend fun enqueueDownload(
        fileId: Long,
        fileName: String,
        fileSize: Long,
        mimeType: String?,
        destinationHandle: String,
        scope: TransferScope = TransferScope.Personal(),
        expectedMd5: String? = null,
        versionNumber: Int? = null,
    ): TransferTask {
        files.validateWritable(destinationHandle)
        val task = repository.enqueueDownload(
            backend.serverBaseUrl, fileId, fileName, fileSize, destinationHandle, scope, mimeType, versionNumber,
        )
        val enriched = if (expectedMd5 == null) task else repository.update(task.id) { it.copy(md5Hash = expectedMd5) } ?: task
        scheduleBatch(listOf(enriched))
        return enriched
    }

    suspend fun pause(taskId: String) {
        repository.update(taskId) {
            if (it.status in setOf(TransferStatus.QUEUED, TransferStatus.PREPARING, TransferStatus.RUNNING)) {
                it.copy(status = TransferStatus.PAUSED, errorKey = null)
            } else it
        }
    }

    suspend fun resume(taskId: String) {
        val task = repository.update(taskId) {
            if (it.status in setOf(TransferStatus.PAUSED, TransferStatus.FAILED, TransferStatus.WAITING_FOR_LOGIN)) {
                it.copy(status = TransferStatus.QUEUED, errorKey = null)
            } else it
        } ?: return
        if (task.status != TransferStatus.QUEUED) return
        scheduleBatch(listOf(task))
    }

    suspend fun cancel(taskId: String) {
        val task = repository.update(taskId) {
            if (it.status in setOf(TransferStatus.SUCCEEDED, TransferStatus.CANCELED)) it
            else it.copy(status = TransferStatus.CANCELED, errorKey = null)
        } ?: return
        if (task.status != TransferStatus.CANCELED) return
        if (task.direction == TransferDirection.UPLOAD && task.sessionId != null) {
            runCatching { backend.cancelUpload(task.scope, task.sessionId) }
        }
        cleanupTaskResources(task)
    }

    suspend fun retry(taskId: String) = resume(taskId)

    suspend fun resumeWaitingForLogin() {
        repository.resumeWaitingForLogin().chunked(MAX_UPLOAD_SELECTION).forEach { scheduleBatch(it) }
    }

    suspend fun clearForLogout() {
        scheduler.cancelAll()
        val existing = repository.all()
        existing.forEach { task ->
            task.sourceHandle?.let { runCatching { files.releaseReadable(it) } }
            task.destinationHandle?.let { runCatching { files.releaseWritable(it) } }
        }
        files.deleteAllTemps()
        repository.clear()
    }

    suspend fun clearFinished(): Int {
        val finished = repository.all().filter { it.status in TERMINAL }
        if (finished.isEmpty()) return 0
        finished.filter { it.status == TransferStatus.FAILED && it.direction == TransferDirection.UPLOAD && it.sessionId != null }
            .forEach { task -> runCatching { backend.cancelUpload(task.scope, requireNotNull(task.sessionId)) } }
        repository.delete(finished.map(TransferTask::id))
        finished.forEach { cleanupTaskResources(it) }
        return finished.size
    }

    suspend fun onTaskSettled(taskId: String) {
        val task = repository.find(taskId) ?: return
        if (task.status == TransferStatus.SUCCEEDED || task.status == TransferStatus.CANCELED) {
            cleanupTaskResources(task)
        }
    }

    suspend fun reconcile() {
        val scheduled = scheduler.scheduledTaskIds()
        val tasks = repository.all()
        tasks.filter {
            it.id !in scheduled && (it.status == TransferStatus.PREPARING || it.status == TransferStatus.RUNNING)
        }
            .forEach { task -> repository.update(task.id) { it.copy(status = TransferStatus.QUEUED, errorKey = "error.interrupted") } }
        repository.all().filter { it.status == TransferStatus.QUEUED && it.id !in scheduled }
            .chunked(MAX_UPLOAD_SELECTION)
            .forEach { scheduleBatch(it) }
    }

    suspend fun waitForLogin() = repository.markActiveWaitingForLogin()

    private suspend fun scheduleBatch(tasks: List<TransferTask>) = schedulingMutex.withLock {
        val queued = tasks.filter { it.status == TransferStatus.QUEUED }.take(MAX_UPLOAD_SELECTION)
        if (queued.isEmpty()) return@withLock
        val scheduled = scheduler.schedule(
            TransferScheduleSpec(
                batchId = "${queued.first().id}-${Random.nextLong().toString(16)}",
                taskIds = queued.map(TransferTask::id),
                uploadBytes = queued.filter { it.direction == TransferDirection.UPLOAD }.remainingBytes(),
                downloadBytes = queued.filter { it.direction == TransferDirection.DOWNLOAD }.remainingBytes(),
            ),
        )
        if (!scheduled) {
            queued.forEach { task ->
                repository.update(task.id) { current ->
                    if (current.status == TransferStatus.QUEUED) {
                        current.copy(status = TransferStatus.FAILED, errorKey = "error.backgroundScheduling")
                    } else current
                }
            }
        }
    }

    private suspend fun cleanupTaskResources(task: TransferTask) {
        files.deleteTemp(task.id)
        val remaining = repository.all()
        task.sourceHandle?.let { handle ->
            if (remaining.none { it.id != task.id && it.sourceHandle == handle && it.status !in RELEASED }) {
                runCatching { files.releaseReadable(handle) }
            }
        }
        task.destinationHandle?.let { handle ->
            if (remaining.none { it.id != task.id && it.destinationHandle == handle && it.status !in RELEASED }) {
                runCatching { files.releaseWritable(handle) }
            }
        }
    }

    private companion object {
        val TERMINAL = setOf(TransferStatus.SUCCEEDED, TransferStatus.FAILED, TransferStatus.CANCELED)
        val RELEASED = setOf(TransferStatus.SUCCEEDED, TransferStatus.CANCELED)
    }
}

private fun List<TransferTask>.remainingBytes(): Long = fold(0L) { total, task ->
    val remaining = (task.bytesTotal - task.bytesDone).coerceAtLeast(0)
    if (Long.MAX_VALUE - total < remaining) Long.MAX_VALUE else total + remaining
}
