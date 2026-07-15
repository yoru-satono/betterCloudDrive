package com.betterclouddrive.android.ui.shares

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
import com.betterclouddrive.android.domain.model.ShareLink
import com.betterclouddrive.android.ui.components.BCDDialog
import com.betterclouddrive.android.ui.components.EmptyState
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.betterclouddrive.android.data.repository.ShareRepository
import com.betterclouddrive.android.util.NetworkResult
import com.betterclouddrive.android.ui.navigation.MainScaffold
import com.betterclouddrive.android.ui.navigation.Screen
import com.betterclouddrive.android.ui.navigation.Workspace
import com.betterclouddrive.android.ui.navigation.WorkspaceSwitchAction
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SharesUiState(
    val shares: List<ShareLink> = emptyList(),
    val isLoading: Boolean = false,
    val passwords: Map<Long, String> = emptyMap(),
    val loadingPasswordId: Long? = null,
)

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
    fun revealPassword(shareId: Long) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(loadingPasswordId = shareId)
            when (val r = repository.getSharePassword(shareId)) {
                is NetworkResult.Success -> _uiState.value = _uiState.value.copy(
                    passwords = _uiState.value.passwords + (shareId to (r.data.password ?: "")),
                    loadingPasswordId = null,
                )
                is NetworkResult.Error -> _uiState.value = _uiState.value.copy(loadingPasswordId = null)
                is NetworkResult.Loading -> {}
            }
        }
    }

    fun updateShare(
        shareId: Long,
        password: String?,
        expireAt: Long?,
        maxVisits: Int?,
        clearPassword: Boolean,
        clearExpireAt: Boolean,
        clearMaxVisits: Boolean,
    ) {
        viewModelScope.launch {
            when (repository.updateShare(
                shareId = shareId,
                password = if (clearPassword) null else password?.trim()?.takeIf { it.isNotEmpty() },
                expireAt = if (clearExpireAt) null else expireAt,
                maxVisits = if (clearMaxVisits) null else maxVisits,
                clearPassword = clearPassword,
                clearExpireAt = clearExpireAt,
                clearMaxVisits = clearMaxVisits,
            )) {
                is NetworkResult.Success -> load()
                is NetworkResult.Error -> Unit
                is NetworkResult.Loading -> Unit
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SharesScreen(
    onNavigateBack: () -> Unit,
    onNavigateMain: (String) -> Unit,
    viewModel: SharesViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()
    var editShare by remember { mutableStateOf<ShareLink?>(null) }
    var editPassword by remember { mutableStateOf("") }
    var editExpireAt by remember { mutableStateOf("") }
    var editMaxVisits by remember { mutableStateOf("") }
    var editClearPassword by remember { mutableStateOf(false) }
    var editClearExpireAt by remember { mutableStateOf(false) }
    var editClearMaxVisits by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { viewModel.load() }
    MainScaffold(
        currentRoute = Screen.SHARES,
        onNavigate = onNavigateMain,
        topBar = {
            TopAppBar(
                title = { Text("我的分享") },
                navigationIcon = { IconButton(onClick = onNavigateBack) { Icon(Icons.Default.ArrowBack, "返回") } },
                actions = { WorkspaceSwitchAction(Workspace.PERSONAL) },
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
                        modifier = Modifier.fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 4.dp)
                            .testTag("share-card-${share.shareCode}"),
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
                            uiState.passwords[share.id]?.let { password ->
                                Text("密码: $password", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                            }
                            Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                                if (share.hasPassword) {
                                    IconButton(
                                        onClick = { viewModel.revealPassword(share.id) },
                                        enabled = uiState.loadingPasswordId != share.id,
                                        modifier = Modifier.testTag("share-password-${share.shareCode}"),
                                    ) {
                                        Icon(Icons.Default.Visibility, "查看密码")
                                    }
                                }
                                TextButton(
                                    onClick = {
                                        editShare = share
                                        editPassword = ""
                                        editExpireAt = ""
                                        editMaxVisits = share.maxVisits?.toString() ?: ""
                                        editClearPassword = false
                                        editClearExpireAt = false
                                        editClearMaxVisits = false
                                    },
                                    modifier = Modifier.testTag("share-edit-${share.shareCode}"),
                                ) { Text("编辑") }
                                TextButton(
                                    onClick = { viewModel.cancel(share.id) },
                                    modifier = Modifier.testTag("share-cancel-${share.shareCode}"),
                                ) { Text("取消分享", color = MaterialTheme.colorScheme.error) }
                            }
                        }
                    }
                }
            }
        }
    }

    editShare?.let { share ->
        BCDDialog(
            title = "编辑分享",
            onDismiss = { editShare = null },
            onConfirm = {
                val expireAt = if (editClearExpireAt) null else editExpireAt.trim().takeIf { it.isNotEmpty() }?.toLongOrNull()
                val visits = if (editClearMaxVisits) null else editMaxVisits.trim().takeIf { it.isNotEmpty() }?.toIntOrNull()
                viewModel.updateShare(
                    share.id,
                    editPassword,
                    expireAt,
                    visits,
                    editClearPassword,
                    editClearExpireAt,
                    editClearMaxVisits,
                )
                editShare = null
            },
            confirmText = "保存",
        ) {
            OutlinedTextField(
                value = editPassword,
                onValueChange = { editPassword = it },
                placeholder = { Text("新密码，留空不修改") },
                singleLine = true,
                enabled = !editClearPassword,
                modifier = Modifier.fillMaxWidth().testTag("share-edit-password"),
            )
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Checkbox(
                    checked = editClearPassword,
                    onCheckedChange = { editClearPassword = it },
                    modifier = Modifier.testTag("share-clear-password"),
                )
                Text("移除访问密码")
            }
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = editExpireAt,
                onValueChange = { editExpireAt = it.filter { char -> char.isDigit() } },
                placeholder = { Text("过期时间 Unix 毫秒，留空不修改") },
                singleLine = true,
                enabled = !editClearExpireAt,
                modifier = Modifier.fillMaxWidth().testTag("share-edit-expire-at"),
            )
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Checkbox(
                    checked = editClearExpireAt,
                    onCheckedChange = { editClearExpireAt = it },
                    modifier = Modifier.testTag("share-clear-expire-at"),
                )
                Text("清空过期时间")
            }
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = editMaxVisits,
                onValueChange = { editMaxVisits = it.filter { char -> char.isDigit() } },
                placeholder = { Text("访问次数限制，留空不修改") },
                singleLine = true,
                enabled = !editClearMaxVisits,
                modifier = Modifier.fillMaxWidth().testTag("share-edit-max-visits"),
            )
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Checkbox(
                    checked = editClearMaxVisits,
                    onCheckedChange = { editClearMaxVisits = it },
                    modifier = Modifier.testTag("share-clear-max-visits"),
                )
                Text("清空访问次数限制")
            }
        }
    }
}
