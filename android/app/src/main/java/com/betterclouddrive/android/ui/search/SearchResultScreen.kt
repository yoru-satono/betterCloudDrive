package com.betterclouddrive.android.ui.search

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.betterclouddrive.android.domain.model.FileItem
import com.betterclouddrive.android.ui.components.EmptyState
import com.betterclouddrive.android.ui.components.FileRow
import com.betterclouddrive.android.util.Constants
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.betterclouddrive.android.data.repository.FileRepository
import com.betterclouddrive.android.util.NetworkResult
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import javax.inject.Inject

@HiltViewModel
class SearchViewModel @Inject constructor(private val repository: FileRepository) : ViewModel() {
    private val _results = MutableStateFlow<List<FileItem>>(emptyList()); val results = _results.asStateFlow()
    private val _isSearching = MutableStateFlow(false); val isSearching = _isSearching.asStateFlow()
    private var searchJob: Job? = null

    fun search(query: String) {
        searchJob?.cancel()
        if (query.isBlank()) { _results.value = emptyList(); return }
        searchJob = viewModelScope.launch {
            delay(Constants.SEARCH_DEBOUNCE_MS)
            _isSearching.value = true
            when (val r = repository.searchFiles(query)) {
                is NetworkResult.Success -> _results.value = r.data.records
                is NetworkResult.Error -> _results.value = emptyList()
                is NetworkResult.Loading -> {}
            }
            _isSearching.value = false
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchResultScreen(
    onNavigateBack: () -> Unit,
    viewModel: SearchViewModel = hiltViewModel(),
) {
    val results by viewModel.results.collectAsState()
    val isSearching by viewModel.isSearching.collectAsState()
    var query by remember { mutableStateOf("") }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = { IconButton(onClick = onNavigateBack) { Icon(Icons.Default.ArrowBack, "返回") } },
                title = {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it; viewModel.search(it) },
                        placeholder = { Text("搜索文件...") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = MaterialTheme.colorScheme.primary),
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { padding ->
        if (isSearching) {
            Box(modifier = Modifier.fillMaxSize().padding(padding), contentAlignment = androidx.compose.ui.Alignment.Center) {
                CircularProgressIndicator()
            }
        } else if (results.isEmpty() && query.isNotBlank()) {
            EmptyState(icon = Icons.Default.Search, title = "未找到 \"$query\"", modifier = Modifier.padding(padding))
        } else if (results.isEmpty()) {
            EmptyState(icon = Icons.Default.Search, title = "输入关键词搜索文件", modifier = Modifier.padding(padding))
        } else {
            LazyColumn(modifier = Modifier.padding(padding)) {
                items(results, key = { it.id }) { file -> FileRow(file, {}, {}) }
            }
        }
    }
}
