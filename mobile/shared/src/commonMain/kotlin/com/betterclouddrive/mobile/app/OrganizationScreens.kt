package com.betterclouddrive.mobile.app

import com.betterclouddrive.mobile.feature.organization.FavoritesState
import com.betterclouddrive.mobile.feature.organization.TagsState
import com.betterclouddrive.mobile.feature.organization.FileTagEditorState
import com.betterclouddrive.mobile.feature.organization.OrganizationFileListState
import com.betterclouddrive.mobile.feature.organization.TagMutationKind

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Label
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.History
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
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
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.betterclouddrive.mobile.core.localization.LocalMobileStrings
import com.betterclouddrive.mobile.core.localization.MobileString
import com.betterclouddrive.mobile.core.model.FileItem
import com.betterclouddrive.mobile.core.model.PersonalTag
import com.betterclouddrive.mobile.core.platform.DocumentPicker
import com.betterclouddrive.mobile.core.platform.DownloadRequest
import kotlinx.coroutines.flow.distinctUntilChanged

internal val TAG_COLORS = listOf("#00d4aa", "#60a5fa", "#a78bfa", "#f87171", "#fb923c", "#4ade80", "#f472b6")

@Composable
fun FavoritesScreen(
    state: FavoritesState,
    documentPicker: DocumentPicker,
    onQuery: (String) -> Unit,
    onRefresh: () -> Unit,
    onLoadMore: () -> Unit,
    onScrollAnchor: (Long?) -> Unit,
    onOpenFolder: (FileItem) -> Unit,
    onOpenLocation: (FileItem) -> Unit,
    onPreview: (FileItem) -> Unit,
    onDownload: (FileItem, String) -> Unit,
    onToggleFavorite: (FileItem) -> Unit,
    onManageTags: ((FileItem) -> Unit)?,
    onCreateShare: ((FileItem) -> Unit)?,
    onConsumeFeedback: () -> Unit,
    onOpenVersions: ((FileItem) -> Unit)?,
    onMenuOpen: (FileItem) -> Unit = {},
    canManageTags: (FileItem) -> Boolean = { true },
    canCreateShare: (FileItem) -> Boolean = { onCreateShare != null },
    onBack: (() -> Unit)? = null,
) {
    val strings = LocalMobileStrings.current
    val snackbar = remember { SnackbarHostState() }
    var selected by remember { mutableStateOf<FileItem?>(null) }
    var pendingDownload by remember { mutableStateOf<FileItem?>(null) }
    val downloadLauncher = documentPicker.rememberDownloadLauncher { target ->
        pendingDownload?.let { onDownload(it, target.handle) }
        pendingDownload = null
    }
    fun download(file: FileItem) {
        pendingDownload = file
        downloadLauncher(DownloadRequest(file.fileName, file.mimeType))
    }
    LaunchedEffect(state.feedback?.id) {
        state.feedback?.let {
            snackbar.showSnackbar(strings.resolve(it.message).orEmpty())
            onConsumeFeedback()
        }
    }
    Box(Modifier.fillMaxSize().testTag("favorites-screen")) {
        OrganizationFilesContent(
            title = strings[MobileString.FavoritesTitle],
            query = state.files.query,
            queryLabel = strings[MobileString.SearchFavorites],
            files = state.files,
            emptyTitle = strings[MobileString.FavoritesEmpty],
            emptyDetail = strings[MobileString.FavoritesEmptyDetail],
            onQuery = onQuery,
            onRefresh = onRefresh,
            onLoadMore = onLoadMore,
            onScrollAnchor = onScrollAnchor,
            onClick = { if (it.isFolder) onOpenFolder(it) else selected = it },
            onBack = onBack,
            menu = { file ->
                OrganizationFileMenu(
                    file = file,
                    onLocation = { onOpenLocation(file) },
                    onPreview = { onPreview(file) },
                    onDownload = { download(file) },
                    onManageTags = onManageTags?.takeIf { canManageTags(file) }?.let { action -> { action(file) } },
                    onCreateShare = onCreateShare?.takeIf { canCreateShare(file) }?.let { action -> { action(file) } },
                    onOpenVersions = onOpenVersions?.let { action -> { action(file) } },
                    trailingLabel = MobileString.RemoveFavorite,
                    trailingIcon = { Icon(Icons.Default.Star, null) },
                    onTrailing = { onToggleFavorite(file) },
                    onOpenMenu = { onMenuOpen(file) },
                )
            },
        )
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
    }
    selected?.let { file ->
        LaunchedEffect(file.id) { onMenuOpen(file) }
        OrganizationFileDetail(
            file,
            onDismiss = { selected = null },
            onPreview = { selected = null; onPreview(file) },
            onDownload = { download(file) },
            onCreateShare = onCreateShare?.takeIf { canCreateShare(file) }?.let { action -> { selected = null; action(file) } },
            onOpenVersions = onOpenVersions?.let { action -> { selected = null; action(file) } },
        )
    }
}

