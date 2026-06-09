package com.betterclouddrive.android.data.repository

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.betterclouddrive.android.data.remote.ApiService
import com.betterclouddrive.android.data.remote.dto.*
import com.betterclouddrive.android.domain.model.UploadStatus
import com.betterclouddrive.android.util.Constants
import com.betterclouddrive.android.util.NetworkResult
import dagger.hilt.android.qualifiers.ApplicationContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.InputStream
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class UploadRepository @Inject constructor(
    private val api: ApiService,
    @ApplicationContext private val context: Context,
) {
    suspend fun initUpload(
        parentId: Long,
        fileName: String,
        fileSize: Long,
        totalChunks: Int,
        md5Hash: String? = null,
    ): NetworkResult<InitUploadResult> = apiCall {
        api.initUpload(InitUploadRequest(parentId, fileName, fileSize, md5Hash, totalChunks))
    }

    suspend fun uploadChunk(
        sessionId: String,
        chunkNumber: Int,
        data: ByteArray,
    ): NetworkResult<UploadChunkResult> {
        return try {
            val requestBody = data.toRequestBody("application/octet-stream".toMediaType())
            val part = MultipartBody.Part.createFormData("file", "chunk_$chunkNumber", requestBody)
            val response = api.uploadChunk(sessionId, part, chunkNumber)
            if (response.code == 200) NetworkResult.Success(response.data!!)
            else NetworkResult.Error(response.code, response.message)
        } catch (e: Exception) {
            NetworkResult.Error(-1, e.message ?: "Upload chunk failed")
        }
    }

    suspend fun getUploadStatus(sessionId: String): NetworkResult<UploadStatus> = apiCall {
        api.getUploadStatus(sessionId)
    }

    suspend fun completeUpload(sessionId: String): NetworkResult<CompleteResult> = apiCall {
        api.completeUpload(sessionId)
    }

    suspend fun cancelUpload(sessionId: String): NetworkResult<Unit> = apiCall {
        api.cancelUpload(sessionId)
    }

    suspend fun instantUpload(
        parentId: Long,
        fileName: String,
        fileSize: Long,
        md5Hash: String,
    ): NetworkResult<InstantUploadResult> = apiCall {
        api.instantUpload(InstantUploadRequest(parentId, fileName, fileSize, md5Hash))
    }

    fun getFileName(uri: Uri): String {
        var name = "unknown"
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && cursor.moveToFirst()) {
                name = cursor.getString(idx)
            }
        }
        return name
    }

    fun getFileSize(uri: Uri): Long {
        var size = 0L
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val idx = cursor.getColumnIndex(OpenableColumns.SIZE)
            if (idx >= 0 && cursor.moveToFirst()) {
                size = cursor.getLong(idx)
            }
        }
        return size
    }

    fun openInputStream(uri: Uri): InputStream? {
        return context.contentResolver.openInputStream(uri)
    }

    fun computeMd5(data: ByteArray): String {
        val digest = MessageDigest.getInstance("MD5")
        val hash = digest.digest(data)
        return hash.joinToString("") { "%02x".format(it) }
    }
}
