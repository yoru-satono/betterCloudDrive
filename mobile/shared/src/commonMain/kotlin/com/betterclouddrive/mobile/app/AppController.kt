package com.betterclouddrive.mobile.app

import com.betterclouddrive.mobile.core.model.ApiException
import com.betterclouddrive.mobile.core.model.FileItem
import com.betterclouddrive.mobile.core.model.OfficeConfig
import com.betterclouddrive.mobile.core.model.PageResult
import com.betterclouddrive.mobile.core.model.SessionInvalidationReason
import com.betterclouddrive.mobile.core.model.SessionRestoreResult
import com.betterclouddrive.mobile.core.model.UserProfile
import com.betterclouddrive.mobile.core.localization.MobileString
import com.betterclouddrive.mobile.core.localization.UiMessage
import com.betterclouddrive.mobile.core.platform.PickedDocument
import com.betterclouddrive.mobile.feature.auth.AuthRepository
import com.betterclouddrive.mobile.feature.files.BreadcrumbEntry
import com.betterclouddrive.mobile.feature.files.FileRepository
import com.betterclouddrive.mobile.feature.files.FileViewMode
import com.betterclouddrive.mobile.feature.files.PersonalFileSort
import com.betterclouddrive.mobile.feature.files.SortDirection
import com.betterclouddrive.mobile.feature.transfers.TransferManager
import com.betterclouddrive.mobile.feature.transfers.TransferTask
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal interface AppAuthOperations {
    val serverBaseUrl: String
    val sessionInvalidations: kotlinx.coroutines.flow.Flow<SessionInvalidationReason>
    suspend fun restore(): SessionRestoreResult
    suspend fun switchServer(value: String): Result<String>
    fun normalizeServer(value: String): Result<String>
    suspend fun login(username: String, password: String): UserProfile
    suspend fun logout()
}

internal interface AppFileOperations {
    suspend fun listPersonalFiles(
        parentId: Long?, page: Int, size: Int, sortBy: String, order: String,
    ): PageResult<FileItem>
    suspend fun getPersonalFile(fileId: Long): FileItem
    suspend fun createPersonalFolder(parentId: Long?, folderName: String): FileItem
    suspend fun renamePersonalFile(fileId: Long, newName: String): FileItem
    suspend fun movePersonalFile(fileId: Long, targetParentId: Long?)
    suspend fun copyPersonalFile(fileId: Long, targetParentId: Long?)
    suspend fun deletePersonalFiles(fileIds: List<Long>)
    suspend fun loadTeamOffice(teamId: Long, fileId: Long): OfficeConfig
}

internal interface AppTransferOperations {
    val tasks: kotlinx.coroutines.flow.Flow<List<TransferTask>>
    suspend fun enqueueUpload(document: PickedDocument)
    suspend fun enqueueDownload(
        fileId: Long,
        fileName: String,
        fileSize: Long,
        mimeType: String?,
        destinationHandle: String,
        expectedMd5: String?,
    )
    suspend fun pause(taskId: String)
    suspend fun resume(taskId: String)
    suspend fun cancel(taskId: String)
    suspend fun retry(taskId: String)
    suspend fun resumeWaitingForLogin()
    suspend fun clearForLogout()
}

private class RepositoryAuthOperations(private val repository: AuthRepository) : AppAuthOperations {
    override val serverBaseUrl: String get() = repository.serverBaseUrl
    override val sessionInvalidations = repository.sessionInvalidations
    override suspend fun restore() = repository.restore()
    override suspend fun switchServer(value: String) = repository.switchServer(value)
    override fun normalizeServer(value: String) = repository.normalizeServer(value)
    override suspend fun login(username: String, password: String) = repository.login(username, password)
    override suspend fun logout() = repository.logout()
}

