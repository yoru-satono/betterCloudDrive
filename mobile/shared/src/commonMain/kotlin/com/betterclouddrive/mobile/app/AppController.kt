package com.betterclouddrive.mobile.app

import com.betterclouddrive.mobile.core.model.ApiException
import com.betterclouddrive.mobile.core.model.FileItem
import com.betterclouddrive.mobile.core.model.OfficeConfig
import com.betterclouddrive.mobile.core.model.PageResult
import com.betterclouddrive.mobile.core.model.PreviewKind
import com.betterclouddrive.mobile.core.model.TextPreview
import com.betterclouddrive.mobile.core.model.SessionInvalidationReason
import com.betterclouddrive.mobile.core.model.SessionRestoreResult
import com.betterclouddrive.mobile.core.model.UserProfile
import com.betterclouddrive.mobile.core.localization.MobileString
import com.betterclouddrive.mobile.core.localization.MobileLocale
import com.betterclouddrive.mobile.core.localization.UiMessage
import com.betterclouddrive.mobile.core.model.RegistrationResult
import com.betterclouddrive.mobile.core.platform.ApplicationLocaleController
import com.betterclouddrive.mobile.core.platform.ThemeMode
import com.betterclouddrive.mobile.core.platform.ThemePreferenceStore
import com.betterclouddrive.mobile.core.platform.UploadPickResult
import com.betterclouddrive.mobile.feature.auth.AuthRepository
import com.betterclouddrive.mobile.feature.files.BreadcrumbEntry
import com.betterclouddrive.mobile.feature.files.FileRepository
import com.betterclouddrive.mobile.feature.files.FileViewMode
import com.betterclouddrive.mobile.feature.files.PersonalFileSort
import com.betterclouddrive.mobile.feature.files.SortDirection
import com.betterclouddrive.mobile.feature.files.previewKind
import com.betterclouddrive.mobile.feature.transfers.TransferManager
import com.betterclouddrive.mobile.feature.transfers.TransferDirection
import com.betterclouddrive.mobile.feature.transfers.TransferScope
import com.betterclouddrive.mobile.feature.transfers.TransferStatus
import com.betterclouddrive.mobile.feature.transfers.TransferTask
import com.betterclouddrive.mobile.feature.transfers.UploadEnqueueResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
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
    suspend fun sendRegistrationCode(email: String, preferredLocale: String) = Unit
    suspend fun register(
        username: String, password: String, email: String, verificationCode: String, preferredLocale: String,
    ): RegistrationResult = RegistrationResult(0, username)
    suspend fun forgotPassword(email: String) = Unit
    suspend fun resetPassword(email: String, code: String, newPassword: String) = Unit
    suspend fun getCurrentUser(): UserProfile = error("getCurrentUser is not implemented")
    suspend fun updatePreferredLocale(locale: String): UserProfile = error("updatePreferredLocale is not implemented")
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
    suspend fun searchPersonalFiles(query: String, page: Int, size: Int): PageResult<FileItem>
    suspend fun createPersonalDownloadTicket(fileId: Long): String
    suspend fun loadPersonalText(file: FileItem): TextPreview
    suspend fun loadPersonalOffice(fileId: Long): OfficeConfig
    suspend fun loadTeamOffice(teamId: Long, fileId: Long): OfficeConfig
}

internal interface AppTransferOperations {
    val tasks: kotlinx.coroutines.flow.Flow<List<TransferTask>>
    suspend fun enqueueUploads(result: UploadPickResult, parentId: Long?): UploadEnqueueResult
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
    suspend fun clearFinished(): Int
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
    override suspend fun sendRegistrationCode(email: String, preferredLocale: String) =
        repository.sendRegistrationCode(email, preferredLocale)
    override suspend fun register(
        username: String, password: String, email: String, verificationCode: String, preferredLocale: String,
    ) = repository.register(username, password, email, verificationCode, preferredLocale)
    override suspend fun forgotPassword(email: String) = repository.forgotPassword(email)
    override suspend fun resetPassword(email: String, code: String, newPassword: String) =
        repository.resetPassword(email, code, newPassword)
    override suspend fun getCurrentUser() = repository.getCurrentUser()
    override suspend fun updatePreferredLocale(locale: String) = repository.updatePreferredLocale(locale)
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
    override suspend fun searchPersonalFiles(query: String, page: Int, size: Int) =
        repository.searchPersonalFiles(query, page, size)
    override suspend fun createPersonalDownloadTicket(fileId: Long) = repository.createPersonalDownloadTicket(fileId)
    override suspend fun loadPersonalText(file: FileItem) = repository.loadPersonalText(file)
    override suspend fun loadPersonalOffice(fileId: Long) = repository.loadPersonalOffice(fileId)
    override suspend fun loadTeamOffice(teamId: Long, fileId: Long) = repository.loadTeamOffice(teamId, fileId)
}

