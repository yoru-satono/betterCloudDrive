package com.betterclouddrive.mobile.app

import com.betterclouddrive.mobile.feature.auth.AuthState
import com.betterclouddrive.mobile.feature.diagnostics.DiagnosticsState

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.ClearAll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.betterclouddrive.mobile.core.model.FileItem
import com.betterclouddrive.mobile.core.model.UserProfile
import com.betterclouddrive.mobile.core.localization.LocalMobileStrings
import com.betterclouddrive.mobile.core.localization.MobileString
import com.betterclouddrive.mobile.core.localization.MobileLocale
import com.betterclouddrive.mobile.core.localization.UiMessage
import com.betterclouddrive.mobile.core.platform.OfficeRenderer
import com.betterclouddrive.mobile.core.platform.ThemeMode
import com.betterclouddrive.mobile.feature.transfers.TransferStatus
import com.betterclouddrive.mobile.feature.transfers.TransferDirection
import com.betterclouddrive.mobile.feature.transfers.TransferTask
import kotlinx.coroutines.launch

@Composable
fun TransferQueueScreen(
    tasks: List<TransferTask>,
    onPause: (String) -> Unit,
    onResume: (String) -> Unit,
    onCancel: (String) -> Unit,
    onRetry: (String) -> Unit,
    onClearFinished: () -> Unit,
) {
    val strings = LocalMobileStrings.current
    var selectedDirection by rememberSaveable { mutableStateOf(TransferDirection.UPLOAD) }
    var confirmClear by remember { mutableStateOf(false) }
    val terminal = tasks.filter { it.status in TERMINAL_TRANSFER_STATUSES }
    val visible = tasks.filter { it.direction == selectedDirection }.sortedWith(
        compareBy<TransferTask> { it.status in TERMINAL_TRANSFER_STATUSES }
            .thenComparator { left, right ->
                if (left.status in TERMINAL_TRANSFER_STATUSES) right.updatedAt.compareTo(left.updatedAt)
                else left.createdAt.compareTo(right.createdAt)
            },
    )
    Column(Modifier.fillMaxSize().testTag("transfers-content")) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(strings[MobileString.TransferQueueTitle], style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            OutlinedButton(
                enabled = terminal.isNotEmpty(),
                onClick = { confirmClear = true },
                modifier = Modifier.testTag("transfer-clear-finished"),
            ) {
                Icon(Icons.Default.ClearAll, contentDescription = null)
                Text(strings[MobileString.ClearFinished])
            }
        }
        ScrollableTabRow(selectedTabIndex = if (selectedDirection == TransferDirection.UPLOAD) 0 else 1) {
            TransferDirection.entries.forEach { direction ->
                val label = if (direction == TransferDirection.UPLOAD) MobileString.Uploads else MobileString.Downloads
                Tab(
                    selected = selectedDirection == direction,
                    onClick = { selectedDirection = direction },
                    text = { Text(strings.format(label, tasks.count { it.direction == direction })) },
                    modifier = Modifier.testTag("transfer-tab-${direction.name.lowercase()}"),
                )
            }
        }
        if (visible.isEmpty()) {
            EmptyPane(
                icon = if (selectedDirection == TransferDirection.UPLOAD) Icons.Default.Upload else Icons.Default.Download,
                title = strings[if (selectedDirection == TransferDirection.UPLOAD) MobileString.UploadsEmpty else MobileString.DownloadsEmpty],
                detail = strings[MobileString.TransfersEmptyDetail],
                modifier = Modifier.fillMaxSize().padding(24.dp).testTag("transfers-empty"),
            )
        } else LazyColumn(
            modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(visible, key = { it.id }) { task ->
                TransferRow(task, onPause, onResume, onCancel, onRetry)
            }
        }
    }
    if (confirmClear) AlertDialog(
        onDismissRequest = { confirmClear = false },
        modifier = Modifier.testTag("transfer-clear-dialog"),
        title = { Text(strings[MobileString.ClearFinishedTitle]) },
        text = { Text(strings.format(MobileString.ClearFinishedMessage, terminal.size)) },
        confirmButton = {
            Button(onClick = { confirmClear = false; onClearFinished() }, modifier = Modifier.testTag("transfer-clear-confirm")) {
                Text(strings[MobileString.Clear])
            }
        },
        dismissButton = { OutlinedButton(onClick = { confirmClear = false }) { Text(strings[MobileString.Cancel]) } },
    )
}

