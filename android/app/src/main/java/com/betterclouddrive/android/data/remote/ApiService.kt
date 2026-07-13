package com.betterclouddrive.android.data.remote

import com.betterclouddrive.android.data.remote.dto.*
import com.betterclouddrive.android.domain.model.FileItem
import com.betterclouddrive.android.domain.model.FileVersion
import com.betterclouddrive.android.domain.model.OfficeConfig
import com.betterclouddrive.android.domain.model.ShareLink
import com.betterclouddrive.android.domain.model.Team
import com.betterclouddrive.android.domain.model.NotificationItem
import com.betterclouddrive.android.domain.model.TeamEffectivePermission
import com.betterclouddrive.android.domain.model.TeamFilePermission
import com.betterclouddrive.android.domain.model.TeamInvite
import com.betterclouddrive.android.domain.model.TeamMember
import com.betterclouddrive.android.domain.model.TeamOperationLog
import com.betterclouddrive.android.domain.model.UploadStatus
import com.betterclouddrive.android.domain.model.User
import okhttp3.MultipartBody
import okhttp3.ResponseBody
import retrofit2.http.*

interface ApiService {

    // ==================== Auth ====================
    @POST("auth/register")
    suspend fun register(@Body request: RegisterRequest): ApiResponse<RegisterResult>

    @POST("auth/register-code/send")
    suspend fun sendRegisterCode(@Body request: RegisterCodeRequest): ApiResponse<Unit>

    @POST("auth/login")
    suspend fun login(@Body request: LoginRequest): ApiResponse<AuthTokens>

    @POST("auth/refresh")
    suspend fun refresh(@Body request: RefreshRequest): ApiResponse<AuthTokens>

    @POST("auth/logout")
    suspend fun logout(): ApiResponse<Unit>

    @GET("auth/me")
    suspend fun getMe(): ApiResponse<User>

    @POST("auth/forgot-password")
    suspend fun forgotPassword(@Body request: ForgotPasswordRequest): ApiResponse<Unit>

    @POST("auth/reset-password")
    suspend fun resetPassword(@Body request: ResetPasswordRequest): ApiResponse<Unit>

    // ==================== Files ====================
    @GET("files")
    suspend fun listFiles(
        @Query("parentId") parentId: Long? = null,
        @Query("page") page: Int = 1,
        @Query("size") size: Int = 20,
        @Query("sortBy") sortBy: String = "fileName",
        @Query("order") order: String = "asc",
    ): ApiResponse<PageResult<FileItem>>

    @GET("files/{fileId}")
    suspend fun getFile(@Path("fileId") fileId: Long): ApiResponse<FileItem>

    @POST("files/folder")
    suspend fun createFolder(@Body request: CreateFolderRequest): ApiResponse<FileItem>

    @PUT("files/{fileId}")
    suspend fun renameFile(@Path("fileId") fileId: Long, @Body request: RenameRequest): ApiResponse<FileItem>

    @POST("files/{fileId}/move")
    suspend fun moveFile(@Path("fileId") fileId: Long, @Body request: MoveRequest): ApiResponse<Unit>

    @POST("files/{fileId}/copy")
    suspend fun copyFile(@Path("fileId") fileId: Long, @Body request: CopyRequest): ApiResponse<Unit>

    @HTTP(method = "DELETE", path = "files", hasBody = true)
    suspend fun deleteFiles(@Body request: BatchDeleteRequest): ApiResponse<Unit>

    @GET("files/search")
    suspend fun searchFiles(
        @Query("q") query: String,
        @Query("page") page: Int = 1,
        @Query("size") size: Int = 20,
    ): ApiResponse<PageResult<FileItem>>

    // ==================== Teams ====================
    @GET("teams")
    suspend fun listTeams(): ApiResponse<List<Team>>

    @POST("teams")
    suspend fun createTeam(@Body request: CreateTeamRequest): ApiResponse<Team>

    @GET("teams/{teamId}")
    suspend fun getTeam(@Path("teamId") teamId: Long): ApiResponse<Team>

