package com.betterclouddrive.android.ui.recyclebin

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
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.betterclouddrive.android.data.repository.RecycleBinRepository
import com.betterclouddrive.android.domain.model.FileItem
import com.betterclouddrive.android.ui.components.EmptyState
import com.betterclouddrive.android.ui.components.FileRow
import com.betterclouddrive.android.ui.navigation.MainScaffold
import com.betterclouddrive.android.ui.navigation.Screen
import com.betterclouddrive.android.util.NetworkResult
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecycleBinScreen(
    onNavigateBack: () -> Unit,
    onNavigateMain: (String) -> Unit,
    viewModel: RecycleBinViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(Unit) { viewModel.loadRecycleBin() }

    MainScaffold(
        currentRoute = Screen.RECYCLE_BIN,
        onNavigate = onNavigateMain,
        snackbarHostState = snackbarHostState,
        topBar = {
            TopAppBar(
                title = { Text("回收站") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) { Icon(Icons.Default.ArrowBack, "返回") }
                },
                actions = {
                    TextButton(
                        onClick = { viewModel.emptyRecycleBin() },
                        modifier = Modifier.testTag("recycle-empty"),
                    ) {
                        Text("清空", color = MaterialTheme.colorScheme.error)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { padding ->
        if (uiState.files.isEmpty() && !uiState.isLoading) {
            EmptyState(
                icon = Icons.Default.Delete,
                title = "回收站为空",
                modifier = Modifier.padding(padding),
            )
        } else {
            LazyColumn(modifier = Modifier.padding(padding)) {
                items(uiState.files, key = { it.id }) { file ->
                    FileRow(
                        file = file,
                        onClick = {},
                        onLongClick = {},
                        modifier = Modifier,
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        TextButton(
                            onClick = { viewModel.restoreFile(file.id) },
                            modifier = Modifier.testTag("recycle-restore-${file.fileName}"),
                        ) {
                            Text("恢复", color = MaterialTheme.colorScheme.primary)
                        }
                        TextButton(
                            onClick = { viewModel.permanentDelete(file.id) },
                            modifier = Modifier.testTag("recycle-delete-${file.fileName}"),
                        ) {
                            Text("彻底删除", color = MaterialTheme.colorScheme.error)
                        }
                    }
                    Divider(color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
        }
    }
}

@HiltViewModel
class RecycleBinViewModel @Inject constructor(
    private val repository: RecycleBinRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(RecycleBinUiState())
    val uiState = _uiState.asStateFlow()

    fun loadRecycleBin() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true)
            when (val r = repository.listRecycleBin()) {
                is NetworkResult.Success -> _uiState.value = _uiState.value.copy(files = r.data.records, isLoading = false)
                is NetworkResult.Error -> _uiState.value = _uiState.value.copy(isLoading = false)
                is NetworkResult.Loading -> {}
            }
        }
    }

    fun restoreFile(fileId: Long) {
        viewModelScope.launch {
            repository.restoreFile(fileId)
            loadRecycleBin()
        }
    }

    fun permanentDelete(fileId: Long) {
        viewModelScope.launch {
            repository.permanentDeleteFile(fileId)
            loadRecycleBin()
        }
    }

    fun emptyRecycleBin() {
        viewModelScope.launch {
            repository.emptyRecycleBin()
            loadRecycleBin()
        }
    }
}

data class RecycleBinUiState(
    val files: List<FileItem> = emptyList(),
    val isLoading: Boolean = false,
)
