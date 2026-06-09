package com.betterclouddrive.android.data.repository

import com.betterclouddrive.android.data.remote.dto.ApiResponse
import com.betterclouddrive.android.util.NetworkResult

suspend fun <T> apiCall(call: suspend () -> ApiResponse<T>): NetworkResult<T> {
    return try {
        val response = call()
        if (response.code == 200 && response.data != null) {
            NetworkResult.Success(response.data)
        } else if (response.code == 200) {
            @Suppress("UNCHECKED_CAST")
            NetworkResult.Success(Unit as T)
        } else {
            NetworkResult.Error(response.code, response.message, response.requestId)
        }
    } catch (e: Exception) {
        NetworkResult.Error(-1, e.message ?: "Network error")
    }
}
