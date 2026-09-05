package com.betterclouddrive.mobile.feature.teams

import com.betterclouddrive.mobile.app.FileMutationFeedback
import com.betterclouddrive.mobile.app.ResettableStore
import com.betterclouddrive.mobile.app.TransferEnqueueFeedback
import com.betterclouddrive.mobile.app.storeChildScope
import com.betterclouddrive.mobile.app.uiError
import com.betterclouddrive.mobile.core.localization.MobileString
import com.betterclouddrive.mobile.core.localization.UiMessage
import com.betterclouddrive.mobile.core.model.ApiException
import com.betterclouddrive.mobile.core.model.FileItem
import com.betterclouddrive.mobile.core.model.TeamEffectivePermission
import com.betterclouddrive.mobile.core.model.TeamRole
import com.betterclouddrive.mobile.core.model.TeamSummary
import com.betterclouddrive.mobile.core.platform.UploadPickResult
import com.betterclouddrive.mobile.feature.files.BreadcrumbEntry
import com.betterclouddrive.mobile.feature.files.FileMutationKind
import com.betterclouddrive.mobile.feature.transfers.TeamTransferOperations
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class TeamFolderPickerState(
    val source: FileItem,
    val breadcrumb: List<BreadcrumbEntry> = listOf(BreadcrumbEntry(null, "")),
    val currentFolderId: Long? = null,
    val folders: List<FileItem> = emptyList(),
    val targetPermission: TeamEffectivePermission? = null,
    val loading: Boolean = false,
    val error: UiMessage? = null,
) {
    val canConfirm: Boolean get() = currentFolderId != source.parentId && targetPermission?.canWrite == true
}

data class TeamFileActionsState(
    val teamId: Long? = null,
    val folderId: Long? = null,
    val rootPermission: TeamEffectivePermission? = null,
    val directoryPermission: TeamEffectivePermission? = null,
    val permissionLoading: Boolean = false,
    val permissionError: UiMessage? = null,
    val itemPermissions: Map<Long, TeamEffectivePermission> = emptyMap(),
    val loadingPermissionIds: Set<Long> = emptySet(),
    val selectedIds: Set<Long> = emptySet(),
    val mutation: FileMutationKind? = null,
    val mutationError: UiMessage? = null,
    val mutationErrorKind: FileMutationKind? = null,
    val feedback: FileMutationFeedback? = null,
    val folderPicker: TeamFolderPickerState? = null,
    val transferFeedback: TransferEnqueueFeedback? = null,
)