    @PUT("teams/{teamId}")
    suspend fun updateTeam(
        @Path("teamId") teamId: Long,
        @Body request: UpdateTeamRequest,
    ): ApiResponse<Team>

    @DELETE("teams/{teamId}")
    suspend fun deleteTeam(@Path("teamId") teamId: Long): ApiResponse<Unit>

    @POST("teams/{teamId}/leave")
    suspend fun leaveTeam(@Path("teamId") teamId: Long): ApiResponse<Unit>

    @POST("teams/{teamId}/owner")
    suspend fun transferTeamOwner(
        @Path("teamId") teamId: Long,
        @Body request: TransferTeamOwnerRequest,
    ): ApiResponse<Team>

    @GET("teams/{teamId}/members")
    suspend fun listTeamMembers(@Path("teamId") teamId: Long): ApiResponse<List<TeamMember>>

    @POST("teams/{teamId}/members")
    suspend fun addTeamMember(
        @Path("teamId") teamId: Long,
        @Body request: AddTeamMemberRequest,
    ): ApiResponse<TeamMember>

    @PUT("teams/{teamId}/members/{userId}")
    suspend fun updateTeamMember(
        @Path("teamId") teamId: Long,
        @Path("userId") userId: Long,
        @Body request: UpdateTeamMemberRequest,
    ): ApiResponse<TeamMember>

    @DELETE("teams/{teamId}/members/{userId}")
    suspend fun removeTeamMember(
        @Path("teamId") teamId: Long,
        @Path("userId") userId: Long,
    ): ApiResponse<Unit>

    @POST("teams/{teamId}/invites")
    suspend fun createTeamInvite(
        @Path("teamId") teamId: Long,
        @Body request: CreateTeamInviteRequest,
    ): ApiResponse<TeamInvite>

    @GET("teams/{teamId}/invites")
    suspend fun listTeamInvites(@Path("teamId") teamId: Long): ApiResponse<List<TeamInvite>>

    @POST("teams/{teamId}/invites/{inviteId}/resend")
    suspend fun resendTeamInvite(
        @Path("teamId") teamId: Long,
        @Path("inviteId") inviteId: Long,
    ): ApiResponse<TeamInvite>

    @DELETE("teams/{teamId}/invites/{inviteId}")
    suspend fun cancelTeamInvite(
        @Path("teamId") teamId: Long,
        @Path("inviteId") inviteId: Long,
    ): ApiResponse<Unit>

    @GET("team-invites/{token}")
    suspend fun getTeamInvite(@Path("token") token: String): ApiResponse<TeamInvite>

    @POST("team-invites/{token}/accept")
    suspend fun acceptTeamInvite(@Path("token") token: String): ApiResponse<Unit>

    @POST("team-invites/{token}/reject")
    suspend fun rejectTeamInvite(@Path("token") token: String): ApiResponse<Unit>

    @GET("teams/{teamId}/audit-logs")
    suspend fun listTeamAuditLogs(
        @Path("teamId") teamId: Long,
        @Query("actorUserId") actorUserId: Long? = null,
        @Query("action") action: String? = null,
        @Query("targetType") targetType: String? = null,
        @Query("result") result: Int? = null,
        @Query("startAt") startAt: String? = null,
        @Query("endAt") endAt: String? = null,
        @Query("page") page: Int = 1,
        @Query("size") size: Int = 20,
    ): ApiResponse<PageResult<TeamOperationLog>>

    @Streaming
    @GET("teams/{teamId}/audit-logs/export")
    suspend fun exportTeamAuditLogs(
        @Path("teamId") teamId: Long,
        @Query("actorUserId") actorUserId: Long? = null,
        @Query("action") action: String? = null,
        @Query("targetType") targetType: String? = null,
        @Query("result") result: Int? = null,
        @Query("startAt") startAt: String? = null,
        @Query("endAt") endAt: String? = null,
    ): ResponseBody

    @GET("teams/{teamId}/files")
    suspend fun listTeamFiles(
        @Path("teamId") teamId: Long,
        @Query("parentId") parentId: Long? = null,
        @Query("page") page: Int = 1,
        @Query("size") size: Int = 20,
        @Query("sortBy") sortBy: String = "fileName",
        @Query("order") order: String = "asc",
    ): ApiResponse<PageResult<FileItem>>

