package com.betterclouddrive.android.util

import kotlinx.serialization.json.JsonElement

sealed class NetworkResult<out T> {
    data class Success<T>(val data: T) : NetworkResult<T>()
    data class Error(
        val code: Int,
        val messageKey: String,
        val messageParams: Map<String, JsonElement> = emptyMap(),
        val requestId: String? = null,
    ) : NetworkResult<Nothing>() {
        val message: String get() = MessageResolver.resolve(messageKey, messageParams)
    }
    data object Loading : NetworkResult<Nothing>()
}

sealed class UiEvent {
    data class ShowSnackbar(val message: String, val isError: Boolean = false) : UiEvent()
    data object NavigateBack : UiEvent()
    data class NavigateTo(val route: String) : UiEvent()
}