@Composable
fun TagsScreen(
    state: TagsState,
    onQuery: (String) -> Unit,
    onRefresh: () -> Unit,
    onOpenTag: (PersonalTag) -> Unit,
    onCreate: (String, String) -> Unit,
    onUpdate: (PersonalTag, String, String) -> Unit,
    onDelete: (PersonalTag) -> Unit,
    onConsumeFeedback: () -> Unit,
    canManage: Boolean = true,
    showFileCount: Boolean = true,
    onBack: (() -> Unit)? = null,
) {
    val strings = LocalMobileStrings.current
    var createDialog by remember { mutableStateOf(false) }
    var editTarget by remember { mutableStateOf<PersonalTag?>(null) }
    var deleteTarget by remember { mutableStateOf<PersonalTag?>(null) }
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(state.feedback?.id) {
        state.feedback?.let {
            snackbar.showSnackbar(strings.resolve(it.message).orEmpty())
            if (!it.isError) { createDialog = false; editTarget = null; deleteTarget = null }
            onConsumeFeedback()
        }
    }
    Box(Modifier.fillMaxSize().testTag("tags-screen")) {
        Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                if (onBack != null) IconButton(onBack, Modifier.testTag("tags-back")) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, strings[MobileString.BackToParent])
                }
                OutlinedTextField(
                    value = state.query,
                    onValueChange = onQuery,
                    singleLine = true,
                    label = { Text(strings[MobileString.SearchTags]) },
                    modifier = Modifier.weight(1f).testTag("tags-search"),
                )
                IconButton(onClick = onRefresh, modifier = Modifier.testTag("tags-refresh")) {
                    Icon(Icons.Default.Refresh, strings[MobileString.RefreshDirectory])
                }
                if (canManage) IconButton(onClick = { createDialog = true }, modifier = Modifier.testTag("tags-create")) {
                    Icon(Icons.Default.Add, strings[MobileString.NewTag])
                }
            }
            when {
                state.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                state.error != null -> OrganizationError(strings.resolve(state.error).orEmpty(), onRefresh)
                state.tags.isEmpty() -> OrganizationEmpty(
                    strings[MobileString.TagsEmpty], strings[MobileString.TagsEmptyDetail],
                )
                else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(state.tags, key = PersonalTag::id) { tag ->
                        Card(
                            Modifier.fillMaxWidth().clickable { onOpenTag(tag) }.testTag("tag-row-${tag.id}"),
                        ) {
                            Row(
                                Modifier.fillMaxWidth().padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                Box(Modifier.size(18.dp).background(parseTagColor(tag.color), MaterialTheme.shapes.small))
                                Column(Modifier.weight(1f)) {
                                    Text(tag.tagName, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    if (showFileCount) Text(
                                        strings.format(MobileString.TagFileCount, tag.fileCount),
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                }
                                if (canManage) TagActionsMenu(tag, { editTarget = tag }, { deleteTarget = tag })
                            }
                        }
                    }
                }
            }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
    }
    if (createDialog) TagEditorDialog(
        title = strings[MobileString.NewTag], initialName = "", initialColor = TAG_COLORS.first(),
        busy = state.mutation == TagMutationKind.CREATE, error = state.mutationError,
        onDismiss = { if (state.mutation == null) createDialog = false }, onSave = onCreate,
    )
    editTarget?.let { tag -> TagEditorDialog(
        title = strings[MobileString.EditTag], initialName = tag.tagName, initialColor = tag.color ?: TAG_COLORS.first(),
        busy = state.mutation == TagMutationKind.UPDATE, error = state.mutationError,
        onDismiss = { if (state.mutation == null) editTarget = null }, onSave = { name, color -> onUpdate(tag, name, color) },
    ) }
    deleteTarget?.let { tag -> AlertDialog(
        onDismissRequest = { if (state.mutation == null) deleteTarget = null },
        modifier = Modifier.testTag("tag-delete-dialog"),
        title = { Text(strings[MobileString.DeleteTag]) },
        text = { Text(strings.format(MobileString.TagDeleteMessage, tag.tagName)) },
        confirmButton = { Button(onClick = { onDelete(tag) }, modifier = Modifier.testTag("tag-delete-confirm")) {
            Text(strings[MobileString.Delete])
        } },
        dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text(strings[MobileString.Cancel]) } },
    ) }
}

