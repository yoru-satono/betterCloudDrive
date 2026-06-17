package com.betterclouddrive.android.ui.favorites

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.betterclouddrive.android.domain.model.FileItem
import com.betterclouddrive.android.ui.components.EmptyState
import com.betterclouddrive.android.ui.components.FileRow
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.betterclouddrive.android.data.repository.FavoriteRepository
import com.betterclouddrive.android.util.NetworkResult
import com.betterclouddrive.android.ui.navigation.MainScaffold
import com.betterclouddrive.android.ui.navigation.Screen
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class FavoritesUiState(val files: List<FileItem> = emptyList(), val isLoading: Boolean = false)

@HiltViewModel
class FavoritesViewModel @Inject constructor(private val repository: FavoriteRepository) : ViewModel() {
    private val _uiState = MutableStateFlow(FavoritesUiState()); val uiState = _uiState.asStateFlow()
    fun load() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true)
            when (val r = repository.listFavorites()) {
                is NetworkResult.Success -> _uiState.value = _uiState.value.copy(files = r.data.records, isLoading = false)
                is NetworkResult.Error -> _uiState.value = _uiState.value.copy(isLoading = false)
                is NetworkResult.Loading -> {}
            }
        }
    }
    fun remove(fileId: Long) { viewModelScope.launch { repository.removeFavorite(fileId); load() } }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FavoritesScreen(
    onNavigateBack: () -> Unit,
    onNavigateMain: (String) -> Unit,
    viewModel: FavoritesViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()
    LaunchedEffect(Unit) { viewModel.load() }
    MainScaffold(
        currentRoute = Screen.FAVORITES,
        onNavigate = onNavigateMain,
        topBar = {
            TopAppBar(
                title = { Text("收藏夹") },
                navigationIcon = { IconButton(onClick = onNavigateBack) { Icon(Icons.Default.ArrowBack, "返回") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { padding ->
        if (uiState.files.isEmpty()) {
            EmptyState(icon = Icons.Default.Star, title = "暂无收藏", modifier = Modifier.padding(padding))
        } else {
            LazyColumn(modifier = Modifier.padding(padding)) {
                items(uiState.files, key = { it.id }) { file ->
                    FileRow(file = file, onClick = {}, onLongClick = {})
                    Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = { viewModel.remove(file.id) }) { Text("取消收藏", color = MaterialTheme.colorScheme.error) }
                    }
                }
            }
        }
    }
}