private class RepositoryFileOperations(private val repository: FileRepository) : AppFileOperations {
    override suspend fun listPersonalFiles(
        parentId: Long?, page: Int, size: Int, sortBy: String, order: String,
    ) = repository.listPersonalFiles(parentId, page, size, sortBy, order)
    override suspend fun getPersonalFile(fileId: Long) = repository.getPersonalFile(fileId)
    override suspend fun createPersonalFolder(parentId: Long?, folderName: String) =
        repository.createPersonalFolder(parentId, folderName)
    override suspend fun renamePersonalFile(fileId: Long, newName: String) =
        repository.renamePersonalFile(fileId, newName)
    override suspend fun movePersonalFile(fileId: Long, targetParentId: Long?) =
        repository.movePersonalFile(fileId, targetParentId)
    override suspend fun copyPersonalFile(fileId: Long, targetParentId: Long?) =
        repository.copyPersonalFile(fileId, targetParentId)
    override suspend fun deletePersonalFiles(fileIds: List<Long>) = repository.deletePersonalFiles(fileIds)
    override suspend fun loadTeamOffice(teamId: Long, fileId: Long) = repository.loadTeamOffice(teamId, fileId)
}

private class ManagerTransferOperations(private val manager: TransferManager) : AppTransferOperations {
    override val tasks = manager.tasks
    override suspend fun enqueueUpload(document: PickedDocument) { manager.enqueueUpload(document) }
    override suspend fun enqueueDownload(
        fileId: Long,
        fileName: String,
        fileSize: Long,
        mimeType: String?,
        destinationHandle: String,
        expectedMd5: String?,
    ) {
        manager.enqueueDownload(fileId, fileName, fileSize, mimeType, destinationHandle, expectedMd5 = expectedMd5)
    }
    override suspend fun pause(taskId: String) = manager.pause(taskId)
    override suspend fun resume(taskId: String) = manager.resume(taskId)
    override suspend fun cancel(taskId: String) = manager.cancel(taskId)
    override suspend fun retry(taskId: String) = manager.retry(taskId)
    override suspend fun resumeWaitingForLogin() = manager.resumeWaitingForLogin()
    override suspend fun clearForLogout() = manager.clearForLogout()
}

enum class AppPhase { LOADING, RESTORE_FAILED, LOGGED_OUT, LOGGED_IN }

data class DirectoryPageState(
    val folderId: Long? = null,
    val items: List<FileItem> = emptyList(),
    val hasLoaded: Boolean = false,
    val initialLoading: Boolean = false,
    val refreshing: Boolean = false,
    val loadingMore: Boolean = false,
    val error: UiMessage? = null,
    val loadMoreError: UiMessage? = null,
    val page: Int = 0,
    val pages: Int = 1,
    val total: Long = 0,
    val scrollAnchorId: Long? = null,
) {
    val hasMore: Boolean get() = hasLoaded && page < pages
}

data class PersonalFilesUiState(
    val breadcrumb: List<BreadcrumbEntry> = listOf(BreadcrumbEntry(null, "")),
    val current: DirectoryPageState = DirectoryPageState(),
    val sort: PersonalFileSort = PersonalFileSort.NAME,
    val direction: SortDirection = SortDirection.ASCENDING,
    val viewMode: FileViewMode = FileViewMode.LIST,
    val selectedFile: FileItem? = null,
    val selectedIds: Set<Long> = emptySet(),
    val mutation: FileMutationKind? = null,
    val mutationError: UiMessage? = null,
    val mutationErrorKind: FileMutationKind? = null,
    val feedback: FileMutationFeedback? = null,
    val folderPicker: FolderPickerState? = null,
)

enum class FileMutationKind { CREATE_FOLDER, RENAME, MOVE, COPY, DELETE }

data class FileMutationFeedback(
    val id: Long,
    val message: UiMessage,
    val isError: Boolean,
)

data class FolderPickerState(
    val kind: FileMutationKind,
    val source: FileItem,
    val breadcrumb: List<BreadcrumbEntry> = listOf(BreadcrumbEntry(null, "")),
    val currentFolderId: Long? = null,
    val folders: List<FileItem> = emptyList(),
    val loading: Boolean = false,
    val error: UiMessage? = null,
) {
    val canConfirm: Boolean get() = kind == FileMutationKind.COPY || currentFolderId != source.parentId
}

data class DiagnosticUiState(
    val office: OfficeConfig? = null,
    val status: UiMessage = UiMessage(MobileString.OfficeReady),
)

data class AppUiState(
    val phase: AppPhase = AppPhase.LOADING,
    val server: String = com.betterclouddrive.mobile.core.network.BackendClient.DEFAULT_SERVER,
    val user: UserProfile? = null,
    val loginBusy: Boolean = false,
    val sessionError: UiMessage? = null,
    val personalFiles: PersonalFilesUiState = PersonalFilesUiState(),
    val transfers: List<TransferTask> = emptyList(),
    val diagnostics: DiagnosticUiState = DiagnosticUiState(),
)

