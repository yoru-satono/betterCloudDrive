package com.betterclouddrive.android.ui.tags

import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.betterclouddrive.android.ui.components.EmptyState
import com.betterclouddrive.android.ui.components.FileRow
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.betterclouddrive.android.data.repository.TagRepository
import com.betterclouddrive.android.domain.model.FileItem
import com.betterclouddrive.android.util.NetworkResult
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class TagFilesViewModel @Inject constructor(private val repository: TagRepository) : ViewModel() {
    private val _files = MutableStateFlow<List<FileItem>>(emptyList()); val files = _files.asStateFlow()
    fun load(tagId: Long) {
        viewModelScope.launch {
            when (val r = repository.listFilesByTag(tagId)) {
                is NetworkResult.Success -> _files.value = r.data.records
                is NetworkResult.Error -> {}
                is NetworkResult.Loading -> {}
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TagFilesScreen(
    tagId: Long,
    onNavigateBack: () -> Unit,
    viewModel: TagFilesViewModel = hiltViewModel(),
) {
    val files by viewModel.files.collectAsState()
    LaunchedEffect(tagId) { viewModel.load(tagId) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("标签文件") },
                navigationIcon = { IconButton(onClick = onNavigateBack) { Icon(Icons.Default.ArrowBack, "返回") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { padding ->
        if (files.isEmpty()) EmptyState(icon = Icons.Default.Label, title = "没有关联文件", modifier = Modifier.padding(padding))
        else LazyColumn(modifier = Modifier.padding(padding)) { items(files, key = { it.id }) { f -> FileRow(f, {}, {}) } }
    }
}
