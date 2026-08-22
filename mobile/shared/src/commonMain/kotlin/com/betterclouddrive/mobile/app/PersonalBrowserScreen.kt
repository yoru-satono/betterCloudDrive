package com.betterclouddrive.mobile.app

import androidx.compose.foundation.clickable
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
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.betterclouddrive.mobile.core.model.FileItem
import com.betterclouddrive.mobile.core.platform.DocumentPicker
import com.betterclouddrive.mobile.core.platform.PickedDocument
import com.betterclouddrive.mobile.feature.files.FileViewMode
import kotlinx.coroutines.flow.distinctUntilChanged

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PersonalSpaceScreen(
    state: PersonalFilesUiState,
    documentPicker: DocumentPicker,
    onUpload: (PickedDocument) -> Unit,
    onDownload: (FileItem, String) -> Unit,
    onOpenFolder: (FileItem) -> Unit,
    onSelectFile: (FileItem?) -> Unit,
    onLoadMore: () -> Unit,
    onScrollAnchor: (Long?) -> Unit,
    onRetry: () -> Unit,
    onGoRoot: () -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxSize().testTag("personal-content")) {
        val wide = maxWidth >= 600.dp
        if (wide) {
            Row(Modifier.fillMaxSize()) {
                PersonalDirectoryContent(
                    state, documentPicker, onUpload, onOpenFolder, onSelectFile,
                    onLoadMore, onScrollAnchor, onRetry, onGoRoot,
                    Modifier.weight(0.58f),
                )
                VerticalDivider(Modifier.fillMaxHeight())
                Box(Modifier.weight(0.42f).fillMaxHeight().testTag("personal-detail-pane")) {
                    state.selectedFile?.let { file ->
                        FileDetailContent(file, documentPicker, onDownload)
                    } ?: EmptyDetail()
                }
            }
        } else {
            PersonalDirectoryContent(
                state, documentPicker, onUpload, onOpenFolder, onSelectFile,
                onLoadMore, onScrollAnchor, onRetry, onGoRoot,
                Modifier.fillMaxSize(),
            )
            state.selectedFile?.let { file ->
                ModalBottomSheet(
                    onDismissRequest = { onSelectFile(null) },
                    modifier = Modifier.testTag("personal-detail-sheet"),
                ) {
                    FileDetailContent(file, documentPicker, onDownload)
                }
            }
        }
    }
}

@Composable
private fun PersonalDirectoryContent(
    state: PersonalFilesUiState,
    documentPicker: DocumentPicker,
    onUpload: (PickedDocument) -> Unit,
    onOpenFolder: (FileItem) -> Unit,
    onSelectFile: (FileItem?) -> Unit,
    onLoadMore: () -> Unit,
    onScrollAnchor: (Long?) -> Unit,
    onRetry: () -> Unit,
    onGoRoot: () -> Unit,
    modifier: Modifier,
) {
    val directory = state.current
    Column(modifier.padding(horizontal = 16.dp).testTag("personal-folder-${directory.folderId ?: "root"}")) {
        if (directory.refreshing) {
            LinearProgressIndicator(Modifier.fillMaxWidth().testTag("personal-refreshing"))
        }
        if (directory.folderId == null) {
            Card(Modifier.fillMaxWidth().padding(top = 12.dp).testTag("personal-root-upload-card")) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("上传到个人根目录", style = MaterialTheme.typography.titleMedium)
                    Box(Modifier.testTag("personal-upload")) {
                        documentPicker.UploadButton(enabled = true, onPicked = onUpload)
                    }
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
                    Text(message, Modifier.weight(1f), color = MaterialTheme.colorScheme.error)
                    OutlinedButton(onClick = onRetry) { Text("重试") }
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
                state, onOpenFolder, onSelectFile, onLoadMore, onScrollAnchor,
            )
            else -> PersonalGrid(
                state, onOpenFolder, onSelectFile, onLoadMore, onScrollAnchor,
            )
        }
    }
}

@Composable
private fun PersonalList(
    state: PersonalFilesUiState,
    onOpenFolder: (FileItem) -> Unit,
    onSelectFile: (FileItem?) -> Unit,
    onLoadMore: () -> Unit,
    onScrollAnchor: (Long?) -> Unit,
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
            PersonalFileRow(file) { if (file.isFolder) onOpenFolder(file) else onSelectFile(file) }
        }
        item { PagingFooter(state.current, onLoadMore) }
    }
}

@Composable
private fun PersonalGrid(
    state: PersonalFilesUiState,
    onOpenFolder: (FileItem) -> Unit,
    onSelectFile: (FileItem?) -> Unit,
    onLoadMore: () -> Unit,
    onScrollAnchor: (Long?) -> Unit,
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
            PersonalFileCard(file) { if (file.isFolder) onOpenFolder(file) else onSelectFile(file) }
        }
        item { PagingFooter(state.current, onLoadMore) }
    }
}

