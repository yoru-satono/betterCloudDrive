package com.betterclouddrive.android.domain.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import com.betterclouddrive.android.util.MessageResolver

@Serializable
enum class TeamRole {
    OWNER,
    ADMIN,
    EDITOR,
    VIEWER,
}

@Serializable
data class Team(
    val id: Long = 0,
    val name: String = "",
    @SerialName("ownerId") val ownerId: Long = 0,
    @SerialName("storageQuota") val storageQuota: Long = 0,
    @SerialName("storageUsed") val storageUsed: Long = 0,
    val role: TeamRole = TeamRole.VIEWER,
    @SerialName("createdAt") val createdAt: String? = null,
    @SerialName("updatedAt") val updatedAt: String? = null,
) {
    val canWrite: Boolean get() = role in setOf(TeamRole.OWNER, TeamRole.ADMIN, TeamRole.EDITOR)
    val canManageMembers: Boolean get() = role in setOf(TeamRole.OWNER, TeamRole.ADMIN)
}

@Serializable
data class TeamMember(
    @SerialName("teamId") val teamId: Long = 0,
    @SerialName("userId") val userId: Long = 0,
    val username: String? = null,
    val email: String? = null,
    val role: TeamRole = TeamRole.VIEWER,
    val status: Int = 1,
    @SerialName("createdAt") val createdAt: String? = null,
    @SerialName("updatedAt") val updatedAt: String? = null,
)

@Serializable
data class TeamEffectivePermission(
    @SerialName("canRead") val canRead: Boolean = true,
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
    @SerialName("descriptionParams") val descriptionParams: Map<String, JsonElement> = emptyMap(),
)

@Serializable
data class TeamFilePermission(
    val id: Long = 0,
    @SerialName("teamId") val teamId: Long = 0,
    @SerialName("fileId") val fileId: Long = 0,
    @SerialName("subjectType") val subjectType: String = "ROLE",
    @SerialName("subjectUserId") val subjectUserId: Long? = null,
    @SerialName("subjectRole") val subjectRole: TeamRole? = null,
    @SerialName("canRead") val canRead: Boolean = true,
    @SerialName("canWrite") val canWrite: Boolean = false,
    @SerialName("canShare") val canShare: Boolean = false,
    @SerialName("canDelete") val canDelete: Boolean = false,
    @SerialName("canManage") val canManage: Boolean = false,
    @SerialName("createdBy") val createdBy: Long? = null,
    @SerialName("createdAt") val createdAt: String? = null,
    @SerialName("updatedAt") val updatedAt: String? = null,
)

@Serializable
data class NotificationItem(
    val id: Long = 0,
    @SerialName("userId") val userId: Long = 0,
    @SerialName("teamId") val teamId: Long? = null,
    val type: String = "",
    @SerialName("titleKey") val titleKey: String = "",
    @SerialName("contentKey") val contentKey: String = "",
    @SerialName("messageParams") val messageParams: Map<String, JsonElement> = emptyMap(),
    val link: String? = null,
    @SerialName("isRead") val isRead: Boolean = false,
    @SerialName("readAt") val readAt: String? = null,
    @SerialName("createdAt") val createdAt: String? = null,
) {
    val title: String get() = MessageResolver.resolve(titleKey, messageParams)
    val content: String get() = MessageResolver.resolve(contentKey, messageParams)
}

@Serializable
data class TeamInvite(
    val id: Long = 0,
    @SerialName("teamId") val teamId: Long = 0,
    @SerialName("teamName") val teamName: String? = null,
    val email: String = "",
    val role: TeamRole = TeamRole.VIEWER,
    val status: String = "",
    @SerialName("invitedByUserId") val invitedByUserId: Long = 0,
    @SerialName("invitedByUsername") val invitedByUsername: String? = null,
    @SerialName("expiresAt") val expiresAt: String? = null,
    @SerialName("acceptedAt") val acceptedAt: String? = null,
    @SerialName("rejectedAt") val rejectedAt: String? = null,
    @SerialName("createdAt") val createdAt: String? = null,
    @SerialName("updatedAt") val updatedAt: String? = null,
)

@Serializable
data class TeamOperationLog(
    val id: Long = 0,
    @SerialName("teamId") val teamId: Long = 0,
    @SerialName("actorUserId") val actorUserId: Long? = null,
    val action: String = "",
    @SerialName("targetType") val targetType: String? = null,
    @SerialName("targetId") val targetId: Long? = null,
    val result: Int = 0,
    @SerialName("statusCode") val statusCode: Int? = null,
    @SerialName("requestId") val requestId: String? = null,
    @SerialName("traceId") val traceId: String? = null,
    val details: String? = null,
    @SerialName("createdAt") val createdAt: String? = null,
)

@Serializable
data class TeamTask(
    val id: Long = 0,
    @SerialName("teamId") val teamId: Long = 0,
    @SerialName("taskType") val taskType: String = "",
    val status: String = "",
    val progress: Int = 0,
    @SerialName("resultFileName") val resultFileName: String? = null,
    @SerialName("resultContentType") val resultContentType: String? = null,
    @SerialName("resultSizeBytes") val resultSizeBytes: Long? = null,
    @SerialName("resultExpiresAt") val resultExpiresAt: String? = null,
    @SerialName("errorMessageKey") val errorMessageKey: String? = null,
    @SerialName("errorMessageParams") val errorMessageParams: Map<String, JsonElement> = emptyMap(),
    @SerialName("createdAt") val createdAt: String? = null,
    @SerialName("completedAt") val completedAt: String? = null,
) {
    val errorMessage: String? get() = errorMessageKey?.let { MessageResolver.resolve(it, errorMessageParams) }
    val canDownload: Boolean get() = status == "SUCCEEDED" && !resultFileName.isNullOrBlank()
    val canRetry: Boolean get() = status == "FAILED"
}