@Composable
fun TagFilesScreen(
    tag: PersonalTag?,
    state: TagsState,
    documentPicker: DocumentPicker,
    onQuery: (String) -> Unit,
    onRefresh: () -> Unit,
    onLoadMore: () -> Unit,
    onScrollAnchor: (Long?) -> Unit,
    onOpenFolder: (FileItem) -> Unit,
    onOpenLocation: (FileItem) -> Unit,
    onPreview: (FileItem) -> Unit,
    onDownload: (FileItem, String) -> Unit,
    onManageTags: ((FileItem) -> Unit)?,
    onCreateShare: ((FileItem) -> Unit)?,
    onRemove: (FileItem) -> Unit,
    onConsumeFeedback: () -> Unit,
    onOpenVersions: ((FileItem) -> Unit)?,
    onMenuOpen: (FileItem) -> Unit = {},
    canManageTags: (FileItem) -> Boolean = { true },
    canCreateShare: (FileItem) -> Boolean = { onCreateShare != null },
    onBack: (() -> Unit)? = null,
) {
    val strings = LocalMobileStrings.current
    var pendingDownload by remember { mutableStateOf<FileItem?>(null) }
    val downloadLauncher = documentPicker.rememberDownloadLauncher { target ->
        pendingDownload?.let { onDownload(it, target.handle) }
        pendingDownload = null
    }
    fun download(file: FileItem) {
        pendingDownload = file
        downloadLauncher(DownloadRequest(file.fileName, file.mimeType))
    }
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(state.feedback?.id) {
        state.feedback?.let { snackbar.showSnackbar(strings.resolve(it.message).orEmpty()); onConsumeFeedback() }
    }
    Box(Modifier.fillMaxSize().testTag("tag-files-screen")) {
        OrganizationFilesContent(
            title = tag?.tagName ?: strings[MobileString.TagsTitle],
            query = state.tagFiles.query,
            queryLabel = strings[MobileString.SearchTagFiles],
            files = state.tagFiles,
            emptyTitle = strings[MobileString.TagFilesEmpty],
            emptyDetail = "",
            onQuery = onQuery,
            onRefresh = onRefresh,
            onLoadMore = onLoadMore,
            onScrollAnchor = onScrollAnchor,
            onClick = { if (it.isFolder) onOpenFolder(it) else onPreview(it) },
            onBack = onBack,
            menu = { file -> OrganizationFileMenu(
                file, { onOpenLocation(file) }, { onPreview(file) }, { download(file) },
                onManageTags?.takeIf { canManageTags(file) }?.let { action -> { action(file) } },
                onCreateShare?.takeIf { canCreateShare(file) }?.let { action -> { action(file) } },
                onOpenVersions?.let { action -> { action(file) } },
                MobileString.RemoveFromTag, { Icon(Icons.Default.Label, null) }, { onRemove(file) },
                onOpenMenu = { onMenuOpen(file) }, showTrailing = canManageTags(file),
            ) },
        )
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FileTagEditorOverlay(
    editor: FileTagEditorState,
    wide: Boolean,
    onToggle: (Long) -> Unit,
    onSave: () -> Unit,
    onCreate: (String, String) -> Unit,
    onDismiss: () -> Unit,
    canCreate: Boolean = true,
) {
    val strings = LocalMobileStrings.current
    var creating by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    var color by remember { mutableStateOf(TAG_COLORS.first()) }
    val content: @Composable () -> Unit = {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(editor.file.fileName, style = MaterialTheme.typography.titleMedium)
            when {
                editor.loading -> Box(Modifier.fillMaxWidth().height(120.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                editor.tags.isEmpty() && !creating -> OrganizationEmpty(
                    strings[MobileString.TagsEmpty], strings[MobileString.TagsEmptyDetail],
                )
                else -> LazyColumn(Modifier.fillMaxWidth().fillMaxHeight(0.55f)) {
                    items(editor.tags, key = PersonalTag::id) { tag ->
                        Row(
                            Modifier.fillMaxWidth().clickable { onToggle(tag.id) }.padding(vertical = 6.dp)
                                .testTag("file-tag-${tag.id}"),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(checked = tag.id in editor.selectedIds, onCheckedChange = { onToggle(tag.id) })
                            Box(Modifier.size(14.dp).background(parseTagColor(tag.color), MaterialTheme.shapes.small))
                            Text(tag.tagName, Modifier.weight(1f).padding(start = 8.dp))
                        }
                    }
                }
            }
            if (canCreate && creating) {
                OutlinedTextField(
                    value = name, onValueChange = { name = it }, singleLine = true,
                    label = { Text(strings[MobileString.TagName]) }, modifier = Modifier.fillMaxWidth().testTag("file-tag-new-name"),
                )
                TagColorPicker(color) { color = it }
                Button(
                    enabled = name.trim().length in 1..64 && !editor.creating,
                    onClick = { onCreate(name, color); name = ""; creating = false },
                    modifier = Modifier.testTag("file-tag-new-confirm"),
                ) { Text(strings[MobileString.NewTag]) }
            } else if (canCreate) {
                TextButton(onClick = { creating = true }, modifier = Modifier.testTag("file-tag-create")) {
                    Icon(Icons.Default.Add, null); Text(strings[MobileString.CreateTagHere])
                }
            }
            editor.error?.let { Text(strings.resolve(it).orEmpty(), color = MaterialTheme.colorScheme.error) }
        }
    }
    if (wide) AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag("file-tag-dialog"),
        title = { Text(strings[MobileString.ManageTags]) },
        text = content,
        confirmButton = { Button(enabled = !editor.loading && !editor.saving, onClick = onSave) {
            Text(strings[MobileString.Save])
        } },
        dismissButton = { TextButton(enabled = !editor.saving, onClick = onDismiss) { Text(strings[MobileString.Cancel]) } },
    ) else ModalBottomSheet(onDismissRequest = onDismiss, modifier = Modifier.testTag("file-tag-sheet")) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(0.9f).padding(16.dp)) {
            Text(strings[MobileString.ManageTags], style = MaterialTheme.typography.headlineSmall)
            content()
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(enabled = !editor.saving, onClick = onDismiss) { Text(strings[MobileString.Cancel]) }
                Button(enabled = !editor.loading && !editor.saving, onClick = onSave) { Text(strings[MobileString.Save]) }
            }
        }
    }
}

