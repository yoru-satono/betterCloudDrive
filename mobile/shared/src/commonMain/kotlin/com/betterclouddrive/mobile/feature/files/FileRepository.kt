package com.betterclouddrive.mobile.feature.files

import com.betterclouddrive.mobile.core.model.FileItem
import com.betterclouddrive.mobile.core.model.OfficeConfig
import com.betterclouddrive.mobile.core.model.PageResult
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
}
