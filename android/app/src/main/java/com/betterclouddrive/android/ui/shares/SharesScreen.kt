package com.betterclouddrive.android.ui.shares

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
import com.betterclouddrive.android.domain.model.ShareLink
import com.betterclouddrive.android.ui.components.EmptyState
import com.betterclouddrive.android.util.FormatUtil
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.betterclouddrive.android.data.repository.ShareRepository
import com.betterclouddrive.android.util.NetworkResult
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SharesUiState(val shares: List<ShareLink> = emptyList(), val isLoading: Boolean = false)

@HiltViewModel
class SharesViewModel @Inject constructor(private val repository: ShareRepository) : ViewModel() {
    private val _uiState = MutableStateFlow(SharesUiState()); val uiState = _uiState.asStateFlow()
    fun load() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true)
            when (val r = repository.listShares()) {
                is NetworkResult.Success -> _uiState.value = _uiState.value.copy(shares = r.data.records, isLoading = false)
                is NetworkResult.Error -> _uiState.value = _uiState.value.copy(isLoading = false)
                is NetworkResult.Loading -> {}
            }
        }
    }
    fun cancel(shareId: Long) {
        viewModelScope.launch { repository.cancelShare(shareId); load() }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SharesScreen(
    onNavigateBack: () -> Unit,
    viewModel: SharesViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()
    LaunchedEffect(Unit) { viewModel.load() }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("我的分享") },
                navigationIcon = { IconButton(onClick = onNavigateBack) { Icon(Icons.Default.ArrowBack, "返回") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { padding ->
        if (uiState.shares.isEmpty()) {
            EmptyState(icon = Icons.Default.Share, title = "暂无分享", modifier = Modifier.padding(padding))
        } else {
            LazyColumn(modifier = Modifier.padding(padding)) {
                items(uiState.shares, key = { it.id }) { share ->
                    Card(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(share.fileName ?: "文件", style = MaterialTheme.typography.bodyLarge)
                            Text("分享码: ${share.shareCode}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                if (share.hasPassword) AssistChip(onClick = {}, label = { Text("有密码") })
                                if (share.isCanceled) AssistChip(onClick = {}, label = { Text("已取消") })
                                Text("下载: ${share.downloadCount}", style = MaterialTheme.typography.labelSmall)
                            }
                            Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                                TextButton(onClick = { viewModel.cancel(share.id) }) { Text("取消分享", color = MaterialTheme.colorScheme.error) }
                            }
                        }
                    }
                }
            }
        }
    }
}