@Composable
private fun OrganizationFilesContent(
    title: String,
    query: String,
    queryLabel: String,
    files: OrganizationFileListState,
    emptyTitle: String,
    emptyDetail: String,
    onQuery: (String) -> Unit,
    onRefresh: () -> Unit,
    onLoadMore: () -> Unit,
    onScrollAnchor: (Long?) -> Unit,
    onClick: (FileItem) -> Unit,
    menu: @Composable (FileItem) -> Unit,
    onBack: (() -> Unit)? = null,
) {
    val strings = LocalMobileStrings.current
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (onBack != null) IconButton(onBack, Modifier.testTag("organization-back")) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, strings[MobileString.BackToParent])
            }
            Text(title, style = MaterialTheme.typography.titleLarge)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = query, onValueChange = onQuery, singleLine = true,
                label = { Text(queryLabel) }, modifier = Modifier.weight(1f).testTag("organization-search"),
            )
            IconButton(onClick = onRefresh, modifier = Modifier.testTag("organization-refresh")) {
                Icon(Icons.Default.Refresh, strings[MobileString.RefreshDirectory])
            }
        }
        when {
            files.loading && !files.hasLoaded -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            files.error != null && !files.hasLoaded -> OrganizationError(strings.resolve(files.error).orEmpty(), onRefresh)
            files.items.isEmpty() -> OrganizationEmpty(emptyTitle, emptyDetail)
            else -> {
                val index = files.items.indexOfFirst { it.id == files.scrollAnchorId }.coerceAtLeast(0)
                val listState = rememberLazyListState(index)
                LaunchedEffect(listState, files.items) {
                    snapshotFlow { listState.firstVisibleItemIndex }.distinctUntilChanged()
                        .collect { onScrollAnchor(files.items.getOrNull(it)?.id) }
                }
                LaunchedEffect(listState, files.items.size, files.hasMore) {
                    snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }.distinctUntilChanged()
                        .collect { if (files.hasMore && it >= files.items.lastIndex - 5) onLoadMore() }
                }
                LazyColumn(state = listState, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(files.items, key = FileItem::id) { file ->
                        Card(Modifier.fillMaxWidth().clickable { onClick(file) }.testTag("organization-file-${file.id}")) {
                            Row(
                                Modifier.fillMaxWidth().padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                Icon(if (file.isFolder) Icons.Default.Folder else Icons.Default.Description, null)
                                Column(Modifier.weight(1f)) {
                                    Text(file.fileName, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(if (file.isFolder) strings[MobileString.Folder] else formatFileSize(file.fileSize))
                                }
                                menu(file)
                            }
                        }
                    }
                    if (files.loadingMore) item { Box(Modifier.fillMaxWidth().padding(12.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    } }
                    files.loadMoreError?.let { error -> item { OrganizationError(strings.resolve(error).orEmpty(), onLoadMore) } }
                }
            }
        }
    }
}

