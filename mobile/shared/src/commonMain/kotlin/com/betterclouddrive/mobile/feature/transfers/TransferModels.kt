package com.betterclouddrive.mobile.feature.transfers

enum class TransferDirection { UPLOAD, DOWNLOAD }

enum class TransferStatus {
    QUEUED,
    PREPARING,
    RUNNING,
    PAUSED,
    WAITING_FOR_LOGIN,
    SUCCEEDED,
    FAILED,
    CANCELED,
}

sealed interface TransferScope {
    val parentId: Long?

    data class Personal(override val parentId: Long? = null) : TransferScope
    data class Team(val teamId: Long, override val parentId: Long? = null) : TransferScope
}

data class TransferTask(
    val id: String,
    val direction: TransferDirection,
    val status: TransferStatus,
    val serverBaseUrl: String,
    val scope: TransferScope,
    val remoteFileId: Long? = null,
    val remoteResultFileId: Long? = null,
    val fileName: String,
    val mimeType: String? = null,
    val bytesDone: Long = 0,
    val bytesTotal: Long = 0,
    val sourceHandle: String? = null,
    val destinationHandle: String? = null,
    val sessionId: String? = null,
    val chunkSize: Long = DEFAULT_CHUNK_SIZE,
    val totalChunks: Int = 0,
    val md5Hash: String? = null,
    val retryCount: Int = 0,
    val errorKey: String? = null,
    val createdAt: Long,
    val updatedAt: Long,
)

enum class TransferRunResult { COMPLETE, RETRY, PAUSED }

const val DEFAULT_CHUNK_SIZE: Long = 5L * 1024 * 1024
