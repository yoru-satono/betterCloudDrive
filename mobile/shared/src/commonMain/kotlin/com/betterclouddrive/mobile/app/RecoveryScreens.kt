package com.betterclouddrive.mobile.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.betterclouddrive.mobile.core.localization.LocalMobileStrings
import com.betterclouddrive.mobile.core.localization.MobileString
import com.betterclouddrive.mobile.core.model.PersonalFileVersion
import com.betterclouddrive.mobile.core.model.RecycleBinItem
import com.betterclouddrive.mobile.core.platform.DocumentPicker
import com.betterclouddrive.mobile.core.platform.DownloadRequest
import com.betterclouddrive.mobile.feature.recovery.PersonalVersionsState
import com.betterclouddrive.mobile.feature.recovery.RecycleBinState
import com.betterclouddrive.mobile.feature.recovery.TeamRecycleBinState
import com.betterclouddrive.mobile.feature.recovery.TeamVersionsState

@Composable
fun PersonalRecycleBinScreen(
    state: RecycleBinState,
    onQuery: (String) -> Unit,
    onRefresh: () -> Unit,
    onLoadMore: () -> Unit,
    onRestore: (Long) -> Unit,
    onPermanentlyDelete: (Long) -> Unit,
    onEmpty: () -> Unit,
    onConsumeFeedback: () -> Unit,
) {
    val strings = LocalMobileStrings.current
    val snackbar = remember { SnackbarHostState() }
    var restoreTarget by remember { mutableStateOf<RecycleBinItem?>(null) }
    var deleteTarget by remember { mutableStateOf<RecycleBinItem?>(null) }
    var confirmEmpty by remember { mutableStateOf(false) }
    LaunchedEffect(state.feedback?.id) {
        state.feedback?.let {
            snackbar.showSnackbar(strings.resolve(it.message).orEmpty())
            if (!it.isError) { restoreTarget = null; deleteTarget = null; confirmEmpty = false }
            onConsumeFeedback()
        }
    }
    Box(Modifier.fillMaxSize().testTag("recycle-bin-screen")) {
        Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    state.query, onQuery, singleLine = true,
                    label = { Text(strings[MobileString.SearchRecycleBin]) },
                    modifier = Modifier.weight(1f).testTag("recycle-search"),
                )
                IconButton(onRefresh, enabled = !state.loading && !state.refreshing, modifier = Modifier.testTag("recycle-refresh")) {
                    Icon(Icons.Default.Refresh, strings[MobileString.RefreshDirectory])
                }
            }
            if (state.items.isNotEmpty()) OutlinedButton(
                onClick = { confirmEmpty = true }, enabled = !state.emptying && state.mutatingFileId == null,
                modifier = Modifier.testTag("recycle-empty-action"),
            ) { Icon(Icons.Default.DeleteForever, null); Text(strings[MobileString.EmptyRecycleBin]) }
            when {
                state.loading && state.items.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                state.error != null && state.items.isEmpty() -> RecoveryError(strings.resolve(state.error).orEmpty(), onRefresh)
                state.items.isEmpty() -> RecoveryEmpty(strings[MobileString.RecycleEmpty], strings[MobileString.RecycleEmptyDetail])
                else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxSize()) {
                    items(state.items, key = RecycleBinItem::id) { item ->
                        Card(Modifier.fillMaxWidth().testTag("recycle-item-${item.id}")) {
                            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(if (item.isFolder) Icons.Default.Folder else Icons.Default.DeleteForever, null)
                                    Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                                        Text(item.fileName, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Text(if (item.isFolder) strings[MobileString.Folder] else formatFileSize(item.fileSize))
                                        Text(strings.format(MobileString.DeletedAtValue, formatDate(item.deletedAt)))
                                    }
                                }
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    TextButton(
                                        onClick = { restoreTarget = item }, enabled = state.mutatingFileId == null && !state.emptying,
                                        modifier = Modifier.testTag("recycle-restore-${item.id}"),
                                    ) { Icon(Icons.Default.Restore, null); Text(strings[MobileString.Restore]) }
                                    TextButton(
                                        onClick = { deleteTarget = item }, enabled = state.mutatingFileId == null && !state.emptying,
                                        modifier = Modifier.testTag("recycle-delete-${item.id}"),
                                    ) { Icon(Icons.Default.DeleteForever, null); Text(strings[MobileString.PermanentlyDelete]) }
                                }
                            }
                        }
                    }
                    if (state.hasMore || state.loadMoreError != null) item {
                        TextButton(onClick = onLoadMore, enabled = !state.loadingMore, modifier = Modifier.fillMaxWidth().testTag("recycle-load-more")) {
                            if (state.loadingMore) CircularProgressIndicator() else Text(strings[MobileString.RetryLoadMore])
                        }
                    }
                }
            }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
    }
    restoreTarget?.let { item -> Confirmation(
        strings[MobileString.Restore], strings[MobileString.RestoreRecycleMessage], strings[MobileString.Restore],
        { restoreTarget = null }, { onRestore(item.id) }, state.mutatingFileId == item.id,
    ) }
    deleteTarget?.let { item -> Confirmation(
        strings[MobileString.PermanentlyDelete], strings[MobileString.PermanentlyDeleteMessage], strings[MobileString.PermanentlyDelete],
        { deleteTarget = null }, { onPermanentlyDelete(item.id) }, state.mutatingFileId == item.id,
    ) }
    if (confirmEmpty) Confirmation(
        strings[MobileString.EmptyRecycleBin], strings[MobileString.EmptyRecycleBinMessage], strings[MobileString.EmptyRecycleBin],
        { confirmEmpty = false }, onEmpty, state.emptying,
    )
}