@Composable
private fun OrganizationFileMenu(
    file: FileItem,
    onLocation: () -> Unit,
    onPreview: () -> Unit,
    onDownload: () -> Unit,
    onManageTags: (() -> Unit)?,
    onCreateShare: (() -> Unit)?,
    onOpenVersions: (() -> Unit)?,
    trailingLabel: MobileString,
    trailingIcon: @Composable () -> Unit,
    onTrailing: () -> Unit,
    onOpenMenu: () -> Unit = {},
    showTrailing: Boolean = true,
) {
    val strings = LocalMobileStrings.current
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { onOpenMenu(); expanded = true }, modifier = Modifier.testTag("organization-more-${file.id}")) {
            Icon(Icons.Default.MoreVert, strings[MobileString.MoreActions])
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(text = { Text(strings[MobileString.OpenLocation]) }, leadingIcon = {
                Icon(Icons.Default.LocationOn, null)
            }, onClick = { expanded = false; onLocation() })
            if (!file.isFolder) DropdownMenuItem(text = { Text(strings[MobileString.Preview]) }, leadingIcon = {
                Icon(Icons.Default.Visibility, null)
            }, onClick = { expanded = false; onPreview() })
            if (!file.isFolder) DropdownMenuItem(text = { Text(strings[MobileString.Download]) }, leadingIcon = {
                Icon(Icons.Default.Download, null)
            }, onClick = { expanded = false; onDownload() })
            if (onManageTags != null) DropdownMenuItem(text = { Text(strings[MobileString.ManageTags]) }, leadingIcon = {
                Icon(Icons.Default.Label, null)
            }, onClick = { expanded = false; onManageTags() })
            if (onCreateShare != null) DropdownMenuItem(text = { Text(strings[MobileString.CreateShare]) }, leadingIcon = {
                Icon(Icons.Default.Share, null)
            }, onClick = { expanded = false; onCreateShare() })
            if (!file.isFolder && onOpenVersions != null) DropdownMenuItem(text = { Text(strings[MobileString.VersionHistory]) }, leadingIcon = {
                Icon(Icons.Default.History, null)
            }, onClick = { expanded = false; onOpenVersions() })
            if (showTrailing) DropdownMenuItem(text = { Text(strings[trailingLabel]) }, leadingIcon = trailingIcon,
                onClick = { expanded = false; onTrailing() })
        }
    }
}

@Composable
private fun TagActionsMenu(tag: PersonalTag, onEdit: () -> Unit, onDelete: () -> Unit) {
    val strings = LocalMobileStrings.current
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }, modifier = Modifier.testTag("tag-more-${tag.id}")) {
            Icon(Icons.Default.MoreVert, strings[MobileString.MoreActions])
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(text = { Text(strings[MobileString.EditTag]) }, leadingIcon = { Icon(Icons.Default.Edit, null) },
                onClick = { expanded = false; onEdit() })
            DropdownMenuItem(text = { Text(strings[MobileString.DeleteTag]) }, leadingIcon = { Icon(Icons.Default.Delete, null) },
                onClick = { expanded = false; onDelete() })
        }
    }
}