    @GET("teams/{teamId}/files/search")
    suspend fun searchTeamFiles(
        @Path("teamId") teamId: Long,
        @Query("q") query: String,
        @Query("page") page: Int = 1,
        @Query("size") size: Int = 20,
    ): ApiResponse<PageResult<FileItem>>

    @GET("teams/{teamId}/files/{fileId}")
    suspend fun getTeamFile(
        @Path("teamId") teamId: Long,
        @Path("fileId") fileId: Long,
    ): ApiResponse<FileItem>

    @GET("teams/{teamId}/files/{fileId}/effective-permission")
    suspend fun getTeamFileEffectivePermission(
        @Path("teamId") teamId: Long,
        @Path("fileId") fileId: Long,
    ): ApiResponse<TeamEffectivePermission>

    @GET("teams/{teamId}/files/{fileId}/permissions")
    suspend fun listTeamFilePermissions(
        @Path("teamId") teamId: Long,
        @Path("fileId") fileId: Long,
    ): ApiResponse<List<TeamFilePermission>>

    @PUT("teams/{teamId}/files/{fileId}/permissions")
    suspend fun upsertTeamFilePermission(
        @Path("teamId") teamId: Long,
        @Path("fileId") fileId: Long,
        @Body request: UpdateTeamFilePermissionRequest,
    ): ApiResponse<TeamFilePermission>

    @DELETE("teams/{teamId}/files/{fileId}/permissions/{permissionId}")
    suspend fun deleteTeamFilePermission(
        @Path("teamId") teamId: Long,
        @Path("fileId") fileId: Long,
        @Path("permissionId") permissionId: Long,
    ): ApiResponse<Unit>

    @POST("teams/{teamId}/files/folder")
    suspend fun createTeamFolder(
        @Path("teamId") teamId: Long,
        @Body request: CreateFolderRequest,
    ): ApiResponse<FileItem>

    @PUT("teams/{teamId}/files/{fileId}")
    suspend fun renameTeamFile(
        @Path("teamId") teamId: Long,
        @Path("fileId") fileId: Long,
        @Body request: RenameRequest,
    ): ApiResponse<FileItem>

    @POST("teams/{teamId}/files/{fileId}/move")
    suspend fun moveTeamFile(
        @Path("teamId") teamId: Long,
        @Path("fileId") fileId: Long,
        @Body request: MoveRequest,
    ): ApiResponse<Unit>

    @HTTP(method = "DELETE", path = "teams/{teamId}/files", hasBody = true)
    suspend fun deleteTeamFiles(
        @Path("teamId") teamId: Long,
        @Body request: BatchDeleteRequest,
    ): ApiResponse<Unit>

    @GET("teams/{teamId}/recycle-bin")
    suspend fun listTeamRecycleBin(
        @Path("teamId") teamId: Long,
        @Query("page") page: Int = 1,
        @Query("size") size: Int = 20,
    ): ApiResponse<PageResult<FileItem>>

    @POST("teams/{teamId}/recycle-bin/{fileId}/restore")
    suspend fun restoreTeamFile(
        @Path("teamId") teamId: Long,
        @Path("fileId") fileId: Long,
    ): ApiResponse<Unit>

    @DELETE("teams/{teamId}/recycle-bin/{fileId}")
    suspend fun permanentDeleteTeamFile(
        @Path("teamId") teamId: Long,
        @Path("fileId") fileId: Long,
    ): ApiResponse<Unit>

    @DELETE("teams/{teamId}/recycle-bin")
    suspend fun emptyTeamRecycleBin(@Path("teamId") teamId: Long): ApiResponse<Unit>

    // ==================== Upload ====================
    @POST("upload/init")
    suspend fun initUpload(@Body request: InitUploadRequest): ApiResponse<InitUploadResult>

    @Multipart
    @POST("upload/{sessionId}/chunk")
    suspend fun uploadChunk(
        @Path("sessionId") sessionId: String,
        @Part file: MultipartBody.Part,
        @Query("chunkNumber") chunkNumber: Int,
    ): ApiResponse<UploadChunkResult>

