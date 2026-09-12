package com.betterclouddrive.mobile.app

import com.betterclouddrive.mobile.feature.files.PersonalFilesState
import com.betterclouddrive.mobile.feature.files.DirectoryPageState
import com.betterclouddrive.mobile.feature.files.FileMutationKind
import com.betterclouddrive.mobile.feature.files.FolderPickerState
import com.betterclouddrive.mobile.feature.organization.FavoritesState

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DriveFileMove
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Label
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.backhandler.BackHandler
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.betterclouddrive.mobile.core.model.FileItem
import com.betterclouddrive.mobile.core.localization.LocalMobileStrings
import com.betterclouddrive.mobile.core.localization.MobileString
import com.betterclouddrive.mobile.core.localization.UiMessage
import com.betterclouddrive.mobile.core.platform.DocumentPicker
import com.betterclouddrive.mobile.core.platform.DownloadRequest
import com.betterclouddrive.mobile.core.platform.UploadPickResult
import com.betterclouddrive.mobile.feature.files.FileViewMode
import com.betterclouddrive.mobile.feature.transfers.MAX_UPLOAD_SELECTION
import kotlinx.coroutines.flow.distinctUntilChanged

@OptIn(ExperimentalMaterial3Api::class, ExperimentalComposeUiApi::class)
@Composable
fun PersonalSpaceScreen(
    state: PersonalFilesState,
    favorites: FavoritesState,
    documentPicker: DocumentPicker,
    onUpload: (Long?, UploadPickResult) -> Unit,
    onDownload: (FileItem, String) -> Unit,
    onOpenFolder: (FileItem) -> Unit,
    onSelectFile: (FileItem?) -> Unit,
    onLoadMore: () -> Unit,
    onScrollAnchor: (Long?) -> Unit,
    onRetry: () -> Unit,
    onGoRoot: () -> Unit,
    onToggleSelection: (Long) -> Unit,
    onClearSelection: () -> Unit,
    onCreateFolder: (String) -> Unit,
    onRename: (FileItem, String) -> Unit,
    onDelete: (Set<Long>) -> Unit,
    onOpenPicker: (FileItem, FileMutationKind) -> Unit,
    onOpenPickerFolder: (FileItem) -> Unit,
    onPickerBack: () -> Unit,
    onRetryPicker: () -> Unit,
    onClosePicker: () -> Unit,
    onConfirmPicker: () -> Unit,
    onConsumeFeedback: () -> Unit,
    onConsumeTransferFeedback: () -> Unit,
    onOpenTransfers: () -> Unit,
    onPreview: (FileItem) -> Unit,
    onLoadFavoriteStatus: (Long) -> Unit,
    onToggleFavorite: (FileItem) -> Unit,
    onManageTags: (FileItem) -> Unit,
    onCreateShare: (FileItem) -> Unit,
    onOpenVersions: (FileItem) -> Unit,
) {
    val strings = LocalMobileStrings.current
    val snackbar = remember { SnackbarHostState() }
    var createDialog by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<FileItem?>(null) }
    var deleteTargets by remember { mutableStateOf<List<FileItem>>(emptyList()) }
    var uploadParentId by remember { mutableStateOf<Long?>(null) }
    var pendingDownload by remember { mutableStateOf<FileItem?>(null) }
    val uploadLauncher = documentPicker.rememberUploadLauncher(MAX_UPLOAD_SELECTION) { result ->
        onUpload(uploadParentId, result)
    }
    val downloadLauncher = documentPicker.rememberDownloadLauncher { target ->
        pendingDownload?.let { onDownload(it, target.handle) }
        pendingDownload = null
    }
    fun launchUpload() {
        uploadParentId = state.current.folderId
        uploadLauncher()
    }
    fun launchDownload(file: FileItem) {
        pendingDownload = file
        downloadLauncher(DownloadRequest(file.fileName, file.mimeType))
    }
    BackHandler(enabled = state.folderPicker != null || state.selectedIds.isNotEmpty()) {
        if (state.folderPicker != null) onClosePicker() else onClearSelection()
    }
    LaunchedEffect(state.feedback?.id) {
        state.feedback?.let { feedback ->
            if (!feedback.isError) {
                createDialog = false
                renameTarget = null
                deleteTargets = emptyList()
            }
            snackbar.showSnackbar(strings.resolve(feedback.message).orEmpty())
            onConsumeFeedback()
        }
    }
    LaunchedEffect(state.transferFeedback?.id) {
        state.transferFeedback?.let { feedback ->
            val result = snackbar.showSnackbar(
                message = strings.resolve(feedback.message).orEmpty(),
                actionLabel = strings[MobileString.ViewTransfers].takeIf { feedback.canOpenTransfers },
            )
            onConsumeTransferFeedback()
            if (result == SnackbarResult.ActionPerformed) onOpenTransfers()
        }
    }
    BoxWithConstraints(Modifier.fillMaxSize().testTag("personal-content")) {
        val wide = maxWidth >= 600.dp
        if (wide) {
            Row(Modifier.fillMaxSize()) {
                PersonalDirectoryContent(
                    state, favorites, ::launchUpload, ::launchDownload, onOpenFolder, onSelectFile,
                    onLoadMore, onScrollAnchor, onRetry, onGoRoot,
                    onToggleSelection, onClearSelection, { createDialog = true },
                    { renameTarget = it }, { deleteTargets = listOf(it) }, onOpenPicker,
                    { deleteTargets = state.current.items.filter { item -> item.id in state.selectedIds } },
                    onPreview, onLoadFavoriteStatus, onToggleFavorite, onManageTags, onCreateShare, onOpenVersions,
                    Modifier.weight(0.58f),
                )
                VerticalDivider(Modifier.fillMaxHeight())
                Box(Modifier.weight(0.42f).fillMaxHeight().testTag("personal-detail-pane")) {
                    state.selectedFile?.let { file ->
                        FileDetailContent(
                            file, favorites, ::launchDownload, onPreview,
                            onLoadFavoriteStatus, onToggleFavorite, onManageTags, onCreateShare, onOpenVersions,
                        )
                    } ?: EmptyDetail()
                }
            }
        } else {
            PersonalDirectoryContent(
                state, favorites, ::launchUpload, ::launchDownload, onOpenFolder, onSelectFile,
                onLoadMore, onScrollAnchor, onRetry, onGoRoot,
                onToggleSelection, onClearSelection, { createDialog = true },
                { renameTarget = it }, { deleteTargets = listOf(it) }, onOpenPicker,
                { deleteTargets = state.current.items.filter { item -> item.id in state.selectedIds } },
                onPreview, onLoadFavoriteStatus, onToggleFavorite, onManageTags, onCreateShare, onOpenVersions,
                Modifier.fillMaxSize(),
            )
            state.selectedFile?.let { file ->
                ModalBottomSheet(
                    onDismissRequest = { onSelectFile(null) },
                    modifier = Modifier.testTag("personal-detail-sheet"),
                ) {
                    FileDetailContent(
                        file, favorites, ::launchDownload, onPreview,
                        onLoadFavoriteStatus, onToggleFavorite, onManageTags, onCreateShare, onOpenVersions,
                    )
                }
            }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).testTag("personal-feedback"))
        state.folderPicker?.let { picker ->
            DestinationPicker(
                picker = picker,
                wide = wide,
                onOpenFolder = onOpenPickerFolder,
                onBack = onPickerBack,
                onRetry = onRetryPicker,
                onDismiss = onClosePicker,
                onConfirm = onConfirmPicker,
                busy = state.mutation != null,
                operationError = state.mutationError.takeIf {
                    state.mutationErrorKind == FileMutationKind.MOVE || state.mutationErrorKind == FileMutationKind.COPY
                },
            )
        }
    }
    if (createDialog) NameDialog(
        title = strings[MobileString.CreateFolder], label = strings[MobileString.FolderName], initial = "",
        busy = state.mutation == FileMutationKind.CREATE_FOLDER,
        serverError = state.mutationError.takeIf { state.mutationErrorKind == FileMutationKind.CREATE_FOLDER },
        onDismiss = { if (state.mutation == null) createDialog = false }, onConfirm = onCreateFolder,
    )
    renameTarget?.let { file -> NameDialog(
        title = strings[MobileString.Rename], label = strings[MobileString.NewName], initial = file.fileName,
        busy = state.mutation == FileMutationKind.RENAME,
        serverError = state.mutationError.takeIf { state.mutationErrorKind == FileMutationKind.RENAME },
        onDismiss = { if (state.mutation == null) renameTarget = null }, onConfirm = { onRename(file, it) },
    ) }
    if (deleteTargets.isNotEmpty()) DeleteDialog(
        targets = deleteTargets,
        busy = state.mutation == FileMutationKind.DELETE,
        onDismiss = { if (state.mutation == null) deleteTargets = emptyList() },
        onConfirm = { onDelete(deleteTargets.map(FileItem::id).toSet()) },
    )
}

