package com.betterclouddrive.android.domain.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class UploadSession(
    @SerialName("sessionId") val sessionId: String = "",
    @SerialName("chunkSize") val chunkSize: Long = 0,
    @SerialName("totalChunks") val totalChunks: Int = 0,
)

@Serializable
data class UploadStatus(
    @SerialName("sessionId") val sessionId: String = "",
    @SerialName("totalChunks") val totalChunks: Int = 0,
    @SerialName("uploadedChunks") val uploadedChunks: Int = 0,
    @SerialName("missingChunks") val missingChunks: List<Int> = emptyList(),
)

@Serializable
data class FileVersion(
    val id: Long = 0,
    @SerialName("fileId") val fileId: Long = 0,
    @SerialName("versionNumber") val versionNumber: Int = 0,
    @SerialName("fileSize") val fileSize: Long = 0,
    @SerialName("md5Hash") val md5Hash: String = "",
    @SerialName("storagePath") val storagePath: String = "",
    @SerialName("createdAt") val createdAt: String? = null,
)
