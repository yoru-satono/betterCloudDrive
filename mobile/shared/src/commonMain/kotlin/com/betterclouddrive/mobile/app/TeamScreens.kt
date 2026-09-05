@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package com.betterclouddrive.mobile.app

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.DriveFileMove
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.backhandler.BackHandler
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.betterclouddrive.mobile.core.localization.LocalMobileStrings
import com.betterclouddrive.mobile.core.localization.MobileString
import com.betterclouddrive.mobile.core.localization.UiMessage
import com.betterclouddrive.mobile.core.model.FileItem
import com.betterclouddrive.mobile.core.model.PreviewKind
import com.betterclouddrive.mobile.core.model.TeamRole
import com.betterclouddrive.mobile.core.model.TeamSummary
import com.betterclouddrive.mobile.core.platform.MediaPreviewRenderer
import com.betterclouddrive.mobile.core.platform.MediaPreviewRequest
import com.betterclouddrive.mobile.core.platform.OfficeRenderer
import com.betterclouddrive.mobile.core.platform.DocumentPicker
import com.betterclouddrive.mobile.core.platform.DownloadRequest
import com.betterclouddrive.mobile.feature.files.FileViewMode
import com.betterclouddrive.mobile.feature.files.PersonalFileSort
import com.betterclouddrive.mobile.feature.files.SortDirection
import com.betterclouddrive.mobile.feature.files.previewKind
import com.betterclouddrive.mobile.feature.teams.TeamBrowserState
import com.betterclouddrive.mobile.feature.teams.TeamFileActionsState
import com.betterclouddrive.mobile.feature.teams.TeamFolderPickerState
import com.betterclouddrive.mobile.feature.teams.TeamPreviewState
import com.betterclouddrive.mobile.feature.teams.TeamSearchState
import com.betterclouddrive.mobile.feature.teams.TeamsState
import com.betterclouddrive.mobile.feature.teams.TeamPermissionsState
import com.betterclouddrive.mobile.core.model.TeamFilePermission
import com.betterclouddrive.mobile.core.model.UpdateTeamFilePermissionRequest
import com.betterclouddrive.mobile.feature.transfers.MAX_UPLOAD_SELECTION

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TeamsScreen(
    state: TeamsState,
    wide: Boolean,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onLoadMore: () -> Unit,
    onCreate: (String) -> Unit,
    onClearCreateError: () -> Unit,
    onOpenTeam: (TeamSummary) -> Unit,
) {
    val strings = LocalMobileStrings.current
    var creating by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    BackHandler(onBack = onBack)
    Scaffold(
        modifier = Modifier.testTag("teams-screen"),
        topBar = { TopAppBar(
            title = { Text(strings[MobileString.TeamsTitle]) },
            navigationIcon = { IconButton(onBack, Modifier.testTag("teams-back")) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, strings[MobileString.BackToParent])
            } },
            actions = {
                IconButton(onRefresh, enabled = !state.loading && !state.refreshing, modifier = Modifier.testTag("teams-refresh")) {
                    Icon(Icons.Default.Refresh, strings[MobileString.RefreshDirectory])
                }
                Button(onClick = { onClearCreateError(); creating = true }, modifier = Modifier.testTag("teams-create")) {
                    Text(strings[MobileString.CreateTeam])
                }
            },
        ) },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when {
                state.loading && state.items.isEmpty() -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                state.error != null && state.items.isEmpty() -> TeamRetryPane(strings.resolve(state.error).orEmpty(), onRefresh)
                state.items.isEmpty() -> TeamEmptyPane(MobileString.TeamsEmpty, MobileString.TeamsEmptyDetail)
                else -> LazyColumn(
                    Modifier.fillMaxSize().testTag("teams-list"),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(state.items, key = TeamSummary::id) { team ->
                        TeamCard(team, onOpenTeam)
                        if (team.id == state.items.lastOrNull()?.id && state.hasMore) LaunchedEffect(team.id) { onLoadMore() }
                    }
                    if (state.loadingMore) item { CircularProgressIndicator(Modifier.padding(16.dp)) }
                    state.loadMoreError?.let { error -> item {
                        OutlinedButton(onLoadMore, Modifier.padding(16.dp).testTag("teams-load-more-retry")) {
                            Text(strings.resolve(error) ?: strings[MobileString.RetryLoadMore])
                        }
                    } }
                }
            }
        }
    }
    if (creating) TeamCreateOverlay(
        wide = wide,
        name = name,
        submitting = state.creating,
        error = state.createError,
        onName = { name = it },
        onSubmit = { onCreate(name) },
        onDismiss = { if (!state.creating) { creating = false; name = ""; onClearCreateError() } },
    )
}

