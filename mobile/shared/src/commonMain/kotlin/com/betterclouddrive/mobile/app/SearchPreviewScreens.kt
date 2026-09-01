package com.betterclouddrive.mobile.app

import com.betterclouddrive.mobile.feature.files.PersonalSearchState
import com.betterclouddrive.mobile.feature.files.PersonalPreviewState

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.backhandler.BackHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.betterclouddrive.mobile.core.localization.LocalMobileStrings
import com.betterclouddrive.mobile.core.localization.MobileString
import com.betterclouddrive.mobile.core.localization.UiMessage
import com.betterclouddrive.mobile.core.model.FileItem
import com.betterclouddrive.mobile.core.model.PreviewKind
import com.betterclouddrive.mobile.core.platform.DocumentPicker
import com.betterclouddrive.mobile.core.platform.DownloadRequest
import com.betterclouddrive.mobile.core.platform.MediaPreviewRenderer
import com.betterclouddrive.mobile.core.platform.MediaPreviewRequest
import com.betterclouddrive.mobile.core.platform.OfficeRenderer
import com.betterclouddrive.mobile.feature.files.previewKind
import kotlinx.coroutines.flow.distinctUntilChanged

@OptIn(ExperimentalMaterial3Api::class, ExperimentalComposeUiApi::class)
@Composable
fun PersonalSearchScreen(
    state: PersonalSearchState,
    documentPicker: DocumentPicker,
    onBack: () -> Unit,
    onQueryChanged: (String) -> Unit,
    onSubmit: () -> Unit,
    onLoadMore: () -> Unit,
    onRetry: () -> Unit,
    onScrollAnchor: (Long?) -> Unit,
    onOpenFolder: (FileItem) -> Unit,
    onOpenLocation: (FileItem) -> Unit,
    onPreview: (FileItem) -> Unit,
    onDownload: (FileItem, String) -> Unit,
    onCreateShare: (FileItem) -> Unit,
    onOpenVersions: (FileItem) -> Unit,
) {
    val strings = LocalMobileStrings.current
    var pendingDownload by remember { mutableStateOf<FileItem?>(null) }
    var detail by remember { mutableStateOf<FileItem?>(null) }
    val downloadLauncher = documentPicker.rememberDownloadLauncher { destination ->
        pendingDownload?.let { onDownload(it, destination.handle) }
        pendingDownload = null
    }
    fun download(file: FileItem) {
        pendingDownload = file
        downloadLauncher(DownloadRequest(file.fileName, file.mimeType))
    }
    Scaffold(
        modifier = Modifier.testTag("personal-search-screen"),
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.testTag("search-back")) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, strings[MobileString.BackToParent])
                    }
                },
                title = {
                    OutlinedTextField(
                        value = state.query,
                        onValueChange = onQueryChanged,
                        placeholder = { Text(strings[MobileString.SearchHint]) },
                        leadingIcon = { Icon(Icons.Default.Search, null) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { onSubmit() }),
                        modifier = Modifier.fillMaxWidth().testTag("search-input"),
                    )
                },
            )
        },
    ) { padding ->
        SearchResults(
            state = state,
            modifier = Modifier.fillMaxSize().padding(padding),
            onLoadMore = onLoadMore,
            onRetry = onRetry,
            onScrollAnchor = onScrollAnchor,
            onOpen = { file ->
                when {
                    file.isFolder -> onOpenFolder(file)
                    file.previewKind() != PreviewKind.UNSUPPORTED -> onPreview(file)
                    else -> detail = file
                }
            },
            onOpenLocation = onOpenLocation,
            onPreview = onPreview,
            onDownload = ::download,
            onCreateShare = onCreateShare,
            onOpenVersions = onOpenVersions,
        )
    }
    detail?.let { file ->
        ModalBottomSheet(onDismissRequest = { detail = null }) {
            PreviewUnavailableContent(file, onDownload = { download(file) })
        }
    }
}