class AppController internal constructor(
    private val auth: AppAuthOperations,
    private val files: AppFileOperations,
    private val transfers: AppTransferOperations,
    private val scope: CoroutineScope,
) {
    constructor(
        auth: AuthRepository,
        files: FileRepository,
        transfers: TransferManager,
        scope: CoroutineScope,
    ) : this(
        RepositoryAuthOperations(auth), RepositoryFileOperations(files),
        ManagerTransferOperations(transfers), scope,
    )

    private val mutableState = MutableStateFlow(AppUiState())
    val state: StateFlow<AppUiState> = mutableState.asStateFlow()
    private val directoryCache = mutableMapOf<Long?, DirectoryPageState>()
    private var directoryJob: Job? = null
    private var detailJob: Job? = null
    private var mutationJob: Job? = null
    private var pickerJob: Job? = null
    private var feedbackSequence = 0L
    private var started = false

    init {
        scope.launch {
            transfers.tasks.collectLatest { tasks -> mutableState.update { it.copy(transfers = tasks) } }
        }
        scope.launch {
            auth.sessionInvalidations.collectLatest { reason ->
                if (reason in setOf(
                        SessionInvalidationReason.EXPIRED,
                        SessionInvalidationReason.REVOKED,
                        SessionInvalidationReason.CORRUPT_STORAGE,
                    )
                ) {
                    resetDirectoryState()
                    mutableState.update {
                        AppUiState(
                            phase = AppPhase.LOGGED_OUT,
                            server = auth.serverBaseUrl,
                            transfers = it.transfers,
                            sessionError = UiMessage(MobileString.ErrorSessionExpired),
                        )
                    }
                }
            }
        }
    }

    fun start() {
        if (started) return
        started = true
        scope.launch { restoreInternal() }
    }

    fun retryRestore() = scope.launch { restoreInternal() }

    fun discardSession() = scope.launch {
        mutableState.update { it.copy(loginBusy = true, sessionError = null) }
        auth.logout()
        transfers.clearForLogout()
        resetDirectoryState()
        mutableState.update {
            AppUiState(phase = AppPhase.LOGGED_OUT, server = auth.serverBaseUrl, transfers = emptyList())
        }
    }

    fun login(server: String, username: String, password: String) = scope.launch {
        runSessionAction {
            val normalized = auth.normalizeServer(server).getOrThrow()
            if (normalized != auth.serverBaseUrl) {
                transfers.clearForLogout()
                auth.switchServer(normalized).getOrThrow()
            }
            val user = auth.login(username, password)
            resetDirectoryState()
            mutableState.update {
                it.copy(
                    phase = AppPhase.LOGGED_IN,
                    user = user,
                    server = auth.serverBaseUrl,
                    personalFiles = PersonalFilesUiState(),
                )
            }
            transfers.resumeWaitingForLogin()
            loadDirectory(null, refresh = false)
        }
    }

    fun activatePersonalRoute(folderId: Long?) {
        val personal = mutableState.value.personalFiles
        if (personal.current.folderId == folderId &&
            (personal.current.hasLoaded || personal.current.initialLoading)
        ) return

        directoryJob?.cancel()
        if (folderId == null) {
            val path = listOf(BreadcrumbEntry(null, ""))
            retainPathCache(path)
            showDirectory(null, path)
            loadIfNeeded(null)
            return
        }

        val existingIndex = personal.breadcrumb.indexOfFirst { it.id == folderId }
        if (existingIndex >= 0) {
            val path = personal.breadcrumb.take(existingIndex + 1)
            retainPathCache(path)
            showDirectory(folderId, path)
            loadIfNeeded(folderId)
            return
        }

        showDirectory(
            folderId,
            listOf(BreadcrumbEntry(null, ""), BreadcrumbEntry(folderId, "")),
            DirectoryPageState(folderId = folderId, initialLoading = true),
        )
        directoryJob = scope.launch {
            try {
                val path = rebuildPath(folderId)
                if (mutableState.value.personalFiles.current.folderId != folderId) return@launch
                retainPathCache(path)
                mutableState.update {
                    it.copy(personalFiles = it.personalFiles.copy(breadcrumb = path, selectedFile = null))
                }
                loadDirectoryPage(folderId, page = 1, refresh = false)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                updateCurrent(folderId) { it.copy(initialLoading = false, error = friendly(error)) }
            }
        }
    }

    fun openPersonalFolder(folder: FileItem) {
        if (!folder.isFolder) return
        val path = mutableState.value.personalFiles.breadcrumb + BreadcrumbEntry(folder.id, folder.fileName)
        retainPathCache(path)
        showDirectory(folder.id, path)
        loadIfNeeded(folder.id)
    }

    fun refreshFiles() {
        clearSelection()
        directoryJob?.cancel()
        directoryJob = scope.launch {
            loadDirectoryPage(mutableState.value.personalFiles.current.folderId, page = 1, refresh = true)
        }
    }

    fun loadMoreFiles() {
        val current = mutableState.value.personalFiles.current
        if (!current.hasMore || current.loadingMore || current.initialLoading || current.refreshing) return
        directoryJob?.cancel()
        directoryJob = scope.launch {
            loadDirectoryPage(current.folderId, page = current.page + 1, refresh = false)
        }
    }

    fun changeFileSort(sort: PersonalFileSort, direction: SortDirection) {
        val previous = mutableState.value.personalFiles
        if (previous.sort == sort && previous.direction == direction) return
        val previousCache = directoryCache.toMap()
        val folderId = previous.current.folderId
        directoryJob?.cancel()
        mutableState.update {
            it.copy(
                personalFiles = previous.copy(
                    sort = sort,
                    direction = direction,
                    current = previous.current.copy(refreshing = previous.current.hasLoaded, error = null),
                    selectedFile = null,
                    selectedIds = emptySet(),
                ),
            )
        }
        directoryJob = scope.launch {
            try {
                val result = files.listPersonalFiles(folderId, 1, PAGE_SIZE, sort.apiValue, direction.apiValue)
                val latest = mutableState.value.personalFiles
                if (latest.current.folderId != folderId || latest.sort != sort || latest.direction != direction) return@launch
                directoryCache.clear()
                val snapshot = result.toSnapshot(folderId)
                directoryCache[folderId] = snapshot
                mutableState.update {
                    it.copy(personalFiles = it.personalFiles.copy(current = snapshot, selectedFile = null))
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                directoryCache.clear()
                directoryCache.putAll(previousCache)
                mutableState.update {
                    it.copy(
                        personalFiles = previous.copy(
                            current = previous.current.copy(error = friendly(error)),
                        ),
                    )
                }
            }
        }
    }

    fun changeFileViewMode(mode: FileViewMode) {
        mutableState.update { it.copy(personalFiles = it.personalFiles.copy(viewMode = mode)) }
    }

    fun togglePersonalSelection(fileId: Long) {
        val personal = mutableState.value.personalFiles
        if (personal.current.items.none { it.id == fileId }) return
        val updated = personal.selectedIds.toMutableSet().apply {
            if (!add(fileId)) remove(fileId)
        }
        mutableState.update { it.copy(personalFiles = it.personalFiles.copy(selectedIds = updated, selectedFile = null)) }
    }

    fun clearSelection() {
        mutableState.update { it.copy(personalFiles = it.personalFiles.copy(selectedIds = emptySet())) }
    }

    fun clearPersonalTransientUi() {
        pickerJob?.cancel()
        mutableState.update {
            it.copy(personalFiles = it.personalFiles.copy(
                selectedIds = emptySet(), mutationError = null, mutationErrorKind = null, folderPicker = null,
            ))
        }
    }

    fun createPersonalFolder(name: String) = launchMutation(FileMutationKind.CREATE_FOLDER) {
        files.createPersonalFolder(mutableState.value.personalFiles.current.folderId, name)
        MobileString.FolderCreated
    }

    fun renamePersonalFile(file: FileItem, name: String) = launchMutation(FileMutationKind.RENAME) {
        files.renamePersonalFile(file.id, name)
        MobileString.FileRenamed
    }

    fun deletePersonalFiles(fileIds: Set<Long>) {
        if (fileIds.isEmpty()) return
        launchMutation(FileMutationKind.DELETE) {
            files.deletePersonalFiles(fileIds.toList())
            MobileString.FilesMovedToTrash
        }
    }

    fun openFolderPicker(file: FileItem, kind: FileMutationKind) {
        if (kind != FileMutationKind.MOVE && kind != FileMutationKind.COPY) return
        pickerJob?.cancel()
        mutableState.update {
            it.copy(personalFiles = it.personalFiles.copy(
                mutationError = null,
                mutationErrorKind = null,
                folderPicker = FolderPickerState(kind = kind, source = file, loading = true),
            ))
        }
        loadPickerFolder(null, listOf(BreadcrumbEntry(null, "")))
    }

    fun openPickerFolder(folder: FileItem) {
        if (!folder.isFolder) return
        val picker = mutableState.value.personalFiles.folderPicker ?: return
        loadPickerFolder(folder.id, picker.breadcrumb + BreadcrumbEntry(folder.id, folder.fileName))
    }

    fun pickerBack() {
        val picker = mutableState.value.personalFiles.folderPicker ?: return
        if (picker.breadcrumb.size <= 1) return
        val path = picker.breadcrumb.dropLast(1)
        loadPickerFolder(path.last().id, path)
    }

    fun retryFolderPicker() {
        val picker = mutableState.value.personalFiles.folderPicker ?: return
        loadPickerFolder(picker.currentFolderId, picker.breadcrumb)
    }

    fun closeFolderPicker() {
        pickerJob?.cancel()
        mutableState.update { it.copy(personalFiles = it.personalFiles.copy(
            folderPicker = null, mutationError = null, mutationErrorKind = null,
        )) }
    }

    fun confirmFolderPicker() {
        val picker = mutableState.value.personalFiles.folderPicker ?: return
        if (!picker.canConfirm) return
        val targetId = picker.currentFolderId
        launchMutation(picker.kind) {
            when (picker.kind) {
                FileMutationKind.MOVE -> files.movePersonalFile(picker.source.id, targetId)
                FileMutationKind.COPY -> files.copyPersonalFile(picker.source.id, targetId)
                else -> error("unsupported file destination operation")
            }
            if (targetId != mutableState.value.personalFiles.current.folderId) directoryCache.remove(targetId)
            if (picker.kind == FileMutationKind.MOVE) MobileString.FileMoved else MobileString.FileCopied
        }
    }

    fun consumeFileFeedback() {
        mutableState.update { it.copy(personalFiles = it.personalFiles.copy(feedback = null)) }
    }

    fun selectPersonalFile(file: FileItem?) {
        detailJob?.cancel()
        mutableState.update { it.copy(personalFiles = it.personalFiles.copy(selectedFile = file)) }
        if (file == null || file.isFolder) return
        detailJob = scope.launch {
            try {
                val metadata = files.getPersonalFile(file.id)
                mutableState.update {
                    if (it.personalFiles.selectedFile?.id == file.id) {
                        it.copy(personalFiles = it.personalFiles.copy(selectedFile = metadata))
                    } else {
                        it
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                // 列表响应已经包含可展示的基础元数据；详情刷新失败不打断目录浏览。
            }
        }
    }

    fun updatePersonalScrollAnchor(fileId: Long?) {
        val folderId = mutableState.value.personalFiles.current.folderId
        updateCurrent(folderId) { it.copy(scrollAnchorId = fileId) }
    }

    fun logout() = scope.launch {
        mutableState.update { it.copy(loginBusy = true, sessionError = null) }
        auth.logout()
        transfers.clearForLogout()
        resetDirectoryState()
        mutableState.update {
            AppUiState(phase = AppPhase.LOGGED_OUT, server = auth.serverBaseUrl, transfers = emptyList())
        }
    }

    fun upload(document: PickedDocument) = scope.launch {
        runPersonalAction { transfers.enqueueUpload(document) }
    }

    fun download(file: FileItem, destinationHandle: String) = scope.launch {
        runPersonalAction {
            transfers.enqueueDownload(
                file.id, file.fileName, file.fileSize, file.mimeType, destinationHandle,
                expectedMd5 = file.md5Hash,
            )
        }
    }

    fun pause(taskId: String) = scope.launch { transfers.pause(taskId) }
    fun resume(taskId: String) = scope.launch { transfers.resume(taskId) }
    fun cancel(taskId: String) = scope.launch { transfers.cancel(taskId) }
    fun retry(taskId: String) = scope.launch { transfers.retry(taskId) }

    fun loadDiagnosticOffice(teamId: Long, fileId: Long) = scope.launch {
        mutableState.update { it.copy(diagnostics = it.diagnostics.copy(status = UiMessage(MobileString.OfficeConfigLoading))) }
        runCatching { files.loadTeamOffice(teamId, fileId) }
            .onSuccess { config ->
                mutableState.update {
                    it.copy(diagnostics = DiagnosticUiState(config, UiMessage(MobileString.OfficeConfigLoaded)))
                }
            }
            .onFailure { error ->
                if (error is CancellationException) throw error
                mutableState.update { it.copy(diagnostics = it.diagnostics.copy(status = friendly(error))) }
            }
    }

    fun updateDiagnosticStatus(value: UiMessage) {
        mutableState.update { it.copy(diagnostics = it.diagnostics.copy(status = value)) }
    }

    private fun loadIfNeeded(folderId: Long?) {
        val current = mutableState.value.personalFiles.current
        if (current.hasLoaded || current.initialLoading) return
        loadDirectory(folderId, refresh = false)
    }

    private fun loadDirectory(folderId: Long?, refresh: Boolean) {
        directoryJob?.cancel()
        directoryJob = scope.launch { loadDirectoryPage(folderId, page = 1, refresh = refresh) }
    }

    private suspend fun loadDirectoryPage(folderId: Long?, page: Int, refresh: Boolean) {
        val personal = mutableState.value.personalFiles
        if (personal.current.folderId != folderId) return
        val existing = personal.current
        updateCurrent(folderId) {
            when {
                page > 1 -> it.copy(loadingMore = true, loadMoreError = null)
                refresh && it.hasLoaded -> it.copy(refreshing = true, error = null)
                else -> it.copy(initialLoading = true, error = null)
            }
        }
        try {
            val result = files.listPersonalFiles(
                folderId, page, PAGE_SIZE, personal.sort.apiValue, personal.direction.apiValue,
            )
            val latest = mutableState.value.personalFiles
            if (latest.current.folderId != folderId || latest.sort != personal.sort || latest.direction != personal.direction) return
            val merged = if (page == 1) {
                result.records.distinctBy(FileItem::id)
            } else {
                (existing.items + result.records).distinctBy(FileItem::id)
            }
            val snapshot = DirectoryPageState(
                folderId = folderId,
                items = merged,
                hasLoaded = true,
                page = result.page,
                pages = result.pages.coerceAtLeast(result.page),
                total = result.total,
                scrollAnchorId = if (page == 1) null else existing.scrollAnchorId,
            )
            directoryCache[folderId] = snapshot
            mutableState.update { it.copy(personalFiles = it.personalFiles.copy(current = snapshot)) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            updateCurrent(folderId) {
                when {
                    page > 1 -> it.copy(loadingMore = false, loadMoreError = friendly(error))
                    refresh && it.hasLoaded -> it.copy(refreshing = false, error = friendly(error))
                    else -> it.copy(initialLoading = false, error = friendly(error))
                }
            }
        }
    }

    private suspend fun rebuildPath(folderId: Long): List<BreadcrumbEntry> {
        val seen = mutableSetOf<Long>()
        val reversed = mutableListOf<BreadcrumbEntry>()
        var currentId: Long? = folderId
        while (currentId != null) {
            if (!seen.add(currentId) || seen.size > MAX_BREADCRUMB_DEPTH) {
                throw IllegalArgumentException("invalid folder path")
            }
            val item = files.getPersonalFile(currentId)
            if (!item.isFolder) throw IllegalArgumentException("target is not a folder")
            reversed += BreadcrumbEntry(item.id, item.fileName)
            currentId = item.parentId
        }
        return listOf(BreadcrumbEntry(null, "")) + reversed.asReversed()
    }

    private fun showDirectory(
        folderId: Long?,
        path: List<BreadcrumbEntry>,
        fallback: DirectoryPageState = DirectoryPageState(folderId = folderId),
    ) {
        val snapshot = directoryCache[folderId] ?: fallback
        mutableState.update {
            it.copy(
                personalFiles = it.personalFiles.copy(
                    breadcrumb = path,
                    current = snapshot,
                    selectedFile = null,
                    selectedIds = emptySet(),
                    mutationError = null,
                    mutationErrorKind = null,
                    folderPicker = null,
                ),
            )
        }
    }

    private fun updateCurrent(folderId: Long?, transform: (DirectoryPageState) -> DirectoryPageState) {
        val personal = mutableState.value.personalFiles
        if (personal.current.folderId != folderId) return
        val updated = transform(personal.current)
        directoryCache[folderId] = updated
        mutableState.update { it.copy(personalFiles = it.personalFiles.copy(current = updated)) }
    }

    private fun retainPathCache(path: List<BreadcrumbEntry>) {
        directoryCache.keys.retainAll(path.map(BreadcrumbEntry::id).toSet())
    }

    private fun resetDirectoryState() {
        directoryJob?.cancel()
        directoryJob = null
        detailJob?.cancel()
        detailJob = null
        mutationJob?.cancel()
        mutationJob = null
        pickerJob?.cancel()
        pickerJob = null
        directoryCache.clear()
    }

    private suspend fun runSessionAction(block: suspend () -> Unit) {
        mutableState.update { it.copy(loginBusy = true, sessionError = null) }
        try {
            block()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            mutableState.update { it.copy(sessionError = friendly(error)) }
        } finally {
            mutableState.update { it.copy(loginBusy = false) }
        }
    }

    private suspend fun runPersonalAction(block: suspend () -> Unit) {
        val folderId = mutableState.value.personalFiles.current.folderId
        updateCurrent(folderId) { it.copy(error = null) }
        try {
            block()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            updateCurrent(folderId) { it.copy(error = friendly(error)) }
        }
    }

    private suspend fun restoreInternal() {
        resetDirectoryState()
        mutableState.update {
            it.copy(
                phase = AppPhase.LOADING,
                loginBusy = false,
                sessionError = null,
                personalFiles = PersonalFilesUiState(),
            )
        }
        when (val result = auth.restore()) {
            SessionRestoreResult.NoSession,
            is SessionRestoreResult.Invalidated -> mutableState.update {
                AppUiState(phase = AppPhase.LOGGED_OUT, server = auth.serverBaseUrl, transfers = it.transfers)
            }
            is SessionRestoreResult.RetryableFailure -> mutableState.update {
                it.copy(
                    phase = AppPhase.RESTORE_FAILED,
                    server = auth.serverBaseUrl,
                    sessionError = UiMessage(MobileString.ErrorSessionPreserved),
                )
            }
            is SessionRestoreResult.Restored -> {
                mutableState.update {
                    it.copy(
                        phase = AppPhase.LOGGED_IN,
                        user = result.user,
                        server = auth.serverBaseUrl,
                        personalFiles = PersonalFilesUiState(),
                    )
                }
                loadDirectory(null, refresh = false)
            }
        }
    }

    private fun friendly(error: Throwable): UiMessage = when (error) {
        is ApiException -> when (error.messageKey) {
            "error.unauthorized" -> UiMessage(MobileString.ErrorCredentials)
            "error.storageQuotaExceeded" -> UiMessage(MobileString.ErrorQuota)
            "error.fileNameExists", "error.fileNameConflict" -> UiMessage(MobileString.ErrorNameConflict)
            "error.invalidFileDestination" -> UiMessage(MobileString.ErrorInvalidDestination)
            "error.invalidFileName", "error.validation", "error.invalidParameter" -> UiMessage(MobileString.ErrorInvalidFileName)
            "error.fileNotFound", "error.notFound" -> UiMessage(MobileString.ErrorNotFound)
            else -> if (error.requestId.isNullOrBlank()) {
                UiMessage(MobileString.ErrorRequest)
            } else {
                UiMessage(MobileString.ErrorRequestWithId, listOf(error.requestId))
            }
        }
        is IllegalArgumentException -> when (error.message) {
            "server.empty" -> UiMessage(MobileString.ErrorServerEmpty)
            "server.scheme" -> UiMessage(MobileString.ErrorServerScheme)
            "server.httpsRequired" -> UiMessage(MobileString.ErrorServerHttps)
            "server.hostMissing" -> UiMessage(MobileString.ErrorServerHost)
            else -> UiMessage(MobileString.ErrorInvalidInput)
        }
        else -> UiMessage(MobileString.ErrorNetwork)
    }

    private fun PageResult<FileItem>.toSnapshot(folderId: Long?) = DirectoryPageState(
        folderId = folderId,
        items = records.distinctBy(FileItem::id),
        hasLoaded = true,
        page = page,
        pages = pages.coerceAtLeast(page),
        total = total,
    )

    private fun launchMutation(
        kind: FileMutationKind,
        block: suspend () -> MobileString,
    ) {
        if (mutableState.value.personalFiles.mutation != null) return
        mutationJob = scope.launch {
            mutableState.update {
                it.copy(personalFiles = it.personalFiles.copy(
                    mutation = kind, mutationError = null, mutationErrorKind = null,
                ))
            }
            try {
                val success = block()
                val refreshError = try {
                    reloadCurrentLoadedPages()
                    null
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Throwable) {
                    friendly(error)
                }
                mutableState.update {
                    it.copy(personalFiles = it.personalFiles.copy(
                        mutation = null,
                        mutationError = null,
                        mutationErrorKind = null,
                        selectedIds = emptySet(),
                        selectedFile = null,
                        folderPicker = null,
                        feedback = FileMutationFeedback(++feedbackSequence, UiMessage(success), false),
                        current = if (refreshError == null) it.personalFiles.current
                        else it.personalFiles.current.copy(error = refreshError),
                    ))
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                val message = friendly(error)
                mutableState.update {
                    it.copy(personalFiles = it.personalFiles.copy(
                        mutation = null,
                        mutationError = message,
                        mutationErrorKind = kind,
                        feedback = FileMutationFeedback(++feedbackSequence, message, true),
                    ))
                }
            }
        }
    }

    private suspend fun reloadCurrentLoadedPages() {
        val personal = mutableState.value.personalFiles
        val folderId = personal.current.folderId
        val pageCount = personal.current.page.coerceAtLeast(1)
        val accumulated = mutableListOf<FileItem>()
        var last: PageResult<FileItem>? = null
        for (page in 1..pageCount) {
            last = files.listPersonalFiles(folderId, page, PAGE_SIZE, personal.sort.apiValue, personal.direction.apiValue)
            accumulated += last.records
            if (page >= last.pages) break
        }
        val result = checkNotNull(last)
        val snapshot = DirectoryPageState(
            folderId = folderId,
            items = accumulated.distinctBy(FileItem::id),
            hasLoaded = true,
            page = minOf(pageCount, result.pages.coerceAtLeast(1)),
            pages = result.pages.coerceAtLeast(1),
            total = result.total,
            scrollAnchorId = personal.current.scrollAnchorId,
        )
        directoryCache[folderId] = snapshot
        mutableState.update { it.copy(personalFiles = it.personalFiles.copy(current = snapshot)) }
    }

    private fun loadPickerFolder(folderId: Long?, breadcrumb: List<BreadcrumbEntry>) {
        pickerJob?.cancel()
        pickerJob = scope.launch {
            val original = mutableState.value.personalFiles.folderPicker ?: return@launch
            mutableState.update {
                it.copy(personalFiles = it.personalFiles.copy(folderPicker = original.copy(
                    breadcrumb = breadcrumb, currentFolderId = folderId, loading = true, error = null, folders = emptyList(),
                )))
            }
            try {
                val folders = mutableListOf<FileItem>()
                var page = 1
                while (true) {
                    val result = files.listPersonalFiles(folderId, page, PICKER_PAGE_SIZE, "fileName", "asc")
                    val firstFile = result.records.indexOfFirst { !it.isFolder }
                    val folderRecords = if (firstFile >= 0) result.records.take(firstFile) else result.records
                    folders += folderRecords.filterNot { it.id == original.source.id }
                    if (firstFile >= 0 || page >= result.pages) break
                    page++
                }
                mutableState.update {
                    val current = it.personalFiles.folderPicker
                    if (current?.source?.id != original.source.id || current.currentFolderId != folderId) it
                    else it.copy(personalFiles = it.personalFiles.copy(folderPicker = current.copy(
                        folders = folders.distinctBy(FileItem::id), loading = false,
                    )))
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                mutableState.update {
                    val current = it.personalFiles.folderPicker
                    if (current?.source?.id != original.source.id || current.currentFolderId != folderId) it
                    else it.copy(personalFiles = it.personalFiles.copy(folderPicker = current.copy(
                        loading = false, error = friendly(error),
                    )))
                }
            }
        }
    }

    private companion object {
        const val PAGE_SIZE = 50
        const val PICKER_PAGE_SIZE = 100
        const val MAX_BREADCRUMB_DEPTH = 128
    }
}