@Composable
private fun TeamCard(team: TeamSummary, onOpen: (TeamSummary) -> Unit) {
    val strings = LocalMobileStrings.current
    val fraction = if (team.storageQuota > 0) (team.storageUsed.toFloat() / team.storageQuota).coerceIn(0f, 1f) else 0f
    Card(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp).clickable { onOpen(team) }.testTag("team-${team.id}"),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Groups, null)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(team.name, style = MaterialTheme.typography.titleMedium)
                    Text(strings[team.role.stringKey()], style = MaterialTheme.typography.labelMedium)
                }
            }
            LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
            Text(strings.format(MobileString.TeamQuota, formatFileSize(team.storageUsed), formatFileSize(team.storageQuota)))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TeamCreateOverlay(
    wide: Boolean,
    name: String,
    submitting: Boolean,
    error: UiMessage?,
    onName: (String) -> Unit,
    onSubmit: () -> Unit,
    onDismiss: () -> Unit,
) {
    val strings = LocalMobileStrings.current
    val content: @Composable () -> Unit = {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(strings[MobileString.CreateTeam], style = MaterialTheme.typography.titleLarge)
            OutlinedTextField(
                value = name, onValueChange = onName, enabled = !submitting,
                label = { Text(strings[MobileString.TeamName]) }, singleLine = true,
                isError = error != null, supportingText = { error?.let { Text(strings.resolve(it).orEmpty()) } },
                modifier = Modifier.fillMaxWidth().testTag("team-name"),
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                OutlinedButton(onDismiss, enabled = !submitting) { Text(strings[MobileString.Cancel]) }
                Spacer(Modifier.width(8.dp))
                Button(onSubmit, enabled = !submitting, modifier = Modifier.testTag("team-create-confirm")) {
                    if (submitting) CircularProgressIndicator(Modifier.width(18.dp).height(18.dp))
                    else Text(strings[MobileString.CreateTeam])
                }
            }
        }
    }
    if (wide) AlertDialog(onDismissRequest = onDismiss, confirmButton = {}, text = content)
    else ModalBottomSheet(onDismissRequest = onDismiss) { content() }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun TeamBrowserScreen(
    state: TeamBrowserState,
    actions: TeamFileActionsState,
    permissions: TeamPermissionsState,
    wide: Boolean,
    actorUserId: Long?,
    documentPicker: DocumentPicker,
    onBack: () -> Unit,
    onBackToTeams: () -> Unit,
    onBreadcrumb: (Long?) -> Unit,
    onOpenFolder: (FileItem) -> Unit,
    onOpenSearch: () -> Unit,
    onOpenMembers: () -> Unit,
    onRetryWorkspace: () -> Unit,
    onRefresh: () -> Unit,
    onLoadMore: () -> Unit,
    onSort: (PersonalFileSort, SortDirection) -> Unit,
    onViewMode: (FileViewMode) -> Unit,
    onSelect: (FileItem?) -> Unit,
    onPreview: (FileItem) -> Unit,
    onRequestPermission: (FileItem) -> Unit,
    onToggleSelection: (FileItem) -> Unit,
    onClearSelection: () -> Unit,
    onCreateFolder: (String) -> Unit,
    onRename: (FileItem, String) -> Unit,
    onDelete: (Set<Long>) -> Unit,
    onOpenMovePicker: (FileItem) -> Unit,
    onOpenPickerFolder: (FileItem) -> Unit,
    onPickerBack: () -> Unit,
    onRetryPicker: () -> Unit,
    onClosePicker: () -> Unit,
    onConfirmMove: () -> Unit,
    onUpload: (com.betterclouddrive.mobile.core.platform.UploadPickResult) -> Unit,
    onDownload: (FileItem, String) -> Unit,
    onConsumeFeedback: () -> Unit,
    onConsumeTransferFeedback: () -> Unit,
    onClearPermissionError: () -> Unit,
    onManagePermissions: (FileItem) -> Unit,
    onRetryPermissions: () -> Unit,
    onSavePermission: (UpdateTeamFilePermissionRequest) -> Unit,
    onDeletePermission: (TeamFilePermission) -> Unit,
    onClosePermissions: () -> Unit,
    onClearPermissionsFeedback: () -> Unit,
    onOpenTransfers: () -> Unit,
) {
    val strings = LocalMobileStrings.current
    val snackbar = remember { SnackbarHostState() }
    var sortMenu by remember { mutableStateOf(false) }
    var createDialog by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<FileItem?>(null) }
    var deleteTargets by remember { mutableStateOf<List<FileItem>>(emptyList()) }
    var pendingDownload by remember { mutableStateOf<FileItem?>(null) }
    val uploadLauncher = documentPicker.rememberUploadLauncher(MAX_UPLOAD_SELECTION, onUpload)
    val downloadLauncher = documentPicker.rememberDownloadLauncher { target ->
        pendingDownload?.let { onDownload(it, target.handle) }
        pendingDownload = null
    }
    fun download(file: FileItem) {
        if (file.isFolder) return
        pendingDownload = file
        downloadLauncher(DownloadRequest(file.fileName, file.mimeType))
    }
    LaunchedEffect(state.selectedFile?.id) { state.selectedFile?.let(onRequestPermission) }
    BackHandler(enabled = actions.folderPicker != null || actions.selectedIds.isNotEmpty()) {
        if (actions.folderPicker != null) onClosePicker() else onClearSelection()
    }
    BackHandler(enabled = actions.folderPicker == null && actions.selectedIds.isEmpty(), onBack = onBack)
    LaunchedEffect(actions.feedback?.id) {
        actions.feedback?.let {
            if (!it.isError) { createDialog = false; renameTarget = null; deleteTargets = emptyList() }
            snackbar.showSnackbar(strings.resolve(it.message).orEmpty())
            onConsumeFeedback()
        }
    }
    LaunchedEffect(actions.transferFeedback?.id) {
        actions.transferFeedback?.let {
            val result = snackbar.showSnackbar(
                strings.resolve(it.message).orEmpty(),
                strings[MobileString.ViewTransfers].takeIf { _ -> it.canOpenTransfers },
            )
            onConsumeTransferFeedback()
            if (result == SnackbarResult.ActionPerformed) onOpenTransfers()
        }
    }
    LaunchedEffect(actions.permissionError) {
        actions.permissionError?.let {
            snackbar.showSnackbar(strings.resolve(it).orEmpty())
            onClearPermissionError()
        }
    }
    LaunchedEffect(actions.mutationError, actions.mutationErrorKind) {
        if (actions.mutationErrorKind == com.betterclouddrive.mobile.feature.files.FileMutationKind.MOVE ||
            actions.mutationErrorKind == com.betterclouddrive.mobile.feature.files.FileMutationKind.DELETE
        ) {
            actions.mutationError?.let { snackbar.showSnackbar(strings.resolve(it).orEmpty()) }
        }
    }
    Scaffold(
        modifier = Modifier.testTag("team-browser-screen"),
        snackbarHost = { SnackbarHost(snackbar, Modifier.testTag("team-feedback")) },
        topBar = { TopAppBar(
            title = { TeamBreadcrumb(state, onBreadcrumb) },
            navigationIcon = { IconButton(onBack, Modifier.testTag("team-browser-back")) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, strings[MobileString.BackToParent])
            } },
            actions = {
                IconButton(onOpenMembers, Modifier.testTag("team-members-open")) { Icon(Icons.Default.Groups, strings[MobileString.TeamMembers]) }
                IconButton(onOpenSearch, Modifier.testTag("team-search-open")) { Icon(Icons.Default.Search, strings[MobileString.TeamSearch]) }
                Box {
                    IconButton({ sortMenu = true }, Modifier.testTag("team-sort")) { Icon(Icons.AutoMirrored.Filled.Sort, strings[MobileString.Sort]) }
                    DropdownMenu(sortMenu, { sortMenu = false }) {
                        PersonalFileSort.entries.forEach { sort -> DropdownMenuItem(
                            text = { Text(strings[sort.stringKey()]) },
                            onClick = { onSort(sort, state.direction); sortMenu = false },
                            modifier = Modifier.testTag("team-sort-${sort.apiValue}"),
                        ) }
                        DropdownMenuItem(
                            text = { Text(strings[state.direction.stringKey()]) },
                            leadingIcon = { Icon(if (state.direction == SortDirection.ASCENDING) Icons.Default.ArrowUpward else Icons.Default.ArrowDownward, null) },
                            onClick = {
                                onSort(state.sort, if (state.direction == SortDirection.ASCENDING) SortDirection.DESCENDING else SortDirection.ASCENDING)
                                sortMenu = false
                            }, modifier = Modifier.testTag("team-sort-direction"),
                        )
                    }
                }
                IconButton({ onViewMode(if (state.viewMode == FileViewMode.LIST) FileViewMode.GRID else FileViewMode.LIST) }, Modifier.testTag("team-view-mode")) {
                    Icon(if (state.viewMode == FileViewMode.LIST) Icons.Default.GridView else Icons.AutoMirrored.Filled.List, strings[MobileString.SwitchView])
                }
                IconButton(onRefresh, Modifier.testTag("team-refresh")) { Icon(Icons.Default.Refresh, strings[MobileString.RefreshDirectory]) }
            },
        ) },
    ) { padding ->
        Row(Modifier.fillMaxSize().padding(padding)) {
            Box(Modifier.weight(if (wide && state.selectedFile != null) .62f else 1f).fillMaxSize()) {
                when {
                    state.workspaceLoading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                    state.workspaceError != null -> Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                        Text(strings.resolve(state.workspaceError).orEmpty(), color = MaterialTheme.colorScheme.error)
                        Button(onRetryWorkspace, Modifier.testTag("team-workspace-retry")) { Text(strings[MobileString.Retry]) }
                        OutlinedButton(onBackToTeams) { Text(strings[MobileString.BackToTeams]) }
                    }
                    else -> Column(Modifier.fillMaxSize()) {
                        if (actions.selectedIds.isNotEmpty()) Row(
                            Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(strings.format(MobileString.SelectedCount, actions.selectedIds.size), Modifier.weight(1f))
                            OutlinedButton(onClearSelection) { Text(strings[MobileString.Cancel]) }
                            Button({ deleteTargets = state.current.items.filter { it.id in actions.selectedIds } }, Modifier.testTag("team-delete-selected")) {
                                Icon(Icons.Default.Delete, null); Text(strings[MobileString.Delete])
                            }
                        } else Row(
                            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.End,
                        ) {
                            if (actions.permissionLoading) Text(strings[MobileString.TeamPermissionLoading])
                            if (actions.directoryPermission?.canWrite == true) {
                                OutlinedButton({ createDialog = true }, Modifier.testTag("team-create-folder")) {
                                    Icon(Icons.Default.Add, null); Text(strings[MobileString.CreateFolder])
                                }
                                Spacer(Modifier.width(8.dp))
                                Button(uploadLauncher, Modifier.testTag("team-upload")) {
                                    Icon(Icons.Default.Upload, null); Text(strings[MobileString.Upload])
                                }
                            }
                        }
                        TeamDirectoryContent(
                            state, actions, onOpenFolder, onSelect, onLoadMore, onRequestPermission,
                            onToggleSelection, { renameTarget = it }, onOpenMovePicker,
                            { deleteTargets = listOf(it) }, ::download, onManagePermissions,
                        )
                    }
                }
            }
            if (wide && state.selectedFile != null) {
                HorizontalDivider(Modifier.width(1.dp).fillMaxSize())
                TeamFileDetails(
                    state.selectedFile, actions, onPreview, onRequestPermission,
                    { renameTarget = it }, onOpenMovePicker, { deleteTargets = listOf(it) }, ::download,
                    onManagePermissions,
                    Modifier.weight(.38f).fillMaxSize(),
                )
            }
        }
    }
    if (!wide && state.selectedFile != null) ModalBottomSheet(onDismissRequest = { onSelect(null) }) {
        TeamFileDetails(
            state.selectedFile, actions, onPreview, onRequestPermission,
            { renameTarget = it }, onOpenMovePicker, { deleteTargets = listOf(it) }, ::download,
            onManagePermissions,
            Modifier.fillMaxWidth(),
        )
    }
    if (createDialog) TeamNameDialog(
        strings[MobileString.CreateFolder], "", actions.mutation == com.betterclouddrive.mobile.feature.files.FileMutationKind.CREATE_FOLDER,
        actions.mutationError.takeIf { actions.mutationErrorKind == com.betterclouddrive.mobile.feature.files.FileMutationKind.CREATE_FOLDER },
        { createDialog = false }, onCreateFolder,
    )
    renameTarget?.let { file -> TeamNameDialog(
        strings[MobileString.Rename], file.fileName,
        actions.mutation == com.betterclouddrive.mobile.feature.files.FileMutationKind.RENAME,
        actions.mutationError.takeIf { actions.mutationErrorKind == com.betterclouddrive.mobile.feature.files.FileMutationKind.RENAME },
        { renameTarget = null }, { onRename(file, it) },
    ) }
    if (deleteTargets.isNotEmpty()) AlertDialog(
        onDismissRequest = { if (actions.mutation == null) deleteTargets = emptyList() },
        title = { Text(if (deleteTargets.size == 1) strings.format(MobileString.DeleteSingleTitle, deleteTargets.single().fileName)
            else strings[MobileString.DeleteMultipleTitle]) },
        text = { Text(if (deleteTargets.size == 1) strings[MobileString.DeleteSingleMessage]
            else strings.format(MobileString.DeleteMultipleMessage, deleteTargets.size)) },
        confirmButton = { Button(
            { onDelete(deleteTargets.map(FileItem::id).toSet()) }, enabled = actions.mutation == null,
            modifier = Modifier.testTag("team-delete-confirm"),
        ) { Text(strings[MobileString.Delete]) } },
        dismissButton = { OutlinedButton({ deleteTargets = emptyList() }, enabled = actions.mutation == null) { Text(strings[MobileString.Cancel]) } },
    )
    actions.folderPicker?.let { TeamMovePicker(
        it, wide, actions.mutation != null, onOpenPickerFolder, onPickerBack,
        onRetryPicker, onClosePicker, onConfirmMove,
    ) }
    TeamPermissionsOverlay(
        state = permissions,
        wide = wide,
        actorUserId = actorUserId,
        actorRole = state.team?.role ?: TeamRole.VIEWER,
        onDismiss = onClosePermissions,
        onRetry = onRetryPermissions,
        onSave = onSavePermission,
        onDelete = onDeletePermission,
        onClearFeedback = onClearPermissionsFeedback,
    )
}