@Composable
private fun TransferRow(
    task: TransferTask,
    onPause: (String) -> Unit,
    onResume: (String) -> Unit,
    onCancel: (String) -> Unit,
    onRetry: (String) -> Unit,
) {
    val strings = LocalMobileStrings.current
    Card(Modifier.fillMaxWidth().testTag("transfer-task-${task.id}")) {
        Column(
            Modifier.fillMaxWidth().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    if (task.direction == TransferDirection.UPLOAD) Icons.Default.Upload else Icons.Default.Download,
                    contentDescription = strings[if (task.direction == TransferDirection.UPLOAD) MobileString.Upload else MobileString.Download],
                )
                Text(task.fileName, Modifier.weight(1f).padding(horizontal = 8.dp))
                AssistChip(onClick = {}, label = { Text(strings[statusKey(task.status)]) })
            }
            Text(
                strings.format(MobileString.TransferBytes, formatFileSize(task.bytesDone), formatFileSize(task.bytesTotal), task.progressPercent()),
                style = MaterialTheme.typography.bodySmall,
            )
            if (task.bytesTotal > 0) {
                LinearProgressIndicator(
                    progress = { (task.bytesDone.toFloat() / task.bytesTotal).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            task.errorKey?.let {
                Text(strings[transferErrorKey(it)], color = MaterialTheme.colorScheme.error)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                when (task.status) {
                    TransferStatus.QUEUED,
                    TransferStatus.PREPARING,
                    TransferStatus.RUNNING -> OutlinedButton(
                        onClick = { onPause(task.id) },
                        modifier = Modifier.testTag("transfer-pause-${task.id}"),
                    ) { Text(strings[MobileString.Pause]) }
                    TransferStatus.PAUSED,
                    TransferStatus.WAITING_FOR_LOGIN -> OutlinedButton(
                        onClick = { onResume(task.id) },
                        modifier = Modifier.testTag("transfer-resume-${task.id}"),
                    ) { Text(strings[MobileString.Resume]) }
                    TransferStatus.FAILED -> OutlinedButton(
                        onClick = { onRetry(task.id) },
                        modifier = Modifier.testTag("transfer-retry-${task.id}"),
                    ) { Text(strings[MobileString.Retry]) }
                    else -> Unit
                }
                if (task.status !in setOf(TransferStatus.SUCCEEDED, TransferStatus.CANCELED)) {
                    OutlinedButton(
                        onClick = { onCancel(task.id) },
                        modifier = Modifier.testTag("transfer-cancel-${task.id}"),
                    ) { Text(strings[MobileString.Cancel]) }
                }
            }
        }
    }
}

@Composable
fun ProfileScreen(
    state: AuthState,
    debugToolsEnabled: Boolean,
    onOpenDiagnostics: () -> Unit,
    onRefresh: () -> Unit,
    onLocale: (MobileLocale) -> Unit,
    onTheme: (ThemeMode) -> Unit,
    onRequestServerSwitch: (String) -> Unit,
    onCancelServerSwitch: () -> Unit,
    onConfirmServerSwitch: () -> Unit,
    onLogout: () -> Unit,
) {
    val strings = LocalMobileStrings.current
    val user = state.user
    var serverInput by remember(state.server) { mutableStateOf(state.server) }
    val quota = user?.storageQuota?.coerceAtLeast(0) ?: 0
    val used = user?.storageUsed?.coerceAtLeast(0) ?: 0
    val percent = if (quota > 0) ((used.toDouble() / quota) * 100).toInt().coerceIn(0, 100) else 0
    BoxWithConstraints(Modifier.fillMaxSize()) {
        LazyVerticalGrid(
            columns = if (maxWidth >= 600.dp) GridCells.Fixed(2) else GridCells.Fixed(1),
            modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp).testTag("profile-content"),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(strings[MobileString.AccountDetails], style = MaterialTheme.typography.titleMedium)
                    InfoRow(strings[MobileString.Username], user?.username.orEmpty())
                    InfoRow(strings[MobileString.Email], user?.email?.ifBlank { strings[MobileString.NotSet] } ?: strings[MobileString.NotSet])
                    InfoRow(
                        strings[MobileString.EmailVerified],
                        strings[if (user?.emailVerified == true) MobileString.EmailVerified else MobileString.EmailUnverified],
                    )
                    InfoRow(strings[MobileString.Role], user?.role.orEmpty())
                    InfoRow(strings[MobileString.RegisteredAt], user?.createdAt ?: strings[MobileString.NotSet])
                    state.account.profileError?.let { Text(strings.resolve(it).orEmpty(), color = MaterialTheme.colorScheme.error) }
                    OutlinedButton(onClick = onRefresh, enabled = !state.account.profileRefreshing) {
                        Text(strings[MobileString.RefreshProfile])
                    }
                }
            }
        }
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(strings[MobileString.Storage], style = MaterialTheme.typography.titleMedium)
                    Text(strings.format(MobileString.StorageUsage, formatBytes(used), formatBytes(quota), percent))
                    LinearProgressIndicator(progress = { percent / 100f }, modifier = Modifier.fillMaxWidth())
                    if (percent >= 90) Text(strings[MobileString.StorageNearQuota], color = MaterialTheme.colorScheme.error)
                }
            }
        }
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(strings[MobileString.Language], style = MaterialTheme.typography.titleMedium)
                    listOf(
                        MobileLocale.ENGLISH_US to MobileString.LanguageEnglish,
                        MobileLocale.SIMPLIFIED_CHINESE_CN to MobileString.LanguageChineseCn,
                        MobileLocale.SIMPLIFIED_CHINESE_SG to MobileString.LanguageChineseSg,
                    ).forEach { (locale, label) ->
                        OutlinedButton(
                            onClick = { onLocale(locale) },
                            enabled = !state.account.localeSaving && state.account.locale != locale,
                            modifier = Modifier.fillMaxWidth().testTag("profile-locale-${locale.languageTag}"),
                        ) { Text(strings[label]) }
                    }
                    state.account.localeError?.let { Text(strings.resolve(it).orEmpty(), color = MaterialTheme.colorScheme.error) }
                }
            }
        }
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(strings[MobileString.Appearance], style = MaterialTheme.typography.titleMedium)
                    listOf(
                        ThemeMode.SYSTEM to MobileString.ThemeSystem,
                        ThemeMode.LIGHT to MobileString.ThemeLight,
                        ThemeMode.DARK to MobileString.ThemeDark,
                    ).forEach { (mode, label) ->
                        OutlinedButton(
                            onClick = { onTheme(mode) },
                            enabled = state.account.themeMode != mode,
                            modifier = Modifier.fillMaxWidth().testTag("profile-theme-${mode.name.lowercase()}"),
                        ) { Text(strings[label]) }
                    }
                }
            }
        }
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(strings[MobileString.CurrentServer], style = MaterialTheme.typography.titleMedium)
                    OutlinedTextField(
                        value = serverInput,
                        onValueChange = { serverInput = it },
                        label = { Text(strings[MobileString.ServerAddress]) },
                        modifier = Modifier.fillMaxWidth().testTag("profile-server"),
                    )
                    state.account.serverError?.let { Text(strings.resolve(it).orEmpty(), color = MaterialTheme.colorScheme.error) }
                    OutlinedButton(
                        onClick = { onRequestServerSwitch(serverInput) },
                        enabled = !state.account.serverSwitching,
                        modifier = Modifier.fillMaxWidth().testTag("profile-switch-server"),
                    ) { Text(strings[MobileString.SwitchServer]) }
                    HorizontalDivider()
                    InfoRow(strings[MobileString.CurrentServer], state.server)
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
                    Text(strings[MobileString.Diagnostics])
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
                    Text(strings[MobileString.Logout])
                }
            }
        }
    }
    if (state.account.serverSwitchConfirming) {
        AlertDialog(
            onDismissRequest = onCancelServerSwitch,
            title = { Text(strings[MobileString.SwitchServerTitle]) },
            text = { Text(strings[MobileString.SwitchServerMessage]) },
            confirmButton = {
                Button(onClick = onConfirmServerSwitch, enabled = !state.account.serverSwitching) {
                    Text(strings[MobileString.Confirm])
                }
            },
            dismissButton = { OutlinedButton(onClick = onCancelServerSwitch) { Text(strings[MobileString.Cancel]) } },
        )
    }
}

