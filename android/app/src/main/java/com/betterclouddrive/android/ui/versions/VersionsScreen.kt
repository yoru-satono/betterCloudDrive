package com.betterclouddrive.android.ui.versions

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
import com.betterclouddrive.android.data.repository.UploadRepository
import com.betterclouddrive.android.data.remote.ApiService
import com.betterclouddrive.android.util.NetworkResult
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class VersionsViewModel @Inject constructor(private val api: ApiService) : ViewModel() {
    private val _versions = MutableStateFlow<List<FileVersion>>(emptyList()); val versions = _versions.asStateFlow()
    fun load(fileId: Long) {
        viewModelScope.launch {
            when (val r = com.betterclouddrive.android.data.repository.apiCall { api.listVersions(fileId) }) {
                is NetworkResult.Success -> _versions.value = r.data
                is NetworkResult.Error -> {}
                is NetworkResult.Loading -> {}
            }
        }
    }
    fun delete(fileId: Long, versionNumber: Int) {
        viewModelScope.launch {
            api.deleteVersion(fileId, versionNumber)
            load(fileId)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VersionsScreen(
    fileId: Long,
    onNavigateBack: () -> Unit,
    viewModel: VersionsViewModel = hiltViewModel(),
) {
    val versions by viewModel.versions.collectAsState()
    LaunchedEffect(fileId) { viewModel.load(fileId) }
    Scaffold(
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
                            TextButton(
                                onClick = { viewModel.delete(fileId, v.versionNumber) },
                                modifier = Modifier.testTag("version-delete-${v.versionNumber}"),
                            ) {
                                Text("删除", color = MaterialTheme.colorScheme.error)
                            }
                        },
                        modifier = Modifier.testTag("version-row-${v.versionNumber}"),
                    )
                }
            }
        }
    }
}