@Composable
private fun TeamDirectoryContent(
    state: TeamBrowserState,
    actions: TeamFileActionsState,
    onOpenFolder: (FileItem) -> Unit,
    onSelect: (FileItem) -> Unit,
    onLoadMore: () -> Unit,
    onRequestPermission: (FileItem) -> Unit,
    onToggleSelection: (FileItem) -> Unit,
    onRename: (FileItem) -> Unit,
    onMove: (FileItem) -> Unit,
    onDelete: (FileItem) -> Unit,
    onDownload: (FileItem) -> Unit,
    onManagePermissions: (FileItem) -> Unit,
) {
    val strings = LocalMobileStrings.current
    val current = state.current
    when {
        current.initialLoading && current.items.isEmpty() -> Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }
        current.error != null && current.items.isEmpty() -> TeamRetryPane(strings.resolve(current.error).orEmpty(), onLoadMore)
        current.hasLoaded && current.items.isEmpty() -> TeamEmptyPane(MobileString.TeamDirectoryEmpty, MobileString.TeamDirectoryEmptyDetail)
        state.viewMode == FileViewMode.LIST -> LazyColumn(Modifier.fillMaxSize().testTag("team-file-list")) {
            items(current.items, key = FileItem::id) { file ->
                TeamFileRow(
                    file, file.id in actions.selectedIds, actions.itemPermissions[file.id],
                    { if (actions.selectedIds.isNotEmpty()) onToggleSelection(file) else if (file.isFolder) onOpenFolder(file) else onSelect(file) },
                    { onToggleSelection(file) }, { onRequestPermission(file) }, onRename, onMove, onDelete, onDownload,
                    onManagePermissions,
                )
                if (file.id == current.items.lastOrNull()?.id && current.hasMore) LaunchedEffect(file.id) { onLoadMore() }
            }
            teamLoadMoreFooter(current.loadingMore, current.loadMoreError, onLoadMore)
        }
        else -> LazyVerticalGrid(GridCells.Adaptive(150.dp), Modifier.fillMaxSize().testTag("team-file-grid")) {
            items(current.items, key = FileItem::id) { file ->
                TeamFileCard(
                    file, file.id in actions.selectedIds, actions.itemPermissions[file.id],
                    { if (actions.selectedIds.isNotEmpty()) onToggleSelection(file) else if (file.isFolder) onOpenFolder(file) else onSelect(file) },
                    { onToggleSelection(file) }, { onRequestPermission(file) }, onRename, onMove, onDelete, onDownload,
                    onManagePermissions,
                )
                if (file.id == current.items.lastOrNull()?.id && current.hasMore) LaunchedEffect(file.id) { onLoadMore() }
            }
            if (current.loadingMore) item { CircularProgressIndicator(Modifier.padding(16.dp)) }
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.teamLoadMoreFooter(
    loading: Boolean, error: UiMessage?, retry: () -> Unit,
) {
    if (loading) item { CircularProgressIndicator(Modifier.padding(16.dp)) }
    if (error != null) item { OutlinedButton(retry, Modifier.padding(16.dp).testTag("team-load-more-retry")) {
        Text(LocalMobileStrings.current[MobileString.RetryLoadMore])
    } }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TeamFileRow(
    file: FileItem, selected: Boolean, permission: com.betterclouddrive.mobile.core.model.TeamEffectivePermission?,
    onClick: () -> Unit, onLongClick: () -> Unit, onRequestPermission: () -> Unit,
    onRename: (FileItem) -> Unit, onMove: (FileItem) -> Unit, onDelete: (FileItem) -> Unit, onDownload: (FileItem) -> Unit,
    onManagePermissions: (FileItem) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 16.dp, vertical = 12.dp).testTag("team-file-${file.id}"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(if (selected) Icons.Default.Delete else if (file.isFolder) Icons.Default.Folder else Icons.Default.Description, null)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) { Text(file.fileName, maxLines = 1, overflow = TextOverflow.Ellipsis); Text(formatFileSize(file.fileSize)) }
        TeamFileActionMenu(file, permission, onRequestPermission, onRename, onMove, onDelete, onDownload, onManagePermissions)
    }
    HorizontalDivider()
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TeamFileCard(
    file: FileItem, selected: Boolean, permission: com.betterclouddrive.mobile.core.model.TeamEffectivePermission?,
    onClick: () -> Unit, onLongClick: () -> Unit, onRequestPermission: () -> Unit,
    onRename: (FileItem) -> Unit, onMove: (FileItem) -> Unit, onDelete: (FileItem) -> Unit, onDownload: (FileItem) -> Unit,
    onManagePermissions: (FileItem) -> Unit,
) {
    Card(Modifier.padding(8.dp).combinedClickable(onClick = onClick, onLongClick = onLongClick).testTag("team-grid-${file.id}")) {
        Column(Modifier.fillMaxWidth().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(if (selected) Icons.Default.Delete else if (file.isFolder) Icons.Default.Folder else Icons.Default.Description, null)
            Text(file.fileName, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(formatFileSize(file.fileSize), style = MaterialTheme.typography.labelSmall)
            TeamFileActionMenu(file, permission, onRequestPermission, onRename, onMove, onDelete, onDownload, onManagePermissions)
        }
    }
}

@Composable
private fun TeamFileDetails(
    file: FileItem?, actions: TeamFileActionsState, onPreview: (FileItem) -> Unit,
    onRequestPermission: (FileItem) -> Unit, onRename: (FileItem) -> Unit,
    onMove: (FileItem) -> Unit, onDelete: (FileItem) -> Unit, onDownload: (FileItem) -> Unit,
    onManagePermissions: (FileItem) -> Unit,
    modifier: Modifier,
) {
    val strings = LocalMobileStrings.current
    if (file == null) return
    Column(modifier.padding(20.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(file.fileName, style = MaterialTheme.typography.titleLarge)
        TeamDetailRow(strings[MobileString.Type], file.mimeType ?: file.fileType)
        TeamDetailRow(strings[MobileString.Size], formatFileSize(file.fileSize))
        TeamDetailRow(strings[MobileString.CreatedAt], formatDate(file.createdAt))
        TeamDetailRow(strings[MobileString.UpdatedAt], formatDate(file.updatedAt))
        TeamDetailRow("MD5", file.md5Hash ?: "—")
        actions.itemPermissions[file.id]?.let { permission ->
            TeamDetailRow(strings[MobileString.EffectivePermission], listOfNotNull(
                strings[MobileString.PermissionRead].takeIf { permission.canRead },
                strings[MobileString.PermissionWrite].takeIf { permission.canWrite },
                strings[MobileString.PermissionShare].takeIf { permission.canShare },
                strings[MobileString.PermissionDelete].takeIf { permission.canDelete },
                strings[MobileString.PermissionManage].takeIf { permission.canManage },
            ).ifEmpty { listOf("—") }.joinToString(" · "))
            permission.sourceChain.forEach { source ->
                Text(if (source.type == "BASE_ROLE") strings.format(MobileString.PermissionBaseRole, source.subjectRole ?: "—")
                else if (source.inherited) strings.format(MobileString.PermissionInherited, source.fileName ?: "—")
                else strings[MobileString.PermissionCurrentOverride], style = MaterialTheme.typography.labelSmall)
            }
            if (permission.canManage) OutlinedButton({ onManagePermissions(file) }, Modifier.testTag("team-manage-permissions-${file.id}")) {
                Text(strings[MobileString.ManagePermissions])
            }
        }
        if (!file.isFolder && file.previewKind() != PreviewKind.UNSUPPORTED) Button(
            onClick = { onPreview(file) }, modifier = Modifier.testTag("team-preview-${file.id}"),
        ) { Icon(Icons.Default.Visibility, null); Text(strings[MobileString.Preview]) }
        if (!file.isFolder) OutlinedButton({ onDownload(file) }, Modifier.testTag("team-download-${file.id}")) {
            Icon(Icons.Default.Download, null); Text(strings[MobileString.Download])
        }
        TeamFileActionMenu(file, actions.itemPermissions[file.id], { onRequestPermission(file) }, onRename, onMove, onDelete, onDownload, onManagePermissions)
    }
}

@Composable
private fun TeamFileActionMenu(
    file: FileItem,
    permission: com.betterclouddrive.mobile.core.model.TeamEffectivePermission?,
    onRequestPermission: () -> Unit,
    onRename: (FileItem) -> Unit,
    onMove: (FileItem) -> Unit,
    onDelete: (FileItem) -> Unit,
    onDownload: (FileItem) -> Unit,
    onManagePermissions: (FileItem) -> Unit,
) {
    val strings = LocalMobileStrings.current
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton({ onRequestPermission(); expanded = true }, Modifier.testTag("team-more-${file.id}")) {
            Icon(Icons.Default.MoreVert, strings[MobileString.MoreActions])
        }
        DropdownMenu(expanded, { expanded = false }) {
            if (!file.isFolder) DropdownMenuItem(
                text = { Text(strings[MobileString.Download]) }, leadingIcon = { Icon(Icons.Default.Download, null) },
                onClick = { expanded = false; onDownload(file) },
            )
            if (permission?.canWrite == true) {
                DropdownMenuItem(
                    text = { Text(strings[MobileString.Rename]) }, leadingIcon = { Icon(Icons.Default.Edit, null) },
                    onClick = { expanded = false; onRename(file) },
                )
                DropdownMenuItem(
                    text = { Text(strings[MobileString.Move]) }, leadingIcon = { Icon(Icons.Default.DriveFileMove, null) },
                    onClick = { expanded = false; onMove(file) },
                )
            }
            if (permission?.canDelete == true) DropdownMenuItem(
                text = { Text(strings[MobileString.Delete]) }, leadingIcon = { Icon(Icons.Default.Delete, null) },
                onClick = { expanded = false; onDelete(file) },
            )
            if (permission?.canManage == true) DropdownMenuItem(
                text = { Text(strings[MobileString.ManagePermissions]) },
                onClick = { expanded = false; onManagePermissions(file) },
            )
        }
    }
}

@Composable
private fun TeamNameDialog(
    title: String, initial: String, busy: Boolean, error: UiMessage?,
    onDismiss: () -> Unit, onConfirm: (String) -> Unit,
) {
    val strings = LocalMobileStrings.current
    var value by remember(initial) { mutableStateOf(initial) }
    val normalized = value.trim()
    val localError = when {
        normalized.isEmpty() -> MobileString.NameRequired
        normalized.length > 255 -> MobileString.NameTooLong
        else -> null
    }
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() }, title = { Text(title) },
        text = { OutlinedTextField(
            value, { value = it }, enabled = !busy, singleLine = true,
            label = { Text(strings[MobileString.NewName]) }, isError = error != null || localError != null,
            supportingText = {
                if (error != null) Text(strings.resolve(error).orEmpty())
                else localError?.let { Text(strings[it]) }
            },
            modifier = Modifier.fillMaxWidth().testTag("team-name-input"),
        ) },
        confirmButton = { Button({ onConfirm(value) }, enabled = !busy && localError == null, modifier = Modifier.testTag("team-name-confirm")) {
            Text(strings[MobileString.Confirm])
        } },
        dismissButton = { OutlinedButton(onDismiss, enabled = !busy) { Text(strings[MobileString.Cancel]) } },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TeamMovePicker(
    picker: TeamFolderPickerState, wide: Boolean, busy: Boolean,
    onOpenFolder: (FileItem) -> Unit, onBack: () -> Unit, onRetry: () -> Unit,
    onDismiss: () -> Unit, onConfirm: () -> Unit,
) {
    val strings = LocalMobileStrings.current
    val content: @Composable () -> Unit = {
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(strings[MobileString.ChooseDestination], style = MaterialTheme.typography.titleLarge)
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onBack, enabled = picker.breadcrumb.size > 1) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null) }
                Text(picker.breadcrumb.lastOrNull()?.name?.ifBlank { strings[MobileString.DestinationRoot] }.orEmpty())
            }
            when {
                picker.loading -> CircularProgressIndicator()
                picker.error != null -> OutlinedButton(onRetry) { Text(strings[MobileString.Retry]) }
                picker.folders.isEmpty() -> Text(strings[MobileString.DestinationEmpty])
                else -> LazyColumn(Modifier.fillMaxWidth().height(320.dp)) {
                    items(picker.folders, key = FileItem::id) { folder ->
                        Row(Modifier.fillMaxWidth().clickable { onOpenFolder(folder) }.padding(12.dp)) {
                            Icon(Icons.Default.Folder, null); Spacer(Modifier.width(8.dp)); Text(folder.fileName)
                        }
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                OutlinedButton(onDismiss, enabled = !busy) { Text(strings[MobileString.Cancel]) }
                Spacer(Modifier.width(8.dp))
                Button(onConfirm, enabled = !busy && picker.canConfirm, modifier = Modifier.testTag("team-move-confirm")) {
                    Text(strings[MobileString.Move])
                }
            }
        }
    }
    if (wide) AlertDialog(onDismissRequest = onDismiss, confirmButton = {}, text = content)
    else ModalBottomSheet(onDismissRequest = onDismiss) { content() }
}