class TeamFileActionsStore(
    private val repository: TeamWorkspaceRepository,
    private val permissions: TeamPermissionsRepository,
    private val transfers: TeamTransferOperations,
    parentScope: CoroutineScope,
    private val onContentChanged: (Long, Set<Long?>) -> Unit = { _, _ -> },
) : ResettableStore {
    private val scope = parentScope.storeChildScope()
    private val mutableState = MutableStateFlow(TeamFileActionsState())
    val state: StateFlow<TeamFileActionsState> = mutableState.asStateFlow()
    private var permissionJob: Job? = null
    private var mutationJob: Job? = null
    private var pickerJob: Job? = null
    private var uploadRefreshJob: Job? = null
    private var feedbackSequence = 0L
    private var ignoreTeamSummaryRole = false

    init {
        scope.launch {
            transfers.teamUploadFoldersSettled.collect { folder ->
                if (folder.teamId == state.value.teamId) {
                    onContentChanged(folder.teamId, setOf(folder.parentId))
                }
            }
        }
        scope.launch {
            transfers.teamPermissionRevoked.collect { teamId ->
                if (teamId == state.value.teamId) refreshPermissionsAfterRevocation(teamId)
            }
        }
    }

    fun activate(team: TeamSummary?, teamId: Long, folderId: Long?) {
        val changedWorkspace = state.value.teamId != teamId
        val changedFolder = changedWorkspace || state.value.folderId != folderId
        if (changedWorkspace) ignoreTeamSummaryRole = false
        if (changedFolder) {
            permissionJob?.cancel(); pickerJob?.cancel()
            mutableState.value = TeamFileActionsState(
                teamId = teamId, folderId = folderId,
                rootPermission = team?.role?.rootPermission()?.takeUnless { ignoreTeamSummaryRole }
                    ?: state.value.rootPermission.takeIf { !changedWorkspace },
            )
        }
        if (team != null && !ignoreTeamSummaryRole) {
            mutableState.update { it.copy(rootPermission = team.role.rootPermission()) }
        }
        if (folderId == null) {
            mutableState.update { it.copy(
                rootPermission = team?.role?.rootPermission()?.takeUnless { ignoreTeamSummaryRole } ?: it.rootPermission,
                directoryPermission = team?.role?.rootPermission()?.takeUnless { ignoreTeamSummaryRole } ?: it.rootPermission,
                permissionLoading = team == null && it.rootPermission == null,
            ) }
            return
        }
        if (state.value.directoryPermission != null || state.value.permissionLoading) return
        loadDirectoryPermission(teamId, folderId)
    }

    fun retryDirectoryPermission() {
        val current = state.value
        val teamId = current.teamId ?: return
        val folderId = current.folderId ?: return
        loadDirectoryPermission(teamId, folderId)
    }

    fun requestItemPermission(file: FileItem) {
        val current = state.value
        val teamId = current.teamId ?: return
        if (file.id in current.itemPermissions || file.id in current.loadingPermissionIds) return
        mutableState.update { it.copy(loadingPermissionIds = it.loadingPermissionIds + file.id) }
        scope.launch {
            try {
                val permission = permissions.effective(teamId, file.id)
                mutableState.update { state -> if (state.teamId == teamId) state.copy(
                    itemPermissions = state.itemPermissions + (file.id to permission),
                    loadingPermissionIds = state.loadingPermissionIds - file.id,
                ) else state }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Throwable) { mutableState.update { it.copy(
                loadingPermissionIds = it.loadingPermissionIds - file.id,
                permissionError = uiError(error),
            ) } }
        }
    }

    fun toggleSelection(file: FileItem) {
        val permission = state.value.itemPermissions[file.id]
        if (permission == null) {
            val teamId = state.value.teamId ?: return
            if (file.id in state.value.loadingPermissionIds) return
            mutableState.update { it.copy(loadingPermissionIds = it.loadingPermissionIds + file.id) }
            scope.launch {
                try {
                    val loaded = permissions.effective(teamId, file.id)
                    mutableState.update { it.copy(
                        itemPermissions = it.itemPermissions + (file.id to loaded),
                        loadingPermissionIds = it.loadingPermissionIds - file.id,
                    ) }
                    if (loaded.canDelete) toggleSelection(file)
                    else mutableState.update { it.copy(permissionError = UiMessage(MobileString.ErrorPermissionChanged)) }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Throwable) { mutableState.update { it.copy(
                    loadingPermissionIds = it.loadingPermissionIds - file.id, permissionError = uiError(error),
                ) } }
            }
            return
        }
        if (!permission.canDelete) return
        mutableState.update { current ->
            val selected = current.selectedIds.toMutableSet().apply { if (!add(file.id)) remove(file.id) }
            current.copy(selectedIds = selected)
        }
    }

    fun clearSelection() = mutableState.update { it.copy(selectedIds = emptySet()) }
    fun clearTransientUi() { pickerJob?.cancel(); mutableState.update { it.copy(
        selectedIds = emptySet(), mutationError = null, mutationErrorKind = null, folderPicker = null,
    ) } }

    fun createFolder(name: String) {
        val current = state.value
        val teamId = current.teamId ?: return
        launchMutation(FileMutationKind.CREATE_FOLDER, setOf(current.folderId)) {
            repository.createFolder(teamId, current.folderId, name)
            MobileString.FolderCreated
        }
    }

    fun rename(file: FileItem, name: String) {
        val teamId = state.value.teamId ?: return
        launchMutation(FileMutationKind.RENAME, setOf(file.parentId)) {
            repository.rename(teamId, file.id, name)
            MobileString.FileRenamed
        }
    }

    fun delete(fileIds: Set<Long>) {
        val current = state.value
        val teamId = current.teamId ?: return
        if (fileIds.isEmpty() || !fileIds.all { current.itemPermissions[it]?.canDelete == true }) return
        launchMutation(FileMutationKind.DELETE, setOf(current.folderId)) {
            repository.delete(teamId, fileIds.toList())
            MobileString.FilesMovedToTrash
        }
    }

    fun openMovePicker(file: FileItem) {
        val teamId = state.value.teamId ?: return
        if (state.value.itemPermissions[file.id]?.canWrite != true) return
        pickerJob?.cancel()
        mutableState.update { it.copy(folderPicker = TeamFolderPickerState(source = file, loading = true)) }
        loadPickerFolder(teamId, null, listOf(BreadcrumbEntry(null, "")))
    }

    fun openPickerFolder(folder: FileItem) {
        val picker = state.value.folderPicker ?: return
        val teamId = state.value.teamId ?: return
        if (!folder.isFolder || folder.id == picker.source.id) return
        loadPickerFolder(teamId, folder.id, picker.breadcrumb + BreadcrumbEntry(folder.id, folder.fileName))
    }

    fun pickerBack() {
        val picker = state.value.folderPicker ?: return
        val teamId = state.value.teamId ?: return
        if (picker.breadcrumb.size <= 1) return
        val path = picker.breadcrumb.dropLast(1)
        loadPickerFolder(teamId, path.last().id, path)
    }

    fun retryPicker() {
        val picker = state.value.folderPicker ?: return
        val teamId = state.value.teamId ?: return
        loadPickerFolder(teamId, picker.currentFolderId, picker.breadcrumb)
    }

    fun closePicker() { pickerJob?.cancel(); mutableState.update { it.copy(folderPicker = null) } }

    fun confirmMove() {
        val picker = state.value.folderPicker ?: return
        val teamId = state.value.teamId ?: return
        if (!picker.canConfirm) return
        launchMutation(FileMutationKind.MOVE, setOf(picker.source.parentId, picker.currentFolderId)) {
            repository.move(teamId, picker.source.id, picker.currentFolderId)
            MobileString.FileMoved
        }
    }

    fun upload(result: UploadPickResult) {
        val current = state.value
        val teamId = current.teamId ?: return
        if (current.directoryPermission?.canWrite != true) return
        uploadRefreshJob?.cancel()
        uploadRefreshJob = scope.launch {
            val outcome = try { transfers.enqueueTeamUploads(teamId, result, current.folderId) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Throwable) {
                mutableState.update { it.copy(transferFeedback = TransferEnqueueFeedback(++feedbackSequence, uiError(error), false)) }
                return@launch
            }
            val message = if (outcome.tasks.isEmpty()) UiMessage(
                MobileString.UploadNothingQueued, listOf(outcome.rejectedCount, outcome.overflowCount),
            ) else UiMessage(MobileString.UploadQueuedSummary, listOf(outcome.tasks.size, outcome.rejectedCount, outcome.overflowCount))
            mutableState.update { it.copy(transferFeedback = TransferEnqueueFeedback(
                ++feedbackSequence, message, outcome.tasks.isNotEmpty(),
            )) }
        }
    }

    fun download(file: FileItem, destinationHandle: String) {
        val teamId = state.value.teamId ?: return
        if (file.isFolder) return
        scope.launch {
            try { transfers.enqueueTeamDownload(
                teamId, file.id, file.fileName, file.fileSize, file.mimeType, destinationHandle, file.md5Hash,
            ) } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Throwable) { mutableState.update { it.copy(permissionError = uiError(error)) } }
        }
    }

    fun consumeFeedback() = mutableState.update { it.copy(feedback = null) }
    fun consumeTransferFeedback() = mutableState.update { it.copy(transferFeedback = null) }
    fun clearPermissionError() = mutableState.update { it.copy(permissionError = null) }

    fun invalidatePermission(teamId: Long, fileId: Long) {
        if (state.value.teamId != teamId) return
        mutableState.update { current -> current.copy(
            itemPermissions = current.itemPermissions - fileId,
            directoryPermission = current.directoryPermission.takeUnless { current.folderId == fileId },
        ) }
        if (state.value.folderId == fileId) loadDirectoryPermission(teamId, fileId)
    }

    override fun reset() {
        permissionJob?.cancel(); mutationJob?.cancel(); pickerJob?.cancel(); uploadRefreshJob?.cancel()
        ignoreTeamSummaryRole = false
        mutableState.value = TeamFileActionsState()
    }

    private fun refreshPermissionsAfterRevocation(teamId: Long) {
        ignoreTeamSummaryRole = true
        permissionJob?.cancel()
        val folderId = state.value.folderId
        mutableState.update { it.copy(
            rootPermission = null,
            directoryPermission = null,
            itemPermissions = emptyMap(),
            selectedIds = emptySet(),
            permissionLoading = true,
            permissionError = UiMessage(MobileString.ErrorPermissionChanged),
        ) }
        permissionJob = scope.launch {
            try {
                val root = repository.getTeam(teamId).role.rootPermission()
                val directory = if (folderId == null) root else permissions.effective(teamId, folderId)
                mutableState.update { current -> if (current.teamId == teamId && current.folderId == folderId) current.copy(
                    rootPermission = root,
                    directoryPermission = directory,
                    permissionLoading = false,
                ) else current }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Throwable) {
                mutableState.update { current -> if (current.teamId == teamId) current.copy(
                    permissionLoading = false,
                    permissionError = uiError(error),
                ) else current }
            }
        }
    }

    private fun loadDirectoryPermission(teamId: Long, folderId: Long, clearError: Boolean = true) {
        permissionJob?.cancel()
        mutableState.update { it.copy(
            permissionLoading = true,
            permissionError = if (clearError) null else it.permissionError,
        ) }
        permissionJob = scope.launch {
            try {
                val permission = permissions.effective(teamId, folderId)
                mutableState.update { if (it.teamId == teamId && it.folderId == folderId) it.copy(
                    directoryPermission = permission, permissionLoading = false,
                ) else it }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Throwable) { mutableState.update { it.copy(permissionLoading = false, permissionError = uiError(error)) } }
        }
    }

    private fun loadPickerFolder(teamId: Long, folderId: Long?, breadcrumb: List<BreadcrumbEntry>) {
        pickerJob?.cancel()
        mutableState.update { current -> current.copy(folderPicker = current.folderPicker?.copy(
            breadcrumb = breadcrumb, currentFolderId = folderId, loading = true, error = null,
        )) }
        pickerJob = scope.launch {
            try {
                val folders = mutableListOf<FileItem>()
                var page = 1
                var hasMore: Boolean
                do {
                    val result = repository.listFiles(teamId, folderId, page, PICKER_PAGE_SIZE, "fileName", "asc")
                    folders += result.records.takeWhile { it.isFolder }
                        .filterNot { it.id == state.value.folderPicker?.source?.id }
                    hasMore = result.page < result.pages && result.records.all { it.isFolder }
                    page++
                } while (hasMore)
                val permission = if (folderId == null) state.value.rootPermission
                else permissions.effective(teamId, folderId)
                mutableState.update { current -> current.copy(folderPicker = current.folderPicker?.copy(
                    breadcrumb = breadcrumb, currentFolderId = folderId, folders = folders.distinctBy(FileItem::id),
                    targetPermission = permission, loading = false,
                )) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Throwable) { mutableState.update { current -> current.copy(folderPicker = current.folderPicker?.copy(
                loading = false, error = uiError(error), targetPermission = null,
            )) } }
        }
    }

    private fun launchMutation(kind: FileMutationKind, affectedFolders: Set<Long?>, block: suspend () -> MobileString) {
        if (state.value.mutation != null) return
        val teamId = state.value.teamId ?: return
        mutationJob = scope.launch {
            mutableState.update { it.copy(mutation = kind, mutationError = null, mutationErrorKind = null) }
            try {
                val success = block()
                onContentChanged(teamId, affectedFolders)
                mutableState.update { it.copy(
                    mutation = null, selectedIds = emptySet(), folderPicker = null,
                    itemPermissions = emptyMap(), feedback = FileMutationFeedback(++feedbackSequence, UiMessage(success), false),
                ) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Throwable) {
                if (error is ApiException && (error.code == 403 || error.messageKey == "error.forbidden")) {
                    refreshPermissionsAfterRevocation(teamId)
                }
                mutableState.update { it.copy(mutation = null, mutationError = uiError(error), mutationErrorKind = kind) }
            }
        }
    }

    private fun TeamRole.rootPermission() = when (this) {
        TeamRole.OWNER, TeamRole.ADMIN -> TeamEffectivePermission(true, true, true, true, true, "BASE_ROLE")
        TeamRole.EDITOR -> TeamEffectivePermission(true, true, true, true, false, "BASE_ROLE")
        TeamRole.VIEWER -> TeamEffectivePermission(true, false, false, false, false, "BASE_ROLE")
    }

    private companion object { const val PICKER_PAGE_SIZE = 100 }
}
