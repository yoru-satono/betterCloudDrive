package com.betterclouddrive.mobile.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

@Serializable
data class ApiResponse<T>(
    val code: Int = 200,
    @SerialName("messageKey") val messageKey: String = "common.success",
    @SerialName("messageParams") val messageParams: Map<String, JsonElement> = emptyMap(),
    val data: T? = null,
    val timestamp: Long = 0,
    @SerialName("requestId") val requestId: String? = null,
)

@Serializable
data class PageResult<T>(
    val records: List<T> = emptyList(),
    val total: Long = 0,
    val page: Int = 1,
    val size: Int = 20,
    val pages: Int = 1,
)

@Serializable data class LoginRequest(val username: String, val password: String)
@Serializable data class RefreshRequest(@SerialName("refreshToken") val refreshToken: String)

@Serializable
data class AuthTokens(
    @SerialName("accessToken") val accessToken: String,
    @SerialName("refreshToken") val refreshToken: String,
    @SerialName("expiresIn") val expiresIn: Int = 1800,
)

@Serializable
data class UserProfile(
    val id: Long = 0,
    val username: String = "",
    val email: String = "",
    @SerialName("preferredLocale") val preferredLocale: String = "zh-Hans",
    val role: String = "ROLE_USER",
)

@Serializable
data class StoredSession(
    val accessToken: String,
    val refreshToken: String,
    val expiresAtEpochMillis: Long,
    val serverBaseUrl: String,
    val version: Int = 1,
)

@Serializable
data class FileItem(
    val id: Long = 0,
    @SerialName("parentId") val parentId: Long? = null,
    @SerialName("fileName") val fileName: String = "",
    @SerialName("fileType") val fileType: String = "file",
    @SerialName("mimeType") val mimeType: String? = null,
    @SerialName("fileSize") val fileSize: Long = 0,
    @SerialName("md5Hash") val md5Hash: String? = null,
    @SerialName("versionCount") val versionCount: Int = 1,
    @SerialName("createdAt") val createdAt: String? = null,
    @SerialName("updatedAt") val updatedAt: String? = null,
) {
    val isFolder: Boolean get() = fileType == "folder"
}

@Serializable
data class CreatePersonalFolderRequest(
    @SerialName("parentId") val parentId: Long? = null,
    @SerialName("folderName") val folderName: String,
)

@Serializable data class RenamePersonalFileRequest(@SerialName("newName") val newName: String)
@Serializable data class PersonalFileDestinationRequest(@SerialName("targetParentId") val targetParentId: Long? = null)
@Serializable data class DeletePersonalFilesRequest(@SerialName("fileIds") val fileIds: List<Long>)

@Serializable
data class InitUploadRequest(
    @SerialName("parentId") val parentId: Long? = null,
    @SerialName("fileName") val fileName: String,
    @SerialName("fileSize") val fileSize: Long,
    @SerialName("md5Hash") val md5Hash: String? = null,
    @SerialName("totalChunks") val totalChunks: Int,
)

@Serializable
data class InitUploadResult(
    @SerialName("sessionId") val sessionId: String,
    @SerialName("chunkSize") val chunkSize: Long,
    @SerialName("totalChunks") val totalChunks: Int,
)

@Serializable
data class UploadStatusResult(
    @SerialName("sessionId") val sessionId: String,
    @SerialName("totalChunks") val totalChunks: Int,
    @SerialName("uploadedChunks") val uploadedChunks: Int,
    @SerialName("missingChunks") val missingChunks: List<Int> = emptyList(),
)

@Serializable
data class InstantUploadRequest(
    @SerialName("parentId") val parentId: Long? = null,
    @SerialName("fileName") val fileName: String,
    @SerialName("fileSize") val fileSize: Long,
    @SerialName("md5Hash") val md5Hash: String,
)

@Serializable data class InstantUploadResult(val fileId: Long, val instant: Boolean = true)
@Serializable data class UploadChunkResult(@SerialName("chunkNumber") val chunkNumber: Int)
@Serializable data class CompleteResult(@SerialName("fileId") val fileId: Long)

@Serializable
data class OfficeConfig(
    @SerialName("documentServerUrl") val documentServerUrl: String = "",
    val editable: Boolean = false,
    val config: JsonObject = JsonObject(emptyMap()),
)

class ApiException(
    val code: Int,
    val messageKey: String,
    val messageParams: Map<String, JsonElement> = emptyMap(),
    val requestId: String? = null,
) : RuntimeException(messageKey)

class SessionExpiredException : RuntimeException("error.unauthorized")

enum class SessionInvalidationReason {
    EXPIRED,
    REVOKED,
    CORRUPT_STORAGE,
    LOGOUT,
    SERVER_CHANGED,
}

sealed interface SessionRestoreResult {
    data object NoSession : SessionRestoreResult
    data class Restored(val user: UserProfile) : SessionRestoreResult
    data class RetryableFailure(val messageKey: String = "error.network") : SessionRestoreResult
    data class Invalidated(val reason: SessionInvalidationReason) : SessionRestoreResult
}

class SessionStorageInvalidException : RuntimeException("error.sessionStorageInvalid")
