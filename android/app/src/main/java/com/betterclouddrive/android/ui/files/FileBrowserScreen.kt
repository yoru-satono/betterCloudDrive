package com.betterclouddrive.android.ui.files

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.ListItem
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.betterclouddrive.android.domain.model.FileItem
import com.betterclouddrive.android.ui.components.BCDDialog
import com.betterclouddrive.android.ui.components.BreadcrumbBar
import com.betterclouddrive.android.ui.components.EmptyState
import com.betterclouddrive.android.ui.components.FileCard
import com.betterclouddrive.android.ui.components.FileIcon
import com.betterclouddrive.android.ui.components.FileRow
import com.betterclouddrive.android.ui.components.InputDialog
import com.betterclouddrive.android.ui.navigation.MainScaffold
import com.betterclouddrive.android.util.FormatUtil
import com.betterclouddrive.android.util.UiEvent

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FileBrowserScreen(
    folderId: Long?,
    onNavigateToPreview: (FileItem) -> Unit,
    onNavigateToSearch: () -> Unit,
    onNavigateToVersions: (Long) -> Unit,
    onNavigateToProfile: () -> Unit,
    onNavigateToTransfers: () -> Unit,
    onNavigateMain: (String) -> Unit,
    viewModel: FilesViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()
    val events by viewModel.events.collectAsState(initial = null)
    val snackbarHostState = remember { SnackbarHostState() }
    val isTablet = LocalConfiguration.current.screenWidthDp >= 600

    var showCreateDialog by remember { mutableStateOf(false) }
    var showRenameDialog by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<FileItem?>(null) }
    var showContextMenu by remember { mutableStateOf(false) }
    var contextTarget by remember { mutableStateOf<FileItem?>(null) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var deleteTarget by remember { mutableStateOf<FileItem?>(null) }
    var pendingDownload by remember { mutableStateOf<FileItem?>(null) }

    val uploadLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris: List<Uri> -> viewModel.uploadFiles(uris) }

    val downloadLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/octet-stream"),
    ) { uri: Uri? ->
        val file = pendingDownload
        if (uri != null && file != null) viewModel.enqueueDownload(file, uri)
        pendingDownload = null
    }

    LaunchedEffect(folderId) {
        viewModel.openFolder(folderId)
    }

    LaunchedEffect(events) {
        when (events) {
            is UiEvent.ShowSnackbar -> snackbarHostState.showSnackbar((events as UiEvent.ShowSnackbar).message)
            else -> Unit
        }
    }

    MainScaffold(
        currentRoute = "files",
        onNavigate = onNavigateMain,
        snackbarHostState = snackbarHostState,
        topBar = {
            TopAppBar(
                title = {
                    BreadcrumbBar(
                        items = uiState.breadcrumb,
                        onCrumbClick = viewModel::navigateToBreadcrumb,
                    )
                },
                actions = {
                    IconButton(
                        onClick = onNavigateToSearch,
                        modifier = Modifier.testTag("files-search"),
                    ) { Icon(Icons.Default.Search, "搜索") }
                    IconButton(
                        onClick = viewModel::toggleViewMode,
                        modifier = Modifier.testTag("files-toggle-view"),
                    ) {
                        Icon(
                            imageVector = if (uiState.viewMode == ViewMode.LIST) Icons.Default.GridView else Icons.Default.List,
                            contentDescription = "切换视图",
                        )
                    }
                    IconButton(
                        onClick = onNavigateToTransfers,
                        modifier = Modifier.testTag("files-transfers"),
                    ) { Icon(Icons.Default.Sync, "传输队列") }
                    IconButton(
                        onClick = onNavigateToProfile,
                        modifier = Modifier.testTag("files-profile"),
                    ) { Icon(Icons.Default.Person, "我的") }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        floatingActionButton = {
            Column(horizontalAlignment = Alignment.End) {
                SmallFloatingActionButton(
                    onClick = { showCreateDialog = true },
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.testTag("files-create-folder"),
                ) {
                    Icon(Icons.Default.CreateNewFolder, "新建文件夹")
                }
                Spacer(modifier = Modifier.height(8.dp))
                FloatingActionButton(
                    onClick = { uploadLauncher.launch(arrayOf("*/*")) },
                    containerColor = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.testTag("files-upload"),
                ) {
                    Icon(Icons.Default.Upload, "上传", tint = MaterialTheme.colorScheme.onPrimary)
                }
            }
        },
    ) { contentPadding ->
        if (isTablet) {
            Row(modifier = Modifier.padding(contentPadding).fillMaxSize()) {
                Box(modifier = Modifier.weight(0.42f)) {
                    FileContentArea(
                        uiState = uiState,
                        onFileClick = { file ->
                            if (file.isFolder) viewModel.openFolder(file.id) else viewModel.selectFile(file)
                        },
                        onFileLongClick = { file -> contextTarget = file; showContextMenu = true },
                        onParentClick = viewModel::navigateToParent,
                        onCreateFolder = { showCreateDialog = true },
                    )
                }
                Box(
                    modifier = Modifier
                        .weight(0.58f)
                        .fillMaxHeight(),
                ) {
                    val selected = uiState.selectedFile
                    if (selected != null) {
                        FileDetailContent(
                            file = selected,
                            onPreview = { onNavigateToPreview(selected) },
                            onDownload = {
                                pendingDownload = selected
                                downloadLauncher.launch(selected.fileName)
                            },
                        )
                    } else {
                        EmptyState(
                            icon = Icons.Default.Info,
                            title = "选择文件查看详情",
                            subtitle = "点击文件查看元数据或预览",
                        )
                    }
                }
            }
        } else {
            FileContentArea(
                uiState = uiState,
                onFileClick = { file ->
                    if (file.isFolder) viewModel.openFolder(file.id) else onNavigateToPreview(file)
                },
                onFileLongClick = { file -> contextTarget = file; showContextMenu = true },
                onParentClick = viewModel::navigateToParent,
                onCreateFolder = { showCreateDialog = true },
                modifier = Modifier.padding(contentPadding),
            )
        }
    }

    if (showContextMenu && contextTarget != null) {
        ContextMenuSheet(
            file = contextTarget!!,
            onDismiss = { showContextMenu = false },
            onRename = { file ->
                renameTarget = file
                showRenameDialog = true
                showContextMenu = false
            },
            onDelete = { file ->
                deleteTarget = file
                showDeleteConfirm = true
                showContextMenu = false
            },
            onVersions = { file ->
                onNavigateToVersions(file.id)
                showContextMenu = false
            },
            onPreview = { file ->
                onNavigateToPreview(file)
                showContextMenu = false
            },
            onDownload = { file ->
                pendingDownload = file
                downloadLauncher.launch(file.fileName)
                showContextMenu = false
            },
            onFavorite = { file ->
                viewModel.addFavorite(file)
                showContextMenu = false
            },
            onShare = { file ->
                viewModel.createShare(file)
                showContextMenu = false
            },
        )
    }

    if (showCreateDialog) {
        InputDialog(
            title = "新建文件夹",
            placeholder = "文件夹名称",
            onDismiss = { showCreateDialog = false },
            onConfirm = { name ->
                viewModel.createFolder(name)
                showCreateDialog = false
            },
        )
    }

    if (showRenameDialog && renameTarget != null) {
        InputDialog(
            title = "重命名",
            initialValue = renameTarget!!.fileName,
            onDismiss = { showRenameDialog = false },
            onConfirm = { name ->
                viewModel.renameFile(renameTarget!!.id, name)
                showRenameDialog = false
            },
        )
    }

    if (showDeleteConfirm && deleteTarget != null) {
        BCDDialog(
            title = "确认删除",
            onDismiss = { showDeleteConfirm = false },
            onConfirm = {
                viewModel.deleteFiles(listOf(deleteTarget!!.id))
                showDeleteConfirm = false
            },
            confirmText = "删除",
        ) {
            Text("确定删除 \"${deleteTarget!!.fileName}\"？文件将移至回收站。")
        }
    }
}

@Composable
private fun FileContentArea(
    uiState: FilesUiState,
    onFileClick: (FileItem) -> Unit,
    onFileLongClick: (FileItem) -> Unit,
    onParentClick: () -> Unit,
    onCreateFolder: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize().testTag("files-content")) {
        if (uiState.isUploading) {
            LinearProgressIndicator(
                progress = { uiState.uploadProgress },
                modifier = Modifier.fillMaxWidth().height(3.dp),
                color = MaterialTheme.colorScheme.primary,
            )
        }

        when {
            uiState.isLoading -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                }
            }
            uiState.files.isEmpty() -> {
                EmptyState(
                    icon = Icons.Default.FolderOpen,
                    title = "此目录为空",
                    subtitle = "点击右下角按钮上传文件或创建文件夹",
                )
            }
            uiState.viewMode == ViewMode.LIST -> {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    if (uiState.currentFolderId != null) {
                        item {
                            FileRow(
                                file = FileItem(fileName = "..", fileType = "folder"),
                                onClick = onParentClick,
                                onLongClick = {},
                            )
                        }
                    }
                    items(uiState.files, key = { it.id }) { file ->
                        FileRow(
                            file = file,
                            onClick = { onFileClick(file) },
                            onLongClick = { onFileLongClick(file) },
                        )
                    }
                }
            }
            else -> {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(140.dp),
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (uiState.currentFolderId != null) {
                        item {
                            FileCard(
                                file = FileItem(fileName = "..", fileType = "folder"),
                                onClick = onParentClick,
                                onLongClick = {},
                            )
                        }
                    }
                    items(uiState.files, key = { it.id }) { file ->
                        FileCard(
                            file = file,
                            onClick = { onFileClick(file) },
                            onLongClick = { onFileLongClick(file) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun FileDetailContent(
    file: FileItem,
    onPreview: () -> Unit,
    onDownload: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize().padding(24.dp)) {
        FileIcon(file = file, size = 64.dp)
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            file.fileName,
            style = MaterialTheme.typography.headlineMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(modifier = Modifier.height(12.dp))
        DetailRow("类型", if (file.isFolder) "文件夹" else file.mimeType ?: "文件")
        DetailRow("大小", FormatUtil.formatFileSize(file.fileSize))
        DetailRow("修改时间", FormatUtil.formatFullDate(file.updatedAt))
        DetailRow("创建时间", FormatUtil.formatFullDate(file.createdAt))
        if (file.md5Hash != null) DetailRow("MD5", file.md5Hash)
        if (!file.isFolder) {
            Spacer(modifier = Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onPreview) {
                    Icon(Icons.Default.Visibility, null)
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("预览")
                }
                OutlinedButton(
                    onClick = onDownload,
                    modifier = Modifier.testTag("file-detail-download"),
                ) {
                    Icon(Icons.Default.Download, null)
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("下载")
                }
            }
        }
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(modifier = Modifier.padding(vertical = 4.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(80.dp),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ContextMenuSheet(
    file: FileItem,
    onDismiss: () -> Unit,
    onRename: (FileItem) -> Unit,
    onDelete: (FileItem) -> Unit,
    onVersions: (FileItem) -> Unit,
    onPreview: (FileItem) -> Unit,
    onDownload: (FileItem) -> Unit,
    onFavorite: (FileItem) -> Unit,
    onShare: (FileItem) -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Column(modifier = Modifier.padding(bottom = 32.dp)) {
            if (!file.isFolder) {
                MenuItem("预览", Icons.Default.Visibility, "file-menu-preview") { onPreview(file) }
                MenuItem("下载", Icons.Default.Download, "file-menu-download") { onDownload(file) }
            }
            MenuItem("收藏", Icons.Default.Star, "file-menu-favorite") { onFavorite(file) }
            MenuItem("分享", Icons.Default.Share, "file-menu-share") { onShare(file) }
            MenuItem("重命名", Icons.Default.Edit, "file-menu-rename") { onRename(file) }
            if (!file.isFolder) {
                MenuItem("版本历史", Icons.Default.History, "file-menu-versions") { onVersions(file) }
            }
            ListItem(
                headlineContent = { Text("删除", color = MaterialTheme.colorScheme.error) },
                leadingContent = { Icon(Icons.Default.Delete, null, tint = MaterialTheme.colorScheme.error) },
                modifier = Modifier.testTag("file-menu-delete").clickable { onDelete(file) },
            )
        }
    }
}

@Composable
private fun MenuItem(
    title: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    tag: String,
    onClick: () -> Unit,
) {
    ListItem(
        headlineContent = { Text(title, color = MaterialTheme.colorScheme.onSurface) },
        leadingContent = { Icon(icon, null) },
        modifier = Modifier.testTag(tag).clickable(onClick = onClick),
    )
}