@Composable
fun PersonalVersionsScreen(
    state: PersonalVersionsState,
    documentPicker: DocumentPicker,
    onRetry: () -> Unit,
    onDownload: (PersonalFileVersion, String) -> Unit,
    onRestore: (Int) -> Unit,
    onDelete: (Int) -> Unit,
    onOpenTransfers: () -> Unit,
    onConsumeFeedback: () -> Unit,
    onConsumeTransferFeedback: () -> Unit,
) {
    val strings = LocalMobileStrings.current
    val snackbar = remember { SnackbarHostState() }
    var pendingDownload by remember { mutableStateOf<PersonalFileVersion?>(null) }
    var restoreTarget by remember { mutableStateOf<PersonalFileVersion?>(null) }
    var deleteTarget by remember { mutableStateOf<PersonalFileVersion?>(null) }
    val downloadLauncher = documentPicker.rememberDownloadLauncher { target ->
        pendingDownload?.let { onDownload(it, target.handle) }
        pendingDownload = null
    }
    LaunchedEffect(state.feedback?.id) {
        state.feedback?.let {
            snackbar.showSnackbar(strings.resolve(it.message).orEmpty())
            if (!it.isError) { restoreTarget = null; deleteTarget = null }
            onConsumeFeedback()
        }
    }
    LaunchedEffect(state.transferFeedback?.id) {
        state.transferFeedback?.let {
            val result = snackbar.showSnackbar(strings.resolve(it.message).orEmpty(), strings[MobileString.ViewTransfers])
            onConsumeTransferFeedback()
            if (result == androidx.compose.material3.SnackbarResult.ActionPerformed) onOpenTransfers()
        }
    }
    Box(Modifier.fillMaxSize().testTag("versions-screen")) {
        when {
            state.loading && state.file == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            state.error != null && state.file == null -> RecoveryError(strings.resolve(state.error).orEmpty(), onRetry)
            else -> Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                state.file?.let { Text(it.fileName, modifier = Modifier.testTag("versions-file-name")) }
                if (state.versions.isEmpty()) RecoveryEmpty(strings[MobileString.VersionsEmpty], strings[MobileString.VersionsEmptyDetail])
                else LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(state.versions, key = { it.id }) { version ->
                        Card(Modifier.fillMaxWidth().testTag("version-item-${version.versionNumber}")) {
                            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.History, null)
                                    Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                                        Text(strings.format(MobileString.VersionNumber, version.versionNumber))
                                        Text(strings.format(MobileString.VersionMetadata, formatFileSize(version.fileSize), formatDate(version.createdAt)))
                                        Text(strings.format(MobileString.Md5Value, version.md5Hash ?: "—"), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    }
                                }
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    TextButton(onClick = {
                                        pendingDownload = version
                                        val file = state.file ?: return@TextButton
                                        downloadLauncher(DownloadRequest("v${version.versionNumber}_${file.fileName}", file.mimeType))
                                    }, enabled = state.downloadingVersion == null, modifier = Modifier.testTag("version-download-${version.versionNumber}")) {
                                        Icon(Icons.Default.Download, null); Text(strings[MobileString.Download])
                                    }
                                    TextButton(onClick = { restoreTarget = version }, enabled = state.restoringVersion == null && state.deletingVersion == null,
                                        modifier = Modifier.testTag("version-restore-${version.versionNumber}")) {
                                        Icon(Icons.Default.Restore, null); Text(strings[MobileString.Restore])
                                    }
                                    TextButton(onClick = { deleteTarget = version }, enabled = state.restoringVersion == null && state.deletingVersion == null,
                                        modifier = Modifier.testTag("version-delete-${version.versionNumber}")) {
                                        Icon(Icons.Default.DeleteForever, null); Text(strings[MobileString.Delete])
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
    }
    restoreTarget?.let { version -> Confirmation(
        strings[MobileString.RestoreVersion], strings[MobileString.RestoreVersionMessage], strings[MobileString.Restore],
        { restoreTarget = null }, { onRestore(version.versionNumber) }, state.restoringVersion == version.versionNumber,
    ) }
    deleteTarget?.let { version -> Confirmation(
        strings[MobileString.DeleteVersion], strings[MobileString.DeleteVersionMessage], strings[MobileString.Delete],
        { deleteTarget = null }, { onDelete(version.versionNumber) }, state.deletingVersion == version.versionNumber,
    ) }
}

@Composable
fun TeamRecycleBinScreen(
    state: TeamRecycleBinState,
    onQuery: (String) -> Unit,
    onRefresh: () -> Unit,
    onLoadMore: () -> Unit,
    onRequestPermission: (Long) -> Unit,
    onRestore: (Long) -> Unit,
    onPermanentlyDelete: (Long) -> Unit,
    onEmpty: () -> Unit,
    onConsumeFeedback: () -> Unit,
    onClearPermissionError: () -> Unit,
) {
    val strings = LocalMobileStrings.current
    val snackbar = remember { SnackbarHostState() }
    var restoreTarget by remember { mutableStateOf<RecycleBinItem?>(null) }
    var deleteTarget by remember { mutableStateOf<RecycleBinItem?>(null) }
    var confirmEmpty by remember { mutableStateOf(false) }
    LaunchedEffect(state.feedback?.id) {
        state.feedback?.let {
            snackbar.showSnackbar(strings.resolve(it.message).orEmpty())
            if (!it.isError) { restoreTarget = null; deleteTarget = null; confirmEmpty = false }
            onConsumeFeedback()
        }
    }
    LaunchedEffect(state.permissionError) {
        state.permissionError?.let {
            snackbar.showSnackbar(strings.resolve(it).orEmpty())
            onClearPermissionError()
        }
    }
    Box(Modifier.fillMaxSize().testTag("team-recycle-bin-screen")) {
        Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    state.query, onQuery, singleLine = true,
                    label = { Text(strings[MobileString.SearchRecycleBin]) },
                    modifier = Modifier.weight(1f).testTag("team-recycle-search"),
                )
                IconButton(onRefresh, enabled = !state.loading && !state.refreshing, modifier = Modifier.testTag("team-recycle-refresh")) {
                    Icon(Icons.Default.Refresh, strings[MobileString.RefreshDirectory])
                }
            }
            if (state.canEmpty && state.items.isNotEmpty()) OutlinedButton(
                onClick = { confirmEmpty = true }, enabled = !state.emptying && state.mutatingFileId == null,
                modifier = Modifier.testTag("team-recycle-empty-action"),
            ) { Icon(Icons.Default.DeleteForever, null); Text(strings[MobileString.EmptyRecycleBin]) }
            when {
                state.loading && state.items.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                state.error != null && state.items.isEmpty() -> RecoveryError(strings.resolve(state.error).orEmpty(), onRefresh)
                state.items.isEmpty() -> RecoveryEmpty(strings[MobileString.RecycleEmpty], strings[MobileString.RecycleEmptyDetail])
                else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxSize()) {
                    items(state.items, key = RecycleBinItem::id) { item ->
                        var expanded by remember(item.id) { mutableStateOf(false) }
                        val permission = state.permissions[item.id]
                        Card(Modifier.fillMaxWidth().testTag("team-recycle-item-${item.id}")) {
                            Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(if (item.isFolder) Icons.Default.Folder else Icons.Default.DeleteForever, null)
                                Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                                    Text(item.fileName, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(if (item.isFolder) strings[MobileString.Folder] else formatFileSize(item.fileSize))
                                    Text(strings.format(MobileString.DeletedAtValue, formatDate(item.deletedAt)))
                                }
                                Box {
                                    IconButton(
                                        onClick = { onRequestPermission(item.id); expanded = true },
                                        modifier = Modifier.testTag("team-recycle-more-${item.id}"),
                                    ) { Icon(Icons.Default.MoreVert, strings[MobileString.MoreActions]) }
                                    DropdownMenu(expanded, { expanded = false }) {
                                        if (item.id in state.loadingPermissionIds) DropdownMenuItem(
                                            text = { Text(strings[MobileString.TeamPermissionLoading]) },
                                            enabled = false, onClick = {},
                                        )
                                        if (permission?.canWrite == true) DropdownMenuItem(
                                            text = { Text(strings[MobileString.Restore]) },
                                            leadingIcon = { Icon(Icons.Default.Restore, null) },
                                            onClick = { expanded = false; restoreTarget = item },
                                            modifier = Modifier.testTag("team-recycle-restore-${item.id}"),
                                        )
                                        if (permission?.canDelete == true) DropdownMenuItem(
                                            text = { Text(strings[MobileString.PermanentlyDelete]) },
                                            leadingIcon = { Icon(Icons.Default.DeleteForever, null) },
                                            onClick = { expanded = false; deleteTarget = item },
                                            modifier = Modifier.testTag("team-recycle-delete-${item.id}"),
                                        )
                                    }
                                }
                            }
                        }
                    }
                    if (state.hasMore || state.loadMoreError != null) item {
                        TextButton(onClick = onLoadMore, enabled = !state.loadingMore, modifier = Modifier.fillMaxWidth().testTag("team-recycle-load-more")) {
                            if (state.loadingMore) CircularProgressIndicator() else Text(strings[MobileString.RetryLoadMore])
                        }
                    }
                }
            }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
    }
    restoreTarget?.let { item -> Confirmation(
        strings[MobileString.Restore], strings[MobileString.RestoreRecycleMessage], strings[MobileString.Restore],
        { restoreTarget = null }, { onRestore(item.id) }, state.mutatingFileId == item.id,
    ) }
    deleteTarget?.let { item -> Confirmation(
        strings[MobileString.PermanentlyDelete], strings[MobileString.PermanentlyDeleteMessage], strings[MobileString.PermanentlyDelete],
        { deleteTarget = null }, { onPermanentlyDelete(item.id) }, state.mutatingFileId == item.id,
    ) }
    if (confirmEmpty) Confirmation(
        strings[MobileString.EmptyRecycleBin], strings[MobileString.EmptyRecycleBinMessage], strings[MobileString.EmptyRecycleBin],
        { confirmEmpty = false }, onEmpty, state.emptying,
    )
}