@Composable
private fun PersonalDirectoryContent(
    state: PersonalFilesState,
    favorites: FavoritesState,
    onUpload: () -> Unit,
    onDownload: (FileItem) -> Unit,
    onOpenFolder: (FileItem) -> Unit,
    onSelectFile: (FileItem?) -> Unit,
    onLoadMore: () -> Unit,
    onScrollAnchor: (Long?) -> Unit,
    onRetry: () -> Unit,
    onGoRoot: () -> Unit,
    onToggleSelection: (Long) -> Unit,
    onClearSelection: () -> Unit,
    onCreateFolder: () -> Unit,
    onRename: (FileItem) -> Unit,
    onDeleteOne: (FileItem) -> Unit,
    onOpenPicker: (FileItem, FileMutationKind) -> Unit,
    onDeleteSelected: () -> Unit,
    onPreview: (FileItem) -> Unit,
    onLoadFavoriteStatus: (Long) -> Unit,
    onToggleFavorite: (FileItem) -> Unit,
    onManageTags: (FileItem) -> Unit,
    onCreateShare: (FileItem) -> Unit,
    onOpenVersions: (FileItem) -> Unit,
    modifier: Modifier,
) {
    val strings = LocalMobileStrings.current
    val directory = state.current
    Column(modifier.padding(horizontal = 16.dp).testTag("personal-folder-${directory.folderId ?: "root"}")) {
        if (state.selectedIds.isNotEmpty()) {
            SelectionBar(state.selectedIds.size, onClearSelection, onDeleteSelected)
        } else {
            Row(
                Modifier.fillMaxWidth().padding(top = 8.dp),
                horizontalArrangement = Arrangement.End,
            ) {
                OutlinedButton(onClick = onCreateFolder, modifier = Modifier.testTag("personal-create-folder")) {
                    Icon(Icons.Default.Add, contentDescription = null)
                    Text(strings[MobileString.CreateFolder])
                }
            }
        }
        if (directory.refreshing) {
            LinearProgressIndicator(Modifier.fillMaxWidth().testTag("personal-refreshing"))
        }
        Card(Modifier.fillMaxWidth().padding(top = 12.dp).testTag("personal-upload-card")) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(strings[MobileString.UploadToCurrentFolder], style = MaterialTheme.typography.titleMedium)
                    Button(onClick = onUpload, modifier = Modifier.testTag("personal-upload")) {
                        Text(strings[MobileString.ChooseFiles])
                    }
                }
            }
        directory.error?.takeIf { directory.hasLoaded }?.let { message ->
            Card(Modifier.fillMaxWidth().padding(top = 8.dp).testTag("personal-inline-error")) {
                Row(
                    Modifier.fillMaxWidth().padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(strings.resolve(message).orEmpty(), Modifier.weight(1f), color = MaterialTheme.colorScheme.error)
                    OutlinedButton(onClick = onRetry) { Text(strings[MobileString.Retry]) }
                }
            }
        }
        when {
            directory.initialLoading && !directory.hasLoaded -> Box(
                Modifier.fillMaxSize().testTag("personal-loading"), contentAlignment = Alignment.Center,
            ) { CircularProgressIndicator() }
            !directory.hasLoaded && directory.error != null -> DirectoryError(
                directory.error, directory.folderId != null, onRetry, onGoRoot,
            )
            directory.items.isEmpty() -> EmptyDirectory(directory.folderId == null)
            state.viewMode == FileViewMode.LIST -> PersonalList(
                state, favorites, onOpenFolder, onSelectFile, onLoadMore, onScrollAnchor,
                onToggleSelection, onRename, onDeleteOne, onOpenPicker, onDownload, onPreview,
                onLoadFavoriteStatus, onToggleFavorite, onManageTags, onCreateShare, onOpenVersions,
            )
            else -> PersonalGrid(
                state, favorites, onOpenFolder, onSelectFile, onLoadMore, onScrollAnchor,
                onToggleSelection, onRename, onDeleteOne, onOpenPicker, onDownload, onPreview,
                onLoadFavoriteStatus, onToggleFavorite, onManageTags, onCreateShare, onOpenVersions,
            )
        }
    }
}

