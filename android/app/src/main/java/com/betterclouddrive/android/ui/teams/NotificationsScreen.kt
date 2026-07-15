package com.betterclouddrive.android.ui.teams

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.betterclouddrive.android.data.repository.TeamRepository
import com.betterclouddrive.android.domain.model.NotificationItem
import com.betterclouddrive.android.ui.components.EmptyState
import com.betterclouddrive.android.ui.navigation.Screen
import com.betterclouddrive.android.util.FormatUtil
import com.betterclouddrive.android.util.NetworkResult
import com.betterclouddrive.android.util.UiEvent
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import javax.inject.Inject

data class NotificationsUiState(
    val notifications: List<NotificationItem> = emptyList(),
    val unreadOnly: Boolean = false,
    val isLoading: Boolean = false,
)

@HiltViewModel
class NotificationsViewModel @Inject constructor(
    private val repository: TeamRepository,
) : ViewModel() {
    private val _uiState = MutableStateFlow(NotificationsUiState())
    val uiState = _uiState.asStateFlow()

    private val _events = MutableSharedFlow<UiEvent>()
    val events = _events.asSharedFlow()

    fun load(showLoading: Boolean = true) {
        viewModelScope.launch {
            if (showLoading) _uiState.value = _uiState.value.copy(isLoading = true)
            when (val result = repository.listNotifications(unreadOnly = _uiState.value.unreadOnly)) {
                is NetworkResult.Success -> _uiState.value = _uiState.value.copy(
                    notifications = result.data.records,
                    isLoading = false,
                )
                is NetworkResult.Error -> {
                    _uiState.value = _uiState.value.copy(isLoading = false)
                    _events.emit(UiEvent.ShowSnackbar("通知加载失败: ${result.message}", true))
                }
                is NetworkResult.Loading -> Unit
            }
        }
    }

    fun toggleUnreadOnly() {
        _uiState.value = _uiState.value.copy(unreadOnly = !_uiState.value.unreadOnly)
        load()
    }

    fun markAllRead() {
        viewModelScope.launch {
            when (val result = repository.markAllNotificationsRead()) {
                is NetworkResult.Success -> {
                    _events.emit(UiEvent.ShowSnackbar("通知已全部标记为已读"))
                    load()
                }
                is NetworkResult.Error -> _events.emit(UiEvent.ShowSnackbar("操作失败: ${result.message}", true))
                is NetworkResult.Loading -> Unit
            }
        }
    }

    fun open(notification: NotificationItem, onNavigate: (String) -> Unit) {
        viewModelScope.launch {
            if (!notification.isRead) {
                repository.markNotificationRead(notification.id)
            }
            routeForLink(notification.link)?.let(onNavigate)
            load()
        }
    }

    private fun routeForLink(link: String?): String? {
        if (link.isNullOrBlank()) return null
        Regex("/team-invites/([^/?#]+)").find(link)?.let {
            return Screen.teamInvite(it.groupValues[1])
        }
        Regex("/teams/(\\d+)/settings").find(link)?.let {
            return Screen.teamSettings(it.groupValues[1].toLong())
        }
        Regex("/teams/(\\d+)/files(?:/(\\d+))?").find(link)?.let {
            val teamId = it.groupValues[1].toLong()
            val folderId = it.groupValues.getOrNull(2)?.toLongOrNull()
            return Screen.teamFiles(teamId, folderId)
        }
        return null
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotificationsScreen(
    onNavigateBack: () -> Unit,
    onNavigateRoute: (String) -> Unit,
    viewModel: NotificationsViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()
    val events by viewModel.events.collectAsState(initial = null)
    val snackbarHostState = androidx.compose.runtime.remember { SnackbarHostState() }

    LaunchedEffect(Unit) {
        viewModel.load()
        while (true) {
            delay(30_000)
            viewModel.load(showLoading = false)
        }
    }
    LaunchedEffect(events) {
        if (events is UiEvent.ShowSnackbar) {
            snackbarHostState.showSnackbar((events as UiEvent.ShowSnackbar).message)
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("通知") },
                navigationIcon = { IconButton(onClick = onNavigateBack) { Icon(Icons.Default.ArrowBack, "返回") } },
                actions = {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(end = 8.dp)) {
                        Text("仅未读", style = MaterialTheme.typography.bodySmall)
                        Switch(
                            checked = uiState.unreadOnly,
                            onCheckedChange = { viewModel.toggleUnreadOnly() },
                            modifier = Modifier.testTag("notifications-unread-switch"),
                        )
                    }
                    IconButton(onClick = viewModel::markAllRead, modifier = Modifier.testTag("notifications-mark-all-read")) {
                        Icon(Icons.Default.DoneAll, "全部已读")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { padding ->
        when {
            uiState.isLoading -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            uiState.notifications.isEmpty() -> EmptyState(
                icon = Icons.Default.Notifications,
                title = "暂无通知",
                subtitle = "团队邀请、配额告警和权限变更会显示在这里",
                modifier = Modifier.fillMaxSize().padding(padding).testTag("notifications-empty"),
            )
            else -> LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding).testTag("notifications-list"),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                items(uiState.notifications, key = { it.id }) { notification ->
                    NotificationRow(notification = notification, onClick = {
                        viewModel.open(notification, onNavigateRoute)
                    })
                }
            }
        }
    }
}

@Composable
private fun NotificationRow(notification: NotificationItem, onClick: () -> Unit) {
    ListItem(
        headlineContent = {
            Text(
                notification.title,
                fontWeight = if (notification.isRead) FontWeight.Normal else FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        supportingContent = {
            Column {
                Text(notification.content, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(
                    "${notification.type} · ${FormatUtil.formatFullDate(notification.createdAt)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).testTag("notification-${notification.id}"),
    )
}