    @GET("upload/{sessionId}/status")
    suspend fun getUploadStatus(@Path("sessionId") sessionId: String): ApiResponse<UploadStatus>

    @POST("upload/{sessionId}/complete")
    suspend fun completeUpload(@Path("sessionId") sessionId: String): ApiResponse<CompleteResult>

    @POST("upload/{sessionId}/cancel")
    suspend fun cancelUpload(@Path("sessionId") sessionId: String): ApiResponse<Unit>

    @POST("upload/instant")
    suspend fun instantUpload(@Body request: InstantUploadRequest): ApiResponse<InstantUploadResult>

    @POST("teams/{teamId}/upload/init")
    suspend fun initTeamUpload(
        @Path("teamId") teamId: Long,
        @Body request: InitUploadRequest,
    ): ApiResponse<InitUploadResult>

    @Multipart
    @POST("teams/{teamId}/upload/{sessionId}/chunk")
    suspend fun uploadTeamChunk(
        @Path("teamId") teamId: Long,
        @Path("sessionId") sessionId: String,
        @Part file: MultipartBody.Part,
        @Query("chunkNumber") chunkNumber: Int,
    ): ApiResponse<UploadChunkResult>

    @GET("teams/{teamId}/upload/{sessionId}/status")
    suspend fun getTeamUploadStatus(
        @Path("teamId") teamId: Long,
        @Path("sessionId") sessionId: String,
    ): ApiResponse<UploadStatus>

    @POST("teams/{teamId}/upload/{sessionId}/complete")
    suspend fun completeTeamUpload(
        @Path("teamId") teamId: Long,
        @Path("sessionId") sessionId: String,
    ): ApiResponse<CompleteResult>

    @POST("teams/{teamId}/upload/{sessionId}/cancel")
    suspend fun cancelTeamUpload(
        @Path("teamId") teamId: Long,
        @Path("sessionId") sessionId: String,
    ): ApiResponse<Unit>

    @POST("teams/{teamId}/upload/instant")
    suspend fun instantTeamUpload(
        @Path("teamId") teamId: Long,
        @Body request: InstantUploadRequest,
    ): ApiResponse<InstantUploadResult>

    // ==================== Download / Preview ====================
    @Streaming
    @GET("download/{fileId}")
    suspend fun downloadFile(
        @Path("fileId") fileId: Long,
        @Header("Range") range: String? = null,
    ): ResponseBody

    @Streaming
    @GET("preview/{fileId}")
    suspend fun previewFile(@Path("fileId") fileId: Long): ResponseBody

    @Streaming
    @GET("teams/{teamId}/download/{fileId}")
    suspend fun downloadTeamFile(
        @Path("teamId") teamId: Long,
        @Path("fileId") fileId: Long,
        @Header("Range") range: String? = null,
    ): ResponseBody

    @POST("teams/{teamId}/download/{fileId}/ticket")
    suspend fun createTeamDownloadTicket(
        @Path("teamId") teamId: Long,
        @Path("fileId") fileId: Long,
    ): ApiResponse<DownloadTicketResponse>

    @Streaming
    @GET("teams/{teamId}/preview/{fileId}")
    suspend fun previewTeamFile(
        @Path("teamId") teamId: Long,
        @Path("fileId") fileId: Long,
    ): ResponseBody

    // ==================== Recycle Bin ====================
    @GET("recycle-bin")
    suspend fun listRecycleBin(
        @Query("page") page: Int = 1,
        @Query("size") size: Int = 20,
    ): ApiResponse<PageResult<FileItem>>

    @POST("recycle-bin/{fileId}/restore")
    suspend fun restoreFile(@Path("fileId") fileId: Long): ApiResponse<Unit>

    @DELETE("recycle-bin/{fileId}")
    suspend fun permanentDeleteFile(@Path("fileId") fileId: Long): ApiResponse<Unit>

    @DELETE("recycle-bin")
    suspend fun emptyRecycleBin(): ApiResponse<Unit>