@Composable
private fun TagEditorDialog(
    title: String,
    initialName: String,
    initialColor: String,
    busy: Boolean,
    error: com.betterclouddrive.mobile.core.localization.UiMessage?,
    onDismiss: () -> Unit,
    onSave: (String, String) -> Unit,
) {
    val strings = LocalMobileStrings.current
    var name by remember(initialName) { mutableStateOf(initialName) }
    var color by remember(initialColor) { mutableStateOf(initialColor) }
    val valid = name.trim().length in 1..64
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag("tag-editor-dialog"),
        title = { Text(title) },
        text = { Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(
                value = name, onValueChange = { name = it }, enabled = !busy, singleLine = true,
                isError = !valid || error != null, label = { Text(strings[MobileString.TagName]) },
                supportingText = { Text(strings.resolve(error) ?: if (!valid) strings[MobileString.ErrorInvalidTagName] else "") },
                modifier = Modifier.fillMaxWidth().testTag("tag-name-input"),
            )
            Text(strings[MobileString.TagColor])
            TagColorPicker(color) { color = it }
        } },
        confirmButton = { Button(enabled = valid && !busy, onClick = { onSave(name, color) }, modifier = Modifier.testTag("tag-save")) {
            if (busy) CircularProgressIndicator(Modifier.height(18.dp)) else Text(strings[MobileString.Save])
        } },
        dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text(strings[MobileString.Cancel]) } },
    )
}

@Composable
private fun TagColorPicker(selected: String, onSelect: (String) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TAG_COLORS.forEach { color ->
            val parsed = parseTagColor(color)
            Box(
                Modifier.size(if (selected.equals(color, true)) 32.dp else 26.dp)
                    .background(parsed, MaterialTheme.shapes.small)
                    .clickable { onSelect(color) }
                    .testTag("tag-color-$color"),
            )
        }
    }
}

@Composable
private fun OrganizationEmpty(title: String, detail: String) {
    Column(
        Modifier.fillMaxSize().padding(24.dp).testTag("organization-empty"),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        if (detail.isNotEmpty()) Text(detail, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun OrganizationError(message: String, onRetry: () -> Unit) {
    val strings = LocalMobileStrings.current
    Column(
        Modifier.fillMaxWidth().padding(24.dp).testTag("organization-error"),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(message, color = MaterialTheme.colorScheme.error)
        OutlinedButton(onClick = onRetry) { Text(strings[MobileString.Retry]) }
    }
}

@Composable
private fun OrganizationFileDetail(
    file: FileItem,
    onDismiss: () -> Unit,
    onPreview: () -> Unit,
    onDownload: () -> Unit,
    onCreateShare: (() -> Unit)?,
    onOpenVersions: (() -> Unit)?,
) {
    val strings = LocalMobileStrings.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(file.fileName) },
        text = { Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            OrganizationDetailRow(strings[MobileString.Type], file.mimeType ?: file.fileType)
            OrganizationDetailRow(strings[MobileString.Size], formatFileSize(file.fileSize))
            OrganizationDetailRow(strings[MobileString.UpdatedAt], formatDate(file.updatedAt))
        } },
        confirmButton = { if (!file.isFolder) Button(onClick = onPreview) { Text(strings[MobileString.Preview]) } },
        dismissButton = { Row {
            if (onCreateShare != null) TextButton(onClick = onCreateShare) { Text(strings[MobileString.CreateShare]) }
            if (!file.isFolder && onOpenVersions != null) TextButton(onClick = onOpenVersions) { Text(strings[MobileString.VersionHistory]) }
            if (!file.isFolder) TextButton(onClick = onDownload) { Text(strings[MobileString.Download]) }
            TextButton(onClick = onDismiss) { Text(strings[MobileString.Close]) }
        } },
    )
}

@Composable
private fun OrganizationDetailRow(label: String, value: String) {
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value)
    }
}

private fun parseTagColor(value: String?): Color = runCatching {
    val hex = value?.removePrefix("#") ?: "1890ff"
    Color((0xFF000000L or hex.toLong(16)).toInt())
}.getOrDefault(Color(0xFF1890FF.toInt()))
