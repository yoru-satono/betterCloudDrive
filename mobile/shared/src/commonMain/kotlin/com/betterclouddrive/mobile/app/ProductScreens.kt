package com.betterclouddrive.mobile.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.betterclouddrive.mobile.core.model.FileItem
import com.betterclouddrive.mobile.core.model.UserProfile
import com.betterclouddrive.mobile.core.platform.OfficeRenderer
import com.betterclouddrive.mobile.feature.transfers.TransferStatus
import com.betterclouddrive.mobile.feature.transfers.TransferTask
import kotlinx.coroutines.launch

@Composable
fun TeamsPlaceholderScreen() {
    EmptyPane(
        icon = Icons.Default.Groups,
        title = "团队空间",
        detail = "团队功能将在后续阶段提供",
        modifier = Modifier.fillMaxSize().padding(24.dp).testTag("teams-placeholder"),
    )
}

@Composable
fun TransferQueueScreen(
    tasks: List<TransferTask>,
    onPause: (String) -> Unit,
    onResume: (String) -> Unit,
    onCancel: (String) -> Unit,
    onRetry: (String) -> Unit,
) {
    if (tasks.isEmpty()) {
        EmptyPane(
            icon = Icons.Default.Cloud,
            title = "暂无传输任务",
            detail = "上传和下载任务会显示在这里",
            modifier = Modifier.fillMaxSize().padding(24.dp).testTag("transfers-empty"),
        )
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp).testTag("transfers-content"),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items(tasks, key = { it.id }) { task ->
            TransferRow(task, onPause, onResume, onCancel, onRetry)
        }
    }
}