private fun formatBytes(value: Long): String {
    if (value < 1024) return "$value B"
    val units = listOf("KiB", "MiB", "GiB", "TiB")
    var amount = value.toDouble()
    var index = -1
    do { amount /= 1024.0; index++ } while (amount >= 1024 && index < units.lastIndex)
    val rounded = (amount * 10).toLong() / 10.0
    return "$rounded ${units[index]}"
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
    state: DiagnosticsState,
    username: String,
    officeRenderer: OfficeRenderer,
    roomTaskCount: suspend () -> Int,
    onLoadOffice: (Long, Long) -> Unit,
    onOfficeStatus: (UiMessage) -> Unit,
) {
    val strings = LocalMobileStrings.current
    var teamId by remember { mutableStateOf("") }
    var fileId by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    var databaseStatus by remember(strings.locale) { mutableStateOf(strings[MobileString.RoomUnchecked]) }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp).testTag("diagnostics-content"),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(strings[MobileString.RoomDatabase], style = MaterialTheme.typography.titleMedium)
                    Button(
                        onClick = {
                            scope.launch {
                                databaseStatus = runCatching {
                                    val count = roomTaskCount()
                                    strings.format(MobileString.RoomHealthy, count)
                                }.getOrElse { strings.format(MobileString.RoomCheckFailed, it.message ?: strings[MobileString.UnknownError]) }
                            }
                        },
                        modifier = Modifier.testTag("diagnostics-room-check"),
                    ) { Text(strings[MobileString.CheckRoom]) }
                    Text(databaseStatus, modifier = Modifier.testTag("diagnostics-room-status"))
                }
            }
        }
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(strings[MobileString.OfficeDiagnostics], style = MaterialTheme.typography.titleMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = teamId,
                            onValueChange = { teamId = it.filter(Char::isDigit) },
                            label = { Text(strings[MobileString.TeamId]) },
                            modifier = Modifier.weight(1f).testTag("diagnostics-team-id"),
                            singleLine = true,
                        )
                        OutlinedTextField(
                            value = fileId,
                            onValueChange = { fileId = it.filter(Char::isDigit) },
                            label = { Text(strings[MobileString.FileId]) },
                            modifier = Modifier.weight(1f).testTag("diagnostics-file-id"),
                            singleLine = true,
                        )
                    }
                    Button(
                        enabled = teamId.isNotBlank() && fileId.isNotBlank(),
                        onClick = { onLoadOffice(teamId.toLong(), fileId.toLong()) },
                        modifier = Modifier.testTag("diagnostics-office-load"),
                    ) { Text(strings[MobileString.LoadReadOnlyDocument]) }
                    state.office?.let { office ->
                        Box(Modifier.fillMaxWidth().height(480.dp).testTag("diagnostics-office-renderer")) {
                            officeRenderer.Render(
                                office,
                                Modifier.fillMaxSize(),
                                onOfficeStatus,
                            )
                        }
                    }
                    Text(strings.resolve(state.status).orEmpty(), modifier = Modifier.testTag("diagnostics-office-status"))
                    Text(strings.format(MobileString.CurrentUser, username))
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
    val strings = LocalMobileStrings.current
    Card(modifier) {
        Column(
            Modifier.fillMaxWidth().padding(if (compact) 12.dp else 24.dp),
            horizontalAlignment = if (compact) Alignment.Start else Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(strings[MobileString.LoadingFailed], style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.error)
            Text(message)
            OutlinedButton(onClick = onRetry, modifier = Modifier.testTag("content-retry")) {
                Text(strings[MobileString.Retry])
            }
        }
    }
}

