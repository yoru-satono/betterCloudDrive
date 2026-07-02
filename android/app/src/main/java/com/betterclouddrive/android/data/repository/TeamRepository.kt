package com.betterclouddrive.android.data.repository

import com.betterclouddrive.android.data.remote.ApiService
import com.betterclouddrive.android.data.remote.dto.AddTeamMemberRequest
import com.betterclouddrive.android.data.remote.dto.BatchDeleteRequest
import com.betterclouddrive.android.data.remote.dto.CreateFolderRequest
import com.betterclouddrive.android.data.remote.dto.CreateTeamRequest
import com.betterclouddrive.android.data.remote.dto.CreateTagRequest
import com.betterclouddrive.android.data.remote.dto.CreateTeamInviteRequest
import com.betterclouddrive.android.data.remote.dto.MoveRequest
import com.betterclouddrive.android.data.remote.dto.PageResult
import com.betterclouddrive.android.data.remote.dto.RenameRequest
import com.betterclouddrive.android.data.remote.dto.CreateShareRequest
import com.betterclouddrive.android.data.remote.dto.TagFilesRequest
import com.betterclouddrive.android.data.remote.dto.TransferTeamOwnerRequest
import com.betterclouddrive.android.data.remote.dto.UpdateShareRequest
import com.betterclouddrive.android.data.remote.dto.UpdateTagRequest
import com.betterclouddrive.android.data.remote.dto.UpdateTeamFilePermissionRequest
import com.betterclouddrive.android.data.remote.dto.UpdateTeamMemberRequest
import com.betterclouddrive.android.data.remote.dto.UpdateTeamRequest
import com.betterclouddrive.android.domain.model.FileItem
import com.betterclouddrive.android.domain.model.FileVersion
import com.betterclouddrive.android.domain.model.NotificationItem
import com.betterclouddrive.android.domain.model.OfficeConfig
import com.betterclouddrive.android.domain.model.ShareLink
import com.betterclouddrive.android.data.remote.dto.SharePasswordResponse
import com.betterclouddrive.android.domain.model.Tag
import com.betterclouddrive.android.domain.model.Team
import com.betterclouddrive.android.domain.model.TeamEffectivePermission
import com.betterclouddrive.android.domain.model.TeamFilePermission
import com.betterclouddrive.android.domain.model.TeamInvite
import com.betterclouddrive.android.domain.model.TeamMember
import com.betterclouddrive.android.domain.model.TeamOperationLog
import com.betterclouddrive.android.domain.model.TeamRole
import com.betterclouddrive.android.util.NetworkResult
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TeamRepository @Inject constructor(private val api: ApiService) {
    suspend fun listTeams(): NetworkResult<List<Team>> = apiCall { api.listTeams() }

    suspend fun createTeam(name: String): NetworkResult<Team> = apiCall {
        api.createTeam(CreateTeamRequest(name))
    }

    suspend fun getTeam(teamId: Long): NetworkResult<Team> = apiCall {
        api.getTeam(teamId)
    }

    suspend fun updateTeam(teamId: Long, name: String): NetworkResult<Team> = apiCall {
        api.updateTeam(teamId, UpdateTeamRequest(name))
    }

    suspend fun deleteTeam(teamId: Long): NetworkResult<Unit> = apiCall {
        api.deleteTeam(teamId)
    }

    suspend fun leaveTeam(teamId: Long): NetworkResult<Unit> = apiCall {
        api.leaveTeam(teamId)
    }

    suspend fun transferOwner(teamId: Long, userId: Long): NetworkResult<Team> = apiCall {
        api.transferTeamOwner(teamId, TransferTeamOwnerRequest(userId))
    }

    suspend fun listMembers(teamId: Long): NetworkResult<List<TeamMember>> = apiCall {
        api.listTeamMembers(teamId)
    }

    suspend fun addMember(teamId: Long, username: String, role: TeamRole): NetworkResult<TeamMember> = apiCall {
        api.addTeamMember(teamId, AddTeamMemberRequest(username, role.name))
    }

    suspend fun updateMember(teamId: Long, userId: Long, role: TeamRole): NetworkResult<TeamMember> = apiCall {
        api.updateTeamMember(teamId, userId, UpdateTeamMemberRequest(role.name))
    }

    suspend fun removeMember(teamId: Long, userId: Long): NetworkResult<Unit> = apiCall {
        api.removeTeamMember(teamId, userId)
    }

    suspend fun createInvite(teamId: Long, email: String, role: TeamRole): NetworkResult<TeamInvite> = apiCall {
        api.createTeamInvite(teamId, CreateTeamInviteRequest(email, role.name))
    }

    suspend fun listInvites(teamId: Long): NetworkResult<List<TeamInvite>> = apiCall {
        api.listTeamInvites(teamId)
    }

    suspend fun resendInvite(teamId: Long, inviteId: Long): NetworkResult<TeamInvite> = apiCall {
        api.resendTeamInvite(teamId, inviteId)
    }

    suspend fun cancelInvite(teamId: Long, inviteId: Long): NetworkResult<Unit> = apiCall {
        api.cancelTeamInvite(teamId, inviteId)
    }

    suspend fun getInvite(token: String): NetworkResult<TeamInvite> = apiCall {
        api.getTeamInvite(token)
    }

    suspend fun acceptInvite(token: String): NetworkResult<Unit> = apiCall {
        api.acceptTeamInvite(token)
    }

    suspend fun rejectInvite(token: String): NetworkResult<Unit> = apiCall {
        api.rejectTeamInvite(token)
    }

    suspend fun listAuditLogs(
        teamId: Long,
        actorUserId: Long? = null,
        action: String? = null,
        targetType: String? = null,
        result: Int? = null,
        page: Int = 1,
        size: Int = 20,
    ): NetworkResult<PageResult<TeamOperationLog>> = apiCall {
        api.listTeamAuditLogs(teamId, actorUserId, action, targetType, result, page, size)
    }

    suspend fun listFiles(
        teamId: Long,
        parentId: Long? = null,
        page: Int = 1,
        size: Int = 20,
        sortBy: String = "fileName",
        order: String = "asc",
    ): NetworkResult<PageResult<FileItem>> = apiCall {
        api.listTeamFiles(teamId, parentId, page, size, sortBy, order)
    }

    suspend fun searchFiles(teamId: Long, query: String, page: Int = 1, size: Int = 20): NetworkResult<PageResult<FileItem>> =
        apiCall {
            api.searchTeamFiles(teamId, query, page, size)
        }

    suspend fun getFile(teamId: Long, fileId: Long): NetworkResult<FileItem> = apiCall {
        api.getTeamFile(teamId, fileId)
    }

    suspend fun getEffectivePermission(teamId: Long, fileId: Long): NetworkResult<TeamEffectivePermission> = apiCall {
        api.getTeamFileEffectivePermission(teamId, fileId)
    }

    suspend fun listFilePermissions(teamId: Long, fileId: Long): NetworkResult<List<TeamFilePermission>> = apiCall {
        api.listTeamFilePermissions(teamId, fileId)
    }

    suspend fun upsertFilePermission(
        teamId: Long,
        fileId: Long,
        subjectType: String,
        subjectUserId: Long?,
        subjectRole: TeamRole?,
        permission: TeamEffectivePermission,
    ): NetworkResult<TeamFilePermission> = apiCall {
        api.upsertTeamFilePermission(
            teamId,
            fileId,
            UpdateTeamFilePermissionRequest(
                subjectType = subjectType,
                subjectUserId = subjectUserId,
                subjectRole = subjectRole?.name,
                canRead = permission.canRead,
                canWrite = permission.canWrite,
                canShare = permission.canShare,
                canDelete = permission.canDelete,
                canManage = permission.canManage,
            ),
        )
    }

    suspend fun deleteFilePermission(teamId: Long, fileId: Long, permissionId: Long): NetworkResult<Unit> = apiCall {
        api.deleteTeamFilePermission(teamId, fileId, permissionId)
    }

    suspend fun createFolder(teamId: Long, parentId: Long?, folderName: String): NetworkResult<FileItem> = apiCall {
        api.createTeamFolder(teamId, CreateFolderRequest(parentId, folderName))
    }

    suspend fun renameFile(teamId: Long, fileId: Long, newName: String): NetworkResult<FileItem> = apiCall {
        api.renameTeamFile(teamId, fileId, RenameRequest(newName))
    }

    suspend fun moveFile(teamId: Long, fileId: Long, targetParentId: Long?): NetworkResult<Unit> = apiCall {
        api.moveTeamFile(teamId, fileId, MoveRequest(targetParentId))
    }

    suspend fun deleteFiles(teamId: Long, fileIds: List<Long>): NetworkResult<Unit> = apiCall {
        api.deleteTeamFiles(teamId, BatchDeleteRequest(fileIds))
    }

    suspend fun listRecycleBin(teamId: Long, page: Int = 1, size: Int = 100): NetworkResult<PageResult<FileItem>> = apiCall {
        api.listTeamRecycleBin(teamId, page, size)
    }

    suspend fun restoreFile(teamId: Long, fileId: Long): NetworkResult<Unit> = apiCall {
        api.restoreTeamFile(teamId, fileId)
    }

    suspend fun permanentDeleteFile(teamId: Long, fileId: Long): NetworkResult<Unit> = apiCall {
        api.permanentDeleteTeamFile(teamId, fileId)
    }

    suspend fun emptyRecycleBin(teamId: Long): NetworkResult<Unit> = apiCall {
        api.emptyTeamRecycleBin(teamId)
    }

    suspend fun listVersions(teamId: Long, fileId: Long): NetworkResult<List<FileVersion>> = apiCall {
        api.listTeamVersions(teamId, fileId)
    }

    suspend fun deleteVersion(teamId: Long, fileId: Long, versionNumber: Int): NetworkResult<Unit> = apiCall {
        api.deleteTeamVersion(teamId, fileId, versionNumber)
    }

    suspend fun restoreVersion(teamId: Long, fileId: Long, versionNumber: Int): NetworkResult<Unit> = apiCall {
        api.restoreTeamVersion(teamId, fileId, versionNumber)
    }

    suspend fun getOfficeConfig(teamId: Long, fileId: Long): NetworkResult<OfficeConfig> = apiCall {
        api.getTeamOfficeConfig(teamId, fileId)
    }

    suspend fun createShare(teamId: Long, fileId: Long): NetworkResult<ShareLink> = apiCall {
        api.createTeamShare(teamId, CreateShareRequest(fileId = fileId))
    }

    suspend fun listShares(teamId: Long, page: Int = 1, size: Int = 100): NetworkResult<PageResult<ShareLink>> = apiCall {
        api.listTeamShares(teamId, page, size)
    }

    suspend fun getShare(teamId: Long, shareId: Long): NetworkResult<ShareLink> = apiCall {
        api.getTeamShare(teamId, shareId)
    }

    suspend fun getSharePassword(teamId: Long, shareId: Long): NetworkResult<SharePasswordResponse> = apiCall {
        api.getTeamSharePassword(teamId, shareId)
    }

    suspend fun updateShare(
        teamId: Long,
        shareId: Long,
        password: String? = null,
        expireAt: Long? = null,
        maxVisits: Int? = null,
        clearPassword: Boolean = false,
        clearExpireAt: Boolean = false,
        clearMaxVisits: Boolean = false,
    ): NetworkResult<ShareLink> = apiCall {
        api.updateTeamShare(
            teamId,
            shareId,
            UpdateShareRequest(
                password = password,
                expireAt = expireAt,
                maxVisits = maxVisits,
                clearPassword = clearPassword,
                clearExpireAt = clearExpireAt,
                clearMaxVisits = clearMaxVisits,
            ),
        )
    }

    suspend fun cancelShare(teamId: Long, shareId: Long): NetworkResult<Unit> = apiCall {
        api.cancelTeamShare(teamId, shareId)
    }

    suspend fun addFavorite(teamId: Long, fileId: Long): NetworkResult<Unit> = apiCall {
        api.addTeamFavorite(teamId, fileId)
    }

    suspend fun removeFavorite(teamId: Long, fileId: Long): NetworkResult<Unit> = apiCall {
        api.removeTeamFavorite(teamId, fileId)
    }

    suspend fun getFavoriteStatus(teamId: Long, fileId: Long): NetworkResult<Boolean> = apiCall {
        api.getTeamFavoriteStatus(teamId, fileId)
    }

    suspend fun listFavorites(
        teamId: Long,
        query: String? = null,
        page: Int = 1,
        size: Int = 20,
    ): NetworkResult<PageResult<FileItem>> = apiCall {
        api.listTeamFavorites(teamId, query, page, size)
    }

    suspend fun createTag(teamId: Long, tagName: String, color: String = "#1890ff"): NetworkResult<Tag> = apiCall {
        api.createTeamTag(teamId, CreateTagRequest(tagName, color))
    }

    suspend fun listTags(teamId: Long, query: String? = null): NetworkResult<List<Tag>> = apiCall {
        api.listTeamTags(teamId, query)
    }

    suspend fun updateTag(
        teamId: Long,
        tagId: Long,
        tagName: String? = null,
        color: String? = null,
    ): NetworkResult<Tag> = apiCall {
        api.updateTeamTag(teamId, tagId, UpdateTagRequest(tagName, color))
    }

    suspend fun deleteTag(teamId: Long, tagId: Long): NetworkResult<Unit> = apiCall {
        api.deleteTeamTag(teamId, tagId)
    }

    suspend fun tagFiles(teamId: Long, tagId: Long, fileIds: List<Long>): NetworkResult<Unit> = apiCall {
        api.tagTeamFiles(teamId, tagId, TagFilesRequest(fileIds))
    }

    suspend fun untagFile(teamId: Long, tagId: Long, fileId: Long): NetworkResult<Unit> = apiCall {
        api.untagTeamFile(teamId, tagId, fileId)
    }

    suspend fun listFilesByTag(
        teamId: Long,
        tagId: Long,
        query: String? = null,
        page: Int = 1,
        size: Int = 20,
    ): NetworkResult<PageResult<FileItem>> = apiCall {
        api.listTeamFilesByTag(teamId, tagId, query, page, size)
    }

    suspend fun listNotifications(page: Int = 1, size: Int = 20, unreadOnly: Boolean = false, type: String? = null): NetworkResult<PageResult<NotificationItem>> =
        apiCall {
            api.listNotifications(page, size, unreadOnly, type)
        }

    suspend fun getNotificationUnreadCount(): NetworkResult<Int> = apiCall {
        api.getNotificationUnreadCount()
    }

    suspend fun markNotificationRead(notificationId: Long): NetworkResult<Unit> = apiCall {
        api.markNotificationRead(notificationId)
    }

    suspend fun markAllNotificationsRead(): NetworkResult<Unit> = apiCall {
        api.markAllNotificationsRead()
    }
}
