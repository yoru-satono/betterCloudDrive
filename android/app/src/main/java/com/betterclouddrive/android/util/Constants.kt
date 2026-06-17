package com.betterclouddrive.android.util

object Constants {
    const val DEFAULT_SERVER_BASE_URL = "http://10.0.2.2:8080"
    const val BASE_URL = "$DEFAULT_SERVER_BASE_URL/api/v1/"
    const val CHUNK_SIZE = 5L * 1024 * 1024 // 5MB
    const val MAX_FILE_SIZE = 10L * 1024 * 1024 * 1024 // 10GB
    const val SEARCH_DEBOUNCE_MS = 300L
    const val PAGE_SIZE_DEFAULT = 20
}
