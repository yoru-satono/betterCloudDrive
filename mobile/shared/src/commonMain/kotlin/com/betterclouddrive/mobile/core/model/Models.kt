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
@Serializable data class RegisterCodeRequest(val email: String, @SerialName("preferredLocale") val preferredLocale: String)
@Serializable data class RegisterRequest(
    val username: String,
    val password: String,
    val email: String,
    @SerialName("verificationCode") val verificationCode: String,
    @SerialName("preferredLocale") val preferredLocale: String,
)
@Serializable data class RegistrationResult(val userId: Long, val username: String)
@Serializable data class ForgotPasswordRequest(val email: String)
@Serializable data class ResetPasswordRequest(
    val email: String,
    val code: String,
    @SerialName("newPassword") val newPassword: String,
)
@Serializable data class UpdateLocaleRequest(@SerialName("preferredLocale") val preferredLocale: String)

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
    @SerialName("emailVerified") val emailVerified: Boolean = false,
    @SerialName("preferredLocale") val preferredLocale: String = "en-US",
    val nickname: String? = null,
    @SerialName("avatarUrl") val avatarUrl: String? = null,
    val role: String = "ROLE_USER",
    val status: Int = 1,
    @SerialName("storageQuota") val storageQuota: Long = 0,
    @SerialName("storageUsed") val storageUsed: Long = 0,
    @SerialName("webdavEnabled") val webdavEnabled: Boolean = false,
    @SerialName("createdAt") val createdAt: String? = null,
    @SerialName("updatedAt") val updatedAt: String? = null,
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
enum class TeamRole { OWNER, ADMIN, EDITOR, VIEWER }

@Serializable
data class TeamSummary(
    val id: Long = 0,
    val name: String = "",
    @SerialName("ownerId") val ownerId: Long = 0,
    @SerialName("storageQuota") val storageQuota: Long = 0,
    @SerialName("storageUsed") val storageUsed: Long = 0,
    val role: TeamRole = TeamRole.VIEWER,
    @SerialName("createdAt") val createdAt: String? = null,
    @SerialName("updatedAt") val updatedAt: String? = null,
)

@Serializable
data class TeamEffectivePermission(
    @SerialName("canRead") val canRead: Boolean = false,
    @SerialName("canWrite") val canWrite: Boolean = false,
    @SerialName("canShare") val canShare: Boolean = false,
    @SerialName("canDelete") val canDelete: Boolean = false,
    @SerialName("canManage") val canManage: Boolean = false,
    @SerialName("sourceType") val sourceType: String = "UNSPECIFIED",
    @SerialName("sourceChain") val sourceChain: List<TeamPermissionSource> = emptyList(),
)

@Serializable
data class TeamPermissionSource(
    val type: String = "UNSPECIFIED",
    @SerialName("fileId") val fileId: Long? = null,
    @SerialName("fileName") val fileName: String? = null,
    @SerialName("subjectType") val subjectType: String? = null,
    @SerialName("subjectUserId") val subjectUserId: Long? = null,
    @SerialName("subjectRole") val subjectRole: String? = null,
    val inherited: Boolean = false,
    @SerialName("canRead") val canRead: Boolean = false,
    @SerialName("canWrite") val canWrite: Boolean = false,
    @SerialName("canShare") val canShare: Boolean = false,
    @SerialName("canDelete") val canDelete: Boolean = false,
    @SerialName("canManage") val canManage: Boolean = false,
    @SerialName("descriptionKey") val descriptionKey: String? = null,
    @SerialName("descriptionParams") val descriptionParams: Map<String, String> = emptyMap(),
)

@Serializable
data class TeamMember(
    @SerialName("teamId") val teamId: Long = 0,
    @SerialName("userId") val userId: Long = 0,
    val username: String = "",
    val email: String? = null,
    val role: TeamRole = TeamRole.VIEWER,
    val status: Int = 1,
    @SerialName("createdAt") val createdAt: String? = null,
    @SerialName("updatedAt") val updatedAt: String? = null,
)

@Serializable
enum class TeamInviteStatus { PENDING, ACCEPTED, REJECTED, CANCELED, EXPIRED }

