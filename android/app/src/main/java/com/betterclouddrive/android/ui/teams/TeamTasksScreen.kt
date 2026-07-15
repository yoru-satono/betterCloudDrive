package com.betterclouddrive.android.ui.teams

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.hilt.navigation.compose.hiltViewModel
import com.betterclouddrive.android.data.remote.ApiService
import com.betterclouddrive.android.data.remote.dto.PageResult
import com.betterclouddrive.android.data.repository.apiCall
import com.betterclouddrive.android.domain.model.TeamTask
import com.betterclouddrive.android.util.NetworkResult
import com.betterclouddrive.android.util.UiEvent
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

data class TeamTasksUiState(
    val tasks: List<TeamTask> = emptyList(), val total: Long = 0, val page: Int = 1,
    val pages: Int = 1, val status: String? = null, val type: String? = null,
    val loading: Boolean = false, val error: String? = null,
)

@HiltViewModel
class TeamTasksViewModel @Inject constructor(
    private val api: ApiService,
    @ApplicationContext private val context: Context,
) : ViewModel() {
    private val _state = MutableStateFlow(TeamTasksUiState()); val state = _state.asStateFlow()
    private val _events = MutableSharedFlow<UiEvent>(); val events = _events.asSharedFlow()
    private var teamId = 0L

    fun load(teamId: Long, page: Int = 1) { this.teamId = teamId; fetch(page) }
    fun filter(status: String?, type: String?) { _state.update { it.copy(status = status, type = type) }; fetch(1) }
    private fun fetch(page: Int) = viewModelScope.launch {
        _state.update { it.copy(loading = true, error = null) }
        when (val result = apiCall { api.listTeamTasks(teamId, state.value.status, state.value.type, page) }) {
            is NetworkResult.Success -> applyPage(result.data)
            is NetworkResult.Error -> _state.update { it.copy(loading = false, error = result.message) }
            is NetworkResult.Loading -> Unit
        }
    }
    private fun applyPage(page: PageResult<TeamTask>) = _state.update {
        it.copy(tasks = page.records, total = page.total, page = page.page, pages = page.pages, loading = false)
    }
    fun retry(task: TeamTask) = viewModelScope.launch {
        when (val result = apiCall { api.retryTeamTask(teamId, task.id) }) {
            is NetworkResult.Success -> { _events.emit(UiEvent.ShowSnackbar("任务已重新提交")); fetch(state.value.page) }
            is NetworkResult.Error -> _events.emit(UiEvent.ShowSnackbar("无法重试: ${result.message}", true))
            is NetworkResult.Loading -> Unit
        }
    }
    fun download(task: TeamTask, uri: Uri) = viewModelScope.launch {
        try {
            withContext(Dispatchers.IO) {
                val body = api.downloadTeamTaskResult(teamId, task.id)
                context.contentResolver.openOutputStream(uri, "wt")?.use { out -> body.byteStream().use { it.copyTo(out) } }
                    ?: error("无法写入目标位置")
            }
            _events.emit(UiEvent.ShowSnackbar("任务结果已下载"))
        } catch (e: Exception) { _events.emit(UiEvent.ShowSnackbar("下载失败: ${e.message}", true)) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TeamTasksScreen(teamId: Long, onNavigateBack: () -> Unit, viewModel: TeamTasksViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsState(); val events by viewModel.events.collectAsState(initial = null)
    val snackbar = remember { SnackbarHostState() }; var downloadTask by remember { mutableStateOf<TeamTask?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val task = downloadTask; if (uri != null && task != null) viewModel.download(task, uri); downloadTask = null
    }
    LaunchedEffect(teamId) { viewModel.load(teamId) }
    LaunchedEffect(events) { (events as? UiEvent.ShowSnackbar)?.let { snackbar.showSnackbar(it.message) } }
    Scaffold(snackbarHost = { SnackbarHost(snackbar) }, topBar = { TopAppBar(
        title = { Text("团队任务") }, navigationIcon = { IconButton(onClick = onNavigateBack) { Icon(Icons.Default.ArrowBack, "返回") } },
        actions = { IconButton(onClick = { viewModel.load(teamId, state.page) }) { Icon(Icons.Default.Refresh, "刷新") } }
    ) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            FilterRow("状态", listOf(null, "PENDING", "RUNNING", "SUCCEEDED", "FAILED"), state.status) { viewModel.filter(it, state.type) }
            FilterRow("类型", listOf(null, "AUDIT_EXPORT", "FOLDER_ZIP", "THUMBNAIL", "ONLYOFFICE_SAVE", "TEAM_CLEANUP"), state.type) { viewModel.filter(state.status, it) }
            if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp)) }
            LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(state.tasks, key = { it.id }) { task -> Card(Modifier.fillMaxWidth().testTag("team-task-${task.id}")) {
                    Column(Modifier.padding(12.dp)) {
                        Text(task.taskType, style = MaterialTheme.typography.titleMedium); Text("${task.status} · ${task.progress}%")
                        task.errorMessage?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            if (task.canRetry) TextButton(onClick = { viewModel.retry(task) }) { Text("重试") }
                            if (task.canDownload) TextButton(onClick = { downloadTask = task; launcher.launch(task.resultFileName ?: "result") }) { Icon(Icons.Default.Download, null); Text("下载") }
                        }
                    }
                } }
            }
            Row(Modifier.fillMaxWidth().padding(8.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = { viewModel.load(teamId, state.page - 1) }, enabled = state.page > 1) { Text("上一页") }
                Text("${state.page}/${state.pages} · ${state.total} 项")
                Button(onClick = { viewModel.load(teamId, state.page + 1) }, enabled = state.page < state.pages) { Text("下一页") }
            }
        }
    }
}

@Composable private fun FilterRow(label: String, values: List<String?>, selected: String?, onSelect: (String?) -> Unit) {
    LazyColumn(Modifier.fillMaxWidth().height(52.dp), horizontalAlignment = Alignment.Start) {
        item { Row(Modifier.padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(label); values.forEach { value -> FilterChip(selected = selected == value, onClick = { onSelect(value) }, label = { Text(value ?: "全部") }) }
        } }
    }
}
