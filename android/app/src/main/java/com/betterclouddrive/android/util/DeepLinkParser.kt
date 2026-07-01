package com.betterclouddrive.android.util

import android.net.Uri
import java.net.URI
import java.net.URLDecoder

object DeepLinkParser {
    fun teamInviteToken(uri: Uri?): String? = teamInviteToken(uri?.toString())

    fun teamInviteToken(rawUri: String?): String? {
        if (rawUri.isNullOrBlank()) return null
        val uri = runCatching { URI(rawUri) }.getOrNull() ?: return null
        val segments = uri.rawPath
            ?.split("/")
            ?.filter { it.isNotBlank() }
            ?.map { URLDecoder.decode(it, Charsets.UTF_8.name()) }
            .orEmpty()
        if (uri.scheme == "betterclouddrive" && uri.host == "team-invites") {
            return segments.firstOrNull()?.takeIf { it.isNotBlank() }
        }
        if ((uri.scheme == "http" || uri.scheme == "https") &&
            (uri.host == "localhost" || uri.host == "127.0.0.1") &&
            uri.port == 3000 &&
            segments.firstOrNull() == "team-invites"
        ) {
            return segments.getOrNull(1)?.takeIf { it.isNotBlank() }
        }
        return null
    }
}