    // ==================== Shares ====================
    @POST("shares")
    suspend fun createShare(@Body request: CreateShareRequest): ApiResponse<ShareLink>

    @GET("shares")
    suspend fun listShares(
        @Query("page") page: Int = 1,
        @Query("size") size: Int = 20,
        @Query("q") keyword: String? = null,
        @Query("passwordProtected") passwordProtected: Boolean? = null,
        @Query("expirationStatus") expirationStatus: String? = null,
    ): ApiResponse<PageResult<ShareLink>>

    @GET("shares/{shareId}")
    suspend fun getShare(@Path("shareId") shareId: Long): ApiResponse<ShareLink>

    @GET("shares/{shareId}/password")
    suspend fun getSharePassword(@Path("shareId") shareId: Long): ApiResponse<SharePasswordResponse>

    @PUT("shares/{shareId}")
    suspend fun updateShare(
        @Path("shareId") shareId: Long,
        @Body request: UpdateShareRequest,
    ): ApiResponse<ShareLink>

    @DELETE("shares/{shareId}")
    suspend fun cancelShare(@Path("shareId") shareId: Long): ApiResponse<Unit>

    @POST("teams/{teamId}/shares")
    suspend fun createTeamShare(
        @Path("teamId") teamId: Long,
        @Body request: CreateShareRequest,
    ): ApiResponse<ShareLink>

    @GET("teams/{teamId}/shares")
    suspend fun listTeamShares(
        @Path("teamId") teamId: Long,
        @Query("page") page: Int = 1,
        @Query("size") size: Int = 20,
        @Query("q") keyword: String? = null,
        @Query("passwordProtected") passwordProtected: Boolean? = null,
        @Query("expirationStatus") expirationStatus: String? = null,
    ): ApiResponse<PageResult<ShareLink>>

    @GET("teams/{teamId}/shares/{shareId}")
    suspend fun getTeamShare(
        @Path("teamId") teamId: Long,
        @Path("shareId") shareId: Long,
    ): ApiResponse<ShareLink>

    @GET("teams/{teamId}/shares/{shareId}/password")
    suspend fun getTeamSharePassword(
        @Path("teamId") teamId: Long,
        @Path("shareId") shareId: Long,
    ): ApiResponse<SharePasswordResponse>

    @PUT("teams/{teamId}/shares/{shareId}")
    suspend fun updateTeamShare(
        @Path("teamId") teamId: Long,
        @Path("shareId") shareId: Long,
        @Body request: UpdateShareRequest,
    ): ApiResponse<ShareLink>

    @DELETE("teams/{teamId}/shares/{shareId}")
    suspend fun cancelTeamShare(
        @Path("teamId") teamId: Long,
        @Path("shareId") shareId: Long,
    ): ApiResponse<Unit>

    @POST("shares/access/{shareCode}")
    suspend fun accessShare(
        @Path("shareCode") shareCode: String,
        @Body request: AccessShareRequest = AccessShareRequest(),
    ): ApiResponse<ShareAccessResult>

    @GET("shares/access/{shareCode}/files")
    suspend fun listSharedFiles(
        @Path("shareCode") shareCode: String,
        @Query("parentId") parentId: Long? = null,
        @Query("page") page: Int = 1,
        @Query("size") size: Int = 20,
        @Query("password") password: String? = null,
    ): ApiResponse<PageResult<FileItem>>

    @POST("shares/access/{shareCode}/save")
    suspend fun saveSharedItem(
        @Path("shareCode") shareCode: String,
        @Body request: SaveSharedItemRequest = SaveSharedItemRequest(),
    ): ApiResponse<FileItem>

    @Streaming
    @POST("shares/access/{shareCode}/download/{fileId}")
    suspend fun downloadSharedFile(
        @Path("shareCode") shareCode: String,
        @Path("fileId") fileId: Long,
        @Body request: AccessShareRequest = AccessShareRequest(),
    ): ResponseBody

