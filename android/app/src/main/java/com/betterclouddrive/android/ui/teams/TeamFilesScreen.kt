package com.betterclouddrive.android.ui.teams

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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.Button
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.betterclouddrive.android.data.repository.TeamRepository
import com.betterclouddrive.android.data.repository.TransferRepository
import com.betterclouddrive.android.domain.model.FileItem
import com.betterclouddrive.android.domain.model.Tag
import com.betterclouddrive.android.domain.model.Team
import com.betterclouddrive.android.domain.model.TeamEffectivePermission
import com.betterclouddrive.android.ui.components.BCDDialog
import com.betterclouddrive.android.ui.components.BreadcrumbItem
import com.betterclouddrive.android.ui.components.BreadcrumbBar
import com.betterclouddrive.android.ui.components.EmptyState
import com.betterclouddrive.android.ui.components.FileRow
import com.betterclouddrive.android.ui.components.InputDialog
import com.betterclouddrive.android.ui.navigation.Screen
import com.betterclouddrive.android.util.FileTypeHelper
import com.betterclouddrive.android.util.FormatUtil
import com.betterclouddrive.android.util.NetworkResult
import com.betterclouddrive.android.util.UiEvent
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class TeamFilesUiState(
    val team: Team? = null,
    val files: List<FileItem> = emptyList(),
    val teamTags: List<Tag> = emptyList(),
    val selectedTagIds: Set<Long> = emptySet(),
    val originalTagIds: Set<Long> = emptySet(),
    val breadcrumb: List<BreadcrumbItem> = listOf(BreadcrumbItem(null, "团队文件")),
    val isLoading: Boolean = false,
    val currentFolderId: Long? = null,
    val searchQuery: String = "",
    val currentPermission: TeamEffectivePermission = TeamEffectivePermission(),
    val unreadNotifications: Int = 0,
)