@Composable
private fun PersonalFileRow(file: FileItem, onClick: () -> Unit) {
    Card(Modifier.fillMaxWidth().testTag("personal-file-${file.id}").clickable(onClick = onClick)) {
        Row(
            Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(
                if (file.isFolder) Icons.Default.Folder else Icons.Default.Description,
                contentDescription = if (file.isFolder) "文件夹" else "文件",
            )
            Column(Modifier.weight(1f)) {
                Text(file.fileName, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    if (file.isFolder) "文件夹" else "${formatFileSize(file.fileSize)} · ${formatDate(file.updatedAt)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun PersonalFileCard(file: FileItem, onClick: () -> Unit) {
    Card(Modifier.testTag("personal-file-${file.id}").clickable(onClick = onClick)) {
        Column(
            Modifier.fillMaxWidth().padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(
                if (file.isFolder) Icons.Default.Folder else Icons.Default.Description,
                contentDescription = if (file.isFolder) "文件夹" else "文件",
            )
            Text(file.fileName, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(
                if (file.isFolder) "文件夹" else formatFileSize(file.fileSize),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun PagingFooter(state: DirectoryPageState, onRetry: () -> Unit) {
    when {
        state.loadingMore -> Box(
            Modifier.fillMaxWidth().padding(12.dp).testTag("personal-loading-more"),
            contentAlignment = Alignment.Center,
        ) { CircularProgressIndicator() }
        state.loadMoreError != null -> Column(
            Modifier.fillMaxWidth().padding(12.dp).testTag("personal-load-more-error"),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(state.loadMoreError, color = MaterialTheme.colorScheme.error)
            OutlinedButton(onClick = onRetry, modifier = Modifier.testTag("personal-load-more-retry")) {
                Text("重试加载更多")
            }
        }
        !state.hasMore -> Text(
            "共 ${state.total} 项",
            Modifier.fillMaxWidth().padding(12.dp).testTag("personal-page-end"),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        else -> Spacer(Modifier.height(1.dp))
    }
}

@Composable
private fun EmptyDirectory(isRoot: Boolean) {
    Column(
        Modifier.fillMaxSize().testTag("personal-empty"),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(Icons.Default.Folder, contentDescription = null)
        Text(if (isRoot) "个人空间为空" else "此目录为空", style = MaterialTheme.typography.titleMedium)
        Text(if (isRoot) "上传文件后会显示在这里" else "返回上级目录继续浏览")
    }
}

@Composable
private fun DirectoryError(message: String, nested: Boolean, onRetry: () -> Unit, onGoRoot: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(24.dp).testTag("personal-error"),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("目录加载失败", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.error)
        Text(message)
        Button(onClick = onRetry, modifier = Modifier.testTag("personal-error-retry")) { Text("重试") }
        if (nested) OutlinedButton(onClick = onGoRoot, modifier = Modifier.testTag("personal-error-root")) {
            Text("返回根目录")
        }
    }
}

@Composable
private fun EmptyDetail() {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(Icons.Default.Info, contentDescription = null)
        Text("选择文件查看详情")
    }
}

@Composable
private fun FileDetailContent(
    file: FileItem,
    documentPicker: DocumentPicker,
    onDownload: (FileItem, String) -> Unit,
) {
    Column(
        Modifier.fillMaxWidth().padding(24.dp).testTag("personal-file-detail-${file.id}"),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(Icons.Default.Description, contentDescription = "文件", tint = MaterialTheme.colorScheme.primary)
        Text(file.fileName, style = MaterialTheme.typography.headlineSmall)
        DetailRow("类型", file.mimeType ?: "文件")
        DetailRow("大小", formatFileSize(file.fileSize))
        DetailRow("修改时间", formatDate(file.updatedAt))
        DetailRow("创建时间", formatDate(file.createdAt))
        DetailRow("MD5", file.md5Hash ?: "—")
        DetailRow("版本数", file.versionCount.toString())
        Box(Modifier.testTag("personal-detail-download-${file.id}")) {
            documentPicker.DownloadButton(file.fileName, enabled = true) { target ->
                onDownload(file, target.handle)
            }
        }
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
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "${bytes / 1024} KiB"
    bytes < 1024L * 1024 * 1024 -> "${bytes / (1024 * 1024)} MiB"
    else -> "${bytes / (1024L * 1024 * 1024)} GiB"
}

internal fun formatDate(value: String?): String = value
    ?.take(19)
    ?.replace('T', ' ')
    ?: "—"
