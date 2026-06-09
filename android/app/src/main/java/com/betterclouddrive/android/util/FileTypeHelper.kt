package com.betterclouddrive.android.util

import androidx.compose.ui.graphics.Color

object FileTypeHelper {
    private val extColorMap = mapOf(
        "pdf" to Color(0xFFFF4757),
        "jpg" to Color(0xFFA78BFA),
        "jpeg" to Color(0xFFA78BFA),
        "png" to Color(0xFFA78BFA),
        "gif" to Color(0xFFA78BFA),
        "webp" to Color(0xFFA78BFA),
        "svg" to Color(0xFFA78BFA),
        "mp4" to Color(0xFF4A9EFF),
        "mov" to Color(0xFF4A9EFF),
        "avi" to Color(0xFF4A9EFF),
        "mkv" to Color(0xFF4A9EFF),
        "mp3" to Color(0xFFF0A500),
        "wav" to Color(0xFFF0A500),
        "flac" to Color(0xFFF0A500),
        "aac" to Color(0xFFF0A500),
        "zip" to Color(0xFFF0A500),
        "rar" to Color(0xFFF0A500),
        "7z" to Color(0xFFF0A500),
        "tar" to Color(0xFFF0A500),
        "gz" to Color(0xFFF0A500),
        "txt" to Color(0xFF00D4AA),
        "md" to Color(0xFF00D4AA),
        "json" to Color(0xFFF0A500),
        "xml" to Color(0xFFF0A500),
        "html" to Color(0xFF4A9EFF),
        "css" to Color(0xFF4A9EFF),
        "js" to Color(0xFFF0A500),
        "kt" to Color(0xFFA78BFA),
        "java" to Color(0xFFA78BFA),
        "py" to Color(0xFF4A9EFF),
        "apk" to Color(0xFF00D4AA),
        "doc" to Color(0xFF4A9EFF),
        "docx" to Color(0xFF4A9EFF),
        "xls" to Color(0xFF00D4AA),
        "xlsx" to Color(0xFF00D4AA),
        "ppt" to Color(0xFFFF4757),
        "pptx" to Color(0xFFFF4757),
    )

    fun getColorForExtension(fileName: String): Color {
        val ext = fileName.substringAfterLast('.', "").lowercase()
        return extColorMap[ext] ?: Color(0xFF8899AA)
    }

    fun getExtension(fileName: String): String {
        return fileName.substringAfterLast('.', "").lowercase()
    }

    fun isImage(fileName: String): Boolean {
        val ext = getExtension(fileName)
        return ext in setOf("jpg", "jpeg", "png", "gif", "webp", "svg", "bmp", "heic", "heif")
    }

    fun isVideo(fileName: String): Boolean {
        val ext = getExtension(fileName)
        return ext in setOf("mp4", "mov", "avi", "mkv", "webm", "flv", "3gp")
    }

    fun isAudio(fileName: String): Boolean {
        val ext = getExtension(fileName)
        return ext in setOf("mp3", "wav", "flac", "aac", "ogg", "wma", "m4a")
    }

    fun isText(fileName: String): Boolean {
        val ext = getExtension(fileName)
        return ext in setOf("txt", "md", "json", "xml", "html", "css", "js", "kt", "java", "py", "csv", "log", "yaml", "yml", "ini", "cfg", "sh", "bat")
    }

    fun isArchive(fileName: String): Boolean {
        val ext = getExtension(fileName)
        return ext in setOf("zip", "rar", "7z", "tar", "gz", "bz2", "xz")
    }

    fun getMimeType(fileName: String): String = when {
        isImage(fileName) -> "image/${getExtension(fileName)}"
        isVideo(fileName) -> "video/${getExtension(fileName)}"
        isAudio(fileName) -> "audio/${getExtension(fileName)}"
        fileName.endsWith(".pdf") -> "application/pdf"
        else -> "application/octet-stream"
    }
}