    @Streaming
    @POST("shares/access/{shareCode}/download/{fileId}/zip")
    suspend fun downloadSharedFolderZip(
        @Path("shareCode") shareCode: String,
        @Path("fileId") fileId: Long,
        @Body request: AccessShareRequest = AccessShareRequest(),
    ): ResponseBody

    // ==================== Favorites ====================
    @POST("favorites/{fileId}")
    suspend fun addFavorite(@Path("fileId") fileId: Long): ApiResponse<Unit>

    @DELETE("favorites/{fileId}")
    suspend fun removeFavorite(@Path("fileId") fileId: Long): ApiResponse<Unit>

    @GET("favorites")
    suspend fun listFavorites(
        @Query("page") page: Int = 1,
        @Query("size") size: Int = 20,
    ): ApiResponse<PageResult<FileItem>>

    @GET("favorites/{fileId}/status")
    suspend fun getFavoriteStatus(@Path("fileId") fileId: Long): ApiResponse<Boolean>

    @POST("teams/{teamId}/favorites/{fileId}")
    suspend fun addTeamFavorite(
        @Path("teamId") teamId: Long,
        @Path("fileId") fileId: Long,
    ): ApiResponse<Unit>

    @DELETE("teams/{teamId}/favorites/{fileId}")
    suspend fun removeTeamFavorite(
        @Path("teamId") teamId: Long,
        @Path("fileId") fileId: Long,
    ): ApiResponse<Unit>

    @GET("teams/{teamId}/favorites/{fileId}/status")
    suspend fun getTeamFavoriteStatus(
        @Path("teamId") teamId: Long,
        @Path("fileId") fileId: Long,
    ): ApiResponse<Boolean>

    @GET("teams/{teamId}/favorites")
    suspend fun listTeamFavorites(
        @Path("teamId") teamId: Long,
        @Query("q") query: String? = null,
        @Query("page") page: Int = 1,
        @Query("size") size: Int = 20,
    ): ApiResponse<PageResult<FileItem>>

    // ==================== Tags ====================
    @POST("tags")
    suspend fun createTag(@Body request: CreateTagRequest): ApiResponse<com.betterclouddrive.android.domain.model.Tag>

    @GET("tags")
    suspend fun listTags(): ApiResponse<List<com.betterclouddrive.android.domain.model.Tag>>

    @PUT("tags/{tagId}")
    suspend fun updateTag(@Path("tagId") tagId: Long, @Body request: UpdateTagRequest): ApiResponse<com.betterclouddrive.android.domain.model.Tag>

    @DELETE("tags/{tagId}")
    suspend fun deleteTag(@Path("tagId") tagId: Long): ApiResponse<Unit>

    @POST("tags/{tagId}/files")
    suspend fun tagFiles(@Path("tagId") tagId: Long, @Body request: TagFilesRequest): ApiResponse<Unit>

    @DELETE("tags/{tagId}/files/{fileId}")
    suspend fun untagFile(@Path("tagId") tagId: Long, @Path("fileId") fileId: Long): ApiResponse<Unit>

    @GET("tags/{tagId}/files")
    suspend fun listFilesByTag(
        @Path("tagId") tagId: Long,
        @Query("page") page: Int = 1,
        @Query("size") size: Int = 20,
    ): ApiResponse<PageResult<FileItem>>

    @POST("teams/{teamId}/tags")
    suspend fun createTeamTag(
        @Path("teamId") teamId: Long,
        @Body request: CreateTagRequest,
    ): ApiResponse<com.betterclouddrive.android.domain.model.Tag>

    @GET("teams/{teamId}/tags")
    suspend fun listTeamTags(
        @Path("teamId") teamId: Long,
        @Query("q") query: String? = null,
    ): ApiResponse<List<com.betterclouddrive.android.domain.model.Tag>>

    @PUT("teams/{teamId}/tags/{tagId}")
    suspend fun updateTeamTag(
        @Path("teamId") teamId: Long,
        @Path("tagId") tagId: Long,
        @Body request: UpdateTagRequest,
    ): ApiResponse<com.betterclouddrive.android.domain.model.Tag>

    @DELETE("teams/{teamId}/tags/{tagId}")
    suspend fun deleteTeamTag(
        @Path("teamId") teamId: Long,
        @Path("tagId") tagId: Long,
    ): ApiResponse<Unit>

