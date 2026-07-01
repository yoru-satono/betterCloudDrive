package com.betterclouddrive.android.ui.versions

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.betterclouddrive.android.domain.model.FileVersion
import com.betterclouddrive.android.ui.components.EmptyState
import com.betterclouddrive.android.util.FormatUtil
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.betterclouddrive.android.data.remote.ApiService
import com.betterclouddrive.android.util.NetworkResult
import com.betterclouddrive.android.util.UiEvent
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@HiltViewModel
class VersionsViewModel @Inject constructor(
    private val api: ApiService,
    @ApplicationContext private val context: Context,
) : ViewModel() {
    private val _versions = MutableStateFlow<List<FileVersion>>(emptyList()); val versions = _versions.asStateFlow()
    private val _events = MutableSharedFlow<UiEvent>(); val events = _events.asSharedFlow()
    fun load(fileId: Long, teamId: Long? = null) {
        viewModelScope.launch {
            when (val r = com.betterclouddrive.android.data.repository.apiCall {
                if (teamId == null) api.listVersions(fileId) else api.listTeamVersions(teamId, fileId)
            }) {
                is NetworkResult.Success -> _versions.value = r.data
                is NetworkResult.Error -> {}
                is NetworkResult.Loading -> {}
            }
        }
    }
    fun delete(fileId: Long, versionNumber: Int, teamId: Long? = null) {
        viewModelScope.launch {
            if (teamId == null) {
                api.deleteVersion(fileId, versionNumber)
            } else {
                api.deleteTeamVersion(teamId, fileId, versionNumber)
            }
            load(fileId, teamId)
        }
    }
    fun restore(fileId: Long, versionNumber: Int, teamId: Long? = null) {
        viewModelScope.launch {
            when (val result = com.betterclouddrive.android.data.repository.apiCall {
                if (teamId == null) api.restoreVersion(fileId, versionNumber) else api.restoreTeamVersion(teamId, fileId, versionNumber)
            }) {
                is NetworkResult.Success -> {
                    _events.emit(UiEvent.ShowSnackbar("版本已恢复"))
                    load(fileId, teamId)
                }
                is NetworkResult.Error -> _events.emit(UiEvent.ShowSnackbar("恢复失败: ${result.message}", true))
                is NetworkResult.Loading -> Unit
            }
        }
    }
    fun download(fileId: Long, versionNumber: Int, teamId: Long? = null, targetUri: Uri) {
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val body = if (teamId == null) api.downloadVersion(fileId, versionNumber) else api.downloadTeamVersion(teamId, fileId, versionNumber)
                    context.contentResolver.openOutputStream(targetUri, "wt")?.use { output ->
                        body.byteStream().use { input -> input.copyTo(output) }
                    } ?: throw IllegalStateException("无法写入目标位置")
                }
                _events.emit(UiEvent.ShowSnackbar("版本已下载"))
            } catch (e: Exception) {
                _events.emit(UiEvent.ShowSnackbar("下载失败: ${e.message}", true))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VersionsScreen(
    fileId: Long,
    teamId: Long? = null,
    onNavigateBack: () -> Unit,
    viewModel: VersionsViewModel = hiltViewModel(),
) {
    val versions by viewModel.versions.collectAsState()
    val events by viewModel.events.collectAsState(initial = null)
    val snackbarHostState = remember { SnackbarHostState() }
    var pendingDownload by remember { mutableStateOf<FileVersion?>(null) }
    val downloadLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val version = pendingDownload
        if (uri != null && version != null) viewModel.download(fileId, version.versionNumber, teamId, uri)
        pendingDownload = null
    }
    LaunchedEffect(fileId, teamId) { viewModel.load(fileId, teamId) }
    LaunchedEffect(events) {
        if (events is UiEvent.ShowSnackbar) snackbarHostState.showSnackbar((events as UiEvent.ShowSnackbar).message)
    }
    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("版本历史") },
                navigationIcon = { IconButton(onClick = onNavigateBack) { Icon(Icons.Default.ArrowBack, "返回") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { padding ->
        if (versions.isEmpty()) {
            EmptyState(icon = Icons.Default.History, title = "暂无版本", modifier = Modifier.padding(padding))
        } else {
            LazyColumn(modifier = Modifier.padding(padding)) {
                items(versions, key = { it.id }) { v ->
                    ListItem(
                        headlineContent = { Text("版本 ${v.versionNumber}") },
                        supportingContent = { Text("${FormatUtil.formatFileSize(v.fileSize)} · ${FormatUtil.formatDate(v.createdAt)}") },
                        trailingContent = {
                            Row {
                                TextButton(
                                    onClick = {
                                        pendingDownload = v
                                        downloadLauncher.launch("version-${v.versionNumber}")
                                    },
                                    modifier = Modifier.testTag("version-download-${v.versionNumber}"),
                                ) {
                                    Text("下载")
                                }
                                TextButton(
                                    onClick = { viewModel.restore(fileId, v.versionNumber, teamId) },
                                    modifier = Modifier.testTag("version-restore-${v.versionNumber}"),
                                ) {
                                    Text("恢复")
                                }
                                TextButton(
                                    onClick = { viewModel.delete(fileId, v.versionNumber, teamId) },
                                    modifier = Modifier.testTag("version-delete-${v.versionNumber}"),
                                ) {
                                    Text("删除", color = MaterialTheme.colorScheme.error)
                                }
                            }
                        },
                        modifier = Modifier.testTag("version-row-${v.versionNumber}"),
                    )
                }
            }
        }
    }
}