@Composable
private fun SearchResults(
    state: PersonalSearchState,
    modifier: Modifier,
    onLoadMore: () -> Unit,
    onRetry: () -> Unit,
    onScrollAnchor: (Long?) -> Unit,
    onOpen: (FileItem) -> Unit,
    onOpenLocation: (FileItem) -> Unit,
    onPreview: (FileItem) -> Unit,
    onDownload: (FileItem) -> Unit,
    onCreateShare: (FileItem) -> Unit,
    onOpenVersions: (FileItem) -> Unit,
) {
    val strings = LocalMobileStrings.current
    when {
        state.query.trim().isEmpty() -> CenterMessage(modifier, MobileString.SearchInitial)
        state.searching && state.items.isEmpty() -> Box(modifier, contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        state.error != null && state.items.isEmpty() -> RetryMessage(modifier, strings.resolve(state.error).orEmpty(), onRetry)
        !state.searching && state.submittedQuery.isNotEmpty() && state.items.isEmpty() -> Box(modifier, contentAlignment = Alignment.Center) {
            Text(strings.format(MobileString.SearchNoResults, state.submittedQuery), modifier = Modifier.testTag("search-empty"))
        }
        else -> {
            val initial = state.scrollAnchorId?.let { id -> state.items.indexOfFirst { it.id == id } }?.takeIf { it >= 0 } ?: 0
            val listState = rememberLazyListState(initialFirstVisibleItemIndex = initial)
            LaunchedEffect(listState, state.items.size, state.hasMore) {
                snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }
                    .distinctUntilChanged()
                    .collect { if (state.hasMore && it >= state.items.lastIndex - 5) onLoadMore() }
            }
            LaunchedEffect(listState) {
                snapshotFlow { listState.firstVisibleItemIndex }
                    .distinctUntilChanged()
                    .collect { index -> onScrollAnchor(state.items.getOrNull(index)?.id) }
            }
            LazyColumn(modifier.testTag("search-results"), state = listState) {
                item { Text(strings.format(MobileString.SearchResultCount, state.total), Modifier.padding(16.dp)) }
                items(state.items, key = FileItem::id) { file ->
                    SearchResultRow(file, onOpen, onOpenLocation, onPreview, onDownload, onCreateShare, onOpenVersions)
                    HorizontalDivider()
                }
                item {
                    when {
                        state.loadingMore -> Box(Modifier.fillMaxWidth().padding(20.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator()
                        }
                        state.loadMoreError != null -> RetryMessage(
                            Modifier.fillMaxWidth().padding(16.dp), strings.resolve(state.loadMoreError).orEmpty(), onLoadMore,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchResultRow(
    file: FileItem,
    onOpen: (FileItem) -> Unit,
    onOpenLocation: (FileItem) -> Unit,
    onPreview: (FileItem) -> Unit,
    onDownload: (FileItem) -> Unit,
    onCreateShare: (FileItem) -> Unit,
    onOpenVersions: (FileItem) -> Unit,
) {
    val strings = LocalMobileStrings.current
    var expanded by remember { mutableStateOf(false) }
    ListItem(
        headlineContent = { Text(file.fileName, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = { Text(if (file.isFolder) strings[MobileString.Folder] else formatPreviewSize(file.fileSize)) },
        leadingContent = { Icon(if (file.isFolder) Icons.Default.Folder else Icons.Default.Description, null) },
        trailingContent = {
            Box {
                IconButton(onClick = { expanded = true }, modifier = Modifier.testTag("search-more-${file.id}")) {
                    Icon(Icons.Default.MoreVert, strings[MobileString.MoreActions])
                }
                DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    DropdownMenuItem(
                        text = { Text(strings[MobileString.OpenLocation]) },
                        leadingIcon = { Icon(Icons.Default.LocationOn, null) },
                        onClick = { expanded = false; onOpenLocation(file) },
                        modifier = Modifier.testTag("search-location-${file.id}"),
                    )
                    if (!file.isFolder && file.previewKind() != PreviewKind.UNSUPPORTED) DropdownMenuItem(
                        text = { Text(strings[MobileString.Preview]) },
                        leadingIcon = { Icon(Icons.Default.Visibility, null) },
                        onClick = { expanded = false; onPreview(file) },
                        modifier = Modifier.testTag("search-preview-${file.id}"),
                    )
                    if (!file.isFolder) DropdownMenuItem(
                        text = { Text(strings[MobileString.Download]) },
                        leadingIcon = { Icon(Icons.Default.Download, null) },
                        onClick = { expanded = false; onDownload(file) },
                        modifier = Modifier.testTag("search-download-${file.id}"),
                    )
                    if (!file.isFolder) DropdownMenuItem(
                        text = { Text(strings[MobileString.VersionHistory]) },
                        leadingIcon = { Icon(Icons.Default.History, null) },
                        onClick = { expanded = false; onOpenVersions(file) },
                        modifier = Modifier.testTag("search-versions-${file.id}"),
                    )
                    DropdownMenuItem(
                        text = { Text(strings[MobileString.CreateShare]) },
                        leadingIcon = { Icon(Icons.Default.Share, null) },
                        onClick = { expanded = false; onCreateShare(file) },
                        modifier = Modifier.testTag("search-share-${file.id}"),
                    )
                }
            }
        },
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onOpen(file) }
            .testTag("search-result-${file.id}"),
    )
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalComposeUiApi::class)
@Composable
fun PersonalPreviewScreen(
    state: PersonalPreviewState,
    documentPicker: DocumentPicker,
    officeRenderer: OfficeRenderer,
    mediaRenderer: MediaPreviewRenderer,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    onDownload: (FileItem, String) -> Unit,
    onPositionChanged: (Long) -> Unit,
    onRendererError: (UiMessage) -> Unit,
) {
    val strings = LocalMobileStrings.current
    var officeStatus by remember(state.fileId) { mutableStateOf<UiMessage?>(null) }
    var pendingDownload by remember { mutableStateOf<FileItem?>(null) }
    val downloadLauncher = documentPicker.rememberDownloadLauncher { destination ->
        pendingDownload?.let { onDownload(it, destination.handle) }
        pendingDownload = null
    }
    fun download() {
        state.file?.let { file ->
            pendingDownload = file
            downloadLauncher(DownloadRequest(file.fileName, file.mimeType))
        }
    }
    BackHandler(onBack = onBack)
    Scaffold(
        modifier = Modifier.testTag("personal-preview-screen"),
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(state.file?.fileName ?: strings[MobileString.Preview], maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(strings[MobileString.PreviewReadOnly], style = MaterialTheme.typography.labelSmall)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.testTag("preview-back")) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, strings[MobileString.BackToParent])
                    }
                },
                actions = {
                    IconButton(onClick = ::download, enabled = state.file != null, modifier = Modifier.testTag("preview-download")) {
                        Icon(Icons.Default.Download, strings[MobileString.Download])
                    }
                },
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
            when {
                state.loading -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator()
                    Text(strings[MobileString.PreviewLoading])
                }
                state.error != null -> RetryMessage(Modifier.fillMaxSize(), strings.resolve(state.error).orEmpty(), onRetry)
                state.kind == PreviewKind.TEXT -> TextPreviewContent(state)
                state.kind == PreviewKind.OFFICE && state.office != null -> Column(Modifier.fillMaxSize()) {
                    officeStatus?.let { Text(strings.resolve(it).orEmpty(), Modifier.padding(8.dp).testTag("office-preview-status")) }
                    officeRenderer.Render(state.office, Modifier.fillMaxSize().weight(1f)) { officeStatus = it }
                }
                state.kind in setOf(PreviewKind.IMAGE, PreviewKind.VIDEO) && state.mediaUrl != null -> mediaRenderer.Render(
                    MediaPreviewRequest(
                        kind = requireNotNull(state.kind),
                        url = state.mediaUrl,
                        fileKey = "personal-${state.fileId}-${state.file?.md5Hash ?: state.file?.versionCount}",
                        initialPositionMillis = state.playbackPositionMillis,
                    ),
                    Modifier.fillMaxSize().testTag(if (state.kind == PreviewKind.IMAGE) "image-preview" else "video-preview"),
                    onPositionChanged,
                    onRendererError,
                )
                else -> state.file?.let { PreviewUnavailableContent(it, ::download) }
            }
        }
    }
}

@Composable
private fun TextPreviewContent(state: PersonalPreviewState) {
    val strings = LocalMobileStrings.current
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        if (state.textTruncated) Text(
            strings[MobileString.PreviewTruncated],
            color = MaterialTheme.colorScheme.secondary,
            modifier = Modifier.padding(bottom = 12.dp).testTag("text-preview-truncated"),
        )
        if (state.text.isNullOrEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text(strings[MobileString.PreviewEmptyText]) }
        } else {
            SelectionContainer {
                Text(
                    state.text,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).testTag("text-preview-content"),
                )
            }
        }
    }
}

