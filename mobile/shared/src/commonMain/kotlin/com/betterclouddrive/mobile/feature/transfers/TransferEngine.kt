package com.betterclouddrive.mobile.feature.transfers

import com.betterclouddrive.mobile.core.model.ApiException
import com.betterclouddrive.mobile.core.model.SessionExpiredException
import com.betterclouddrive.mobile.core.network.NetworkDefaults
import com.betterclouddrive.mobile.core.network.TransferApi
import com.betterclouddrive.mobile.core.platform.TransferFileAccess
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.math.min

class TransferEngine(
    private val backend: TransferApi,
    private val repository: TransferRepository,
    private val files: TransferFileAccess,
) {
    private val executionMutex = Mutex()

    suspend fun run(taskId: String): TransferRunResult = executionMutex.withLock {
        val task = repository.find(taskId) ?: return@withLock TransferRunResult.COMPLETE
        if (task.status in TERMINAL) return@withLock TransferRunResult.COMPLETE
        if (task.status == TransferStatus.PAUSED || task.status == TransferStatus.WAITING_FOR_LOGIN) return@withLock TransferRunResult.PAUSED
        try {
            if (backend.serverBaseUrl != task.serverBaseUrl) {
                repository.updateActive(task.id) { it.copy(status = TransferStatus.FAILED, errorKey = "error.serverChanged") }
                return@withLock TransferRunResult.COMPLETE
            }
            when (task.direction) {
                TransferDirection.UPLOAD -> runUpload(task)
                TransferDirection.DOWNLOAD -> runDownload(task)
            }
            TransferRunResult.COMPLETE
        } catch (_: SessionExpiredException) {
            repository.updateActive(task.id) { it.copy(status = TransferStatus.WAITING_FOR_LOGIN, errorKey = "error.unauthorized") }
            TransferRunResult.PAUSED
        } catch (cancelled: CancellationException) {
            val status = repository.find(task.id)?.status
            if (status != TransferStatus.PAUSED && status != TransferStatus.CANCELED) throw cancelled
            TransferRunResult.PAUSED
        } catch (error: ApiException) {
            if (isRetryableTransferError(error.code)) {
                repository.updateActive(task.id) { it.copy(status = TransferStatus.QUEUED, retryCount = it.retryCount + 1, errorKey = error.messageKey) }
                TransferRunResult.RETRY
            } else {
                repository.updateActive(task.id) { it.copy(status = TransferStatus.FAILED, errorKey = error.messageKey) }
                TransferRunResult.COMPLETE
            }
        } catch (_: IllegalArgumentException) {
            repository.updateActive(task.id) { it.copy(status = TransferStatus.FAILED, errorKey = "error.invalidTransfer") }
            TransferRunResult.COMPLETE
        } catch (_: IllegalStateException) {
            repository.updateActive(task.id) { it.copy(status = TransferStatus.FAILED, errorKey = "error.fileUnavailable") }
            TransferRunResult.COMPLETE
        } catch (_: Throwable) {
            repository.updateActive(task.id) { it.copy(status = TransferStatus.QUEUED, retryCount = it.retryCount + 1, errorKey = "error.network") }
            TransferRunResult.RETRY
        }
    }

    private suspend fun runUpload(initial: TransferTask) {
        val source = initial.sourceHandle ?: error("transfer.sourceUnavailable")
        repository.updateActive(initial.id) { it.copy(status = TransferStatus.PREPARING, errorKey = null) }
        ensureRunnable(initial.id)
        files.validateReadable(source)

        val digest = files.computeDigest(source)
        if (initial.sessionId != null &&
            (initial.bytesTotal != digest.size || !initial.md5Hash.equals(digest.md5, ignoreCase = true))
        ) {
            throw IllegalStateException("transfer.sourceChanged")
        }
        val md5 = digest.md5
        var task = repository.updateActive(initial.id) {
            it.copy(md5Hash = md5, bytesTotal = digest.size, totalChunks = chunkCount(digest.size))
        } ?: return
        ensureRunnable(initial.id)

        if (task.sessionId == null) {
            try {
                val fileId = backend.instantUpload(task.scope, task.fileName, task.bytesTotal, md5)
                repository.updateActive(initial.id) {
                    it.copy(status = TransferStatus.SUCCEEDED, bytesDone = it.bytesTotal, remoteResultFileId = fileId, errorKey = null)
                }
                return
            } catch (error: ApiException) {
                if (error.code != NetworkDefaults.INSTANT_UPLOAD_MISS) throw error
            }
        }

        task = repository.find(initial.id) ?: return
        if (task.sessionId == null) {
            val session = backend.initUpload(task.scope, task.fileName, task.bytesTotal, md5, task.totalChunks)
            check(session.chunkSize == DEFAULT_CHUNK_SIZE) { "transfer.chunkSizeMismatch" }
            check(session.totalChunks == task.totalChunks) { "transfer.chunkCountMismatch" }
            task = repository.updateActive(task.id) {
                it.copy(sessionId = session.sessionId, chunkSize = session.chunkSize, totalChunks = session.totalChunks)
            } ?: return
        }

        val sessionId = requireNotNull(task.sessionId)
        val status = backend.uploadStatus(task.scope, sessionId)
        check(status.sessionId == sessionId) { "transfer.sessionMismatch" }
        check(status.totalChunks == task.totalChunks) { "transfer.statusChunkCountMismatch" }
        check(status.missingChunks.all { it in 0 until task.totalChunks }) { "transfer.invalidMissingChunk" }
        val missing = status.missingChunks.toSet()
        check(missing.size == status.missingChunks.size) { "transfer.duplicateMissingChunk" }
        val completedBytes = (0 until task.totalChunks)
            .filterNot(missing::contains)
            .sumOf { chunkLength(it, task.totalChunks, task.bytesTotal, task.chunkSize) }
        repository.updateActive(task.id) {
            it.copy(
                status = TransferStatus.RUNNING,
                bytesDone = completedBytes,
                errorKey = null,
            )
        }

        for (chunk in status.missingChunks.sorted()) {
            ensureRunnable(task.id)
            val offset = chunk * task.chunkSize
            val length = min(task.chunkSize, task.bytesTotal - offset).coerceAtLeast(0)
            coroutineScope {
                val progress = Channel<Long>(Channel.UNLIMITED)
                val collector = launch {
                    for (delta in progress) {
                        repository.updateActive(task.id) {
                            it.copy(bytesDone = min(it.bytesTotal, it.bytesDone + delta), status = TransferStatus.RUNNING)
                        }
                        ensureRunnable(task.id)
                    }
                }
                try {
                    backend.uploadChunk(
                        task.scope,
                        sessionId,
                        chunk,
                        task.fileName,
                        files.uploadPart(source, offset, length) { progress.trySend(it) },
                    )
                } finally {
                    progress.close()
                    collector.join()
                }
            }
        }

        ensureRunnable(task.id)
        val fileId = backend.completeUpload(task.scope, sessionId)
        repository.updateActive(task.id) {
            it.copy(status = TransferStatus.SUCCEEDED, bytesDone = it.bytesTotal, remoteResultFileId = fileId, errorKey = null)
        }
    }

    private suspend fun runDownload(initial: TransferTask) {
        val fileId = initial.remoteFileId ?: error("transfer.remoteFileMissing")
        val destination = initial.destinationHandle ?: error("transfer.destinationUnavailable")
        repository.updateActive(initial.id) { it.copy(status = TransferStatus.RUNNING, errorKey = null) }
        ensureRunnable(initial.id)
        files.validateWritable(destination)

        var offset = files.tempLength(initial.id)
        if (initial.bytesTotal > 0 && offset > initial.bytesTotal) {
            files.resetTemp(initial.id)
            offset = 0
        }
        repository.updateActive(initial.id) { it.copy(bytesDone = offset, status = TransferStatus.RUNNING) }
        var expectedTotal = initial.bytesTotal.takeIf { it > 0 }
        backend.download(initial.scope, fileId, offset) { status, headers, channel ->
            when (status) {
                HttpStatusCode.OK -> {
                    if (offset > 0) {
                        files.resetTemp(initial.id)
                        offset = 0
                        repository.updateActive(initial.id) { it.copy(bytesDone = 0) }
                    }
                    headers[HttpHeaders.ContentLength]?.toLongOrNull()?.let {
                        expectedTotal = it
                        repository.updateActive(initial.id) { task -> task.copy(bytesTotal = it) }
                    }
                    files.appendTemp(initial.id, channel) { count -> updateDownloadProgress(initial.id, count) }
                }
                HttpStatusCode.PartialContent -> {
                    val range = parseContentRange(headers[HttpHeaders.ContentRange])
                        ?: throw ApiException(416, "error.invalidDownloadRange")
                    if (range.start != offset || range.end < range.start || range.end >= range.total) {
                        throw ApiException(416, "error.invalidDownloadRange")
                    }
                    val rangeLength = range.end - range.start + 1
                    headers[HttpHeaders.ContentLength]?.toLongOrNull()?.let {
                        if (it != rangeLength) throw ApiException(416, "error.invalidDownloadRange")
                    }
                    expectedTotal?.let { if (it != range.total) throw ApiException(416, "error.invalidDownloadRange") }
                    expectedTotal = range.total
                    repository.updateActive(initial.id) { task -> task.copy(bytesTotal = range.total) }
                    files.appendTemp(initial.id, channel) { count -> updateDownloadProgress(initial.id, count) }
                }
                HttpStatusCode.RequestedRangeNotSatisfiable -> {
                    val total = parseUnsatisfiedTotal(headers[HttpHeaders.ContentRange])
                        ?: throw ApiException(416, "error.invalidDownloadRange")
                    if (offset != total || (expectedTotal != null && expectedTotal != total)) {
                        throw ApiException(416, "error.invalidDownloadRange")
                    }
                    expectedTotal = total
                    repository.updateActive(initial.id) { task -> task.copy(bytesTotal = total) }
                }
                else -> throw ApiException(status.value, if (status.value >= 500) "error.internal" else "error.downloadFailed")
            }
        }

        ensureRunnable(initial.id)
        val finalLength = files.tempLength(initial.id)
        val finalExpected = expectedTotal ?: finalLength
        if (finalLength != finalExpected) throw ApiException(422, "error.downloadIncomplete")
        initial.md5Hash?.let { expectedMd5 ->
            if (!files.tempMd5(initial.id).equals(expectedMd5, ignoreCase = true)) {
                files.deleteTemp(initial.id)
                throw ApiException(422, "error.integrity")
            }
        }
        files.commitTemp(initial.id, destination)
        files.deleteTemp(initial.id)
        repository.updateActive(initial.id) {
            it.copy(status = TransferStatus.SUCCEEDED, bytesDone = finalExpected, bytesTotal = finalExpected, errorKey = null)
        }
    }

    private suspend fun updateDownloadProgress(taskId: String, appended: Long) {
        repository.updateActive(taskId) { current ->
            val next = current.bytesDone + appended
            current.copy(
                bytesDone = if (current.bytesTotal > 0) min(current.bytesTotal, next) else next,
                status = TransferStatus.RUNNING,
            )
        }
        ensureRunnable(taskId)
    }

    private suspend fun ensureRunnable(taskId: String) {
        when (repository.find(taskId)?.status) {
            TransferStatus.PAUSED, TransferStatus.CANCELED -> throw CancellationException("transfer stopped")
            else -> Unit
        }
    }

    private companion object {
        val TERMINAL = setOf(TransferStatus.SUCCEEDED, TransferStatus.FAILED, TransferStatus.CANCELED)
    }
}

private data class ParsedContentRange(val start: Long, val end: Long, val total: Long)

private fun parseContentRange(value: String?): ParsedContentRange? {
    val match = value?.let { Regex("bytes (\\d+)-(\\d+)/(\\d+)").matchEntire(it.trim()) } ?: return null
    return ParsedContentRange(
        match.groupValues[1].toLongOrNull() ?: return null,
        match.groupValues[2].toLongOrNull() ?: return null,
        match.groupValues[3].toLongOrNull() ?: return null,
    )
}

private fun parseUnsatisfiedTotal(value: String?): Long? =
    value?.let { Regex("bytes \\*/(\\d+)").matchEntire(it.trim()) }?.groupValues?.get(1)?.toLongOrNull()

private fun chunkLength(index: Int, totalChunks: Int, totalBytes: Long, chunkSize: Long): Long =
    if (index == totalChunks - 1) (totalBytes - index * chunkSize).coerceAtLeast(0) else chunkSize

internal fun isRetryableTransferError(code: Int): Boolean =
    code == 429 || code in 500..599 || code in 500_000..599_999
