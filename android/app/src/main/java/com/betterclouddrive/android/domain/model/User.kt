package com.betterclouddrive.android.domain.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class User(
    val id: Long = 0,
    val username: String = "",
    val email: String? = null,
    val nickname: String? = null,
    @SerialName("avatarUrl") val avatarUrl: String? = null,
    @SerialName("storageQuota") val storageQuota: Long = 0,
    @SerialName("storageUsed") val storageUsed: Long = 0,
    val status: Int = 1,
    val role: String = "ROLE_USER",
    @SerialName("emailVerified") val emailVerified: Boolean = false,
    @SerialName("createdAt") val createdAt: String? = null,
    @SerialName("updatedAt") val updatedAt: String? = null,
)
