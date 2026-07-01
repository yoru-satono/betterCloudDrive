package com.betterclouddrive.android.domain.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
data class OfficeConfig(
    @SerialName("documentServerUrl") val documentServerUrl: String = "",
    val editable: Boolean = false,
    val config: JsonObject = JsonObject(emptyMap()),
)
