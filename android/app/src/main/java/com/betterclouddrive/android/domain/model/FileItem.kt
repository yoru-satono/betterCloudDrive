package com.betterclouddrive.android.domain.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class FileItem(
    val id: Long = 0,
    @SerialName("userId") val userId: Long = 0,
    @SerialName("parentId") val parentId: Long? = null,
    @SerialName("fileName") val fileName: String = "",
    @SerialName("fileType") val fileType: String = "file", // "file" | "folder"
    @SerialName("mimeType") val mimeType: String? = null,
    @SerialName("fileSize") val fileSize: Long = 0,
    @SerialName("storagePath") val storagePath: String? = null,
    @SerialName("md5Hash") val md5Hash: String? = null,
    @SerialName("thumbnailPath") val thumbnailPath: String? = null,
    @SerialName("isDeleted") val isDeleted: Boolean = false,
    @SerialName("deletedAt") val deletedAt: String? = null,
    @SerialName("versionCount") val versionCount: Int = 1,
    @SerialName("createdAt") val createdAt: String? = null,
    @SerialName("updatedAt") val updatedAt: String? = null,
) {
    val isFolder: Boolean get() = fileType == "folder"
}