@Composable
private fun TeamDetailRow(label: String, value: String) {
    Column { Text(label, style = MaterialTheme.typography.labelMedium); Text(value) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TeamSearchScreen(
    state: TeamSearchState,
    documentPicker: DocumentPicker,
    onBack: () -> Unit,
    onQuery: (String) -> Unit,
    onSubmit: () -> Unit,
    onRetry: () -> Unit,
    onLoadMore: () -> Unit,
    onOpenFolder: (FileItem) -> Unit,
    onOpenLocation: (FileItem) -> Unit,
    onPreview: (FileItem) -> Unit,
    onDownload: (FileItem, String) -> Unit,
) {
    val strings = LocalMobileStrings.current
    var selectedUnsupported by remember { mutableStateOf<FileItem?>(null) }
    var pendingDownload by remember { mutableStateOf<FileItem?>(null) }
    val downloadLauncher = documentPicker.rememberDownloadLauncher { target ->
        pendingDownload?.let { onDownload(it, target.handle) }
        pendingDownload = null
    }
    fun download(file: FileItem) {
        if (file.isFolder) return
        pendingDownload = file
        downloadLauncher(DownloadRequest(file.fileName, file.mimeType))
    }
    BackHandler(onBack = onBack)
    Scaffold(
        modifier = Modifier.testTag("team-search-screen"),
        topBar = { TopAppBar(
            title = { OutlinedTextField(
                state.query, onQuery, singleLine = true, placeholder = { Text(strings[MobileString.TeamSearchHint]) },
                modifier = Modifier.fillMaxWidth().testTag("team-search-query"),
            ) },
            navigationIcon = { IconButton(onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, strings[MobileString.BackToParent]) } },
            actions = { IconButton(onSubmit) { Icon(Icons.Default.Search, strings[MobileString.Search]) } },
        ) },
    ) { padding -> Box(Modifier.fillMaxSize().padding(padding)) {
        when {
            state.submittedQuery.isEmpty() && !state.searching -> TeamEmptyPane(MobileString.TeamSearch, MobileString.TeamSearchInitial)
            state.searching && state.items.isEmpty() -> CircularProgressIndicator(Modifier.align(Alignment.Center))
            state.error != null && state.items.isEmpty() -> TeamRetryPane(strings.resolve(state.error).orEmpty(), onRetry)
            state.items.isEmpty() -> TeamEmptyPane(
                MobileString.TeamSearchEmpty,
                MobileString.SearchNoResults,
                state.submittedQuery,
            )
            else -> LazyColumn(Modifier.fillMaxSize().testTag("team-search-results")) {
                items(state.items, key = FileItem::id) { file ->
                    Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
                        TeamFileRow(
                            file, false, null,
                            {
                                if (file.isFolder) onOpenFolder(file)
                                else if (file.previewKind() != PreviewKind.UNSUPPORTED) onPreview(file)
                                else selectedUnsupported = file
                            }, {}, {}, {}, {}, {}, ::download, {},
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton({ onOpenLocation(file) }, Modifier.testTag("team-open-location-${file.id}")) { Text(strings[MobileString.OpenLocation]) }
                            if (!file.isFolder && file.previewKind() != PreviewKind.UNSUPPORTED) OutlinedButton({ onPreview(file) }) { Text(strings[MobileString.Preview]) }
                            if (!file.isFolder) OutlinedButton({ download(file) }) { Text(strings[MobileString.Download]) }
                        }
                    }
                    if (file.id == state.items.lastOrNull()?.id && state.hasMore) LaunchedEffect(file.id) { onLoadMore() }
                }
                if (state.loadingMore) item { CircularProgressIndicator(Modifier.padding(16.dp)) }
                state.loadMoreError?.let { error -> item {
                    OutlinedButton(onLoadMore, Modifier.padding(16.dp).testTag("team-search-load-more-retry")) {
                        Text(strings.resolve(error) ?: strings[MobileString.RetryLoadMore])
                    }
                } }
            }
        }
    } }
    selectedUnsupported?.let { file -> ModalBottomSheet(onDismissRequest = { selectedUnsupported = null }) {
        TeamFileDetails(
            file, TeamFileActionsState(), onPreview, {}, {}, {}, {}, ::download, {}, Modifier.fillMaxWidth(),
        )
    } }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TeamPreviewScreen(
    state: TeamPreviewState,
    documentPicker: DocumentPicker,
    officeRenderer: OfficeRenderer,
    mediaRenderer: MediaPreviewRenderer,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    onPositionChanged: (Long) -> Unit,
    onRendererError: (UiMessage) -> Unit,
    onDownload: (FileItem, String) -> Unit,
) {
    val strings = LocalMobileStrings.current
    var officeStatus by remember(state.teamId, state.fileId) { mutableStateOf<UiMessage?>(null) }
    var pendingDownload by remember { mutableStateOf<FileItem?>(null) }
    val downloadLauncher = documentPicker.rememberDownloadLauncher { target ->
        pendingDownload?.let { onDownload(it, target.handle) }
        pendingDownload = null
    }
    BackHandler(onBack = onBack)
    Scaffold(
        modifier = Modifier.testTag("team-preview-screen"),
        topBar = { TopAppBar(
            title = { Column { Text(state.file?.fileName ?: strings[MobileString.Preview]); Text(strings[MobileString.TeamReadOnly], style = MaterialTheme.typography.labelSmall) } },
            navigationIcon = { IconButton(onBack, Modifier.testTag("team-preview-back")) { Icon(Icons.AutoMirrored.Filled.ArrowBack, strings[MobileString.BackToParent]) } },
            actions = { state.file?.takeIf { !it.isFolder }?.let { file -> IconButton({
                pendingDownload = file; downloadLauncher(DownloadRequest(file.fileName, file.mimeType))
            }, Modifier.testTag("team-preview-download")) { Icon(Icons.Default.Download, strings[MobileString.Download]) } } },
        ) },
    ) { padding -> Box(Modifier.fillMaxSize().padding(padding), Alignment.Center) {
        when {
            state.loading -> CircularProgressIndicator()
            state.error != null -> TeamRetryPane(strings.resolve(state.error).orEmpty(), onRetry)
            state.kind == PreviewKind.TEXT -> Column(Modifier.fillMaxSize().padding(16.dp)) {
                if (state.textTruncated) Text(strings[MobileString.PreviewTruncated], color = MaterialTheme.colorScheme.secondary)
                if (state.text.isNullOrEmpty()) Box(Modifier.fillMaxSize(), Alignment.Center) { Text(strings[MobileString.PreviewEmptyText]) }
                else SelectionContainer { Text(state.text, fontFamily = FontFamily.Monospace, modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).testTag("team-text-preview")) }
            }
            state.kind == PreviewKind.OFFICE && state.office != null -> Column(Modifier.fillMaxSize()) {
                officeStatus?.let { Text(strings.resolve(it).orEmpty(), Modifier.padding(8.dp).testTag("team-office-status")) }
                officeRenderer.Render(state.office, Modifier.fillMaxSize().weight(1f), onStatus = { officeStatus = it })
            }
            state.kind in setOf(PreviewKind.IMAGE, PreviewKind.VIDEO) && state.mediaUrl != null -> mediaRenderer.Render(
                MediaPreviewRequest(
                    kind = requireNotNull(state.kind), url = state.mediaUrl,
                    fileKey = "team-${state.teamId}-${state.fileId}-${state.file?.md5Hash ?: state.file?.versionCount}",
                    initialPositionMillis = state.playbackPositionMillis,
                ), Modifier.fillMaxSize().testTag("team-media-preview"), onPositionChanged, onRendererError,
            )
            else -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Default.Description, null); Text(strings[MobileString.PreviewUnsupported]); Text(strings[MobileString.TeamReadOnly])
            }
        }
    } }
}

