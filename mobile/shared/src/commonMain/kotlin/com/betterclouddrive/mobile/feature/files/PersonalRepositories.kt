package com.betterclouddrive.mobile.feature.files

import com.betterclouddrive.mobile.core.model.FileItem
import com.betterclouddrive.mobile.core.model.OfficeConfig
import com.betterclouddrive.mobile.core.model.PageResult
import com.betterclouddrive.mobile.core.model.PreviewKind
import com.betterclouddrive.mobile.core.model.TextPreview
import com.betterclouddrive.mobile.core.model.PersonalTag
import com.betterclouddrive.mobile.core.model.CreatePersonalShareRequest
import com.betterclouddrive.mobile.core.model.ShareExpirationFilter
import com.betterclouddrive.mobile.core.model.ShareLink
import com.betterclouddrive.mobile.core.model.UpdatePersonalShareRequest
import com.betterclouddrive.mobile.core.network.PersonalFilesApi
import com.betterclouddrive.mobile.core.network.PersonalOrganizationApi
import com.betterclouddrive.mobile.core.network.PersonalSharesApi
import com.betterclouddrive.mobile.core.network.PreviewApi
import com.betterclouddrive.mobile.core.network.PersonalRecoveryApi
import com.betterclouddrive.mobile.core.model.RecycleBinItem
import com.betterclouddrive.mobile.core.model.PersonalFileVersion

interface PersonalFilesRepository {
    suspend fun listPersonalFiles(parentId: Long?, page: Int, size: Int, sortBy: String, order: String): PageResult<FileItem>
    suspend fun getPersonalFile(fileId: Long): FileItem
    suspend fun searchPersonalFiles(query: String, page: Int, size: Int): PageResult<FileItem>
    suspend fun createPersonalFolder(parentId: Long?, folderName: String): FileItem
    suspend fun renamePersonalFile(fileId: Long, newName: String): FileItem
    suspend fun movePersonalFile(fileId: Long, targetParentId: Long?)
    suspend fun copyPersonalFile(fileId: Long, targetParentId: Long?)
    suspend fun deletePersonalFiles(fileIds: List<Long>)
}

interface FavoritesRepository {
    suspend fun listPersonalFavorites(query: String?, page: Int, size: Int): PageResult<FileItem>
    suspend fun isPersonalFavorite(fileId: Long): Boolean
    suspend fun addPersonalFavorite(fileId: Long)
    suspend fun removePersonalFavorite(fileId: Long)
}

interface TagsRepository {
    suspend fun listPersonalTags(query: String? = null): List<PersonalTag>
    suspend fun createPersonalTag(name: String, color: String): PersonalTag
    suspend fun updatePersonalTag(tagId: Long, name: String, color: String): PersonalTag
    suspend fun deletePersonalTag(tagId: Long)
    suspend fun listPersonalFilesByTag(tagId: Long, query: String?, page: Int, size: Int): PageResult<FileItem>
    suspend fun listPersonalFileTags(fileId: Long): List<PersonalTag>
    suspend fun replacePersonalFileTags(fileId: Long, tagIds: List<Long>): List<PersonalTag>
    suspend fun removePersonalFileTag(tagId: Long, fileId: Long)
}

interface PersonalSharesRepository {
    suspend fun createPersonalShare(request: CreatePersonalShareRequest): ShareLink
    suspend fun listPersonalShares(
        query: String?, passwordProtected: Boolean?, expiration: ShareExpirationFilter, page: Int, size: Int,
    ): PageResult<ShareLink>
    suspend fun getPersonalShare(shareId: Long): ShareLink
    suspend fun getPersonalSharePassword(shareId: Long): String?
    suspend fun updatePersonalShare(shareId: Long, request: UpdatePersonalShareRequest): ShareLink
    suspend fun cancelPersonalShare(shareId: Long)
}

interface PreviewRepository {
    suspend fun getPersonalFile(fileId: Long): FileItem
    suspend fun createPersonalDownloadTicket(fileId: Long): String
    suspend fun loadPersonalText(file: FileItem): TextPreview
    suspend fun loadPersonalOffice(fileId: Long): OfficeConfig
    suspend fun loadTeamOffice(teamId: Long, fileId: Long): OfficeConfig
}

interface RecycleBinRepository {
    suspend fun list(query: String?, page: Int, size: Int): PageResult<RecycleBinItem>
    suspend fun restore(fileId: Long)
    suspend fun permanentlyDelete(fileId: Long)
    suspend fun empty()
}

interface PersonalVersionsRepository {
    suspend fun file(fileId: Long): FileItem
    suspend fun list(fileId: Long): List<PersonalFileVersion>
    suspend fun restore(fileId: Long, versionNumber: Int): FileItem
    suspend fun delete(fileId: Long, versionNumber: Int)
}

class RecycleBinRepositoryImpl(private val api: PersonalRecoveryApi) : RecycleBinRepository {
    override suspend fun list(query: String?, page: Int, size: Int) = api.listRecycleBin(query, page, size)
    override suspend fun restore(fileId: Long) = api.restoreRecycleItem(fileId)
    override suspend fun permanentlyDelete(fileId: Long) = api.permanentlyDeleteRecycleItem(fileId)
    override suspend fun empty() = api.emptyRecycleBin()
}

