package com.betterclouddrive.android.domain.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ShareLink(
    val id: Long = 0,
    @SerialName("userId") val userId: Long = 0,
    @SerialName("fileId") val fileId: Long = 0,
    @SerialName("shareCode") val shareCode: String = "",
    @SerialName("passwordHash") val passwordHash: String? = null,
    @SerialName("hasPassword") val hasPassword: Boolean = false,
    @SerialName("expireAt") val expireAt: String? = null,
    @SerialName("maxDownloads") val maxDownloads: Int? = null,
    @SerialName("downloadCount") val downloadCount: Int = 0,
    @SerialName("visitCount") val visitCount: Int = 0,
    @SerialName("isCanceled") val isCanceled: Boolean = false,
    @SerialName("createdAt") val createdAt: String? = null,
    @SerialName("updatedAt") val updatedAt: String? = null,
    // Expanded file info when returned by API
    @SerialName("fileName") val fileName: String? = null,
    @SerialName("fileSize") val fileSize: Long? = null,
    @SerialName("fileType") val fileType: String? = null,
)
