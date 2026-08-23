package com.betterclouddrive.mobile.core.network

import io.ktor.http.URLProtocol
import io.ktor.http.Url

object ServerAddress {
    fun normalize(input: String, allowHttp: Boolean = true): Result<String> = runCatching {
        val trimmed = input.trim().trimEnd('/')
        require(trimmed.isNotBlank()) { "server.empty" }
        val url = Url(trimmed)
        require(url.protocol == URLProtocol.HTTP || url.protocol == URLProtocol.HTTPS) {
            "server.scheme"
        }
        require(allowHttp || url.protocol == URLProtocol.HTTPS) { "server.httpsRequired" }
        require(url.host.isNotBlank()) { "server.hostMissing" }
        val host = if (':' in url.host && !url.host.startsWith('[')) "[${url.host}]" else url.host
        val port = if (url.port == url.protocol.defaultPort) "" else ":${url.port}"
        "${url.protocol.name}://$host$port"
    }

    fun apiUrl(serverBaseUrl: String, path: String, allowHttp: Boolean = true): String =
        "${normalize(serverBaseUrl, allowHttp).getOrThrow()}/api/v1/${path.trimStart('/')}"
}