@Serializable
data class TeamInvite(
    val id: Long = 0,
    @SerialName("teamId") val teamId: Long = 0,
    @SerialName("teamName") val teamName: String? = null,
    val email: String = "",
    val role: TeamRole = TeamRole.VIEWER,
    val status: TeamInviteStatus = TeamInviteStatus.PENDING,
    @SerialName("invitedByUserId") val invitedByUserId: Long = 0,
    @SerialName("invitedByUsername") val invitedByUsername: String? = null,
    @SerialName("expiresAt") val expiresAt: String? = null,
    @SerialName("acceptedAt") val acceptedAt: String? = null,
    @SerialName("rejectedAt") val rejectedAt: String? = null,
    @SerialName("createdAt") val createdAt: String? = null,
    @SerialName("updatedAt") val updatedAt: String? = null,
)

@Serializable data class CreateTeamInviteRequest(val email: String, val role: TeamRole)

@Serializable
data class NotificationItem(
    val id: Long = 0,
    @SerialName("userId") val userId: Long = 0,
    @SerialName("teamId") val teamId: Long? = null,
    val type: String = "",
    @SerialName("titleKey") val titleKey: String = "",
    @SerialName("contentKey") val contentKey: String = "",
    @SerialName("messageParams") val messageParams: JsonObject = JsonObject(emptyMap()),
    val link: String? = null,
    @SerialName("readAt") val readAt: String? = null,
    @SerialName("createdAt") val createdAt: String? = null,
    @SerialName("isRead") val isRead: Boolean = false,
)

@Serializable
data class NotificationStreamTicket(
    val ticket: String,
    @SerialName("expiresAt") val expiresAt: String? = null,
)

@Serializable
enum class TeamPermissionSubjectType { USER, ROLE }

@Serializable
data class TeamFilePermission(
    val id: Long = 0,
    @SerialName("teamId") val teamId: Long = 0,
    @SerialName("fileId") val fileId: Long = 0,
    @SerialName("subjectType") val subjectType: TeamPermissionSubjectType = TeamPermissionSubjectType.USER,
    @SerialName("subjectUserId") val subjectUserId: Long? = null,
    @SerialName("subjectRole") val subjectRole: TeamRole? = null,
    @SerialName("canRead") val canRead: Boolean = false,
    @SerialName("canWrite") val canWrite: Boolean = false,
    @SerialName("canShare") val canShare: Boolean = false,
    @SerialName("canDelete") val canDelete: Boolean = false,
    @SerialName("canManage") val canManage: Boolean = false,
    @SerialName("createdBy") val createdBy: Long? = null,
    @SerialName("createdAt") val createdAt: String? = null,
    @SerialName("updatedAt") val updatedAt: String? = null,
)

@Serializable data class AddTeamMemberRequest(val username: String, val role: TeamRole)
@Serializable data class UpdateTeamMemberRequest(val role: TeamRole)
@Serializable
data class UpdateTeamFilePermissionRequest(
    @SerialName("subjectType") val subjectType: TeamPermissionSubjectType,
    @SerialName("subjectUserId") val subjectUserId: Long? = null,
    @SerialName("subjectRole") val subjectRole: TeamRole? = null,
    @SerialName("canRead") val canRead: Boolean,
    @SerialName("canWrite") val canWrite: Boolean,
    @SerialName("canShare") val canShare: Boolean,
    @SerialName("canDelete") val canDelete: Boolean,
    @SerialName("canManage") val canManage: Boolean,
)

@Serializable data class CreateTeamFolderRequest(@SerialName("parentId") val parentId: Long? = null, @SerialName("folderName") val folderName: String)
@Serializable data class RenameTeamFileRequest(@SerialName("newName") val newName: String)
@Serializable data class MoveTeamFileRequest(@SerialName("targetParentId") val targetParentId: Long? = null)
@Serializable data class DeleteTeamFilesRequest(@SerialName("fileIds") val fileIds: List<Long>)

@Serializable data class CreateTeamRequest(val name: String)

@Serializable
data class RecycleBinItem(
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
    @SerialName("deletedAt") val deletedAt: String? = null,
) {
    val isFolder: Boolean get() = fileType == "folder"
}

@Serializable
data class PersonalFileVersion(
    val id: Long = 0,
    @SerialName("fileId") val fileId: Long = 0,
    @SerialName("versionNumber") val versionNumber: Int = 0,
    @SerialName("fileSize") val fileSize: Long = 0,
    @SerialName("md5Hash") val md5Hash: String? = null,
    @SerialName("createdAt") val createdAt: String? = null,
)