class PersonalVersionsRepositoryImpl(
    private val files: PersonalFilesApi,
    private val api: PersonalRecoveryApi,
) : PersonalVersionsRepository {
    override suspend fun file(fileId: Long) = files.get(fileId)
    override suspend fun list(fileId: Long) = api.listVersions(fileId)
    override suspend fun restore(fileId: Long, versionNumber: Int) = api.restoreVersion(fileId, versionNumber)
    override suspend fun delete(fileId: Long, versionNumber: Int) = api.deleteVersion(fileId, versionNumber)
}

class PersonalFilesRepositoryImpl(private val api: PersonalFilesApi) : PersonalFilesRepository {
    override suspend fun listPersonalFiles(
        parentId: Long?,
        page: Int,
        size: Int,
        sortBy: String,
        order: String,
    ): PageResult<FileItem> = api.list(parentId, page, size, sortBy, order)

    override suspend fun getPersonalFile(fileId: Long): FileItem = api.get(fileId)
    override suspend fun searchPersonalFiles(query: String, page: Int, size: Int): PageResult<FileItem> =
        api.search(query, page, size)
    override suspend fun createPersonalFolder(parentId: Long?, folderName: String): FileItem =
        api.createFolder(parentId, folderName)
    override suspend fun renamePersonalFile(fileId: Long, newName: String): FileItem =
        api.rename(fileId, newName)
    override suspend fun movePersonalFile(fileId: Long, targetParentId: Long?) =
        api.move(fileId, targetParentId)
    override suspend fun copyPersonalFile(fileId: Long, targetParentId: Long?) =
        api.copy(fileId, targetParentId)
    override suspend fun deletePersonalFiles(fileIds: List<Long>) = api.delete(fileIds)
}

class FavoritesRepositoryImpl(private val api: PersonalOrganizationApi) : FavoritesRepository {
    override suspend fun listPersonalFavorites(query: String?, page: Int, size: Int): PageResult<FileItem> =
        api.listFavorites(query, page, size)
    override suspend fun isPersonalFavorite(fileId: Long): Boolean = api.favoriteStatus(fileId)
    override suspend fun addPersonalFavorite(fileId: Long) = api.addFavorite(fileId)
    override suspend fun removePersonalFavorite(fileId: Long) = api.removeFavorite(fileId)
}

class TagsRepositoryImpl(private val api: PersonalOrganizationApi) : TagsRepository {
    override suspend fun listPersonalTags(query: String?): List<PersonalTag> = api.listTags(query)
    override suspend fun createPersonalTag(name: String, color: String): PersonalTag = api.createTag(name, color)
    override suspend fun updatePersonalTag(tagId: Long, name: String, color: String): PersonalTag =
        api.updateTag(tagId, name, color)
    override suspend fun deletePersonalTag(tagId: Long) = api.deleteTag(tagId)
    override suspend fun listPersonalFilesByTag(tagId: Long, query: String?, page: Int, size: Int): PageResult<FileItem> =
        api.filesByTag(tagId, query, page, size)
    override suspend fun listPersonalFileTags(fileId: Long): List<PersonalTag> = api.fileTags(fileId)
    override suspend fun replacePersonalFileTags(fileId: Long, tagIds: List<Long>): List<PersonalTag> =
        api.replaceFileTags(fileId, tagIds)
    override suspend fun removePersonalFileTag(tagId: Long, fileId: Long) = api.removeFileTag(tagId, fileId)
}

class PersonalSharesRepositoryImpl(private val api: PersonalSharesApi) : PersonalSharesRepository {
    override suspend fun createPersonalShare(request: CreatePersonalShareRequest): ShareLink =
        api.create(request)
    override suspend fun listPersonalShares(
        query: String?, passwordProtected: Boolean?, expiration: ShareExpirationFilter, page: Int, size: Int,
    ) = api.list(query, passwordProtected, expiration, page, size)
    override suspend fun getPersonalShare(shareId: Long) = api.get(shareId)
    override suspend fun getPersonalSharePassword(shareId: Long) = api.password(shareId)
    override suspend fun updatePersonalShare(shareId: Long, request: UpdatePersonalShareRequest) =
        api.update(shareId, request)
    override suspend fun cancelPersonalShare(shareId: Long) = api.cancel(shareId)
}

class PersonalPreviewRepositoryImpl(
    private val files: PersonalFilesApi,
    private val api: PreviewApi,
) : PreviewRepository {
    override suspend fun getPersonalFile(fileId: Long): FileItem = files.get(fileId)
    override suspend fun createPersonalDownloadTicket(fileId: Long): String = api.ticket(fileId)
    override suspend fun loadPersonalOffice(fileId: Long): OfficeConfig = api.personalOffice(fileId)
    override suspend fun loadPersonalText(file: FileItem): TextPreview {
        if (file.fileSize == 0L) return TextPreview("", false)
        val bytes = api.text(file.id, MAX_TEXT_PREVIEW_BYTES)
        return decodePersonalTextPreview(bytes, file.fileSize)
    }
    override suspend fun loadTeamOffice(teamId: Long, fileId: Long): OfficeConfig = api.teamOffice(teamId, fileId)

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
