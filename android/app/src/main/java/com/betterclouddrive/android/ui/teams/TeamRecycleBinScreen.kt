package com.betterclouddrive.android.ui.teams

import com.betterclouddrive.android.ui.navigation.Workspace
import com.betterclouddrive.android.ui.navigation.WorkspaceSwitchAction

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.betterclouddrive.android.data.repository.TeamRepository
import com.betterclouddrive.android.domain.model.FileItem
import com.betterclouddrive.android.domain.model.Team
import com.betterclouddrive.android.ui.components.EmptyState
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

data class TeamRecycleBinUiState(
    val team: Team? = null,
    val files: List<FileItem> = emptyList(),
    val isLoading: Boolean = false,
)

@HiltViewModel
class TeamRecycleBinViewModel @Inject constructor(
    private val repository: TeamRepository,
) : ViewModel() {
    private val _uiState = MutableStateFlow(TeamRecycleBinUiState())
    val uiState = _uiState.asStateFlow()

    private val _events = MutableSharedFlow<UiEvent>()
    val events = _events.asSharedFlow()

    fun load(teamId: Long) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true)
            val team = when (val result = repository.getTeam(teamId)) {
                is NetworkResult.Success -> result.data
                is NetworkResult.Error -> {
                    _events.emit(UiEvent.ShowSnackbar("团队加载失败: ${result.message}", true))
                    null
                }
                is NetworkResult.Loading -> null
            }
            when (val result = repository.listRecycleBin(teamId)) {
                is NetworkResult.Success -> _uiState.value = TeamRecycleBinUiState(
                    team = team,
                    files = result.data.records,
                    isLoading = false,
                )
                is NetworkResult.Error -> {
                    _uiState.value = _uiState.value.copy(team = team, isLoading = false)
                    _events.emit(UiEvent.ShowSnackbar("回收站加载失败: ${result.message}", true))
                }
                is NetworkResult.Loading -> Unit
            }
        }
    }

    fun restore(file: FileItem) {
        val team = _uiState.value.team ?: return
        if (!team.canWrite) return
        viewModelScope.launch {
            when (val result = repository.restoreFile(team.id, file.id)) {
                is NetworkResult.Success -> {
                    _events.emit(UiEvent.ShowSnackbar("已恢复"))
                    load(team.id)
                }
                is NetworkResult.Error -> _events.emit(UiEvent.ShowSnackbar("恢复失败: ${result.message}", true))
                is NetworkResult.Loading -> Unit
            }
        }
    }

    fun permanentDelete(file: FileItem) {
        val team = _uiState.value.team ?: return
        if (!team.canWrite) return
        viewModelScope.launch {
            when (val result = repository.permanentDeleteFile(team.id, file.id)) {
                is NetworkResult.Success -> {
                    _events.emit(UiEvent.ShowSnackbar("已永久删除"))
                    load(team.id)
                }
                is NetworkResult.Error -> _events.emit(UiEvent.ShowSnackbar("删除失败: ${result.message}", true))
                is NetworkResult.Loading -> Unit
            }
        }
    }

    fun emptyBin() {
        val team = _uiState.value.team ?: return
        if (!team.canWrite) return
        viewModelScope.launch {
            when (val result = repository.emptyRecycleBin(team.id)) {
                is NetworkResult.Success -> {
                    _events.emit(UiEvent.ShowSnackbar("团队回收站已清空"))
                    load(team.id)
                }
                is NetworkResult.Error -> _events.emit(UiEvent.ShowSnackbar("清空失败: ${result.message}", true))
                is NetworkResult.Loading -> Unit
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TeamRecycleBinScreen(
    teamId: Long,
    onNavigateBack: () -> Unit,
    viewModel: TeamRecycleBinViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()
    val events by viewModel.events.collectAsState(initial = null)
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(teamId) { viewModel.load(teamId) }
    LaunchedEffect(events) {
        if (events is UiEvent.ShowSnackbar) snackbarHostState.showSnackbar((events as UiEvent.ShowSnackbar).message)
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("团队回收站") },
                navigationIcon = { IconButton(onClick = onNavigateBack) { Icon(Icons.Default.ArrowBack, "返回") } },
                actions = {
                    WorkspaceSwitchAction(Workspace.TEAM)
                    if (uiState.team?.canWrite == true && uiState.files.isNotEmpty()) {
                        IconButton(onClick = viewModel::emptyBin, modifier = Modifier.testTag("team-recycle-empty")) {
                            Icon(Icons.Default.Delete, "清空")
                        }
                    }
                    IconButton(onClick = { viewModel.load(teamId) }, modifier = Modifier.testTag("team-recycle-refresh")) {
                        Icon(Icons.Default.Refresh, "刷新")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { padding ->
        when {
            uiState.isLoading -> Box(modifier = Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            uiState.files.isEmpty() -> EmptyState(
                icon = Icons.Default.FolderOpen,
                title = "团队回收站为空",
                modifier = Modifier.padding(padding).testTag("team-recycle-empty-state"),
            )
            else -> LazyColumn(modifier = Modifier.fillMaxSize().padding(padding).testTag("team-recycle-list")) {
                items(uiState.files, key = { it.id }) { file ->
                    TeamRecycleRow(
                        file = file,
                        canWrite = uiState.team?.canWrite == true,
                        onRestore = { viewModel.restore(file) },
                        onPermanentDelete = { viewModel.permanentDelete(file) },
                    )
                }
            }
        }
    }
}

@Composable
private fun TeamRecycleRow(
    file: FileItem,
    canWrite: Boolean,
    onRestore: () -> Unit,
    onPermanentDelete: () -> Unit,
) {
    ListItem(
        headlineContent = { Text(file.fileName, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = {
            Text(
                "${if (file.isFolder) "文件夹" else FormatUtil.formatFileSize(file.fileSize)} · ${FormatUtil.formatDate(file.updatedAt)}",
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        trailingContent = {
            if (canWrite) {
                Column(horizontalAlignment = Alignment.End) {
                    TextButton(onClick = onRestore, modifier = Modifier.testTag("team-recycle-restore-${file.id}")) {
                        Icon(Icons.Default.Restore, null)
                        Text("恢复")
                    }
                    TextButton(onClick = onPermanentDelete, modifier = Modifier.testTag("team-recycle-delete-${file.id}")) {
                        Text("永久删除", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        },
        modifier = Modifier.testTag("team-recycle-file-${file.id}"),
    )
}
