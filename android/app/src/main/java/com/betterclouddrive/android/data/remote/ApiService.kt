package com.betterclouddrive.android.data.remote

import com.betterclouddrive.android.data.remote.dto.*
import com.betterclouddrive.android.domain.model.FileItem
import com.betterclouddrive.android.domain.model.FileVersion
import com.betterclouddrive.android.domain.model.ShareLink
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

    // ==================== File Versions ====================
    @GET("files/{fileId}/versions")
    suspend fun listVersions(@Path("fileId") fileId: Long): ApiResponse<List<FileVersion>>

    @DELETE("files/{fileId}/versions/{versionNumber}")
    suspend fun deleteVersion(
        @Path("fileId") fileId: Long,
        @Path("versionNumber") versionNumber: Int,
    ): ApiResponse<Unit>
}