    @POST("teams/{teamId}/tags/{tagId}/files")
    suspend fun tagTeamFiles(
        @Path("teamId") teamId: Long,
        @Path("tagId") tagId: Long,
        @Body request: TagFilesRequest,
    ): ApiResponse<Unit>

    @DELETE("teams/{teamId}/tags/{tagId}/files/{fileId}")
    suspend fun untagTeamFile(
        @Path("teamId") teamId: Long,
        @Path("tagId") tagId: Long,
        @Path("fileId") fileId: Long,
    ): ApiResponse<Unit>

    @GET("teams/{teamId}/tags/{tagId}/files")
    suspend fun listTeamFilesByTag(
        @Path("teamId") teamId: Long,
        @Path("tagId") tagId: Long,
        @Query("q") query: String? = null,
        @Query("page") page: Int = 1,
        @Query("size") size: Int = 20,
    ): ApiResponse<PageResult<FileItem>>

    // ==================== Notifications ====================
    @GET("notifications")
    suspend fun listNotifications(
        @Query("page") page: Int = 1,
        @Query("size") size: Int = 20,
        @Query("unreadOnly") unreadOnly: Boolean = false,
        @Query("type") type: String? = null,
    ): ApiResponse<PageResult<NotificationItem>>

    @GET("notifications/unread-count")
    suspend fun getNotificationUnreadCount(): ApiResponse<Int>

    @POST("notifications/{notificationId}/read")
    suspend fun markNotificationRead(@Path("notificationId") notificationId: Long): ApiResponse<Unit>

    @POST("notifications/read-all")
    suspend fun markAllNotificationsRead(): ApiResponse<Unit>

    // ==================== File Versions ====================
    @GET("files/{fileId}/versions")
    suspend fun listVersions(@Path("fileId") fileId: Long): ApiResponse<List<FileVersion>>

    @DELETE("files/{fileId}/versions/{versionNumber}")
    suspend fun deleteVersion(
        @Path("fileId") fileId: Long,
        @Path("versionNumber") versionNumber: Int,
    ): ApiResponse<Unit>

    @POST("files/{fileId}/versions/{versionNumber}/restore")
    suspend fun restoreVersion(
        @Path("fileId") fileId: Long,
        @Path("versionNumber") versionNumber: Int,
    ): ApiResponse<Unit>

    @Streaming
    @GET("files/{fileId}/versions/{versionNumber}/download")
    suspend fun downloadVersion(
        @Path("fileId") fileId: Long,
        @Path("versionNumber") versionNumber: Int,
    ): ResponseBody

    @GET("teams/{teamId}/files/{fileId}/versions")
    suspend fun listTeamVersions(
        @Path("teamId") teamId: Long,
        @Path("fileId") fileId: Long,
    ): ApiResponse<List<FileVersion>>

    @DELETE("teams/{teamId}/files/{fileId}/versions/{versionNumber}")
    suspend fun deleteTeamVersion(
        @Path("teamId") teamId: Long,
        @Path("fileId") fileId: Long,
        @Path("versionNumber") versionNumber: Int,
    ): ApiResponse<Unit>

    @POST("teams/{teamId}/files/{fileId}/versions/{versionNumber}/restore")
    suspend fun restoreTeamVersion(
        @Path("teamId") teamId: Long,
        @Path("fileId") fileId: Long,
        @Path("versionNumber") versionNumber: Int,
    ): ApiResponse<Unit>

    @Streaming
    @GET("teams/{teamId}/files/{fileId}/versions/{versionNumber}/download")
    suspend fun downloadTeamVersion(
        @Path("teamId") teamId: Long,
        @Path("fileId") fileId: Long,
        @Path("versionNumber") versionNumber: Int,
    ): ResponseBody

    @GET("teams/{teamId}/office/files/{fileId}/config")
    suspend fun getTeamOfficeConfig(
        @Path("teamId") teamId: Long,
        @Path("fileId") fileId: Long,
    ): ApiResponse<OfficeConfig>
}