@Composable
private fun TransferRow(
    task: TransferTask,
    onPause: (String) -> Unit,
    onResume: (String) -> Unit,
    onCancel: (String) -> Unit,
    onRetry: (String) -> Unit,
) {
    Card(Modifier.fillMaxWidth().testTag("transfer-task-${task.id}")) {
        Column(
            Modifier.fillMaxWidth().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(task.fileName)
            Text(statusLabel(task.status), style = MaterialTheme.typography.bodySmall)
            if (task.bytesTotal > 0) {
                LinearProgressIndicator(
                    progress = { (task.bytesDone.toFloat() / task.bytesTotal).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            task.errorKey?.let {
                Text(transferError(it), color = MaterialTheme.colorScheme.error)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                when (task.status) {
                    TransferStatus.QUEUED,
                    TransferStatus.PREPARING,
                    TransferStatus.RUNNING -> OutlinedButton(
                        onClick = { onPause(task.id) },
                        modifier = Modifier.testTag("transfer-pause-${task.id}"),
                    ) { Text("暂停") }
                    TransferStatus.PAUSED,
                    TransferStatus.WAITING_FOR_LOGIN -> OutlinedButton(
                        onClick = { onResume(task.id) },
                        modifier = Modifier.testTag("transfer-resume-${task.id}"),
                    ) { Text("继续") }
                    TransferStatus.FAILED -> OutlinedButton(
                        onClick = { onRetry(task.id) },
                        modifier = Modifier.testTag("transfer-retry-${task.id}"),
                    ) { Text("重试") }
                    else -> Unit
                }
                if (task.status !in setOf(TransferStatus.SUCCEEDED, TransferStatus.CANCELED)) {
                    OutlinedButton(
                        onClick = { onCancel(task.id) },
                        modifier = Modifier.testTag("transfer-cancel-${task.id}"),
                    ) { Text("取消") }
                }
            }
        }
    }
}

@Composable
fun ProfileScreen(
    user: UserProfile?,
    server: String,
    debugToolsEnabled: Boolean,
    onOpenDiagnostics: () -> Unit,
    onLogout: () -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp).testTag("profile-content"),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("账号", style = MaterialTheme.typography.titleMedium)
                    InfoRow("用户名", user?.username.orEmpty())
                    InfoRow("邮箱", user?.email?.ifBlank { "未设置" } ?: "未设置")
                    HorizontalDivider()
                    InfoRow("当前服务器", server)
                }
            }
        }
        if (debugToolsEnabled) {
            item {
                OutlinedButton(
                    onClick = onOpenDiagnostics,
                    modifier = Modifier.fillMaxWidth().testTag("profile-diagnostics"),
                ) {
                    Icon(Icons.Default.BugReport, contentDescription = null)
                    Spacer(Modifier.padding(horizontal = 4.dp))
                    Text("Debug 诊断")
                }
            }
        }
        item {
            OutlinedButton(
                onClick = onLogout,
                modifier = Modifier.fillMaxWidth().testTag("profile-logout"),
            ) {
                Icon(Icons.AutoMirrored.Filled.Logout, contentDescription = null)
                Spacer(Modifier.padding(horizontal = 4.dp))
                Text("退出登录")
            }
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
fun DiagnosticsScreen(
    state: AppUiState,
    officeRenderer: OfficeRenderer,
    roomTaskCount: suspend () -> Int,
    onLoadOffice: (Long, Long) -> Unit,
    onOfficeStatus: (String) -> Unit,
) {
    var teamId by remember { mutableStateOf("") }
    var fileId by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    var databaseStatus by remember { mutableStateOf("Room v1 尚未检查") }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp).testTag("diagnostics-content"),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Room 数据库", style = MaterialTheme.typography.titleMedium)
                    Button(
                        onClick = {
                            scope.launch {
                                databaseStatus = runCatching {
                                    val count = roomTaskCount()
                                    "Room v1 正常，当前任务 $count 个"
                                }.getOrElse { "Room 检查失败：${it.message ?: "未知错误"}" }
                            }
                        },
                        modifier = Modifier.testTag("diagnostics-room-check"),
                    ) { Text("检查 Room") }
                    Text(databaseStatus, modifier = Modifier.testTag("diagnostics-room-status"))
                }
            }
        }
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("OnlyOffice 移动只读诊断", style = MaterialTheme.typography.titleMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = teamId,
                            onValueChange = { teamId = it.filter(Char::isDigit) },
                            label = { Text("teamId") },
                            modifier = Modifier.weight(1f).testTag("diagnostics-team-id"),
                            singleLine = true,
                        )
                        OutlinedTextField(
                            value = fileId,
                            onValueChange = { fileId = it.filter(Char::isDigit) },
                            label = { Text("fileId") },
                            modifier = Modifier.weight(1f).testTag("diagnostics-file-id"),
                            singleLine = true,
                        )
                    }
                    Button(
                        enabled = teamId.isNotBlank() && fileId.isNotBlank(),
                        onClick = { onLoadOffice(teamId.toLong(), fileId.toLong()) },
                        modifier = Modifier.testTag("diagnostics-office-load"),
                    ) { Text("加载只读文档") }
                    state.diagnostics.office?.let { office ->
                        Box(Modifier.fillMaxWidth().height(480.dp).testTag("diagnostics-office-renderer")) {
                            officeRenderer.Render(
                                office,
                                Modifier.fillMaxSize(),
                                onOfficeStatus,
                            )
                        }
                    }
                    Text(state.diagnostics.status, modifier = Modifier.testTag("diagnostics-office-status"))
                    Text("当前用户：${state.user?.username.orEmpty()}")
                }
            }
        }
    }
}

@Composable
private fun LoadingPane(message: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        CircularProgressIndicator()
        Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun EmptyPane(
    icon: ImageVector,
    title: String,
    detail: String,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(12.dp))
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(detail, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ErrorPane(
    message: String,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) {
    Card(modifier) {
        Column(
            Modifier.fillMaxWidth().padding(if (compact) 12.dp else 24.dp),
            horizontalAlignment = if (compact) Alignment.Start else Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("加载失败", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.error)
            Text(message)
            OutlinedButton(onClick = onRetry, modifier = Modifier.testTag("content-retry")) {
                Text("重试")
            }
        }
    }
}

private fun statusLabel(status: TransferStatus): String = when (status) {
    TransferStatus.QUEUED -> "排队中"
    TransferStatus.PREPARING -> "准备中"
    TransferStatus.RUNNING -> "传输中"
    TransferStatus.PAUSED -> "已暂停"
    TransferStatus.WAITING_FOR_LOGIN -> "等待重新登录"
    TransferStatus.SUCCEEDED -> "已完成"
    TransferStatus.FAILED -> "失败"
    TransferStatus.CANCELED -> "已取消"
}

private fun transferError(key: String): String = when (key) {
    "error.unauthorized" -> "需要重新登录后继续"
    "error.storageQuotaExceeded" -> "存储空间不足"
    "error.fileNameExists" -> "目标位置已存在同名文件"
    "error.network" -> "网络暂时不可用，将自动重试"
    "error.serverChanged" -> "服务器已经切换"
    else -> "传输失败"
}