@Composable
private fun PersonalList(
    state: PersonalFilesState,
    favorites: FavoritesState,
    onOpenFolder: (FileItem) -> Unit,
    onSelectFile: (FileItem?) -> Unit,
    onLoadMore: () -> Unit,
    onScrollAnchor: (Long?) -> Unit,
    onToggleSelection: (Long) -> Unit,
    onRename: (FileItem) -> Unit,
    onDelete: (FileItem) -> Unit,
    onOpenPicker: (FileItem, FileMutationKind) -> Unit,
    onDownload: (FileItem) -> Unit,
    onPreview: (FileItem) -> Unit,
    onLoadFavoriteStatus: (Long) -> Unit,
    onToggleFavorite: (FileItem) -> Unit,
    onManageTags: (FileItem) -> Unit,
    onCreateShare: (FileItem) -> Unit,
    onOpenVersions: (FileItem) -> Unit,
) {
    val items = state.current.items
    val initialIndex = items.indexOfFirst { it.id == state.current.scrollAnchorId }.coerceAtLeast(0)
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = initialIndex)
    LaunchedEffect(listState, items) {
        snapshotFlow { listState.firstVisibleItemIndex }
            .distinctUntilChanged()
            .collect { onScrollAnchor(items.getOrNull(it)?.id) }
    }
    LaunchedEffect(listState, items.size, state.current.hasMore) {
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }
            .distinctUntilChanged()
            .collect { if (state.current.hasMore && it >= items.lastIndex - 5) onLoadMore() }
    }
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize().padding(top = 12.dp).testTag("personal-file-list"),
        contentPadding = PaddingValues(bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(items, key = { it.id }) { file ->
            PersonalFileRow(
                file = file,
                selected = file.id in state.selectedIds,
                selectionMode = state.selectedIds.isNotEmpty(),
                onClick = {
                    if (state.selectedIds.isNotEmpty()) onToggleSelection(file.id)
                    else if (file.isFolder) onOpenFolder(file) else onSelectFile(file)
                },
                onLongClick = { onToggleSelection(file.id) },
                onRename = { onRename(file) },
                onMove = { onOpenPicker(file, FileMutationKind.MOVE) },
                onCopy = { onOpenPicker(file, FileMutationKind.COPY) },
                onDelete = { onDelete(file) },
                onDownload = { onDownload(file) },
                onPreview = { onPreview(file) },
                favorite = favorites.favoriteStatus[file.id],
                favoriteLoading = file.id in favorites.statusLoading || favorites.mutatingFileId == file.id,
                onLoadFavoriteStatus = { onLoadFavoriteStatus(file.id) },
                onToggleFavorite = { onToggleFavorite(file) },
                onManageTags = { onManageTags(file) },
                onCreateShare = { onCreateShare(file) },
                onOpenVersions = { onOpenVersions(file) },
            )
        }
        item { PagingFooter(state.current, onLoadMore) }
    }
}

