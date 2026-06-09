package com.betterclouddrive.android.util

sealed class NetworkResult<out T> {
    data class Success<T>(val data: T) : NetworkResult<T>()
    data class Error(val code: Int, val message: String, val requestId: String? = null) : NetworkResult<Nothing>()
    data object Loading : NetworkResult<Nothing>()
}

sealed class UiEvent {
    data class ShowSnackbar(val message: String, val isError: Boolean = false) : UiEvent()
    data object NavigateBack : UiEvent()
    data class NavigateTo(val route: String) : UiEvent()
}