private class ManagerTransferOperations(private val manager: TransferManager) : AppTransferOperations {
    override val tasks = manager.tasks
    override suspend fun enqueueUploads(result: UploadPickResult, parentId: Long?) = manager.enqueueUploads(
        result.documents,
        TransferScope.Personal(parentId),
        result.rejectedCount,
        result.overflowCount,
    )
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
    override suspend fun clearFinished() = manager.clearFinished()
    override suspend fun resumeWaitingForLogin() = manager.resumeWaitingForLogin()
    override suspend fun clearForLogout() = manager.clearForLogout()
}

enum class AppPhase { LOADING, RESTORE_FAILED, LOGGED_OUT, LOGGED_IN }
enum class PublicAuthPage { LOGIN, REGISTER, FORGOT_PASSWORD }
enum class PasswordResetStep { EMAIL, RESET }

data class AccountUiState(
    val publicPage: PublicAuthPage = PublicAuthPage.LOGIN,
    val loginUsername: String = "",
    val registerBusy: Boolean = false,
    val registrationCodeBusy: Boolean = false,
    val registrationCodeSeconds: Int = 0,
    val registrationError: UiMessage? = null,
    val registrationSucceeded: Boolean = false,
    val resetStep: PasswordResetStep = PasswordResetStep.EMAIL,
    val resetBusy: Boolean = false,
    val resetEmail: String = "",
    val resetError: UiMessage? = null,
    val resetCodeSent: Boolean = false,
    val passwordResetSucceeded: Boolean = false,
    val profileRefreshing: Boolean = false,
    val profileError: UiMessage? = null,
    val localeSaving: Boolean = false,
    val localeError: UiMessage? = null,
    val locale: MobileLocale = MobileLocale.ENGLISH_US,
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val serverCandidate: String = "",
    val serverSwitchConfirming: Boolean = false,
    val serverSwitching: Boolean = false,
    val serverError: UiMessage? = null,
)

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
    val transferFeedback: TransferEnqueueFeedback? = null,
)