@Composable
private fun PersonalGrid(
    state: PersonalFilesState,
    favorites: FavoritesState,
    onOpenFolder: (FileItem) -> Unit,
    onSelectFile: (FileItem?) -> Unit,
    onLoadMore: () -> Unit,
    onScrollAnchor: (Long?) -> Unit,
    onToggleSelection: (Long) -> Unit,
    onRename: (FileItem) -> Unit,
    onDelete: (FileItem) -> Unit,
    onOpenPicker: (FileItem, FileMutationKind) -> Unit,
    onDownload: (FileItem) -> Unit,
    onPreview: (FileItem) -> Unit,
    onLoadFavoriteStatus: (Long) -> Unit,
    onToggleFavorite: (FileItem) -> Unit,
    onManageTags: (FileItem) -> Unit,
    onCreateShare: (FileItem) -> Unit,
    onOpenVersions: (FileItem) -> Unit,
) {
    val items = state.current.items
    val initialIndex = items.indexOfFirst { it.id == state.current.scrollAnchorId }.coerceAtLeast(0)
    val gridState = rememberLazyGridState(initialFirstVisibleItemIndex = initialIndex)
    LaunchedEffect(gridState, items) {
        snapshotFlow { gridState.firstVisibleItemIndex }
            .distinctUntilChanged()
            .collect { onScrollAnchor(items.getOrNull(it)?.id) }
    }
    LaunchedEffect(gridState, items.size, state.current.hasMore) {
        snapshotFlow { gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }
            .distinctUntilChanged()
            .collect { if (state.current.hasMore && it >= items.lastIndex - 5) onLoadMore() }
    }
    LazyVerticalGrid(
        columns = GridCells.Adaptive(132.dp),
        state = gridState,
        modifier = Modifier.fillMaxSize().padding(top = 12.dp).testTag("personal-file-grid"),
        contentPadding = PaddingValues(bottom = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(items, key = { it.id }) { file ->
            PersonalFileCard(
                file = file,
                selected = file.id in state.selectedIds,
                selectionMode = state.selectedIds.isNotEmpty(),
                onClick = {
                    if (state.selectedIds.isNotEmpty()) onToggleSelection(file.id)
                    else if (file.isFolder) onOpenFolder(file) else onSelectFile(file)
                },
                onLongClick = { onToggleSelection(file.id) },
                onRename = { onRename(file) },
                onMove = { onOpenPicker(file, FileMutationKind.MOVE) },
                onCopy = { onOpenPicker(file, FileMutationKind.COPY) },
                onDelete = { onDelete(file) },
                onDownload = { onDownload(file) },
                onPreview = { onPreview(file) },
                favorite = favorites.favoriteStatus[file.id],
                favoriteLoading = file.id in favorites.statusLoading || favorites.mutatingFileId == file.id,
                onLoadFavoriteStatus = { onLoadFavoriteStatus(file.id) },
                onToggleFavorite = { onToggleFavorite(file) },
                onManageTags = { onManageTags(file) },
                onCreateShare = { onCreateShare(file) },
                onOpenVersions = { onOpenVersions(file) },
            )
        }
        item { PagingFooter(state.current, onLoadMore) }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PersonalFileRow(
    file: FileItem,
    selected: Boolean,
    selectionMode: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onRename: () -> Unit,
    onMove: () -> Unit,
    onCopy: () -> Unit,
    onDelete: () -> Unit,
    onDownload: () -> Unit,
    onPreview: () -> Unit,
    favorite: Boolean?,
    favoriteLoading: Boolean,
    onLoadFavoriteStatus: () -> Unit,
    onToggleFavorite: () -> Unit,
    onManageTags: () -> Unit,
    onCreateShare: () -> Unit,
    onOpenVersions: () -> Unit,
) {
    val strings = LocalMobileStrings.current
    Card(
        Modifier.fillMaxWidth().testTag("personal-file-${file.id}")
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
        colors = if (selected) CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
        else CardDefaults.cardColors(),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(
                if (selected) Icons.Default.CheckCircle else if (file.isFolder) Icons.Default.Folder else Icons.Default.Description,
                contentDescription = strings[if (file.isFolder) MobileString.Folder else MobileString.File],
            )
            Column(Modifier.weight(1f)) {
                Text(file.fileName, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    if (file.isFolder) strings[MobileString.Folder] else "${formatFileSize(file.fileSize)} · ${formatDate(file.updatedAt)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (!selectionMode) FileActionsMenu(
                file, onRename, onMove, onCopy, onDelete, onDownload, onPreview,
                favorite, favoriteLoading, onLoadFavoriteStatus, onToggleFavorite, onManageTags, onCreateShare, onOpenVersions,
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PersonalFileCard(
    file: FileItem,
    selected: Boolean,
    selectionMode: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onRename: () -> Unit,
    onMove: () -> Unit,
    onCopy: () -> Unit,
    onDelete: () -> Unit,
    onDownload: () -> Unit,
    onPreview: () -> Unit,
    favorite: Boolean?,
    favoriteLoading: Boolean,
    onLoadFavoriteStatus: () -> Unit,
    onToggleFavorite: () -> Unit,
    onManageTags: () -> Unit,
    onCreateShare: () -> Unit,
    onOpenVersions: () -> Unit,
) {
    val strings = LocalMobileStrings.current
    Card(
        Modifier.testTag("personal-file-${file.id}")
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
        colors = if (selected) CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
        else CardDefaults.cardColors(),
    ) {
        Column(
            Modifier.fillMaxWidth().padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(
                if (selected) Icons.Default.CheckCircle else if (file.isFolder) Icons.Default.Folder else Icons.Default.Description,
                contentDescription = strings[if (file.isFolder) MobileString.Folder else MobileString.File],
            )
            Text(file.fileName, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(
                if (file.isFolder) strings[MobileString.Folder] else formatFileSize(file.fileSize),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (!selectionMode) FileActionsMenu(
                file, onRename, onMove, onCopy, onDelete, onDownload, onPreview,
                favorite, favoriteLoading, onLoadFavoriteStatus, onToggleFavorite, onManageTags, onCreateShare, onOpenVersions,
            )
        }
    }
}

@Composable
private fun FileActionsMenu(
    file: FileItem,
    onRename: () -> Unit,
    onMove: () -> Unit,
    onCopy: () -> Unit,
    onDelete: () -> Unit,
    onDownload: () -> Unit,
    onPreview: () -> Unit,
    favorite: Boolean?,
    favoriteLoading: Boolean,
    onLoadFavoriteStatus: () -> Unit,
    onToggleFavorite: () -> Unit,
    onManageTags: () -> Unit,
    onCreateShare: () -> Unit,
    onOpenVersions: () -> Unit,
) {
    val strings = LocalMobileStrings.current
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { onLoadFavoriteStatus(); expanded = true }, modifier = Modifier.testTag("personal-more-${file.id}")) {
            Icon(Icons.Default.MoreVert, contentDescription = strings[MobileString.MoreActions])
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text(strings[if (favorite == true) MobileString.RemoveFavorite else MobileString.AddFavorite]) },
                leadingIcon = { Icon(if (favorite == true) Icons.Default.Star else Icons.Default.StarBorder, null) },
                enabled = !favoriteLoading,
                onClick = { expanded = false; onToggleFavorite() },
                modifier = Modifier.testTag("personal-favorite-${file.id}"),
            )
            DropdownMenuItem(
                text = { Text(strings[MobileString.ManageTags]) },
                leadingIcon = { Icon(Icons.Default.Label, null) },
                onClick = { expanded = false; onManageTags() },
                modifier = Modifier.testTag("personal-tags-${file.id}"),
            )
            DropdownMenuItem(
                text = { Text(strings[MobileString.CreateShare]) },
                leadingIcon = { Icon(Icons.Default.Share, null) },
                onClick = { expanded = false; onCreateShare() },
                modifier = Modifier.testTag("personal-share-${file.id}"),
            )
            if (!file.isFolder) DropdownMenuItem(
                text = { Text(strings[MobileString.Preview]) },
                leadingIcon = { Icon(Icons.Default.Visibility, null) },
                onClick = { expanded = false; onPreview() },
                modifier = Modifier.testTag("personal-preview-${file.id}"),
            )
            if (!file.isFolder) DropdownMenuItem(
                text = { Text(strings[MobileString.VersionHistory]) },
                leadingIcon = { Icon(Icons.Default.History, null) },
                onClick = { expanded = false; onOpenVersions() },
                modifier = Modifier.testTag("personal-versions-${file.id}"),
            )
            DropdownMenuItem(
                text = { Text(strings[MobileString.Rename]) }, leadingIcon = { Icon(Icons.Default.Edit, null) },
                onClick = { expanded = false; onRename() }, modifier = Modifier.testTag("personal-rename-${file.id}"),
            )
            DropdownMenuItem(
                text = { Text(strings[MobileString.Move]) }, leadingIcon = { Icon(Icons.Default.DriveFileMove, null) },
                onClick = { expanded = false; onMove() }, modifier = Modifier.testTag("personal-move-${file.id}"),
            )
            DropdownMenuItem(
                text = { Text(strings[MobileString.Copy]) }, leadingIcon = { Icon(Icons.Default.ContentCopy, null) },
                onClick = { expanded = false; onCopy() }, modifier = Modifier.testTag("personal-copy-${file.id}"),
            )
            if (!file.isFolder) DropdownMenuItem(
                text = { Text(strings[MobileString.Download]) },
                leadingIcon = { Icon(Icons.Default.Description, null) },
                onClick = { expanded = false; onDownload() },
                modifier = Modifier.testTag("personal-download-${file.id}"),
            )
            DropdownMenuItem(
                text = { Text(strings[MobileString.Delete]) }, leadingIcon = { Icon(Icons.Default.Delete, null) },
                onClick = { expanded = false; onDelete() }, modifier = Modifier.testTag("personal-delete-${file.id}"),
            )
        }
    }
}

@Composable
private fun SelectionBar(count: Int, onClear: () -> Unit, onDelete: () -> Unit) {
    val strings = LocalMobileStrings.current
    Row(
        Modifier.fillMaxWidth().padding(top = 8.dp).testTag("personal-selection-bar"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(strings.format(MobileString.SelectedCount, count), Modifier.weight(1f))
        TextButton(onClick = onClear, modifier = Modifier.testTag("personal-selection-clear")) {
            Text(strings[MobileString.Cancel])
        }
        Button(onClick = onDelete, modifier = Modifier.testTag("personal-selection-delete")) {
            Icon(Icons.Default.Delete, contentDescription = null)
            Text(strings[MobileString.Delete])
        }
    }
}

@Composable
private fun NameDialog(
    title: String,
    label: String,
    initial: String,
    busy: Boolean,
    serverError: UiMessage?,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
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
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag("personal-name-dialog"),
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it },
                enabled = !busy,
                singleLine = true,
                label = { Text(label) },
                isError = localError != null || serverError != null,
                supportingText = {
                    Text(
                        localError?.let { strings[it] }
                            ?: strings.resolve(serverError).orEmpty(),
                    )
                },
                modifier = Modifier.fillMaxWidth().testTag("personal-name-input"),
            )
        },
        confirmButton = {
            Button(
                enabled = !busy && localError == null,
                onClick = { onConfirm(value) },
                modifier = Modifier.testTag("personal-name-confirm"),
            ) { if (busy) CircularProgressIndicator(Modifier.height(18.dp)) else Text(strings[MobileString.Confirm]) }
        },
        dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text(strings[MobileString.Cancel]) } },
    )
}