@Composable
private fun PreviewUnavailableContent(file: FileItem, onDownload: () -> Unit) {
    val strings = LocalMobileStrings.current
    Column(
        Modifier.fillMaxWidth().padding(24.dp).testTag("preview-unsupported"),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(Icons.Default.Description, null)
        Text(strings[MobileString.PreviewUnsupported], style = MaterialTheme.typography.titleMedium)
        Text(file.fileName)
        Text(strings[MobileString.PreviewUnsupportedDetail])
        Button(onClick = onDownload) { Text(strings[MobileString.Download]) }
    }
}

@Composable
private fun CenterMessage(modifier: Modifier, key: MobileString) {
    val strings = LocalMobileStrings.current
    Box(modifier, contentAlignment = Alignment.Center) { Text(strings[key]) }
}

@Composable
private fun RetryMessage(modifier: Modifier, message: String, onRetry: () -> Unit) {
    val strings = LocalMobileStrings.current
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Text(message, color = MaterialTheme.colorScheme.error)
        Spacer(Modifier.width(8.dp))
        Button(onClick = onRetry, modifier = Modifier.testTag("retry-preview-or-search")) {
            Icon(Icons.Default.Refresh, null)
            Text(strings[MobileString.Retry])
        }
    }
}

private fun formatPreviewSize(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "${bytes / 1024} KiB"
    else -> "${bytes / (1024 * 1024)} MiB"
}
