package com.betterclouddrive.android.ui.files

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.betterclouddrive.android.domain.model.FileItem
import com.betterclouddrive.android.ui.components.*
import com.betterclouddrive.android.util.UiEvent

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FileBrowserScreen(
    folderId: Long?,
    onNavigateToPreview: (Long) -> Unit,
    onNavigateToSearch: () -> Unit,
    onNavigateToVersions: (Long) -> Unit,
    onNavigateToRecycleBin: () -> Unit,
    onNavigateToProfile: () -> Unit,
    onNavigateToShares: () -> Unit,
    onNavigateToFavorites: () -> Unit,
    onNavigateToTags: () -> Unit,
    onLogout: () -> Unit,
    viewModel: FilesViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()
    val events by viewModel.events.collectAsState(initial = null)
    val snackbarHostState = remember { SnackbarHostState() }
    val config = LocalConfiguration.current
    val isTablet = config.screenWidthDp >= 600

    // Dialogs
    var showCreateDialog by remember { mutableStateOf(false) }
    var showRenameDialog by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<FileItem?>(null) }
    var showContextMenu by remember { mutableStateOf(false) }
    var contextTarget by remember { mutableStateOf<FileItem?>(null) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var deleteTarget by remember { mutableStateOf<FileItem?>(null) }

    // File picker for upload
    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { viewModel.uploadFile(it) }
    }

    LaunchedEffect(folderId) {
        viewModel.loadFiles(folderId)
    }

    LaunchedEffect(events) {
        when (events) {
            is UiEvent.ShowSnackbar -> {
                val ev = events as UiEvent.ShowSnackbar
                snackbarHostState.showSnackbar(ev.message)
            }
            else -> {}
        }
    }

    // Tablet: List-Detail layout
    if (isTablet) {
        TabletFileBrowser(
            uiState = uiState,
            onFileClick = { f ->
                if (f.isFolder) viewModel.navigateToFolder(f.id, f.fileName)
                else viewModel.selectFile(f)
            },
            onFileLongClick = { f -> contextTarget = f; showContextMenu = true },
            onParentClick = { viewModel.navigateToParent() },
            onBreadcrumbClick = { viewModel.navigateToBreadcrumb(it) },
            onCreateFolder = { showCreateDialog = true },
            onUploadFile = { filePickerLauncher.launch("*/*") },
            onToggleView = { viewModel.toggleViewMode() },
            onSearch = onNavigateToSearch,
            onProfile = onNavigateToProfile,
            onLogout = onLogout,
            snackbarHostState = snackbarHostState,
        )
    } else {
        // Phone layout
        PhoneFileBrowser(
            uiState = uiState,
            onFileClick = { f ->
                if (f.isFolder) viewModel.navigateToFolder(f.id, f.fileName)
                else onNavigateToPreview(f.id)
            },
            onFileLongClick = { f -> contextTarget = f; showContextMenu = true },
            onParentClick = { viewModel.navigateToParent() },
            onBreadcrumbClick = { viewModel.navigateToBreadcrumb(it) },
            onCreateFolder = { showCreateDialog = true },
            onUploadFile = { filePickerLauncher.launch("*/*") },
            onToggleView = { viewModel.toggleViewMode() },
            onSearch = onNavigateToSearch,
            onProfile = onNavigateToProfile,
            snackbarHostState = snackbarHostState,
        )
    }

    // Context menu (BottomSheet)
    if (showContextMenu && contextTarget != null) {
        ContextMenuSheet(
            file = contextTarget!!,
            onDismiss = { showContextMenu = false },
            onRename = { f -> renameTarget = f; showRenameDialog = true; showContextMenu = false },
            onDelete = { f -> deleteTarget = f; showDeleteConfirm = true; showContextMenu = false },
            onVersions = { f -> onNavigateToVersions(f.id); showContextMenu = false },
            onPreview = { f -> onNavigateToPreview(f.id); showContextMenu = false },
        )
    }

    // Create folder dialog
    if (showCreateDialog) {
        InputDialog(
            title = "新建文件夹",
            placeholder = "文件夹名称",
            onDismiss = { showCreateDialog = false },
            onConfirm = { name -> viewModel.createFolder(name); showCreateDialog = false },
        )
    }

    // Rename dialog
    if (showRenameDialog && renameTarget != null) {
        InputDialog(
            title = "重命名",
            initialValue = renameTarget!!.fileName,
            onDismiss = { showRenameDialog = false },
            onConfirm = { name -> viewModel.renameFile(renameTarget!!.id, name); showRenameDialog = false },
        )
    }

    // Delete confirmation
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PhoneFileBrowser(
    uiState: FilesUiState,
    onFileClick: (FileItem) -> Unit,
    onFileLongClick: (FileItem) -> Unit,
    onParentClick: () -> Unit,
    onBreadcrumbClick: (BreadcrumbItem) -> Unit,
    onCreateFolder: () -> Unit,
    onUploadFile: () -> Unit,
    onToggleView: () -> Unit,
    onSearch: () -> Unit,
    onProfile: () -> Unit,
    snackbarHostState: SnackbarHostState,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    BreadcrumbBar(
                        items = uiState.breadcrumb,
                        onCrumbClick = onBreadcrumbClick,
                    )
                },
                actions = {
                    IconButton(onClick = onSearch) {
                        Icon(Icons.Default.Search, "搜索")
                    }
                    IconButton(onClick = onToggleView) {
                        Icon(
                            if (uiState.viewMode == ViewMode.LIST) Icons.Default.GridView else Icons.Default.List,
                            "切换视图",
                        )
                    }
                    IconButton(onClick = onProfile) {
                        Icon(Icons.Default.Person, "我的")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        floatingActionButton = {
            FloatingActionButton(
                onClick = onUploadFile,
                containerColor = MaterialTheme.colorScheme.primary,
            ) {
                Icon(Icons.Default.Add, "上传", tint = MaterialTheme.colorScheme.onPrimary)
            }
        },
        bottomBar = {
            BottomNavigationBar(
                currentRoute = "files",
                onFiles = {},
                onShares = {},
                onFavorites = {},
                onTags = {},
                onProfile = onProfile,
            )
        },
    ) { padding ->
        FileContentArea(
            uiState = uiState,
            onFileClick = onFileClick,
            onFileLongClick = onFileLongClick,
            onParentClick = onParentClick,
            onCreateFolder = onCreateFolder,
            modifier = Modifier.padding(padding),
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TabletFileBrowser(
    uiState: FilesUiState,
    onFileClick: (FileItem) -> Unit,
    onFileLongClick: (FileItem) -> Unit,
    onParentClick: () -> Unit,
    onBreadcrumbClick: (BreadcrumbItem) -> Unit,
    onCreateFolder: () -> Unit,
    onUploadFile: () -> Unit,
    onToggleView: () -> Unit,
    onSearch: () -> Unit,
    onProfile: () -> Unit,
    onLogout: () -> Unit,
    snackbarHostState: SnackbarHostState,
) {
    Row(modifier = Modifier.fillMaxSize()) {
        // Navigation Rail
        NavigationRail(
            modifier = Modifier.fillMaxHeight(),
            containerColor = MaterialTheme.colorScheme.surface,
        ) {
            Spacer(modifier = Modifier.height(16.dp))
            NavigationRailItem(
                selected = true,
                onClick = {},
                icon = { Icon(Icons.Default.Folder, "文件") },
                label = { Text("文件") },
            )
            NavigationRailItem(
                selected = false,
                onClick = {},
                icon = { Icon(Icons.Default.Share, "分享") },
                label = { Text("分享") },
            )
            NavigationRailItem(
                selected = false,
                onClick = {},
                icon = { Icon(Icons.Default.Star, "收藏") },
                label = { Text("收藏") },
            )
            NavigationRailItem(
                selected = false,
                onClick = {},
                icon = { Icon(Icons.Default.Label, "标签") },
                label = { Text("标签") },
            )
            NavigationRailItem(
                selected = false,
                onClick = {},
                icon = { Icon(Icons.Default.Delete, "回收站") },
                label = { Text("回收站") },
            )
            Spacer(modifier = Modifier.weight(1f))
            NavigationRailItem(
                selected = false,
                onClick = onProfile,
                icon = { Icon(Icons.Default.Settings, "设置") },
                label = { Text("设置") },
            )
            Spacer(modifier = Modifier.height(8.dp))
        }

        // Main content area
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        BreadcrumbBar(items = uiState.breadcrumb, onCrumbClick = onBreadcrumbClick)
                    },
                    actions = {
                        IconButton(onClick = onSearch) { Icon(Icons.Default.Search, "搜索") }
                        IconButton(onClick = onToggleView) {
                            Icon(if (uiState.viewMode == ViewMode.LIST) Icons.Default.GridView else Icons.Default.List, "切换")
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                )
            },
            snackbarHost = { SnackbarHost(snackbarHostState) },
            floatingActionButton = {
                Column(horizontalAlignment = Alignment.End) {
                    FloatingActionButton(
                        onClick = onCreateFolder,
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    ) {
                        Icon(Icons.Default.CreateNewFolder, "新建文件夹", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    FloatingActionButton(
                        onClick = onUploadFile,
                        containerColor = MaterialTheme.colorScheme.primary,
                    ) {
                        Icon(Icons.Default.Upload, "上传", tint = MaterialTheme.colorScheme.onPrimary)
                    }
                }
            },
        ) { padding ->
            // Tablet dual-pane: left file list + right detail
            Row(modifier = Modifier.padding(padding)) {
                // Left pane: file list
                Box(modifier = Modifier.weight(0.4f)) {
                    FileContentArea(
                        uiState = uiState,
                        onFileClick = onFileClick,
                        onFileLongClick = onFileLongClick,
                        onParentClick = onParentClick,
                        onCreateFolder = onCreateFolder,
                    )
                }
                // Right pane: detail
                Box(
                    modifier = Modifier
                        .weight(0.6f)
                        .fillMaxHeight()
                ) {
                    if (uiState.selectedFile != null && !uiState.selectedFile!!.isFolder) {
                        FileDetailContent(file = uiState.selectedFile!!)
                    } else {
                        EmptyState(
                            icon = Icons.Default.Info,
                            title = "选择文件查看详情",
                            subtitle = "点击文件查看元数据或预览",
                        )
                    }
                }
            }
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
    Column(modifier = modifier.fillMaxSize()) {
        // Upload progress bar
        if (uiState.isUploading) {
            LinearProgressIndicator(
                progress = { uiState.uploadProgress },
                modifier = Modifier.fillMaxWidth().height(3.dp),
                color = MaterialTheme.colorScheme.primary,
            )
        }

        if (uiState.isLoading) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
            }
        } else if (uiState.files.isEmpty()) {
            EmptyState(
                icon = Icons.Default.FolderOpen,
                title = "此目录为空",
                subtitle = "点击右下角按钮上传文件或创建文件夹",
            )
        } else {
            if (uiState.viewMode == ViewMode.LIST) {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    // Parent directory entry
                    if (uiState.currentFolderId != null) {
                        item {
                            FileRow(
                                file = FileItem(fileName = "..", fileType = "folder"),
                                onClick = onParentClick,
                                onLongClick = {},
                                modifier = Modifier,
                            )
                        }
                    }
                    items(uiState.files, key = { it.id }) { file ->
                        FileRow(file = file, onClick = { onFileClick(file) }, onLongClick = { onFileLongClick(file) })
                    }
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(140.dp),
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    // Parent directory entry
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
                        FileCard(file = file, onClick = { onFileClick(file) }, onLongClick = { onFileLongClick(file) })
                    }
                }
            }
        }
    }
}

@Composable
private fun FileDetailContent(file: FileItem) {
    Column(modifier = Modifier.fillMaxSize().padding(24.dp)) {
        FileIcon(file = file, size = 64.dp)
        Spacer(modifier = Modifier.height(16.dp))
        Text(file.fileName, style = MaterialTheme.typography.headlineMedium)
        Spacer(modifier = Modifier.height(12.dp))
        DetailRow("类型", if (file.isFolder) "文件夹" else file.mimeType ?: "文件")
        DetailRow("大小", com.betterclouddrive.android.util.FormatUtil.formatFileSize(file.fileSize))
        DetailRow("修改时间", com.betterclouddrive.android.util.FormatUtil.formatFullDate(file.updatedAt))
        DetailRow("创建时间", com.betterclouddrive.android.util.FormatUtil.formatFullDate(file.createdAt))
        if (file.md5Hash != null) DetailRow("MD5", file.md5Hash)
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
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Column(modifier = Modifier.padding(bottom = 32.dp)) {
            ListItem(
                headlineContent = { Text("预览", color = MaterialTheme.colorScheme.onSurface) },
                leadingContent = { Icon(Icons.Default.Visibility, null) },
                modifier = Modifier.clickable { onPreview(file) },
            )
            ListItem(
                headlineContent = { Text("重命名", color = MaterialTheme.colorScheme.onSurface) },
                leadingContent = { Icon(Icons.Default.Edit, null) },
                modifier = Modifier.clickable { onRename(file) },
            )
            if (!file.isFolder) {
                ListItem(
                    headlineContent = { Text("版本历史", color = MaterialTheme.colorScheme.onSurface) },
                    leadingContent = { Icon(Icons.Default.History, null) },
                    modifier = Modifier.clickable { onVersions(file) },
                )
            }
            ListItem(
                headlineContent = { Text("删除", color = MaterialTheme.colorScheme.error) },
                leadingContent = { Icon(Icons.Default.Delete, null, tint = MaterialTheme.colorScheme.error) },
                modifier = Modifier.clickable { onDelete(file) },
            )
        }
    }
}

@Composable
fun BottomNavigationBar(
    currentRoute: String,
    onFiles: () -> Unit,
    onShares: () -> Unit,
    onFavorites: () -> Unit,
    onTags: () -> Unit,
    onProfile: () -> Unit,
) {
    NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
        NavigationBarItem(
            selected = currentRoute == "files",
            onClick = onFiles,
            icon = { Icon(Icons.Default.Folder, "文件") },
            label = { Text("文件") },
        )
        NavigationBarItem(
            selected = currentRoute == "shares",
            onClick = onShares,
            icon = { Icon(Icons.Default.Share, "分享") },
            label = { Text("分享") },
        )
        NavigationBarItem(
            selected = currentRoute == "favorites",
            onClick = onFavorites,
            icon = { Icon(Icons.Default.Star, "收藏") },
            label = { Text("收藏") },
        )
        NavigationBarItem(
            selected = currentRoute == "tags",
            onClick = onTags,
            icon = { Icon(Icons.Default.Label, "标签") },
            label = { Text("标签") },
        )
        NavigationBarItem(
            selected = currentRoute == "profile",
            onClick = onProfile,
            icon = { Icon(Icons.Default.Person, "我的") },
            label = { Text("我的") },
        )
    }
}

