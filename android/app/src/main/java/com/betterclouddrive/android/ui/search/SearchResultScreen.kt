package com.betterclouddrive.android.ui.search

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.hilt.navigation.compose.hiltViewModel
import com.betterclouddrive.android.data.repository.FileRepository
import com.betterclouddrive.android.domain.model.FileItem
import com.betterclouddrive.android.ui.components.EmptyState
import com.betterclouddrive.android.ui.components.FileRow
import com.betterclouddrive.android.util.Constants
import com.betterclouddrive.android.util.NetworkResult
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SearchViewModel @Inject constructor(private val repository: FileRepository) : ViewModel() {
    private val _results = MutableStateFlow<List<FileItem>>(emptyList())
    val results = _results.asStateFlow()
    private val _isSearching = MutableStateFlow(false)
    val isSearching = _isSearching.asStateFlow()
    private var searchJob: Job? = null

    fun search(query: String) {
        searchJob?.cancel()
        if (query.isBlank()) {
            _results.value = emptyList()
            _isSearching.value = false
            return
        }
        searchJob = viewModelScope.launch {
            delay(Constants.SEARCH_DEBOUNCE_MS)
            _isSearching.value = true
            when (val result = repository.searchFiles(query)) {
                is NetworkResult.Success -> _results.value = result.data.records
                is NetworkResult.Error -> _results.value = emptyList()
                is NetworkResult.Loading -> Unit
            }
            _isSearching.value = false
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchResultScreen(
    onNavigateBack: () -> Unit,
    onOpenFile: (FileItem) -> Unit,
    onOpenFolder: (FileItem) -> Unit,
    onOpenLocation: (FileItem) -> Unit,
    viewModel: SearchViewModel = hiltViewModel(),
) {
    val results by viewModel.results.collectAsState()
    val isSearching by viewModel.isSearching.collectAsState()
    var query by remember { mutableStateOf("") }
    var menuTarget by remember { mutableStateOf<FileItem?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) { Icon(Icons.Default.ArrowBack, "返回") }
                },
                title = {
                    OutlinedTextField(
                        value = query,
                        onValueChange = {
                            query = it
                            viewModel.search(it)
                        },
                        placeholder = { Text("搜索文件...") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().testTag("search-input"),
                        colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = MaterialTheme.colorScheme.primary),
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { padding ->
        when {
            isSearching -> {
                Box(modifier = Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }
            results.isEmpty() && query.isNotBlank() -> {
                EmptyState(icon = Icons.Default.Search, title = "未找到 \"$query\"", modifier = Modifier.padding(padding))
            }
            results.isEmpty() -> {
                EmptyState(icon = Icons.Default.Search, title = "输入关键词搜索文件", modifier = Modifier.padding(padding))
            }
            else -> {
                LazyColumn(modifier = Modifier.padding(padding).testTag("search-results")) {
                    items(results, key = { it.id }) { file ->
                        FileRow(
                            file = file,
                            onClick = { if (file.isFolder) onOpenFolder(file) else onOpenFile(file) },
                            onLongClick = { menuTarget = file },
                            modifier = Modifier.testTag("search-result-${file.fileName}"),
                        )
                    }
                }
            }
        }
    }

    val target = menuTarget
    if (target != null) {
        ModalBottomSheet(
            onDismissRequest = { menuTarget = null },
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ) {
            Column(modifier = Modifier.padding(bottom = 32.dp)) {
                ListItem(
                    headlineContent = { Text("打开文件所在位置") },
                    leadingContent = { Icon(Icons.Default.LocationOn, null) },
                    modifier = Modifier.testTag("search-open-location").clickable {
                        onOpenLocation(target)
                        menuTarget = null
                    },
                )
                if (target.isFolder) {
                    ListItem(
                        headlineContent = { Text("进入文件夹") },
                        leadingContent = { Icon(Icons.Default.FolderOpen, null) },
                        modifier = Modifier.testTag("search-open-folder").clickable {
                            onOpenFolder(target)
                            menuTarget = null
                        },
                    )
                } else {
                    ListItem(
                        headlineContent = { Text("预览") },
                        leadingContent = { Icon(Icons.Default.Visibility, null) },
                        modifier = Modifier.testTag("search-preview").clickable {
                            onOpenFile(target)
                            menuTarget = null
                        },
                    )
                }
            }
        }
    }
}
