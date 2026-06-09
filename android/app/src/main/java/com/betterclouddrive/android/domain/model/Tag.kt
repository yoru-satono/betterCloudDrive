package com.betterclouddrive.android.domain.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class Tag(
    val id: Long = 0,
    @SerialName("userId") val userId: Long = 0,
    @SerialName("tagName") val tagName: String = "",
    val color: String = "#1890ff",
    @SerialName("createdAt") val createdAt: String? = null,
)
