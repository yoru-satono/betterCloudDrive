package com.betterclouddrive.android.util

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

object ServerUrlUtil {
    fun normalizeBaseUrl(input: String): Result<String> {
        val trimmed = input.trim().trimEnd('/')
        if (trimmed.isBlank()) return Result.failure(IllegalArgumentException("服务器地址不能为空"))
        val parsed = trimmed.toHttpUrlOrNull() ?: return Result.failure(IllegalArgumentException("服务器地址格式无效"))
        if (parsed.scheme !in setOf("http", "https")) {
            return Result.failure(IllegalArgumentException("服务器地址必须以 http:// 或 https:// 开头"))
        }
        if (parsed.host.isBlank()) return Result.failure(IllegalArgumentException("服务器地址缺少主机名"))
        return Result.success(parsed.newBuilder().encodedPath("/").build().toString().trimEnd('/'))
    }

    fun apiBaseUrl(serverBaseUrl: String): HttpUrl {
        val normalized = normalizeBaseUrl(serverBaseUrl).getOrElse { Constants.DEFAULT_SERVER_BASE_URL }
        return "$normalized/api/v1/".toHttpUrlOrNull()
            ?: "${Constants.DEFAULT_SERVER_BASE_URL}/api/v1/".toHttpUrlOrNull()
            ?: error("Invalid default server URL")
    }

    fun previewUrl(serverBaseUrl: String, fileId: Long): String {
        return apiBaseUrl(serverBaseUrl).newBuilder()
            .addPathSegment("preview")
            .addPathSegment(fileId.toString())
            .build()
            .toString()
    }
}