@Composable
private fun TeamBreadcrumb(state: TeamBrowserState, onBreadcrumb: (Long?) -> Unit) {
    val strings = LocalMobileStrings.current
    Row(Modifier.horizontalScroll(rememberScrollState()).testTag("team-breadcrumb")) {
        state.breadcrumb.forEachIndexed { index, entry ->
            if (index > 0) Text(" / ")
            Text(
                entry.name.ifEmpty { state.team?.name ?: strings[MobileString.TeamRoot] },
                color = if (index == state.breadcrumb.lastIndex) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.primary,
                modifier = Modifier.let { if (index == state.breadcrumb.lastIndex) it else it.clickable { onBreadcrumb(entry.id) } }
                    .testTag("team-breadcrumb-${entry.id ?: "root"}"),
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun TeamRetryPane(message: String, retry: () -> Unit) {
    val strings = LocalMobileStrings.current
    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Text(message, color = MaterialTheme.colorScheme.error)
        Button(retry, Modifier.testTag("team-retry")) { Text(strings[MobileString.Retry]) }
    }
}

@Composable
private fun TeamEmptyPane(title: MobileString, detail: MobileString, vararg detailArguments: Any?) {
    val strings = LocalMobileStrings.current
    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Icon(Icons.Default.Groups, null)
        Text(strings[title], style = MaterialTheme.typography.titleMedium)
        Text(strings.format(detail, *detailArguments))
    }
}

private fun TeamRole.stringKey(): MobileString = when (this) {
    TeamRole.OWNER -> MobileString.TeamRoleOwner
    TeamRole.ADMIN -> MobileString.TeamRoleAdmin
    TeamRole.EDITOR -> MobileString.TeamRoleEditor
    TeamRole.VIEWER -> MobileString.TeamRoleViewer
}

private fun PersonalFileSort.stringKey(): MobileString = when (this) {
    PersonalFileSort.NAME -> MobileString.SortName
    PersonalFileSort.UPDATED_AT -> MobileString.SortUpdated
    PersonalFileSort.SIZE -> MobileString.SortSize
}

private fun SortDirection.stringKey(): MobileString = when (this) {
    SortDirection.ASCENDING -> MobileString.SortAscending
    SortDirection.DESCENDING -> MobileString.SortDescending
}