enum class PreviewKind { OFFICE, IMAGE, TEXT, VIDEO, UNSUPPORTED }

@Serializable
data class DownloadTicketResponse(val url: String)

data class TextPreview(
    val content: String,
    val truncated: Boolean,
)

@Serializable
data class CreatePersonalFolderRequest(
    @SerialName("parentId") val parentId: Long? = null,
    @SerialName("folderName") val folderName: String,
)

@Serializable data class RenamePersonalFileRequest(@SerialName("newName") val newName: String)
@Serializable data class PersonalFileDestinationRequest(@SerialName("targetParentId") val targetParentId: Long? = null)
@Serializable data class DeletePersonalFilesRequest(@SerialName("fileIds") val fileIds: List<Long>)

@Serializable
data class PersonalTag(
    val id: Long = 0,
    @SerialName("userId") val userId: Long = 0,
    @SerialName("teamId") val teamId: Long? = null,
    @SerialName("tagName") val tagName: String = "",
    val color: String? = null,
    @SerialName("fileCount") val fileCount: Long = 0,
    @SerialName("createdAt") val createdAt: String? = null,
)

typealias TeamTag = PersonalTag

@Serializable data class CreatePersonalTagRequest(@SerialName("tagName") val tagName: String, val color: String)
@Serializable data class UpdatePersonalTagRequest(@SerialName("tagName") val tagName: String, val color: String)
@Serializable data class ReplacePersonalFileTagsRequest(@SerialName("tagIds") val tagIds: List<Long>)
@Serializable data class CreateTeamTagRequest(@SerialName("tagName") val tagName: String, val color: String)
@Serializable data class UpdateTeamTagRequest(@SerialName("tagName") val tagName: String, val color: String)
@Serializable data class ReplaceTeamFileTagsRequest(@SerialName("tagIds") val tagIds: List<Long>)

@Serializable
data class ShareLink(
    val id: Long = 0,
    @SerialName("userId") val userId: Long = 0,
    @SerialName("teamId") val teamId: Long? = null,
    @SerialName("fileId") val fileId: Long = 0,
    @SerialName("shareCode") val shareCode: String = "",
    @SerialName("shareUrl") val shareUrl: String = "",
    @SerialName("hasPassword") val hasPassword: Boolean = false,
    @SerialName("expireAt") val expireAt: String? = null,
    @SerialName("expireAtEpochMs") val expireAtEpochMs: Long? = null,
    @SerialName("maxVisits") val maxVisits: Int? = null,
    @SerialName("downloadCount") val downloadCount: Int = 0,
    @SerialName("visitCount") val visitCount: Int = 0,
    @SerialName("isCanceled") val isCanceled: Boolean = false,
    @SerialName("createdAt") val createdAt: String? = null,
    @SerialName("updatedAt") val updatedAt: String? = null,
    @SerialName("fileName") val fileName: String? = null,
    @SerialName("fileType") val fileType: String? = null,
    @SerialName("fileSize") val fileSize: Long? = null,
)

enum class ShareExpirationFilter(val apiValue: String?) {
    ALL(null), ACTIVE("ACTIVE"), EXPIRED("EXPIRED"), NEVER("NEVER")
}

@Serializable
data class CreateShareRequest(
    @SerialName("fileId") val fileId: Long,
    val password: String? = null,
    @SerialName("expireAt") val expireAt: Long? = null,
    @SerialName("maxVisits") val maxVisits: Int? = null,
)

@Serializable
data class UpdateShareRequest(
    val password: String? = null,
    @SerialName("expireAt") val expireAt: Long? = null,
    @SerialName("maxVisits") val maxVisits: Int? = null,
    @SerialName("clearPassword") val clearPassword: Boolean = false,
    @SerialName("clearExpireAt") val clearExpireAt: Boolean = false,
    @SerialName("clearMaxVisits") val clearMaxVisits: Boolean = false,
)

@Serializable data class SharePasswordResponse(val password: String? = null)

typealias CreatePersonalShareRequest = CreateShareRequest
typealias UpdatePersonalShareRequest = UpdateShareRequest

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
