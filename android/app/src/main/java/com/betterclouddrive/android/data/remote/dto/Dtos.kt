package com.betterclouddrive.android.data.remote.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ApiResponse<T>(
    val code: Int = 200,
    val message: String = "success",
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

// === Auth ===
@Serializable
data class LoginRequest(val username: String, val password: String)

@Serializable
data class RegisterRequest(
    val username: String,
    val password: String,
    val email: String,
    @SerialName("verificationCode") val verificationCode: String,
)

@Serializable
data class RegisterCodeRequest(val email: String)

@Serializable
data class RefreshRequest(@SerialName("refreshToken") val refreshToken: String)

@Serializable
data class AuthTokens(
    @SerialName("accessToken") val accessToken: String,
    @SerialName("refreshToken") val refreshToken: String,
    @SerialName("expiresIn") val expiresIn: Int = 1800,
)

@Serializable
data class RegisterResult(
    @SerialName("userId") val userId: Long = 0,
    val username: String = "",
)

// === Files ===
@Serializable
data class CreateFolderRequest(
    @SerialName("parentId") val parentId: Long? = null,
    @SerialName("folderName") val folderName: String,
)

@Serializable
data class RenameRequest(@SerialName("newName") val newName: String)

@Serializable
data class MoveRequest(@SerialName("targetParentId") val targetParentId: Long?)

@Serializable
data class CopyRequest(@SerialName("targetParentId") val targetParentId: Long?)

@Serializable
data class BatchDeleteRequest(@SerialName("fileIds") val fileIds: List<Long>)

// === Upload ===
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
data class UploadChunkResult(@SerialName("chunkNumber") val chunkNumber: Int)

@Serializable
data class CompleteResult(@SerialName("fileId") val fileId: Long)

@Serializable
data class InstantUploadRequest(
    @SerialName("parentId") val parentId: Long? = null,
    @SerialName("fileName") val fileName: String,
    @SerialName("fileSize") val fileSize: Long,
    @SerialName("md5Hash") val md5Hash: String,
)

@Serializable
data class InstantUploadResult(
    @SerialName("fileId") val fileId: Long,
    val instant: Boolean = true,
)

// === Shares ===
@Serializable
data class CreateShareRequest(
    @SerialName("fileId") val fileId: Long,
    val password: String? = null,
    @SerialName("expireAt") val expireAt: Long? = null,
    @SerialName("maxVisits") val maxVisits: Int? = null,
    @SerialName("notifyEmail") val notifyEmail: String? = null,
)

@Serializable
data class UpdateShareRequest(
    val password: String? = null,
    @SerialName("expireAt") val expireAt: Long? = null,
    @SerialName("maxVisits") val maxVisits: Int? = null,
)

@Serializable
data class SharePasswordResponse(
    val password: String? = null,
)

@Serializable
data class AccessShareRequest(val password: String? = null)

@Serializable
data class SaveSharedItemRequest(
    @SerialName("fileId") val fileId: Long? = null,
    @SerialName("targetParentId") val targetParentId: Long? = null,
    val password: String? = null,
)

@Serializable
data class ShareAccessResult(
    @SerialName("fileId") val fileId: Long,
    @SerialName("fileName") val fileName: String = "",
    @SerialName("fileSize") val fileSize: Long = 0,
    @SerialName("fileType") val fileType: String = "",
    @SerialName("mimeType") val mimeType: String? = null,
    @SerialName("downloadUrl") val downloadUrl: String? = null,
)

// === Favorites ===
// No request body needed for add/remove; status returns boolean

// === Tags ===
@Serializable
data class CreateTagRequest(
    @SerialName("tagName") val tagName: String,
    val color: String = "#1890ff",
)

@Serializable
data class UpdateTagRequest(
    @SerialName("tagName") val tagName: String? = null,
    val color: String? = null,
)

@Serializable
data class TagFilesRequest(@SerialName("fileIds") val fileIds: List<Long>)

// === Password reset ===
@Serializable
data class ForgotPasswordRequest(val email: String)

@Serializable
data class ResetPasswordRequest(
    val email: String,
    val code: String,
    @SerialName("newPassword") val newPassword: String,
)