private fun statusKey(status: TransferStatus): MobileString = when (status) {
    TransferStatus.QUEUED -> MobileString.Queued
    TransferStatus.PREPARING -> MobileString.Preparing
    TransferStatus.RUNNING -> MobileString.Running
    TransferStatus.PAUSED -> MobileString.Paused
    TransferStatus.WAITING_FOR_LOGIN -> MobileString.WaitingForLogin
    TransferStatus.SUCCEEDED -> MobileString.Succeeded
    TransferStatus.FAILED -> MobileString.Failed
    TransferStatus.CANCELED -> MobileString.Canceled
}

private fun transferErrorKey(key: String): MobileString = when (key) {
    "error.unauthorized" -> MobileString.ErrorUnauthorized
    "error.storageQuotaExceeded" -> MobileString.ErrorQuota
    "error.fileNameExists" -> MobileString.ErrorNameConflict
    "error.network", "error.interrupted" -> MobileString.ErrorNetworkRetry
    "error.serverChanged" -> MobileString.ErrorServerChanged
    "error.invalidTransfer" -> MobileString.ErrorInvalidTransfer
    "error.fileUnavailable" -> MobileString.ErrorFileUnavailable
    "error.backgroundScheduling" -> MobileString.ErrorBackgroundScheduling
    else -> MobileString.ErrorTransfer
}

private fun TransferTask.progressPercent(): Int =
    if (bytesTotal <= 0) 0 else ((bytesDone.coerceIn(0, bytesTotal) * 100) / bytesTotal).toInt()

private val TERMINAL_TRANSFER_STATUSES = setOf(
    TransferStatus.SUCCEEDED, TransferStatus.FAILED, TransferStatus.CANCELED,
)
