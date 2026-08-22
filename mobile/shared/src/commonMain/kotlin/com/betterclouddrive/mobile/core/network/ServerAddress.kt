package com.betterclouddrive.mobile.core.network

import io.ktor.http.URLProtocol
import io.ktor.http.Url

object ServerAddress {
    fun normalize(input: String, allowHttp: Boolean = true): Result<String> = runCatching {
        val trimmed = input.trim().trimEnd('/')
        require(trimmed.isNotBlank()) { "服务器地址不能为空" }
        val url = Url(trimmed)
        require(url.protocol == URLProtocol.HTTP || url.protocol == URLProtocol.HTTPS) {
            "服务器地址必须以 http:// 或 https:// 开头"
        }
        require(allowHttp || url.protocol == URLProtocol.HTTPS) { "生产版本仅允许 https:// 服务器地址" }
        require(url.host.isNotBlank()) { "服务器地址缺少主机名" }
        val host = if (':' in url.host && !url.host.startsWith('[')) "[${url.host}]" else url.host
        val port = if (url.port == url.protocol.defaultPort) "" else ":${url.port}"
        "${url.protocol.name}://$host$port"
    }

    fun apiUrl(serverBaseUrl: String, path: String, allowHttp: Boolean = true): String =
        "${normalize(serverBaseUrl, allowHttp).getOrThrow()}/api/v1/${path.trimStart('/')}"
}