@Composable
fun TeamVersionsScreen(
    state: TeamVersionsState,
    documentPicker: DocumentPicker,
    onRetry: () -> Unit,
    onDownload: (PersonalFileVersion, String) -> Unit,
    onRestore: (Int) -> Unit,
    onDelete: (Int) -> Unit,
    onOpenTransfers: () -> Unit,
    onConsumeFeedback: () -> Unit,
    onConsumeTransferFeedback: () -> Unit,
) {
    val strings = LocalMobileStrings.current
    val snackbar = remember { SnackbarHostState() }
    var pendingDownload by remember { mutableStateOf<PersonalFileVersion?>(null) }
    var restoreTarget by remember { mutableStateOf<PersonalFileVersion?>(null) }
    var deleteTarget by remember { mutableStateOf<PersonalFileVersion?>(null) }
    val downloadLauncher = documentPicker.rememberDownloadLauncher { target ->
        pendingDownload?.let { onDownload(it, target.handle) }
        pendingDownload = null
    }
    LaunchedEffect(state.feedback?.id) {
        state.feedback?.let {
            snackbar.showSnackbar(strings.resolve(it.message).orEmpty())
            if (!it.isError) { restoreTarget = null; deleteTarget = null }
            onConsumeFeedback()
        }
    }
    LaunchedEffect(state.transferFeedback?.id) {
        state.transferFeedback?.let {
            val result = snackbar.showSnackbar(strings.resolve(it.message).orEmpty(), strings[MobileString.ViewTransfers])
            onConsumeTransferFeedback()
            if (result == androidx.compose.material3.SnackbarResult.ActionPerformed) onOpenTransfers()
        }
    }
    Box(Modifier.fillMaxSize().testTag("team-versions-screen")) {
        when {
            state.loading && state.file == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            state.error != null && state.file == null -> RecoveryError(strings.resolve(state.error).orEmpty(), onRetry)
            else -> Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                state.file?.let { Text(it.fileName, modifier = Modifier.testTag("team-versions-file-name")) }
                if (state.versions.isEmpty()) RecoveryEmpty(strings[MobileString.VersionsEmpty], strings[MobileString.VersionsEmptyDetail])
                else LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(state.versions, key = { it.id }) { version ->
                        Card(Modifier.fillMaxWidth().testTag("team-version-item-${version.versionNumber}")) {
                            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.History, null)
                                    Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                                        Text(strings.format(MobileString.VersionNumber, version.versionNumber))
                                        Text(strings.format(MobileString.VersionMetadata, formatFileSize(version.fileSize), formatDate(version.createdAt)))
                                        Text(strings.format(MobileString.Md5Value, version.md5Hash ?: "—"), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    }
                                }
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    if (state.permission?.canRead == true) TextButton(onClick = {
                                        pendingDownload = version
                                        val file = state.file ?: return@TextButton
                                        downloadLauncher(DownloadRequest("v${version.versionNumber}_${file.fileName}", file.mimeType))
                                    }, enabled = state.downloadingVersion == null, modifier = Modifier.testTag("team-version-download-${version.versionNumber}")) {
                                        Icon(Icons.Default.Download, null); Text(strings[MobileString.Download])
                                    }
                                    if (state.permission?.canWrite == true) TextButton(
                                        onClick = { restoreTarget = version },
                                        enabled = state.restoringVersion == null && state.deletingVersion == null,
                                        modifier = Modifier.testTag("team-version-restore-${version.versionNumber}"),
                                    ) { Icon(Icons.Default.Restore, null); Text(strings[MobileString.Restore]) }
                                    if (state.permission?.canDelete == true) TextButton(
                                        onClick = { deleteTarget = version },
                                        enabled = state.restoringVersion == null && state.deletingVersion == null,
                                        modifier = Modifier.testTag("team-version-delete-${version.versionNumber}"),
                                    ) { Icon(Icons.Default.DeleteForever, null); Text(strings[MobileString.Delete]) }
                                }
                            }
                        }
                    }
                }
            }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
    }
    restoreTarget?.let { version -> Confirmation(
        strings[MobileString.RestoreVersion], strings[MobileString.RestoreVersionMessage], strings[MobileString.Restore],
        { restoreTarget = null }, { onRestore(version.versionNumber) }, state.restoringVersion == version.versionNumber,
    ) }
    deleteTarget?.let { version -> Confirmation(
        strings[MobileString.DeleteVersion], strings[MobileString.DeleteVersionMessage], strings[MobileString.Delete],
        { deleteTarget = null }, { onDelete(version.versionNumber) }, state.deletingVersion == version.versionNumber,
    ) }
}

@Composable
private fun RecoveryEmpty(title: String, detail: String) = Column(
    Modifier.fillMaxWidth().padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.spacedBy(8.dp),
) { Text(title); Text(detail) }

@Composable
private fun RecoveryError(message: String, retry: () -> Unit) = Column(
    Modifier.fillMaxWidth().padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.spacedBy(8.dp),
) { Text(message); Button(retry) { Text(LocalMobileStrings.current[MobileString.Retry]) } }

@Composable
private fun Confirmation(title: String, message: String, confirm: String, dismiss: () -> Unit, action: () -> Unit, busy: Boolean) {
    AlertDialog(
        onDismissRequest = { if (!busy) dismiss() },
        title = { Text(title) }, text = { Text(message) },
        confirmButton = { TextButton(action, enabled = !busy) { Text(confirm) } },
        dismissButton = { TextButton(dismiss, enabled = !busy) { Text(LocalMobileStrings.current[MobileString.Cancel]) } },
    )
}
