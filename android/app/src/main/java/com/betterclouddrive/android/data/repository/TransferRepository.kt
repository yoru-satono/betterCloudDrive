package com.betterclouddrive.android.data.repository

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.betterclouddrive.android.data.remote.ApiService
import com.betterclouddrive.android.util.Constants
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.UUID
import javax.inject.Singleton
import kotlin.math.max

enum class TransferDirection { UPLOAD, DOWNLOAD }

enum class TransferStatus { PENDING, HASHING, RUNNING, PAUSED, DONE, ERROR, CANCELED }

data class TransferTask(
    val id: String,
    val direction: TransferDirection,
    val fileName: String,
    val bytesDone: Long = 0,
    val bytesTotal: Long = 0,
    val status: TransferStatus = TransferStatus.PENDING,
    val error: String? = null,
    val remoteFileId: Long? = null,
    val remoteParentId: Long? = null,
    val sourceUri: Uri? = null,
    val targetUri: Uri? = null,
    val sessionId: String? = null,
    val md5Hash: String? = null,
    val chunkSize: Long = Constants.CHUNK_SIZE,
    val completedChunks: Set<Int> = emptySet(),
)

@Singleton
class TransferRepository(
    private val api: ApiService,
    @ApplicationContext private val context: Context,
) {
    private val _tasks = MutableStateFlow<List<TransferTask>>(emptyList())
    val tasks: StateFlow<List<TransferTask>> = _tasks.asStateFlow()
    private val jobs = mutableMapOf<String, Job>()

    fun enqueueUpload(parentId: Long?, uri: Uri): TransferTask {
        val task = TransferTask(
            id = UUID.randomUUID().toString(),
            direction = TransferDirection.UPLOAD,
            fileName = getDisplayName(uri),
            bytesTotal = getSize(uri),
            remoteParentId = parentId,
            sourceUri = uri,
        )
        upsert(task)
        return task
    }

    fun enqueueDownload(fileId: Long, fileName: String, fileSize: Long, targetUri: Uri): TransferTask {
        val task = TransferTask(
            id = UUID.randomUUID().toString(),
            direction = TransferDirection.DOWNLOAD,
            fileName = fileName,
            bytesTotal = fileSize,
            remoteFileId = fileId,
            targetUri = targetUri,
        )
        upsert(task)
        return task
    }

    suspend fun runTask(taskId: String) {
        val task = current(taskId) ?: return
        when (task.direction) {
            TransferDirection.UPLOAD -> runUpload(task)
            TransferDirection.DOWNLOAD -> runDownload(task)
        }
    }

    fun start(taskId: String, scope: CoroutineScope, onCompletion: (() -> Unit)? = null) {
        val job = scope.launch { runTask(taskId) }
        bindJob(taskId, job)
        if (onCompletion != null) job.invokeOnCompletion { onCompletion() }
    }

    fun bindJob(taskId: String, job: Job) {
        jobs[taskId] = job
        job.invokeOnCompletion { jobs.remove(taskId) }
    }

    fun pause(taskId: String) {
        val task = current(taskId) ?: return
        if (task.status == TransferStatus.RUNNING || task.status == TransferStatus.HASHING || task.status == TransferStatus.PENDING) {
            update(taskId) { it.copy(status = TransferStatus.PAUSED) }
            jobs[taskId]?.cancel(CancellationException("paused"))
        }
    }

    fun cancel(taskId: String) {
        update(taskId) { it.copy(status = TransferStatus.CANCELED, error = null) }
        jobs[taskId]?.cancel(CancellationException("canceled"))
    }

    fun clearFinished() {
        val terminal = setOf(TransferStatus.DONE, TransferStatus.ERROR, TransferStatus.CANCELED)
        _tasks.value = _tasks.value.filterNot { it.status in terminal }
    }

    private suspend fun runUpload(initialTask: TransferTask) = withContext(Dispatchers.IO) {
        val sourceUri = initialTask.sourceUri ?: return@withContext markError(initialTask.id, "上传源不可用")
        try {
            update(initialTask.id) { it.copy(status = TransferStatus.HASHING, error = null) }
            val md5 = initialTask.md5Hash ?: computeMd5(sourceUri)
            ensureNotPausedOrCanceled(initialTask.id)
            val fileSize = max(initialTask.bytesTotal, getSize(sourceUri))
            val totalChunks = calculateUploadTotalChunks(fileSize)

            val instant = api.instantUpload(
                com.betterclouddrive.android.data.remote.dto.InstantUploadRequest(
                    parentId = initialTask.remoteParentId,
                    fileName = initialTask.fileName,
                    fileSize = fileSize,
                    md5Hash = md5,
                ),
            )
            if (instant.code == 200 && instant.data?.instant == true) {
                update(initialTask.id) {
                    it.copy(
                        status = TransferStatus.DONE,
                        bytesDone = fileSize,
                        bytesTotal = fileSize,
                        md5Hash = md5,
                        error = null,
                    )
                }
                return@withContext
            }

            val existing = current(initialTask.id)
            val init = if (existing?.sessionId == null) {
                api.initUpload(
                    com.betterclouddrive.android.data.remote.dto.InitUploadRequest(
                        parentId = initialTask.remoteParentId,
                        fileName = initialTask.fileName,
                        fileSize = fileSize,
                        md5Hash = md5,
                        totalChunks = totalChunks,
                    ),
                )
            } else {
                null
            }
            val sessionId = existing?.sessionId ?: init?.data?.sessionId
                ?: return@withContext markError(initialTask.id, init?.message ?: "初始化上传失败")
            val chunkSize = init?.data?.chunkSize ?: Constants.CHUNK_SIZE

            val completed = mutableSetOf<Int>()
            val status = api.getUploadStatus(sessionId)
            if (status.code == 200) {
                val missing = status.data?.missingChunks?.toSet().orEmpty()
                if (missing.isEmpty() && status.data?.uploadedChunks != null) {
                    completed.addAll(0 until status.data.uploadedChunks)
                } else {
                    completed.addAll((0 until totalChunks).filterNot { it in missing })
                }
            }
            update(initialTask.id) {
                it.copy(
                    status = TransferStatus.RUNNING,
                    sessionId = sessionId,
                    md5Hash = md5,
                    bytesTotal = fileSize,
                    bytesDone = completed.sumOf { chunk -> chunkLength(chunk, totalChunks, fileSize, chunkSize) },
                    completedChunks = completed,
                    error = null,
                )
            }

            for (chunkNumber in uploadChunkNumbers(totalChunks)) {
                ensureNotPausedOrCanceled(initialTask.id)
                if (chunkNumber in current(initialTask.id)?.completedChunks.orEmpty()) continue
                val chunk = readChunk(sourceUri, chunkNumber * chunkSize, chunkSize)
                val requestBody = chunk.toRequestBody("application/octet-stream".toMediaType())
                val part = MultipartBody.Part.createFormData("file", "chunk_$chunkNumber", requestBody)
                val response = api.uploadChunk(sessionId, part, chunkNumber)
                if (response.code != 200) {
                    return@withContext markError(initialTask.id, response.message)
                }
                val doneSet = current(initialTask.id)?.completedChunks.orEmpty() + chunkNumber
                update(initialTask.id) {
                    it.copy(
                        status = TransferStatus.RUNNING,
                        bytesDone = doneSet.sumOf { chunkIndex -> chunkLength(chunkIndex, totalChunks, fileSize, chunkSize) },
                        completedChunks = doneSet,
                        error = null,
                    )
                }
            }

            ensureNotPausedOrCanceled(initialTask.id)
            val complete = api.completeUpload(sessionId)
            if (complete.code == 200) {
                update(initialTask.id) {
                    it.copy(status = TransferStatus.DONE, bytesDone = fileSize, bytesTotal = fileSize, error = null)
                }
            } else {
                markError(initialTask.id, complete.message)
            }
        } catch (e: CancellationException) {
            if (current(initialTask.id)?.status != TransferStatus.CANCELED) {
                update(initialTask.id) { it.copy(status = TransferStatus.PAUSED) }
            }
        } catch (e: Exception) {
            markError(initialTask.id, e.message ?: "上传失败")
        }
    }

    private suspend fun runDownload(initialTask: TransferTask) = withContext(Dispatchers.IO) {
        val remoteFileId = initialTask.remoteFileId ?: return@withContext markError(initialTask.id, "下载文件不可用")
        val targetUri = initialTask.targetUri ?: return@withContext markError(initialTask.id, "下载位置不可用")
        val tempFile = File(context.cacheDir, "bcd-download-${initialTask.id}.part")
        try {
            update(initialTask.id) {
                it.copy(status = TransferStatus.RUNNING, bytesDone = tempFile.length(), error = null)
            }
            val existingBytes = tempFile.length()
            val body = api.downloadFile(
                fileId = remoteFileId,
                range = if (existingBytes > 0) "bytes=$existingBytes-" else null,
            )
            val contentLength = body.contentLength().takeIf { it > 0 }
            val appending = existingBytes > 0 && contentLength != null && initialTask.bytesTotal > existingBytes &&
                contentLength <= initialTask.bytesTotal - existingBytes
            if (existingBytes > 0 && !appending) {
                tempFile.delete()
            }
            val startingBytes = if (appending) existingBytes else 0L
            val total = contentLength?.let { it + startingBytes } ?: max(initialTask.bytesTotal, startingBytes)
            body.byteStream().use { input ->
                FileOutputStream(tempFile, appending).use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var bytesDone = startingBytes
                    while (true) {
                        ensureNotPausedOrCanceled(initialTask.id)
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        bytesDone += read
                        update(initialTask.id) {
                            it.copy(
                                status = TransferStatus.RUNNING,
                                bytesDone = bytesDone,
                                bytesTotal = if (total > 0) total else bytesDone,
                                error = null,
                            )
                        }
                    }
                }
            }
            ensureNotPausedOrCanceled(initialTask.id)
            context.contentResolver.openOutputStream(targetUri, "wt")?.use { target ->
                tempFile.inputStream().use { source -> source.copyTo(target) }
            } ?: return@withContext markError(initialTask.id, "无法写入目标位置")
            update(initialTask.id) {
                it.copy(
                    status = TransferStatus.DONE,
                    bytesDone = tempFile.length(),
                    bytesTotal = max(it.bytesTotal, tempFile.length()),
                    error = null,
                )
            }
            tempFile.delete()
        } catch (e: CancellationException) {
            if (current(initialTask.id)?.status != TransferStatus.CANCELED) {
                update(initialTask.id) { it.copy(status = TransferStatus.PAUSED) }
            }
        } catch (e: Exception) {
            markError(initialTask.id, e.message ?: "下载失败")
        }
    }

    private fun getDisplayName(uri: Uri): String {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index >= 0 && cursor.moveToFirst()) return cursor.getString(index)
        }
        return uri.lastPathSegment ?: "未命名文件"
    }

    private fun getSize(uri: Uri): Long {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(OpenableColumns.SIZE)
            if (index >= 0 && cursor.moveToFirst()) return cursor.getLong(index)
        }
        return 0L
    }

    private fun computeMd5(uri: Uri): String {
        val digest = MessageDigest.getInstance("MD5")
        context.contentResolver.openInputStream(uri)?.use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun readChunk(uri: Uri, offset: Long, length: Long): ByteArray {
        context.contentResolver.openInputStream(uri)?.use { input ->
            var skipped = 0L
            while (skipped < offset) {
                val step = input.skip(offset - skipped)
                if (step <= 0) break
                skipped += step
            }
            val target = length.toInt()
            val buffer = ByteArray(target)
            var totalRead = 0
            while (totalRead < target) {
                val read = input.read(buffer, totalRead, target - totalRead)
                if (read < 0) break
                totalRead += read
            }
            return buffer.copyOf(totalRead)
        }
        return ByteArray(0)
    }

    private fun chunkLength(chunkIndex: Int, totalChunks: Int, fileSize: Long, chunkSize: Long): Long {
        return if (chunkIndex == totalChunks - 1) {
            fileSize - (chunkIndex * chunkSize)
        } else {
            chunkSize
        }.coerceAtLeast(0)
    }

    private suspend fun ensureNotPausedOrCanceled(taskId: String) {
        val task = current(taskId) ?: throw CancellationException("missing task")
        if (task.status == TransferStatus.CANCELED) throw CancellationException("canceled")
        if (task.status == TransferStatus.PAUSED) throw CancellationException("paused")
        delay(1)
    }

    private fun current(taskId: String): TransferTask? = _tasks.value.firstOrNull { it.id == taskId }

    private fun upsert(task: TransferTask) {
        _tasks.value = _tasks.value.filterNot { it.id == task.id } + task
    }

    private fun update(taskId: String, transform: (TransferTask) -> TransferTask) {
        _tasks.value = _tasks.value.map { task -> if (task.id == taskId) transform(task) else task }
    }

    private fun markError(taskId: String, message: String) {
        update(taskId) { it.copy(status = TransferStatus.ERROR, error = message) }
    }
}

internal fun calculateUploadTotalChunks(fileSize: Long, chunkSize: Long = Constants.CHUNK_SIZE): Int {
    return if (fileSize == 0L) {
        0
    } else {
        ((fileSize + chunkSize - 1) / chunkSize).toInt()
    }
}

internal fun uploadChunkNumbers(totalChunks: Int): IntRange = 0 until totalChunks
