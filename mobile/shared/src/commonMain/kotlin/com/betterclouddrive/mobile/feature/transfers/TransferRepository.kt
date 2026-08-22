package com.betterclouddrive.mobile.feature.transfers

import com.betterclouddrive.mobile.core.database.TransferTaskDao
import com.betterclouddrive.mobile.core.database.TransferTaskEntity
import com.betterclouddrive.mobile.core.platform.PickedDocument
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.random.Random

class TransferRepository(
    private val dao: TransferTaskDao,
    private val now: () -> Long = { kotlin.time.Clock.System.now().toEpochMilliseconds() },
) {
    private val updateMutex = Mutex()
    val tasks: Flow<List<TransferTask>> = dao.observeAll().map { rows -> rows.map(TransferTaskEntity::toModel) }

    suspend fun enqueueUpload(server: String, document: PickedDocument, scope: TransferScope): TransferTask {
        val timestamp = now()
        val task = TransferTask(
            id = newId(timestamp),
            direction = TransferDirection.UPLOAD,
            status = TransferStatus.QUEUED,
            serverBaseUrl = server,
            scope = scope,
            fileName = document.displayName,
            mimeType = document.mimeType,
            bytesTotal = document.size,
            sourceHandle = document.handle,
            totalChunks = chunkCount(document.size),
            createdAt = timestamp,
            updatedAt = timestamp,
        )
        dao.upsert(task.toEntity())
        return task
    }

    suspend fun enqueueDownload(
        server: String,
        fileId: Long,
        fileName: String,
        fileSize: Long,
        destinationHandle: String,
        scope: TransferScope,
        mimeType: String?,
    ): TransferTask {
        val timestamp = now()
        val task = TransferTask(
            id = newId(timestamp),
            direction = TransferDirection.DOWNLOAD,
            status = TransferStatus.QUEUED,
            serverBaseUrl = server,
            scope = scope,
            remoteFileId = fileId,
            fileName = fileName,
            mimeType = mimeType,
            bytesTotal = fileSize,
            destinationHandle = destinationHandle,
            createdAt = timestamp,
            updatedAt = timestamp,
        )
        dao.upsert(task.toEntity())
        return task
    }

    suspend fun find(id: String): TransferTask? = dao.find(id)?.toModel()
    suspend fun all(): List<TransferTask> = dao.all().map(TransferTaskEntity::toModel)

    suspend fun update(id: String, transform: (TransferTask) -> TransferTask): TransferTask? = updateMutex.withLock {
        val current = dao.find(id)?.toModel() ?: return@withLock null
        val updated = transform(current).copy(updatedAt = now())
        dao.upsert(updated.toEntity())
        updated
    }

    suspend fun updateActive(id: String, transform: (TransferTask) -> TransferTask): TransferTask? = update(id) { current ->
        if (current.status in setOf(TransferStatus.QUEUED, TransferStatus.PREPARING, TransferStatus.RUNNING)) transform(current)
        else current
    }

    suspend fun clear() = updateMutex.withLock { dao.deleteAll() }

    suspend fun markActiveWaitingForLogin() {
        dao.all().map(TransferTaskEntity::toModel)
            .filter { it.status in setOf(TransferStatus.QUEUED, TransferStatus.PREPARING, TransferStatus.RUNNING) }
            .forEach { task -> updateActive(task.id) { it.copy(status = TransferStatus.WAITING_FOR_LOGIN) } }
    }

    suspend fun resumeWaitingForLogin(): List<TransferTask> {
        val waiting = all().filter { it.status == TransferStatus.WAITING_FOR_LOGIN }
        waiting.forEach { task ->
            update(task.id) { current ->
                if (current.status == TransferStatus.WAITING_FOR_LOGIN) current.copy(status = TransferStatus.QUEUED, errorKey = null)
                else current
            }
        }
        return waiting.mapNotNull { find(it.id) }
    }

    suspend fun reconcileInterrupted(): List<TransferTask> {
        val resumable = dao.all().map(TransferTaskEntity::toModel)
            .filter { it.status == TransferStatus.PREPARING || it.status == TransferStatus.RUNNING }
        resumable.forEach { task ->
            updateActive(task.id) { current -> current.copy(status = TransferStatus.QUEUED) }
        }
        return resumable
    }

    private fun newId(timestamp: Long): String = "$timestamp-${Random.nextLong().toString(16)}"
}

fun chunkCount(size: Long): Int {
    require(size >= 0) { "文件大小不能为负数" }
    if (size == 0L) return 0
    val count = ((size - 1) / DEFAULT_CHUNK_SIZE) + 1
    require(count <= Int.MAX_VALUE) { "文件分片数量超出支持范围" }
    return count.toInt()
}

private fun TransferTaskEntity.toModel(): TransferTask = TransferTask(
    id = id,
    direction = TransferDirection.valueOf(direction),
    status = TransferStatus.valueOf(status),
    serverBaseUrl = serverBaseUrl,
    scope = if (teamId == null) TransferScope.Personal(remoteParentId) else TransferScope.Team(teamId, remoteParentId),
    remoteFileId = remoteFileId,
    remoteResultFileId = remoteResultFileId,
    fileName = fileName,
    mimeType = mimeType,
    bytesDone = bytesDone,
    bytesTotal = bytesTotal,
    sourceHandle = sourceHandle,
    destinationHandle = destinationHandle,
    sessionId = sessionId,
    chunkSize = chunkSize,
    totalChunks = totalChunks,
    md5Hash = md5Hash,
    retryCount = retryCount,
    errorKey = errorKey,
    createdAt = createdAt,
    updatedAt = updatedAt,
)

private fun TransferTask.toEntity(): TransferTaskEntity = TransferTaskEntity(
    id = id,
    direction = direction.name,
    status = status.name,
    serverBaseUrl = serverBaseUrl,
    teamId = (scope as? TransferScope.Team)?.teamId,
    remoteParentId = scope.parentId,
    remoteFileId = remoteFileId,
    remoteResultFileId = remoteResultFileId,
    fileName = fileName,
    mimeType = mimeType,
    bytesDone = bytesDone,
    bytesTotal = bytesTotal,
    sourceHandle = sourceHandle,
    destinationHandle = destinationHandle,
    sessionId = sessionId,
    chunkSize = chunkSize,
    totalChunks = totalChunks,
    md5Hash = md5Hash,
    retryCount = retryCount,
    errorKey = errorKey,
    createdAt = createdAt,
    updatedAt = updatedAt,
)
