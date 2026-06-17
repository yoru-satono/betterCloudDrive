package com.betterclouddrive.android.ui.transfer

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
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.ClearAll
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.betterclouddrive.android.data.repository.TransferDirection
import com.betterclouddrive.android.data.repository.TransferRepository
import com.betterclouddrive.android.data.repository.TransferStatus
import com.betterclouddrive.android.data.repository.TransferTask
import com.betterclouddrive.android.ui.components.EmptyState
import com.betterclouddrive.android.ui.navigation.MainScaffold
import com.betterclouddrive.android.ui.navigation.Screen
import com.betterclouddrive.android.util.FormatUtil
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

@HiltViewModel
class TransferQueueViewModel @Inject constructor(
    private val repository: TransferRepository,
) : ViewModel() {
    val tasks = repository.tasks

    fun resume(task: TransferTask) {
        repository.start(task.id, viewModelScope)
    }

    fun pause(taskId: String) = repository.pause(taskId)
    fun cancel(taskId: String) = repository.cancel(taskId)
    fun clearFinished() = repository.clearFinished()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TransferQueueScreen(
    onNavigate: (String) -> Unit,
    onNavigateBack: (() -> Unit)? = null,
    viewModel: TransferQueueViewModel = hiltViewModel(),
) {
    val tasks by viewModel.tasks.collectAsState()
    var selectedTab by remember { mutableStateOf(0) }
    val tabs = listOf("上传" to TransferDirection.UPLOAD, "下载" to TransferDirection.DOWNLOAD)
    val visibleTasks = tasks.filter { it.direction == tabs[selectedTab].second }

    MainScaffold(
        currentRoute = Screen.TRANSFERS,
        onNavigate = onNavigate,
        topBar = {
            TopAppBar(
                title = { Text("传输队列") },
                navigationIcon = {
                    if (onNavigateBack != null) {
                        IconButton(onClick = onNavigateBack) { Icon(Icons.Default.ArrowBack, "返回") }
                    }
                },
                actions = {
                    IconButton(
                        onClick = viewModel::clearFinished,
                        modifier = Modifier.testTag("transfer-clear-finished"),
                    ) {
                        Icon(Icons.Default.ClearAll, "清除已完成")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { contentPadding ->
        Column(modifier = Modifier.padding(contentPadding).fillMaxSize()) {
            ScrollableTabRow(
                selectedTabIndex = selectedTab,
                edgePadding = 16.dp,
                containerColor = MaterialTheme.colorScheme.background,
            ) {
                tabs.forEachIndexed { index, tab ->
                    val count = tasks.count { it.direction == tab.second }
                    Tab(
                        modifier = Modifier.testTag("transfer-tab-${tab.first}"),
                        selected = selectedTab == index,
                        onClick = { selectedTab = index },
                        text = { Text("${tab.first} $count") },
                    )
                }
            }
            if (visibleTasks.isEmpty()) {
                EmptyState(
                    icon = if (selectedTab == 0) Icons.Default.Upload else Icons.Default.Download,
                    title = "暂无${tabs[selectedTab].first}任务",
                    subtitle = "从文件页发起上传或下载后会显示在这里",
                )
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
                ) {
                    items(visibleTasks, key = { it.id }) { task ->
                        TransferTaskCard(
                            task = task,
                            onPause = { viewModel.pause(task.id) },
                            onResume = { viewModel.resume(task) },
                            onCancel = { viewModel.cancel(task.id) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TransferTaskCard(
    task: TransferTask,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onCancel: () -> Unit,
) {
    val progress = if (task.bytesTotal > 0) (task.bytesDone.toFloat() / task.bytesTotal).coerceIn(0f, 1f) else 0f
    Card(
        modifier = Modifier.fillMaxWidth().testTag("transfer-task-${task.fileName}"),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = if (task.direction == TransferDirection.UPLOAD) Icons.Default.Upload else Icons.Default.Download,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
                Column(modifier = Modifier.weight(1f).padding(horizontal = 12.dp)) {
                    Text(
                        text = task.fileName,
                        style = MaterialTheme.typography.bodyLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = "${FormatUtil.formatFileSize(task.bytesDone)} / ${FormatUtil.formatFileSize(task.bytesTotal)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TransferStatusChip(task.status)
            }
            Spacer(modifier = Modifier.height(10.dp))
            if (task.status == TransferStatus.HASHING) {
                Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterStart) {
                    CircularProgressIndicator(modifier = Modifier.height(18.dp), strokeWidth = 2.dp)
                }
            } else {
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.fillMaxWidth().height(6.dp),
                )
            }
            if (!task.error.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = task.error,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.labelSmall,
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                horizontalArrangement = Arrangement.End,
            ) {
                when (task.status) {
                    TransferStatus.PAUSED, TransferStatus.ERROR, TransferStatus.PENDING -> {
                        IconButton(
                            onClick = onResume,
                            modifier = Modifier.testTag("transfer-resume-${task.fileName}"),
                        ) { Icon(Icons.Default.PlayArrow, "继续") }
                    }
                    TransferStatus.RUNNING, TransferStatus.HASHING -> {
                        IconButton(
                            onClick = onPause,
                            modifier = Modifier.testTag("transfer-pause-${task.fileName}"),
                        ) { Icon(Icons.Default.Pause, "暂停") }
                    }
                    else -> Unit
                }
                if (task.status != TransferStatus.DONE && task.status != TransferStatus.CANCELED) {
                    IconButton(
                        onClick = onCancel,
                        modifier = Modifier.testTag("transfer-cancel-${task.fileName}"),
                    ) { Icon(Icons.Default.Cancel, "取消") }
                }
            }
        }
    }
}

@Composable
private fun TransferStatusChip(status: TransferStatus) {
    val text = when (status) {
        TransferStatus.PENDING -> "等待"
        TransferStatus.HASHING -> "校验"
        TransferStatus.RUNNING -> "传输中"
        TransferStatus.PAUSED -> "已暂停"
        TransferStatus.DONE -> "完成"
        TransferStatus.ERROR -> "失败"
        TransferStatus.CANCELED -> "已取消"
    }
    AssistChip(onClick = {}, label = { Text(text) })
}
