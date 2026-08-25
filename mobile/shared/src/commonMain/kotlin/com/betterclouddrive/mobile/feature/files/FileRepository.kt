package com.betterclouddrive.mobile.feature.files

import com.betterclouddrive.mobile.core.model.FileItem
import com.betterclouddrive.mobile.core.model.OfficeConfig
import com.betterclouddrive.mobile.core.model.PageResult
import com.betterclouddrive.mobile.core.model.PreviewKind
import com.betterclouddrive.mobile.core.model.TextPreview
import com.betterclouddrive.mobile.core.network.BackendClient

class FileRepository(private val backend: BackendClient) {
    suspend fun listPersonalRoot(): List<FileItem> = backend.listRootFiles()
    suspend fun listPersonalFiles(
        parentId: Long?,
        page: Int,
        size: Int,
        sortBy: String,
        order: String,
    ): PageResult<FileItem> = backend.listPersonalFiles(parentId, page, size, sortBy, order)

    suspend fun getPersonalFile(fileId: Long): FileItem = backend.getPersonalFile(fileId)
    suspend fun searchPersonalFiles(query: String, page: Int, size: Int): PageResult<FileItem> =
        backend.searchPersonalFiles(query, page, size)
    suspend fun createPersonalDownloadTicket(fileId: Long): String = backend.createPersonalDownloadTicket(fileId)
    suspend fun loadPersonalOffice(fileId: Long): OfficeConfig = backend.loadPersonalOffice(fileId)
    suspend fun loadPersonalText(file: FileItem, maxBytes: Int = MAX_TEXT_PREVIEW_BYTES): TextPreview {
        if (file.fileSize == 0L) return TextPreview("", false)
        val bytes = backend.readPersonalText(file.id, maxBytes)
        return decodePersonalTextPreview(bytes, file.fileSize)
    }
    suspend fun createPersonalFolder(parentId: Long?, folderName: String): FileItem =
        backend.createPersonalFolder(parentId, folderName)
    suspend fun renamePersonalFile(fileId: Long, newName: String): FileItem =
        backend.renamePersonalFile(fileId, newName)
    suspend fun movePersonalFile(fileId: Long, targetParentId: Long?) =
        backend.movePersonalFile(fileId, targetParentId)
    suspend fun copyPersonalFile(fileId: Long, targetParentId: Long?) =
        backend.copyPersonalFile(fileId, targetParentId)
    suspend fun deletePersonalFiles(fileIds: List<Long>) = backend.deletePersonalFiles(fileIds)
    suspend fun loadTeamOffice(teamId: Long, fileId: Long): OfficeConfig = backend.loadTeamOffice(teamId, fileId)

    companion object {
        const val MAX_TEXT_PREVIEW_BYTES = 512 * 1024
    }
}

internal fun decodePersonalTextPreview(bytes: ByteArray, fileSize: Long): TextPreview {
    val start = if (
        bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()
    ) 3 else 0
    return TextPreview(
        content = bytes.decodeToString(startIndex = start, endIndex = bytes.size, throwOnInvalidSequence = false),
        truncated = fileSize > bytes.size,
    )
}

fun FileItem.previewKind(): PreviewKind {
    if (isFolder) return PreviewKind.UNSUPPORTED
    val extension = fileName.substringAfterLast('.', "").lowercase()
    val normalizedMime = mimeType?.substringBefore(';')?.trim()?.lowercase()
    return when {
        normalizedMime.isOfficeMime() || extension in OFFICE_EXTENSIONS -> PreviewKind.OFFICE
        normalizedMime?.startsWith("image/") == true || extension in IMAGE_EXTENSIONS -> PreviewKind.IMAGE
        normalizedMime?.startsWith("text/") == true || extension in TEXT_EXTENSIONS -> PreviewKind.TEXT
        normalizedMime?.startsWith("video/") == true || extension in VIDEO_EXTENSIONS -> PreviewKind.VIDEO
        else -> PreviewKind.UNSUPPORTED
    }
}

private fun String?.isOfficeMime(): Boolean = this != null && (
    this in OFFICE_MIME_TYPES ||
        startsWith("application/vnd.openxmlformats-officedocument.") ||
        startsWith("application/vnd.oasis.opendocument.")
    )

private val OFFICE_EXTENSIONS = setOf("doc", "docx", "odt", "rtf", "xls", "xlsx", "ods", "csv", "ppt", "pptx", "odp")
private val OFFICE_MIME_TYPES = setOf(
    "application/msword",
    "application/rtf",
    "application/vnd.ms-excel",
    "application/vnd.ms-powerpoint",
    "text/csv",
)
private val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "gif", "webp", "svg", "bmp", "heic", "heif")
private val TEXT_EXTENSIONS = setOf("txt", "md", "json", "xml", "html", "css", "js", "kt", "java", "py", "log", "yaml", "yml", "ini", "cfg", "sh", "bat")
private val VIDEO_EXTENSIONS = setOf("mp4", "mov", "avi", "mkv", "webm", "flv", "3gp")