@HiltViewModel
class TeamFilesViewModel @Inject constructor(
    private val teamRepository: TeamRepository,
    private val transferRepository: TransferRepository,
) : ViewModel() {
    private val _uiState = MutableStateFlow(TeamFilesUiState())
    val uiState = _uiState.asStateFlow()

    private val _events = MutableSharedFlow<UiEvent>()
    val events = _events.asSharedFlow()

    fun open(teamId: Long, folderId: Long?) {
        viewModelScope.launch {
            val team = loadTeam(teamId) ?: return@launch
            val breadcrumb = buildBreadcrumb(teamId, folderId, team)
            _uiState.value = _uiState.value.copy(
                team = team,
                breadcrumb = breadcrumb,
                currentFolderId = folderId,
                searchQuery = "",
                currentPermission = permissionFor(teamId, folderId, team),
                unreadNotifications = unreadCount(),
            )
            loadFilesInternal(teamId, folderId)
        }
    }

    fun refresh() {
        val teamId = _uiState.value.team?.id ?: return
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(unreadNotifications = unreadCount())
            loadFilesInternal(teamId, _uiState.value.currentFolderId)
        }
    }

    fun setSearchQuery(query: String) {
        _uiState.value = _uiState.value.copy(searchQuery = query)
    }

    fun search() {
        val state = _uiState.value
        val teamId = state.team?.id ?: return
        val query = state.searchQuery.trim()
        viewModelScope.launch {
            if (query.isBlank()) {
                loadFilesInternal(teamId, state.currentFolderId)
                return@launch
            }
            _uiState.value = _uiState.value.copy(isLoading = true)
            when (val result = teamRepository.searchFiles(teamId, query, size = 100)) {
                is NetworkResult.Success -> _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    files = result.data.records,
                )
                is NetworkResult.Error -> {
                    _uiState.value = _uiState.value.copy(isLoading = false)
                    _events.emit(UiEvent.ShowSnackbar("搜索失败: ${result.message}", true))
                }
                is NetworkResult.Loading -> Unit
            }
        }
    }

    fun openFolder(folder: FileItem) {
        val teamId = _uiState.value.team?.id ?: return
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                breadcrumb = _uiState.value.breadcrumb + BreadcrumbItem(folder.id, folder.fileName),
                currentFolderId = folder.id,
                searchQuery = "",
                currentPermission = permissionFor(teamId, folder.id, _uiState.value.team),
            )
            loadFilesInternal(teamId, folder.id)
        }
    }

    fun navigateToBreadcrumb(item: BreadcrumbItem) {
        val teamId = _uiState.value.team?.id ?: return
        val breadcrumb = _uiState.value.breadcrumb
        val idx = breadcrumb.indexOfFirst { it.id == item.id }
        if (idx < 0) return
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                breadcrumb = breadcrumb.take(idx + 1),
                currentFolderId = item.id,
                searchQuery = "",
                currentPermission = permissionFor(teamId, item.id, _uiState.value.team),
            )
            loadFilesInternal(teamId, item.id)
        }
    }

    fun navigateToParent() {
        val parent = _uiState.value.breadcrumb.dropLast(1).lastOrNull() ?: return
        navigateToBreadcrumb(parent)
    }

    fun createFolder(name: String) {
        val state = _uiState.value
        val team = state.team ?: return
        if (!state.currentPermission.canWrite) return
        viewModelScope.launch {
            when (val result = teamRepository.createFolder(team.id, state.currentFolderId, name)) {
                is NetworkResult.Success -> {
                    _events.emit(UiEvent.ShowSnackbar("文件夹已创建"))
                    loadFilesInternal(team.id, state.currentFolderId)
                }
                is NetworkResult.Error -> _events.emit(UiEvent.ShowSnackbar("创建失败: ${result.message}", true))
                is NetworkResult.Loading -> Unit
            }
        }
    }

    fun renameFile(fileId: Long, newName: String) {
        val team = _uiState.value.team ?: return
        if (!_uiState.value.currentPermission.canWrite) return
        viewModelScope.launch {
            when (val result = teamRepository.renameFile(team.id, fileId, newName)) {
                is NetworkResult.Success -> {
                    _events.emit(UiEvent.ShowSnackbar("已重命名"))
                    loadFilesInternal(team.id, _uiState.value.currentFolderId)
                }
                is NetworkResult.Error -> _events.emit(UiEvent.ShowSnackbar("重命名失败: ${result.message}", true))
                is NetworkResult.Loading -> Unit
            }
        }
    }

    fun deleteFile(fileId: Long) {
        val team = _uiState.value.team ?: return
        if (!_uiState.value.currentPermission.canDelete) return
        viewModelScope.launch {
            when (val result = teamRepository.deleteFiles(team.id, listOf(fileId))) {
                is NetworkResult.Success -> {
                    _events.emit(UiEvent.ShowSnackbar("已删除"))
                    loadFilesInternal(team.id, _uiState.value.currentFolderId)
                }
                is NetworkResult.Error -> _events.emit(UiEvent.ShowSnackbar("删除失败: ${result.message}", true))
                is NetworkResult.Loading -> Unit
            }
        }
    }

    fun createShare(fileId: Long) {
        val state = _uiState.value
        val team = state.team ?: return
        if (!state.currentPermission.canShare) return
        viewModelScope.launch {
            when (val result = teamRepository.createShare(team.id, fileId)) {
                is NetworkResult.Success -> _events.emit(UiEvent.ShowSnackbar("团队分享已创建: ${result.data.shareCode}"))
                is NetworkResult.Error -> _events.emit(UiEvent.ShowSnackbar("分享失败: ${result.message}", true))
                is NetworkResult.Loading -> Unit
            }
        }
    }

    fun addFavorite(fileId: Long) {
        val team = _uiState.value.team ?: return
        viewModelScope.launch {
            when (val result = teamRepository.addFavorite(team.id, fileId)) {
                is NetworkResult.Success -> _events.emit(UiEvent.ShowSnackbar("已收藏"))
                is NetworkResult.Error -> _events.emit(UiEvent.ShowSnackbar("收藏失败: ${result.message}", true))
                is NetworkResult.Loading -> Unit
            }
        }
    }

    fun loadTagsFor(file: FileItem) {
        val team = _uiState.value.team ?: return
        viewModelScope.launch {
            when (val tagsResult = teamRepository.listTags(team.id)) {
                is NetworkResult.Success -> {
                    val selected = mutableSetOf<Long>()
                    tagsResult.data.forEach { tag ->
                        when (val filesResult = teamRepository.listFilesByTag(team.id, tag.id, size = 100)) {
                            is NetworkResult.Success -> {
                                if (filesResult.data.records.any { it.id == file.id }) selected.add(tag.id)
                            }
                            else -> Unit
                        }
                    }
                    _uiState.value = _uiState.value.copy(
                        teamTags = tagsResult.data.sortedBy { it.tagName.lowercase() },
                        selectedTagIds = selected,
                        originalTagIds = selected,
                    )
                }
                is NetworkResult.Error -> _events.emit(UiEvent.ShowSnackbar("标签加载失败: ${tagsResult.message}", true))
                is NetworkResult.Loading -> Unit
            }
        }
    }

    fun toggleTag(tagId: Long) {
        val selected = _uiState.value.selectedTagIds
        _uiState.value = _uiState.value.copy(
            selectedTagIds = if (tagId in selected) selected - tagId else selected + tagId,
        )
    }

    fun saveTags(file: FileItem) {
        val team = _uiState.value.team ?: return
        val selected = _uiState.value.selectedTagIds
        val original = _uiState.value.originalTagIds
        viewModelScope.launch {
            var failed = false
            (selected - original).forEach { tagId ->
                if (teamRepository.tagFiles(team.id, tagId, listOf(file.id)) is NetworkResult.Error) failed = true
            }
            (original - selected).forEach { tagId ->
                if (teamRepository.untagFile(team.id, tagId, file.id) is NetworkResult.Error) failed = true
            }
            if (failed) {
                _events.emit(UiEvent.ShowSnackbar("部分标签更新失败", true))
            } else {
                _events.emit(UiEvent.ShowSnackbar("标签已更新"))
            }
        }
    }

    fun uploadFiles(uris: List<Uri>) {
        val state = _uiState.value
        val team = state.team ?: return
        if (!state.currentPermission.canWrite || uris.isEmpty()) return
        viewModelScope.launch {
            uris.forEach { uri ->
                val task = transferRepository.enqueueUpload(state.currentFolderId, uri, team.id)
                transferRepository.start(task.id, viewModelScope) {
                    viewModelScope.launch { loadFilesInternal(team.id, state.currentFolderId) }
                }
            }
            _events.emit(UiEvent.ShowSnackbar("已加入传输队列"))
        }
    }

    fun enqueueDownload(file: FileItem, targetUri: Uri) {
        val team = _uiState.value.team ?: return
        viewModelScope.launch {
            val task = transferRepository.enqueueDownload(file.id, file.fileName, file.fileSize, targetUri, team.id)
            _events.emit(UiEvent.ShowSnackbar("已加入传输队列"))
            transferRepository.start(task.id, viewModelScope)
        }
    }

    private suspend fun loadFilesInternal(teamId: Long, folderId: Long?) {
        _uiState.value = _uiState.value.copy(isLoading = true)
        when (val result = teamRepository.listFiles(teamId, folderId, size = 100)) {
            is NetworkResult.Success -> _uiState.value = _uiState.value.copy(
                isLoading = false,
                files = result.data.records,
            )
            is NetworkResult.Error -> {
                _uiState.value = _uiState.value.copy(isLoading = false)
                _events.emit(UiEvent.ShowSnackbar("加载失败: ${result.message}", true))
            }
            is NetworkResult.Loading -> Unit
        }
    }

    private suspend fun permissionFor(teamId: Long, folderId: Long?, team: Team?): TeamEffectivePermission {
        if (folderId == null) {
            val canWrite = team?.canWrite == true
            val canManage = team?.canManageMembers == true
            return TeamEffectivePermission(
                canRead = true,
                canWrite = canWrite,
                canShare = canWrite,
                canDelete = canWrite,
                canManage = canManage,
            )
        }
        return when (val result = teamRepository.getEffectivePermission(teamId, folderId)) {
            is NetworkResult.Success -> result.data
            is NetworkResult.Error -> {
                _events.emit(UiEvent.ShowSnackbar("权限加载失败: ${result.message}", true))
                TeamEffectivePermission()
            }
            is NetworkResult.Loading -> TeamEffectivePermission()
        }
    }

    private suspend fun loadTeam(teamId: Long): Team? {
        return when (val result = teamRepository.getTeam(teamId)) {
            is NetworkResult.Success -> result.data
            is NetworkResult.Error -> {
                _events.emit(UiEvent.ShowSnackbar("团队加载失败: ${result.message}", true))
                null
            }
            is NetworkResult.Loading -> null
        }
    }

    private suspend fun unreadCount(): Int {
        return when (val result = teamRepository.getNotificationUnreadCount()) {
            is NetworkResult.Success -> result.data
            else -> 0
        }
    }

    private suspend fun buildBreadcrumb(teamId: Long, folderId: Long?, team: Team): List<BreadcrumbItem> {
        val items = mutableListOf(BreadcrumbItem(null, team.name))
        if (folderId == null) return items
        val path = mutableListOf<FileItem>()
        var currentId: Long? = folderId
        while (currentId != null) {
            when (val result = teamRepository.getFile(teamId, currentId)) {
                is NetworkResult.Success -> {
                    path.add(result.data)
                    currentId = result.data.parentId
                }
                else -> currentId = null
            }
        }
        path.asReversed().forEach { items.add(BreadcrumbItem(it.id, it.fileName)) }
        return items
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TeamFilesScreen(
    teamId: Long,
    folderId: Long?,
    onNavigateBack: () -> Unit,
    onOpenFolderRoute: (Long, Long?) -> Unit,
    onOpenPreview: (Long, FileItem) -> Unit,
    onOpenOffice: (Long, FileItem) -> Unit,
    onOpenMembers: (Long) -> Unit,
    onOpenSettings: (Long) -> Unit,
    onOpenRecycleBin: (Long) -> Unit,
    onOpenVersions: (Long, Long) -> Unit,
    onOpenNotifications: () -> Unit,
    onNavigateTransfers: () -> Unit,
    viewModel: TeamFilesViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()
    val events by viewModel.events.collectAsState(initial = null)
    val snackbarHostState = remember { SnackbarHostState() }
    var showCreateDialog by remember { mutableStateOf(false) }
    var showRenameDialog by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<FileItem?>(null) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var deleteTarget by remember { mutableStateOf<FileItem?>(null) }
    var menuTarget by remember { mutableStateOf<FileItem?>(null) }
    var showTagsDialog by remember { mutableStateOf(false) }
    var tagsTarget by remember { mutableStateOf<FileItem?>(null) }
    var pendingDownload by remember { mutableStateOf<FileItem?>(null) }

    val uploadLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        viewModel.uploadFiles(uris)
    }
    val downloadLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val file = pendingDownload
        if (uri != null && file != null) viewModel.enqueueDownload(file, uri)
        pendingDownload = null
    }

    LaunchedEffect(teamId, folderId) { viewModel.open(teamId, folderId) }
    LaunchedEffect(events) {
        if (events is UiEvent.ShowSnackbar) {
            snackbarHostState.showSnackbar((events as UiEvent.ShowSnackbar).message)
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    BreadcrumbBar(
                        items = uiState.breadcrumb,
                        onCrumbClick = { crumb ->
                            if (crumb.id == folderId) return@BreadcrumbBar
                            onOpenFolderRoute(teamId, crumb.id)
                            viewModel.navigateToBreadcrumb(crumb)
                        },
                    )
                },
                navigationIcon = { IconButton(onClick = onNavigateBack) { Icon(Icons.Default.ArrowBack, "返回") } },
                actions = {
                    IconButton(onClick = { onOpenMembers(teamId) }, modifier = Modifier.testTag("team-files-members")) {
                        Icon(Icons.Default.Groups, "成员")
                    }
                    IconButton(onClick = { onOpenRecycleBin(teamId) }, modifier = Modifier.testTag("team-files-recycle-bin")) {
                        Icon(Icons.Default.Delete, "团队回收站")
                    }
                    IconButton(onClick = { onOpenSettings(teamId) }, modifier = Modifier.testTag("team-files-settings")) {
                        Icon(Icons.Default.Settings, "设置")
                    }
                    IconButton(onClick = onOpenNotifications, modifier = Modifier.testTag("team-files-notifications")) {
                        BadgedBox(
                            badge = {
                                if (uiState.unreadNotifications > 0) {
                                    Badge { Text(if (uiState.unreadNotifications > 99) "99+" else uiState.unreadNotifications.toString()) }
                                }
                            },
                        ) {
                            Icon(Icons.Default.Notifications, "通知")
                        }
                    }
                    IconButton(onClick = viewModel::refresh, modifier = Modifier.testTag("team-files-refresh")) {
                        Icon(Icons.Default.Refresh, "刷新")
                    }
                    IconButton(onClick = onNavigateTransfers, modifier = Modifier.testTag("team-files-transfers")) {
                        Icon(Icons.Default.Upload, "传输")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        floatingActionButton = {
            if (uiState.currentPermission.canWrite) {
                Column(horizontalAlignment = Alignment.End) {
                    FloatingActionButton(
                        onClick = { showCreateDialog = true },
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier.testTag("team-files-create-folder"),
                    ) {
                        Icon(Icons.Default.CreateNewFolder, "新建文件夹")
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    FloatingActionButton(
                        onClick = { uploadLauncher.launch(arrayOf("*/*")) },
                        modifier = Modifier.testTag("team-files-upload"),
                    ) {
                        Icon(Icons.Default.Upload, "上传")
                    }
                }
            }
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            TeamFilesHeader(uiState = uiState, onSearch = viewModel::search, onQueryChange = viewModel::setSearchQuery)
            when {
                uiState.isLoading -> {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
                uiState.files.isEmpty() -> {
                    EmptyState(
                        icon = Icons.Default.FolderOpen,
                        title = if (uiState.searchQuery.isBlank()) "此团队目录为空" else "未找到团队文件",
                        subtitle = if (uiState.currentPermission.canWrite) "可上传文件或新建文件夹" else "暂无可浏览内容",
                        modifier = Modifier.testTag("team-files-empty"),
                    )
                }
                else -> {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize().testTag("team-files-content"),
                        contentPadding = PaddingValues(vertical = 4.dp),
                    ) {
                        if (uiState.currentFolderId != null) {
                            item {
                                FileRow(
                                    file = FileItem(fileName = "..", fileType = "folder"),
                                    onClick = {
                                        viewModel.navigateToParent()
                                        val parentId = uiState.breadcrumb.dropLast(1).lastOrNull()?.id
                                        onOpenFolderRoute(teamId, parentId)
                                    },
                                    onLongClick = {},
                                )
                            }
                        }
                        items(uiState.files, key = { it.id }) { file ->
                            FileRow(
                                file = file,
                                onClick = {
                                    if (file.isFolder) {
                                        viewModel.openFolder(file)
                                        onOpenFolderRoute(teamId, file.id)
                                    } else if (FileTypeHelper.isOffice(file.fileName)) {
                                        onOpenOffice(teamId, file)
                                    } else {
                                        onOpenPreview(teamId, file)
                                    }
                                },
                                onLongClick = { menuTarget = file },
                            )
                        }
                    }
                }
            }
        }
    }

    val target = menuTarget
    if (target != null) {
        TeamFileMenu(
            file = target,
            canWrite = uiState.currentPermission.canWrite,
            canShare = uiState.currentPermission.canShare,
            canDelete = uiState.currentPermission.canDelete,
            onDismiss = { menuTarget = null },
            onOpen = {
                if (FileTypeHelper.isOffice(target.fileName)) onOpenOffice(teamId, target) else onOpenPreview(teamId, target)
                menuTarget = null
            },
            onDownload = {
                pendingDownload = target
                downloadLauncher.launch(target.fileName)
                menuTarget = null
            },
            onVersions = {
                onOpenVersions(teamId, target.id)
                menuTarget = null
            },
            onShare = {
                viewModel.createShare(target.id)
                menuTarget = null
            },
            onFavorite = {
                viewModel.addFavorite(target.id)
                menuTarget = null
            },
            onTags = {
                tagsTarget = target
                showTagsDialog = true
                viewModel.loadTagsFor(target)
                menuTarget = null
            },
            onRename = {
                renameTarget = target
                showRenameDialog = true
                menuTarget = null
            },
            onDelete = {
                deleteTarget = target
                showDeleteConfirm = true
                menuTarget = null
            },
        )
    }

    if (showCreateDialog) {
        InputDialog(
            title = "新建团队文件夹",
            placeholder = "文件夹名称",
            onDismiss = { showCreateDialog = false },
            onConfirm = {
                viewModel.createFolder(it)
                showCreateDialog = false
            },
            confirmText = "创建",
        )
    }

    val rename = renameTarget
    if (showRenameDialog && rename != null) {
        InputDialog(
            title = "重命名",
            initialValue = rename.fileName,
            onDismiss = { showRenameDialog = false },
            onConfirm = {
                viewModel.renameFile(rename.id, it)
                showRenameDialog = false
            },
        )
    }

    val deleting = deleteTarget
    if (showDeleteConfirm && deleting != null) {
        BCDDialog(
            title = "确认删除",
            onDismiss = { showDeleteConfirm = false },
            onConfirm = {
                viewModel.deleteFile(deleting.id)
                showDeleteConfirm = false
            },
            confirmText = "删除",
        ) {
            Text("确定删除 \"${deleting.fileName}\"？文件将从团队空间移入回收站。")
        }
    }

    val tagFile = tagsTarget
    if (showTagsDialog && tagFile != null) {
        BCDDialog(
            title = "团队标签",
            onDismiss = { showTagsDialog = false },
            onConfirm = {
                viewModel.saveTags(tagFile)
                showTagsDialog = false
            },
            confirmText = "保存",
        ) {
            if (uiState.teamTags.isEmpty()) {
                Text("暂无团队标签，可在团队设置中创建。")
            } else {
                Column {
                    uiState.teamTags.forEach { tag ->
                        Row(
                            modifier = Modifier.fillMaxWidth().testTag("team-file-tag-${tag.id}"),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(tag.tagName, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Checkbox(
                                checked = tag.id in uiState.selectedTagIds,
                                onCheckedChange = { viewModel.toggleTag(tag.id) },
                                modifier = Modifier.testTag("team-file-tag-checkbox-${tag.id}"),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TeamFilesHeader(
    uiState: TeamFilesUiState,
    onSearch: () -> Unit,
    onQueryChange: (String) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        val team = uiState.team
        if (team != null) {
            val quotaPercent = if (team.storageQuota > 0) {
                (team.storageUsed.toFloat() / team.storageQuota.toFloat()).coerceIn(0f, 1f)
            } else {
                0f
            }
            Text(
                text = "${team.role} · ${FormatUtil.formatFileSize(team.storageUsed)} / ${FormatUtil.formatFileSize(team.storageQuota)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(modifier = Modifier.height(4.dp))
            LinearProgressIndicator(
                progress = { quotaPercent },
                modifier = Modifier.fillMaxWidth().testTag("team-files-quota-progress"),
                color = if (quotaPercent >= 0.9f) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
            )
            if (quotaPercent >= 0.8f) {
                Text(
                    text = when {
                        quotaPercent >= 1f -> "团队空间已用尽，上传和文档保存可能失败"
                        quotaPercent >= 0.9f -> "团队空间接近上限"
                        else -> "团队空间已超过 80%"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (quotaPercent >= 0.9f) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.tertiary,
                    modifier = Modifier.testTag("team-files-quota-warning"),
                )
            }
            Text(
                text = "当前权限：${permissionSummary(uiState.currentPermission)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag("team-files-permission-summary"),
            )
            Spacer(modifier = Modifier.height(8.dp))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = uiState.searchQuery,
                onValueChange = onQueryChange,
                placeholder = { Text("搜索团队文件...") },
                singleLine = true,
                modifier = Modifier.weight(1f).testTag("team-files-search-input"),
                leadingIcon = { Icon(Icons.Default.Search, null) },
            )
            Button(onClick = onSearch, modifier = Modifier.testTag("team-files-search-submit")) {
                Text("搜索")
            }
        }
    }
}

private fun permissionSummary(permission: TeamEffectivePermission): String {
    val parts = buildList {
        if (permission.canRead) add("读")
        if (permission.canWrite) add("写")
        if (permission.canShare) add("分享")
        if (permission.canDelete) add("删除")
        if (permission.canManage) add("管理")
    }
    return parts.ifEmpty { listOf("无") }.joinToString(" / ")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TeamFileMenu(
    file: FileItem,
    canWrite: Boolean,
    canShare: Boolean,
    canDelete: Boolean,
    onDismiss: () -> Unit,
    onOpen: () -> Unit,
    onDownload: () -> Unit,
    onVersions: () -> Unit,
    onShare: () -> Unit,
    onFavorite: () -> Unit,
    onTags: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Column(modifier = Modifier.padding(bottom = 32.dp)) {
            if (!file.isFolder) {
                MenuItem(
                    title = if (FileTypeHelper.isOffice(file.fileName)) "打开文档" else "预览",
                    icon = Icons.Default.Visibility,
                    tag = "team-file-menu-open",
                    onClick = onOpen,
                )
                MenuItem("下载", Icons.Default.Download, "team-file-menu-download", onDownload)
                MenuItem("版本历史", Icons.Default.History, "team-file-menu-versions", onVersions)
            }
            if (canShare) MenuItem("创建分享", Icons.Default.Share, "team-file-menu-share", onShare)
            MenuItem("收藏", Icons.Default.Star, "team-file-menu-favorite", onFavorite)
            MenuItem("标签", Icons.Default.Settings, "team-file-menu-tags", onTags)
            if (canWrite) {
                MenuItem("重命名", Icons.Default.Edit, "team-file-menu-rename", onRename)
            }
            if (canDelete) {
                ListItem(
                    headlineContent = { Text("删除", color = MaterialTheme.colorScheme.error) },
                    leadingContent = { Icon(Icons.Default.Delete, null, tint = MaterialTheme.colorScheme.error) },
                    modifier = Modifier.testTag("team-file-menu-delete").clickable(onClick = onDelete),
                )
            }
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