@Composable
private fun DeleteDialog(
    targets: List<FileItem>,
    busy: Boolean,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    val strings = LocalMobileStrings.current
    val single = targets.singleOrNull()
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag("personal-delete-dialog"),
        title = {
            Text(
                if (single != null) strings.format(MobileString.DeleteSingleTitle, single.fileName)
                else strings[MobileString.DeleteMultipleTitle],
            )
        },
        text = {
            Text(
                if (single != null) strings[MobileString.DeleteSingleMessage]
                else strings.format(MobileString.DeleteMultipleMessage, targets.size),
            )
        },
        confirmButton = {
            Button(enabled = !busy, onClick = onConfirm, modifier = Modifier.testTag("personal-delete-confirm")) {
                if (busy) CircularProgressIndicator(Modifier.height(18.dp)) else Text(strings[MobileString.Delete])
            }
        },
        dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text(strings[MobileString.Cancel]) } },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DestinationPicker(
    picker: FolderPickerState,
    wide: Boolean,
    onOpenFolder: (FileItem) -> Unit,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
    busy: Boolean,
    operationError: UiMessage?,
) {
    val strings = LocalMobileStrings.current
    if (wide) {
        AlertDialog(
            onDismissRequest = onDismiss,
            modifier = Modifier.testTag("personal-destination-dialog"),
            title = { Text(strings[MobileString.ChooseDestination]) },
            text = {
                Column {
                    FolderPickerContent(picker, onOpenFolder, onBack, onRetry)
                    operationError?.let { Text(strings.resolve(it).orEmpty(), color = MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = {
                Button(
                    enabled = picker.canConfirm && !picker.loading && !busy,
                    onClick = onConfirm,
                    modifier = Modifier.testTag("personal-destination-confirm"),
                ) { Text(strings[MobileString.Confirm]) }
            },
            dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text(strings[MobileString.Cancel]) } },
        )
    } else {
        ModalBottomSheet(
            onDismissRequest = onDismiss,
            modifier = Modifier.testTag("personal-destination-sheet"),
        ) {
            Column(Modifier.fillMaxWidth().fillMaxHeight(0.9f).padding(16.dp)) {
                Text(strings[MobileString.ChooseDestination], style = MaterialTheme.typography.headlineSmall)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(enabled = !busy, onClick = onDismiss) { Text(strings[MobileString.Cancel]) }
                    Button(
                        enabled = picker.canConfirm && !picker.loading && !busy,
                        onClick = onConfirm,
                        modifier = Modifier.testTag("personal-destination-confirm"),
                    ) { Text(strings[MobileString.Confirm]) }
                }
                operationError?.let {
                    Text(strings.resolve(it).orEmpty(), color = MaterialTheme.colorScheme.error)
                }
                FolderPickerContent(picker, onOpenFolder, onBack, onRetry, Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun FolderPickerContent(
    picker: FolderPickerState,
    onOpenFolder: (FileItem) -> Unit,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val strings = LocalMobileStrings.current
    Column(modifier.fillMaxWidth().testTag("personal-destination-content")) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(
                enabled = picker.breadcrumb.size > 1,
                onClick = onBack,
                modifier = Modifier.testTag("personal-destination-back"),
            ) { Icon(Icons.AutoMirrored.Filled.ArrowBack, strings[MobileString.BackToParent]) }
            Text(
                picker.breadcrumb.lastOrNull()?.name?.ifEmpty { strings[MobileString.DestinationRoot] }
                    ?: strings[MobileString.DestinationRoot],
                style = MaterialTheme.typography.titleMedium,
            )
        }
        Text(strings[MobileString.CurrentFolder], color = MaterialTheme.colorScheme.onSurfaceVariant)
        when {
            picker.loading -> Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            picker.error != null -> Column(Modifier.padding(16.dp)) {
                Text(strings.resolve(picker.error).orEmpty(), color = MaterialTheme.colorScheme.error)
                OutlinedButton(onClick = onRetry, modifier = Modifier.testTag("personal-destination-retry")) {
                    Text(strings[MobileString.Retry])
                }
            }
            picker.folders.isEmpty() -> Text(
                strings[MobileString.DestinationEmpty], Modifier.padding(16.dp).testTag("personal-destination-empty"),
            )
            else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(picker.folders, key = { it.id }) { folder ->
                    OutlinedButton(
                        onClick = { onOpenFolder(folder) },
                        modifier = Modifier.fillMaxWidth().testTag("personal-destination-${folder.id}"),
                    ) {
                        Icon(Icons.Default.Folder, null)
                        Text(folder.fileName, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

@Composable
private fun PagingFooter(state: DirectoryPageState, onRetry: () -> Unit) {
    val strings = LocalMobileStrings.current
    when {
        state.loadingMore -> Box(
            Modifier.fillMaxWidth().padding(12.dp).testTag("personal-loading-more"),
            contentAlignment = Alignment.Center,
        ) { CircularProgressIndicator() }
        state.loadMoreError != null -> Column(
            Modifier.fillMaxWidth().padding(12.dp).testTag("personal-load-more-error"),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(strings.resolve(state.loadMoreError).orEmpty(), color = MaterialTheme.colorScheme.error)
            OutlinedButton(onClick = onRetry, modifier = Modifier.testTag("personal-load-more-retry")) {
                Text(strings[MobileString.RetryLoadMore])
            }
        }
        !state.hasMore -> Text(
            strings.format(MobileString.TotalItems, state.total),
            Modifier.fillMaxWidth().padding(12.dp).testTag("personal-page-end"),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        else -> Spacer(Modifier.height(1.dp))
    }
}

@Composable
private fun EmptyDirectory(isRoot: Boolean) {
    val strings = LocalMobileStrings.current
    Column(
        Modifier.fillMaxSize().testTag("personal-empty"),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(Icons.Default.Folder, contentDescription = null)
        Text(strings[if (isRoot) MobileString.PersonalEmpty else MobileString.DirectoryEmpty], style = MaterialTheme.typography.titleMedium)
        Text(strings[if (isRoot) MobileString.PersonalEmptyDetail else MobileString.DirectoryEmptyDetail])
    }
}

@Composable
private fun DirectoryError(message: UiMessage, nested: Boolean, onRetry: () -> Unit, onGoRoot: () -> Unit) {
    val strings = LocalMobileStrings.current
    Column(
        Modifier.fillMaxSize().padding(24.dp).testTag("personal-error"),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(strings[MobileString.DirectoryLoadFailed], style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.error)
        Text(strings.resolve(message).orEmpty())
        Button(onClick = onRetry, modifier = Modifier.testTag("personal-error-retry")) { Text(strings[MobileString.Retry]) }
        if (nested) OutlinedButton(onClick = onGoRoot, modifier = Modifier.testTag("personal-error-root")) {
            Text(strings[MobileString.BackToRoot])
        }
    }
}

@Composable
private fun EmptyDetail() {
    val strings = LocalMobileStrings.current
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(Icons.Default.Info, contentDescription = null)
        Text(strings[MobileString.SelectFileForDetails])
    }
}

@Composable
private fun FileDetailContent(
    file: FileItem,
    favorites: FavoritesState,
    onDownload: (FileItem) -> Unit,
    onPreview: (FileItem) -> Unit,
    onLoadFavoriteStatus: (Long) -> Unit,
    onToggleFavorite: (FileItem) -> Unit,
    onManageTags: (FileItem) -> Unit,
    onCreateShare: (FileItem) -> Unit,
    onOpenVersions: (FileItem) -> Unit,
) {
    val strings = LocalMobileStrings.current
    LaunchedEffect(file.id) { onLoadFavoriteStatus(file.id) }
    Column(
        Modifier.fillMaxWidth().padding(24.dp).testTag("personal-file-detail-${file.id}"),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(Icons.Default.Description, contentDescription = strings[MobileString.File], tint = MaterialTheme.colorScheme.primary)
        Text(file.fileName, style = MaterialTheme.typography.headlineSmall)
        DetailRow(strings[MobileString.Type], file.mimeType ?: strings[MobileString.File])
        DetailRow(strings[MobileString.Size], formatFileSize(file.fileSize))
        DetailRow(strings[MobileString.UpdatedAt], formatDate(file.updatedAt))
        DetailRow(strings[MobileString.CreatedAt], formatDate(file.createdAt))
        DetailRow("MD5", file.md5Hash ?: "—")
        DetailRow(strings[MobileString.VersionCount], file.versionCount.toString())
        OutlinedButton(
            enabled = file.id !in favorites.statusLoading && favorites.mutatingFileId != file.id,
            onClick = { onToggleFavorite(file) },
            modifier = Modifier.testTag("personal-detail-favorite-${file.id}"),
        ) {
            Icon(if (favorites.favoriteStatus[file.id] == true) Icons.Default.Star else Icons.Default.StarBorder, null)
            Text(strings[if (favorites.favoriteStatus[file.id] == true) MobileString.RemoveFavorite else MobileString.AddFavorite])
        }
        OutlinedButton(
            onClick = { onManageTags(file) },
            modifier = Modifier.testTag("personal-detail-tags-${file.id}"),
        ) { Icon(Icons.Default.Label, null); Text(strings[MobileString.ManageTags]) }
        OutlinedButton(
            onClick = { onCreateShare(file) },
            modifier = Modifier.testTag("personal-detail-share-${file.id}"),
        ) { Icon(Icons.Default.Share, null); Text(strings[MobileString.CreateShare]) }
        OutlinedButton(
            onClick = { onOpenVersions(file) },
            modifier = Modifier.testTag("personal-detail-versions-${file.id}"),
        ) { Icon(Icons.Default.History, null); Text(strings[MobileString.VersionHistory]) }
        OutlinedButton(
            onClick = { onPreview(file) },
            modifier = Modifier.testTag("personal-detail-preview-${file.id}"),
        ) { Text(strings[MobileString.Preview]) }
        Button(
            onClick = { onDownload(file) },
            modifier = Modifier.testTag("personal-detail-download-${file.id}"),
        ) { Text(strings[MobileString.Download]) }
        Spacer(Modifier.height(12.dp))
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value)
    }
}

internal fun formatFileSize(bytes: Long): String = when {
    bytes <= 0 -> "0 B"
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "${bytes / 1024} KiB"
    bytes < 1024L * 1024 * 1024 -> "${bytes / (1024 * 1024)} MiB"
    else -> "${bytes / (1024L * 1024 * 1024)} GiB"
}

internal fun formatDate(value: String?): String = value
    ?.takeIf(String::isNotBlank)
    ?.take(19)
    ?.replace('T', ' ')
    ?: "—"