data class TransferEnqueueFeedback(
    val id: Long,
    val message: UiMessage,
    val canOpenTransfers: Boolean,
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

data class PersonalSearchUiState(
    val query: String = "",
    val submittedQuery: String = "",
    val items: List<FileItem> = emptyList(),
    val searching: Boolean = false,
    val loadingMore: Boolean = false,
    val error: UiMessage? = null,
    val loadMoreError: UiMessage? = null,
    val page: Int = 0,
    val pages: Int = 1,
    val total: Long = 0,
    val scrollAnchorId: Long? = null,
) {
    val hasMore: Boolean get() = submittedQuery.isNotEmpty() && page < pages
}

data class PersonalPreviewUiState(
    val fileId: Long? = null,
    val file: FileItem? = null,
    val kind: PreviewKind? = null,
    val loading: Boolean = false,
    val error: UiMessage? = null,
    val mediaUrl: String? = null,
    val text: String? = null,
    val textTruncated: Boolean = false,
    val office: OfficeConfig? = null,
    val playbackPositionMillis: Long = 0,
    val ticketRetryCount: Int = 0,
)

data class AppUiState(
    val phase: AppPhase = AppPhase.LOADING,
    val server: String = com.betterclouddrive.mobile.core.network.BackendClient.DEFAULT_SERVER,
    val user: UserProfile? = null,
    val loginBusy: Boolean = false,
    val sessionError: UiMessage? = null,
    val personalFiles: PersonalFilesUiState = PersonalFilesUiState(),
    val search: PersonalSearchUiState = PersonalSearchUiState(),
    val preview: PersonalPreviewUiState = PersonalPreviewUiState(),
    val transfers: List<TransferTask> = emptyList(),
    val diagnostics: DiagnosticUiState = DiagnosticUiState(),
    val account: AccountUiState = AccountUiState(),
)

private class MemoryThemePreferenceStore : ThemePreferenceStore {
    private val state = MutableStateFlow(ThemeMode.SYSTEM)
    override val themeMode = state.asStateFlow()
    override suspend fun setThemeMode(mode: ThemeMode) { state.value = mode }
}

private class PassiveLocaleController : ApplicationLocaleController {
    private var locale = MobileLocale.ENGLISH_US
    override fun currentLocale(): MobileLocale = locale
    override fun applyLocale(locale: MobileLocale) { this.locale = locale }
}

class AppController internal constructor(
    private val auth: AppAuthOperations,
    private val files: AppFileOperations,
    private val transfers: AppTransferOperations,
    private val scope: CoroutineScope,
    private val localeController: ApplicationLocaleController = PassiveLocaleController(),
    private val themePreferences: ThemePreferenceStore = MemoryThemePreferenceStore(),
) {
    constructor(
        auth: AuthRepository,
        files: FileRepository,
        transfers: TransferManager,
        scope: CoroutineScope,
        localeController: ApplicationLocaleController = PassiveLocaleController(),
        themePreferences: ThemePreferenceStore = MemoryThemePreferenceStore(),
    ) : this(
        RepositoryAuthOperations(auth), RepositoryFileOperations(files),
        ManagerTransferOperations(transfers), scope, localeController, themePreferences,
    )

    private val mutableState = MutableStateFlow(AppUiState())
    val state: StateFlow<AppUiState> = mutableState.asStateFlow()
    private val directoryCache = mutableMapOf<Long?, DirectoryPageState>()
    private var directoryJob: Job? = null
    private var detailJob: Job? = null
    private var mutationJob: Job? = null
    private var pickerJob: Job? = null
    private var uploadRefreshJob: Job? = null
    private var searchJob: Job? = null
    private var previewJob: Job? = null
    private var registrationCountdownJob: Job? = null
    private var feedbackSequence = 0L
    private var previousTransfers = emptyMap<String, TransferTask>()
    private var hasObservedTransfers = false
    private val dirtyUploadFolders = mutableSetOf<Long?>()
    private var started = false

    init {
        mutableState.update {
            it.copy(account = it.account.copy(locale = localeController.currentLocale()))
        }
        scope.launch {
            themePreferences.themeMode.collectLatest { mode ->
                mutableState.update { it.copy(account = it.account.copy(themeMode = mode)) }
            }
        }
        scope.launch {
            transfers.tasks.collectLatest { tasks ->
                tasks.filter { task ->
                    hasObservedTransfers &&
                    task.direction == TransferDirection.UPLOAD &&
                        task.scope is TransferScope.Personal &&
                        task.status == TransferStatus.SUCCEEDED &&
                        previousTransfers[task.id]?.status != TransferStatus.SUCCEEDED
                }.forEach { dirtyUploadFolders += it.scope.parentId }
                hasObservedTransfers = true
                previousTransfers = tasks.associateBy(TransferTask::id)
                mutableState.update { it.copy(transfers = tasks) }
                val ready = dirtyUploadFolders.filter { folderId ->
                    tasks.none { task ->
                        task.direction == TransferDirection.UPLOAD && task.scope is TransferScope.Personal &&
                            task.scope.parentId == folderId && task.status in ACTIVE_UPLOAD_STATUSES
                    }
                }
                ready.forEach { folderId ->
                    dirtyUploadFolders.remove(folderId)
                    directoryCache.remove(folderId)
                    if (mutableState.value.personalFiles.current.folderId == folderId) {
                        uploadRefreshJob?.cancel()
                        uploadRefreshJob = scope.launch {
                            try {
                                reloadCurrentLoadedPages()
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (error: Throwable) {
                                updateCurrent(folderId) { it.copy(error = friendly(error)) }
                            }
                        }
                    }
                }
            }
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
                            account = it.account.copy(publicPage = PublicAuthPage.LOGIN),
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
            AppUiState(
                phase = AppPhase.LOGGED_OUT,
                server = auth.serverBaseUrl,
                transfers = emptyList(),
                account = it.account.copy(publicPage = PublicAuthPage.LOGIN),
            )
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
            applyAccountLocale(user.preferredLocale)
            resetDirectoryState()
            mutableState.update {
                it.copy(
                    phase = AppPhase.LOGGED_IN,
                    user = user,
                    server = auth.serverBaseUrl,
                    personalFiles = PersonalFilesUiState(),
                    account = it.account.copy(
                        publicPage = PublicAuthPage.LOGIN,
                        loginUsername = username,
                        registrationError = null,
                        resetError = null,
                    ),
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

    fun showLogin() {
        mutableState.update { it.copy(account = it.account.copy(
            publicPage = PublicAuthPage.LOGIN,
            registrationError = null,
            resetError = null,
        )) }
    }

    fun showRegistration(server: String) = openPublicPage(server, PublicAuthPage.REGISTER)

    fun showForgotPassword(server: String) = openPublicPage(server, PublicAuthPage.FORGOT_PASSWORD)

    private fun openPublicPage(server: String, page: PublicAuthPage) = scope.launch {
        try {
            val normalized = auth.normalizeServer(server).getOrThrow()
            if (normalized != auth.serverBaseUrl) auth.switchServer(normalized).getOrThrow()
            mutableState.update { it.copy(
                server = auth.serverBaseUrl,
                sessionError = null,
                account = it.account.copy(
                    publicPage = page,
                    registrationError = null,
                    registrationSucceeded = false,
                    resetStep = PasswordResetStep.EMAIL,
                    resetError = null,
                    passwordResetSucceeded = false,
                    resetCodeSent = false,
                ),
            ) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            mutableState.update { it.copy(sessionError = friendly(error)) }
        }
    }

    fun selectPublicLocale(locale: MobileLocale) {
        localeController.applyLocale(locale)
        mutableState.update { it.copy(account = it.account.copy(locale = locale, localeError = null)) }
    }

    fun sendRegistrationCode(email: String) = scope.launch {
        if (mutableState.value.account.registrationCodeBusy || mutableState.value.account.registrationCodeSeconds > 0) return@launch
        mutableState.update { it.copy(account = it.account.copy(registrationCodeBusy = true, registrationError = null)) }
        try {
            auth.sendRegistrationCode(email.trim(), mutableState.value.account.locale.languageTag)
            mutableState.update { it.copy(account = it.account.copy(registrationCodeBusy = false, registrationCodeSeconds = 60)) }
            registrationCountdownJob?.cancel()
            registrationCountdownJob = scope.launch {
                repeat(60) {
                    delay(1_000)
                    mutableState.update { state -> state.copy(account = state.account.copy(
                        registrationCodeSeconds = (state.account.registrationCodeSeconds - 1).coerceAtLeast(0),
                    )) }
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            mutableState.update { it.copy(account = it.account.copy(
                registrationCodeBusy = false, registrationError = friendlyAccount(error),
            )) }
        }
    }

    fun register(username: String, email: String, code: String, password: String, confirmation: String) = scope.launch {
        if (mutableState.value.account.registerBusy) return@launch
        val validation = validateRegistration(username, email, code, password, confirmation)
        if (validation != null) {
            mutableState.update { it.copy(account = it.account.copy(registrationError = validation)) }
            return@launch
        }
        mutableState.update { it.copy(account = it.account.copy(registerBusy = true, registrationError = null)) }
        try {
            val result = auth.register(
                username.trim(), password, email.trim(), code.trim(), mutableState.value.account.locale.languageTag,
            )
            registrationCountdownJob?.cancel()
            mutableState.update { it.copy(account = it.account.copy(
                publicPage = PublicAuthPage.LOGIN,
                loginUsername = result.username,
                registerBusy = false,
                registrationCodeSeconds = 0,
                registrationSucceeded = true,
            )) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            mutableState.update { it.copy(account = it.account.copy(
                registerBusy = false, registrationError = friendlyAccount(error),
            )) }
        }
    }

    fun requestPasswordReset(email: String) = scope.launch {
        if (mutableState.value.account.resetBusy) return@launch
        if (!isEmail(email)) {
            mutableState.update { it.copy(account = it.account.copy(resetError = UiMessage(MobileString.ErrorInvalidInput))) }
            return@launch
        }
        mutableState.update { it.copy(account = it.account.copy(resetBusy = true, resetError = null)) }
        try {
            auth.forgotPassword(email.trim())
            mutableState.update { it.copy(account = it.account.copy(
                resetBusy = false, resetEmail = email.trim(), resetStep = PasswordResetStep.RESET, resetCodeSent = true,
            )) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            mutableState.update { it.copy(account = it.account.copy(resetBusy = false, resetError = friendlyAccount(error))) }
        }
    }

    fun resetPassword(code: String, password: String, confirmation: String) = scope.launch {
        if (mutableState.value.account.resetBusy) return@launch
        if (!code.matches(Regex("\\d{6}")) || password.length !in 6..128 || password != confirmation) {
            mutableState.update { it.copy(account = it.account.copy(resetError = UiMessage(MobileString.ErrorInvalidInput))) }
            return@launch
        }
        mutableState.update { it.copy(account = it.account.copy(resetBusy = true, resetError = null)) }
        try {
            auth.resetPassword(mutableState.value.account.resetEmail, code, password)
            mutableState.update { it.copy(account = it.account.copy(
                publicPage = PublicAuthPage.LOGIN,
                resetBusy = false,
                resetStep = PasswordResetStep.EMAIL,
                resetCodeSent = false,
                passwordResetSucceeded = true,
            )) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            mutableState.update { it.copy(account = it.account.copy(resetBusy = false, resetError = friendlyAccount(error))) }
        }
    }

    fun updateSearchQuery(value: String) {
        searchJob?.cancel()
        val query = value
        if (query.trim().isEmpty()) {
            mutableState.update { it.copy(search = PersonalSearchUiState(query = query)) }
            return
        }
        mutableState.update { it.copy(search = it.search.copy(query = query, error = null, loadMoreError = null)) }
        searchJob = scope.launch {
            delay(SEARCH_DEBOUNCE_MILLIS)
            searchPage(query.trim(), 1)
        }
    }

    fun submitSearch() {
        val query = mutableState.value.search.query.trim()
        searchJob?.cancel()
        if (query.isEmpty()) {
            mutableState.update { it.copy(search = PersonalSearchUiState(query = it.search.query)) }
            return
        }
        searchJob = scope.launch { searchPage(query, 1) }
    }

    fun retrySearch() = submitSearch()

    fun loadMoreSearch() {
        val search = mutableState.value.search
        if (!search.hasMore || search.searching || search.loadingMore) return
        searchJob?.cancel()
        searchJob = scope.launch { searchPage(search.submittedQuery, search.page + 1) }
    }

    fun updateSearchScrollAnchor(fileId: Long?) {
        mutableState.update { it.copy(search = it.search.copy(scrollAnchorId = fileId)) }
    }

    fun prepareOpenSearchLocation(file: FileItem) {
        val parentId = file.parentId
        directoryCache[parentId]?.let { snapshot ->
            if (snapshot.items.any { it.id == file.id }) {
                directoryCache[parentId] = snapshot.copy(scrollAnchorId = file.id)
            }
        }
    }

    fun openPreview(fileId: Long) {
        val existing = mutableState.value.preview
        if (existing.fileId == fileId && (existing.loading || existing.file != null) && existing.error == null) return
        previewJob?.cancel()
        val position = if (existing.fileId == fileId) existing.playbackPositionMillis else 0
        mutableState.update {
            it.copy(preview = PersonalPreviewUiState(fileId = fileId, loading = true, playbackPositionMillis = position))
        }
        previewJob = scope.launch {
            try {
                val file = files.getPersonalFile(fileId)
                loadPreviewContent(file, position, ticketRetryCount = 0)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                updatePreviewFailure(fileId, friendlyPreview(error))
            }
        }
    }

    fun retryPreview() {
        val preview = mutableState.value.preview
        val fileId = preview.fileId ?: return
        previewJob?.cancel()
        mutableState.update { it.copy(preview = preview.copy(loading = true, error = null, mediaUrl = null)) }
        previewJob = scope.launch {
            try {
                val file = preview.file ?: files.getPersonalFile(fileId)
                loadPreviewContent(file, preview.playbackPositionMillis, ticketRetryCount = 0)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                updatePreviewFailure(fileId, friendlyPreview(error))
            }
        }
    }

    fun reportPreviewError(message: UiMessage) {
        val preview = mutableState.value.preview
        val file = preview.file ?: return
        if (preview.kind in setOf(PreviewKind.IMAGE, PreviewKind.VIDEO) && preview.ticketRetryCount == 0) {
            previewJob?.cancel()
            mutableState.update {
                it.copy(preview = preview.copy(loading = true, error = null, mediaUrl = null, ticketRetryCount = 1))
            }
            previewJob = scope.launch {
                try {
                    loadPreviewContent(file, preview.playbackPositionMillis, ticketRetryCount = 1)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Throwable) {
                    updatePreviewFailure(file.id, friendlyPreview(error))
                }
            }
        } else {
            updatePreviewFailure(file.id, message)
        }
    }

    fun updatePreviewPosition(positionMillis: Long) {
        mutableState.update {
            if (it.preview.kind == PreviewKind.VIDEO) {
                it.copy(preview = it.preview.copy(playbackPositionMillis = positionMillis.coerceAtLeast(0)))
            } else it
        }
    }

    fun closePreview() {
        previewJob?.cancel()
        previewJob = null
        mutableState.update { it.copy(preview = PersonalPreviewUiState()) }
    }

    fun refreshProfile() = scope.launch {
        if (mutableState.value.account.profileRefreshing) return@launch
        mutableState.update { it.copy(account = it.account.copy(profileRefreshing = true, profileError = null)) }
        try {
            val profile = auth.getCurrentUser()
            mutableState.update { it.copy(user = profile, account = it.account.copy(profileRefreshing = false)) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            mutableState.update { it.copy(account = it.account.copy(
                profileRefreshing = false, profileError = friendlyAccount(error),
            )) }
        }
    }

    fun updateAccountLocale(locale: MobileLocale) = scope.launch {
        val account = mutableState.value.account
        if (account.localeSaving || account.locale == locale) return@launch
        mutableState.update { it.copy(account = it.account.copy(localeSaving = true, localeError = null)) }
        try {
            val profile = auth.updatePreferredLocale(locale.languageTag)
            mutableState.update { it.copy(
                user = profile,
                account = it.account.copy(localeSaving = false, locale = locale),
            ) }
            localeController.applyLocale(locale)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            mutableState.update { it.copy(account = it.account.copy(
                localeSaving = false, localeError = friendlyAccount(error),
            )) }
        }
    }

    fun setThemeMode(mode: ThemeMode) = scope.launch {
        themePreferences.setThemeMode(mode)
    }

    fun requestServerSwitch(value: String) {
        val normalized = auth.normalizeServer(value)
        normalized.fold(
            onSuccess = { server ->
                mutableState.update { state ->
                    if (server == auth.serverBaseUrl) state.copy(account = state.account.copy(
                        serverCandidate = "", serverSwitchConfirming = false, serverError = null,
                    )) else state.copy(account = state.account.copy(
                        serverCandidate = server, serverSwitchConfirming = true, serverError = null,
                    ))
                }
            },
            onFailure = { error ->
                mutableState.update { it.copy(account = it.account.copy(serverError = friendly(error))) }
            },
        )
    }

    fun cancelServerSwitch() {
        mutableState.update { it.copy(account = it.account.copy(
            serverCandidate = "", serverSwitchConfirming = false, serverError = null,
        )) }
    }

    fun confirmServerSwitch() = scope.launch {
        val target = mutableState.value.account.serverCandidate
        if (target.isBlank() || mutableState.value.account.serverSwitching) return@launch
        mutableState.update { it.copy(account = it.account.copy(serverSwitching = true, serverError = null)) }
        try {
            auth.logout()
            transfers.clearForLogout()
            auth.switchServer(target).getOrThrow()
            resetDirectoryState()
            mutableState.update { state -> AppUiState(
                phase = AppPhase.LOGGED_OUT,
                server = auth.serverBaseUrl,
                transfers = emptyList(),
                account = state.account.copy(
                    publicPage = PublicAuthPage.LOGIN,
                    serverCandidate = "",
                    serverSwitchConfirming = false,
                    serverSwitching = false,
                ),
            ) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            mutableState.update { it.copy(account = it.account.copy(
                serverSwitching = false, serverError = friendly(error),
            )) }
        }
    }

    fun logout() = scope.launch {
        mutableState.update { it.copy(loginBusy = true, sessionError = null) }
        auth.logout()
        transfers.clearForLogout()
        resetDirectoryState()
        mutableState.update {
            AppUiState(
                phase = AppPhase.LOGGED_OUT,
                server = auth.serverBaseUrl,
                transfers = emptyList(),
                account = it.account.copy(publicPage = PublicAuthPage.LOGIN),
            )
        }
    }

    fun upload(parentId: Long?, result: UploadPickResult) = scope.launch {
        val outcome = try {
            transfers.enqueueUploads(result, parentId)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            mutableState.update { state -> state.copy(personalFiles = state.personalFiles.copy(
                transferFeedback = TransferEnqueueFeedback(
                    ++feedbackSequence, friendly(error), canOpenTransfers = false,
                ),
            )) }
            return@launch
        }
        val message = if (outcome.tasks.isEmpty()) {
            UiMessage(MobileString.UploadNothingQueued, listOf(outcome.rejectedCount, outcome.overflowCount))
        } else {
            UiMessage(
                MobileString.UploadQueuedSummary,
                listOf(outcome.tasks.size, outcome.rejectedCount, outcome.overflowCount),
            )
        }
        mutableState.update { state -> state.copy(personalFiles = state.personalFiles.copy(
            transferFeedback = TransferEnqueueFeedback(
                ++feedbackSequence, message, canOpenTransfers = outcome.tasks.isNotEmpty(),
            ),
        )) }
    }

    fun consumeTransferFeedback() {
        mutableState.update { it.copy(personalFiles = it.personalFiles.copy(transferFeedback = null)) }
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
    fun clearFinishedTransfers() = scope.launch { transfers.clearFinished() }

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
        searchJob?.cancel()
        searchJob = null
        previewJob?.cancel()
        previewJob = null
        directoryCache.clear()
    }

    private suspend fun searchPage(query: String, page: Int) {
        val normalized = query.trim()
        if (normalized.isEmpty()) return
        val existing = mutableState.value.search
        mutableState.update {
            it.copy(search = it.search.copy(
                submittedQuery = normalized,
                searching = page == 1,
                loadingMore = page > 1,
                error = if (page == 1) null else it.search.error,
                loadMoreError = null,
            ))
        }
        try {
            val result = files.searchPersonalFiles(normalized, page, SEARCH_PAGE_SIZE)
            val latest = mutableState.value.search
            if (latest.query.trim() != normalized || latest.submittedQuery != normalized) return
            val items = if (page == 1) result.records.distinctBy(FileItem::id)
            else (existing.items + result.records).distinctBy(FileItem::id)
            mutableState.update {
                it.copy(search = it.search.copy(
                    items = items,
                    searching = false,
                    loadingMore = false,
                    error = null,
                    loadMoreError = null,
                    page = result.page,
                    pages = result.pages.coerceAtLeast(result.page),
                    total = result.total,
                    scrollAnchorId = if (page == 1) null else existing.scrollAnchorId,
                ))
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            if (mutableState.value.search.query.trim() != normalized) return
            mutableState.update {
                it.copy(search = it.search.copy(
                    searching = false,
                    loadingMore = false,
                    error = if (page == 1) friendly(error) else it.search.error,
                    loadMoreError = if (page > 1) friendly(error) else null,
                ))
            }
        }
    }

    private suspend fun loadPreviewContent(file: FileItem, position: Long, ticketRetryCount: Int) {
        val kind = file.previewKind()
        val base = PersonalPreviewUiState(
            fileId = file.id,
            file = file,
            kind = kind,
            loading = true,
            playbackPositionMillis = position,
            ticketRetryCount = ticketRetryCount,
        )
        mutableState.update { if (it.preview.fileId == file.id) it.copy(preview = base) else it }
        val loaded = when (kind) {
            PreviewKind.IMAGE, PreviewKind.VIDEO -> base.copy(
                loading = false,
                mediaUrl = files.createPersonalDownloadTicket(file.id),
            )
            PreviewKind.TEXT -> files.loadPersonalText(file).let { text ->
                base.copy(loading = false, text = text.content, textTruncated = text.truncated)
            }
            PreviewKind.OFFICE -> base.copy(loading = false, office = files.loadPersonalOffice(file.id))
            PreviewKind.UNSUPPORTED -> base.copy(loading = false)
        }
        mutableState.update { if (it.preview.fileId == file.id) it.copy(preview = loaded) else it }
    }

    private fun updatePreviewFailure(fileId: Long, message: UiMessage) {
        mutableState.update {
            if (it.preview.fileId == fileId) {
                it.copy(preview = it.preview.copy(loading = false, error = message, mediaUrl = null))
            } else it
        }
    }

    private fun friendlyPreview(error: Throwable): UiMessage = when (error) {
        is CancellationException -> throw error
        else -> UiMessage(MobileString.ErrorPreviewFailed)
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
                AppUiState(
                    phase = AppPhase.LOGGED_OUT,
                    server = auth.serverBaseUrl,
                    transfers = it.transfers,
                    account = it.account.copy(publicPage = PublicAuthPage.LOGIN),
                )
            }
            is SessionRestoreResult.RetryableFailure -> mutableState.update {
                it.copy(
                    phase = AppPhase.RESTORE_FAILED,
                    server = auth.serverBaseUrl,
                    sessionError = UiMessage(MobileString.ErrorSessionPreserved),
                )
            }
            is SessionRestoreResult.Restored -> {
                applyAccountLocale(result.user.preferredLocale)
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
        const val SEARCH_PAGE_SIZE = 50
        const val SEARCH_DEBOUNCE_MILLIS = 300L
        const val PICKER_PAGE_SIZE = 100
        const val MAX_BREADCRUMB_DEPTH = 128
        val ACTIVE_UPLOAD_STATUSES = setOf(
            TransferStatus.QUEUED, TransferStatus.PREPARING, TransferStatus.RUNNING,
        )
    }

    private fun applyAccountLocale(preferredLocale: String) {
        val locale = accountLocale(preferredLocale)
        mutableState.update { it.copy(account = it.account.copy(locale = locale)) }
        localeController.applyLocale(locale)
    }

    private fun accountLocale(value: String): MobileLocale = when (value.replace('_', '-').lowercase()) {
        "zh-sg", "zh-hans-sg" -> MobileLocale.SIMPLIFIED_CHINESE_SG
        "zh-cn", "zh-hans", "zh-hans-cn" -> MobileLocale.SIMPLIFIED_CHINESE_CN
        else -> MobileLocale.ENGLISH_US
    }

    private fun validateRegistration(
        username: String,
        email: String,
        code: String,
        password: String,
        confirmation: String,
    ): UiMessage? = when {
        username.trim().length !in 3..64 -> UiMessage(MobileString.ErrorInvalidInput)
        !isEmail(email) -> UiMessage(MobileString.ErrorInvalidInput)
        !code.trim().matches(Regex("\\d{6}")) -> UiMessage(MobileString.ErrorInvalidInput)
        password.length !in 8..128 || !password.any(Char::isUpperCase) ||
            !password.any(Char::isLowerCase) || !password.any(Char::isDigit) -> UiMessage(MobileString.ErrorInvalidInput)
        password != confirmation -> UiMessage(MobileString.ErrorInvalidInput)
        else -> null
    }

    private fun isEmail(value: String): Boolean {
        val trimmed = value.trim()
        return trimmed.length <= 254 && '@' in trimmed && trimmed.substringAfter('@').contains('.')
    }

    private fun friendlyAccount(error: Throwable): UiMessage = when (error) {
        is ApiException -> when (error.messageKey) {
            "error.rateLimited" -> UiMessage(MobileString.ErrorRateLimited)
            "error.emailCodeExpired" -> UiMessage(MobileString.ErrorVerificationExpired)
            "error.emailCodeMismatch", "error.emailVerificationFailed" -> UiMessage(MobileString.ErrorVerificationCode)
            "error.usernameExists", "error.emailExists", "error.accountConflict" -> UiMessage(MobileString.ErrorAccountConflict)
            "error.unauthorized", "error.invalidCredentials" -> UiMessage(MobileString.ErrorCredentials)
            "error.validation", "error.invalidParameter" -> UiMessage(MobileString.ErrorInvalidInput)
            else -> friendly(error)
        }
        else -> friendly(error)
    }
}
