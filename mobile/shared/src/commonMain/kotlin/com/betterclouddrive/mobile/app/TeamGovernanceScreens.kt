@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.betterclouddrive.mobile.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.betterclouddrive.mobile.core.localization.LocalMobileStrings
import com.betterclouddrive.mobile.core.localization.MobileString
import com.betterclouddrive.mobile.core.localization.MobileStrings
import com.betterclouddrive.mobile.core.model.TeamOperationLog
import com.betterclouddrive.mobile.core.model.TeamTask
import com.betterclouddrive.mobile.core.platform.DocumentPicker
import com.betterclouddrive.mobile.core.platform.DownloadRequest
import com.betterclouddrive.mobile.core.platform.LocalDateTimeService
import com.betterclouddrive.mobile.feature.teams.TeamAuditDraft
import com.betterclouddrive.mobile.feature.teams.TeamAuditState
import com.betterclouddrive.mobile.feature.teams.TeamTasksState
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

@Composable
fun TeamAuditScreen(
    state: TeamAuditState,
    wide: Boolean,
    dateTime: LocalDateTimeService,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onActor: (String) -> Unit,
    onAction: (String) -> Unit,
    onTargetType: (String) -> Unit,
    onResult: (Int?) -> Unit,
    onStartAt: (Long?) -> Unit,
    onEndAt: (Long?) -> Unit,
    onApply: () -> Unit,
    onReset: () -> Unit,
    onExport: () -> Unit,
    onLoadMore: () -> Unit,
    onConsumeFeedback: () -> Unit,
) {
    val strings = LocalMobileStrings.current
    val snackbar = remember { SnackbarHostState() }
    var selectedLog by remember { mutableStateOf<TeamOperationLog?>(null) }
    var filtersExpanded by remember { mutableStateOf(false) }
    LaunchedEffect(state.feedback) {
        state.feedback?.let { snackbar.showSnackbar(strings.resolve(it).orEmpty()); onConsumeFeedback() }
    }
    Scaffold(
        modifier = Modifier.testTag("team-audit-screen"),
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = { TopAppBar(
            title = { Text(strings[MobileString.TeamAuditLogs]) },
            navigationIcon = { IconButton(onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, strings[MobileString.BackToParent]) } },
            actions = { IconButton(onRefresh, enabled = state.canView && !state.loading && !state.refreshing) {
                Icon(Icons.Default.Refresh, strings[MobileString.RefreshDirectory])
            } },
        ) },
    ) { padding ->
        if (!state.canView) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text(strings[MobileString.TeamAuditForbidden], modifier = Modifier.testTag("team-audit-forbidden"))
            }
        } else if (wide) {
            Row(Modifier.fillMaxSize().padding(padding).padding(20.dp).testTag("team-audit-wide"), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                AuditFilters(state, dateTime, onActor, onAction, onTargetType, onResult, onStartAt, onEndAt, onApply, onReset, onExport,
                    Modifier.weight(.42f))
                AuditLogList(state, onRefresh, onLoadMore, { selectedLog = it }, Modifier.weight(.58f))
            }
        } else {
            Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp).testTag("team-audit-phone")) {
                OutlinedButton(
                    onClick = { filtersExpanded = !filtersExpanded },
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp).testTag("team-audit-filter-toggle"),
                ) {
                    Text(strings[if (filtersExpanded) MobileString.HideAuditFilters else MobileString.ShowAuditFilters])
                }
                if (filtersExpanded) {
                    AuditFilters(state, dateTime, onActor, onAction, onTargetType, onResult, onStartAt, onEndAt, onApply, onReset, onExport,
                        Modifier.fillMaxWidth().padding(vertical = 12.dp))
                }
                AuditLogList(state, onRefresh, onLoadMore, { selectedLog = it }, Modifier.weight(1f))
            }
        }
    }
    selectedLog?.let { log -> AuditDetailDialog(log, strings) { selectedLog = null } }
}

@Composable
private fun AuditFilters(
    state: TeamAuditState,
    dateTime: LocalDateTimeService,
    onActor: (String) -> Unit,
    onAction: (String) -> Unit,
    onTargetType: (String) -> Unit,
    onResult: (Int?) -> Unit,
    onStartAt: (Long?) -> Unit,
    onEndAt: (Long?) -> Unit,
    onApply: () -> Unit,
    onReset: () -> Unit,
    onExport: () -> Unit,
    modifier: Modifier,
) {
    val strings = LocalMobileStrings.current
    Card(modifier.testTag("team-audit-filters")) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(strings[MobileString.AuditFilters], style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(state.draft.actorUserId, onActor, Modifier.fillMaxWidth().testTag("audit-filter-actor"),
                label = { Text(strings[MobileString.AuditActor]) }, singleLine = true)
            OutlinedTextField(state.draft.action, onAction, Modifier.fillMaxWidth().testTag("audit-filter-action"),
                label = { Text(strings[MobileString.AuditAction]) }, singleLine = true)
            OutlinedTextField(state.draft.targetType, onTargetType, Modifier.fillMaxWidth().testTag("audit-filter-target"),
                label = { Text(strings[MobileString.AuditTargetType]) }, singleLine = true)
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.testTag("audit-filter-result")) {
                items(listOf<Int?>(null, 1, 0)) { value -> FilterChip(
                    selected = state.draft.result == value,
                    onClick = { onResult(value) },
                    label = { Text(strings[when (value) {
                        1 -> MobileString.AuditSuccess
                        0 -> MobileString.AuditFailure
                        else -> MobileString.All
                    }]) },
                ) }
            }
            AuditDateButton(strings[MobileString.AuditStartAt], state.draft.startAtEpochMillis, dateTime, 0, 0, onStartAt)
            AuditDateButton(
                strings[MobileString.AuditEndAt], state.draft.endAtEpochMillis, dateTime,
                dateTime.hourOfDay(dateTime.nowEpochMillis()), dateTime.minuteOfHour(dateTime.nowEpochMillis()), onEndAt,
            )
            state.filterError?.let { Text(strings.resolve(it).orEmpty(), color = MaterialTheme.colorScheme.error) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onApply, enabled = !state.loading && !state.exporting, modifier = Modifier.testTag("audit-filter-apply")) {
                    Text(strings[MobileString.ApplyFilters])
                }
                OutlinedButton(onReset, enabled = !state.loading && !state.exporting) { Text(strings[MobileString.ResetFilters]) }
            }
            OutlinedButton(onExport, enabled = !state.exporting, modifier = Modifier.fillMaxWidth().testTag("audit-export")) {
                if (state.exporting) CircularProgressIndicator(Modifier.width(18.dp).height(18.dp))
                else Text(strings[MobileString.AuditExport])
            }
        }
    }
}

@Composable
private fun AuditDateButton(
    label: String,
    value: Long?,
    dateTime: LocalDateTimeService,
    defaultHour: Int,
    defaultMinute: Int,
    onChange: (Long?) -> Unit,
) {
    val strings = LocalMobileStrings.current
    var picking by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        OutlinedButton({ picking = true }, Modifier.weight(1f)) {
            Text(value?.let(dateTime::formatDateTime) ?: label, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (value != null) TextButton({ onChange(null) }) { Text(strings[MobileString.Clear]) }
    }
    if (picking) AuditDateTimeDialog(value, dateTime, defaultHour, defaultMinute, { picking = false }, { onChange(it); picking = false })
}

@Composable
private fun AuditDateTimeDialog(
    initial: Long?,
    dateTime: LocalDateTimeService,
    defaultHour: Int,
    defaultMinute: Int,
    onDismiss: () -> Unit,
    onConfirm: (Long) -> Unit,
) {
    val strings = LocalMobileStrings.current
    val base = initial ?: dateTime.nowEpochMillis()
    val picker = rememberDatePickerState(initialSelectedDateMillis = base)
    var hour by remember { mutableStateOf((initial?.let(dateTime::hourOfDay) ?: defaultHour).toString().padStart(2, '0')) }
    var minute by remember { mutableStateOf((initial?.let(dateTime::minuteOfHour) ?: defaultMinute).toString().padStart(2, '0')) }
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton({ picker.selectedDateMillis?.let {
            onConfirm(dateTime.localDateTimeToEpochMillis(it, hour.toIntOrNull()?.coerceIn(0, 23) ?: 0,
                minute.toIntOrNull()?.coerceIn(0, 59) ?: 0))
        } }) { Text(strings[MobileString.Confirm]) } },
        dismissButton = { TextButton(onDismiss) { Text(strings[MobileString.Cancel]) } },
    ) {
        Column {
            DatePicker(picker)
            Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(hour, { hour = it.filter(Char::isDigit).take(2) }, Modifier.weight(1f), label = { Text(strings[MobileString.Hour]) }, singleLine = true)
                OutlinedTextField(minute, { minute = it.filter(Char::isDigit).take(2) }, Modifier.weight(1f), label = { Text(strings[MobileString.Minute]) }, singleLine = true)
            }
        }
    }
}

@Composable
private fun AuditLogList(
    state: TeamAuditState,
    onRetry: () -> Unit,
    onLoadMore: () -> Unit,
    onOpen: (TeamOperationLog) -> Unit,
    modifier: Modifier,
) {
    val strings = LocalMobileStrings.current
    when {
        state.loading && state.logs.isEmpty() -> Box(modifier, contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        state.error != null && state.logs.isEmpty() -> Column(modifier, verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            Text(strings.resolve(state.error).orEmpty(), color = MaterialTheme.colorScheme.error)
            Button(onRetry) { Text(strings[MobileString.Retry]) }
        }
        state.logs.isEmpty() -> Box(modifier, contentAlignment = Alignment.Center) { Text(strings[MobileString.AuditEmpty]) }
        else -> LazyColumn(modifier.testTag("team-audit-list"), contentPadding = PaddingValues(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            state.error?.let { error -> item {
                Text(strings.resolve(error).orEmpty(), color = MaterialTheme.colorScheme.error)
            } }
            item { Text(strings.format(MobileString.AuditCount, state.logs.size, state.total), style = MaterialTheme.typography.bodySmall) }
            items(state.logs, key = { it.id }) { log -> Card(onClick = { onOpen(log) }, modifier = Modifier.fillMaxWidth().testTag("team-audit-${log.id}")) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(log.action, style = MaterialTheme.typography.titleSmall)
                        Text(strings[if (log.result == 1) MobileString.AuditSuccess else MobileString.AuditFailure],
                            color = if (log.result == 1) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
                    }
                    Text(strings.format(MobileString.AuditTargetValue, log.targetType ?: "—", log.targetId ?: "", log.createdAt ?: "—"), style = MaterialTheme.typography.bodySmall)
                    Text(strings.format(MobileString.AuditActorValue, log.actorUserId ?: "—", log.statusCode ?: "—"), style = MaterialTheme.typography.bodySmall)
                }
            } }
            if (state.loadingMore) item { Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() } }
            else if (state.loadMoreError != null) item { TextButton(onLoadMore) { Text(strings[MobileString.RetryLoadMore]) } }
            else if (state.hasMore) item { TextButton(onLoadMore, Modifier.fillMaxWidth().testTag("team-audit-load-more")) { Text(strings[MobileString.LoadMore]) } }
        }
    }
}

@Composable
private fun AuditDetailDialog(log: TeamOperationLog, strings: MobileStrings, onDismiss: () -> Unit) = AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text(log.action) },
    text = { Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(strings.format(MobileString.AuditActorDetail, log.actorUserId ?: "—"))
        Text(strings.format(MobileString.AuditTargetDetail, log.targetType ?: "—", log.targetId ?: "—"))
        Text(strings.format(MobileString.AuditStatusDetail, log.result, log.statusCode ?: "—"))
        Text(log.details ?: "—")
        log.requestId?.let { Text(strings.format(MobileString.RequestIdValue, it), style = MaterialTheme.typography.bodySmall) }
        log.traceId?.let { Text(strings.format(MobileString.TraceIdValue, it), style = MaterialTheme.typography.bodySmall) }
    } },
    confirmButton = { TextButton(onDismiss) { Text(strings[MobileString.Close]) } },
)

@Composable
fun TeamTasksScreen(
    state: TeamTasksState,
    documentPicker: DocumentPicker,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onStatus: (String?) -> Unit,
    onType: (String?) -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onRetry: (Long) -> Unit,
    onDownload: (Long, com.betterclouddrive.mobile.core.platform.PickedDestination) -> Unit,
    onConsumeFeedback: () -> Unit,
) {
    val strings = LocalMobileStrings.current
    val snackbar = remember { SnackbarHostState() }
    var pendingDownloadTask by remember { mutableStateOf<Long?>(null) }
    val downloadLauncher = documentPicker.rememberDownloadLauncher { destination ->
        pendingDownloadTask?.let { onDownload(it, destination) }
        pendingDownloadTask = null
    }
    LaunchedEffect(state.feedback) {
        state.feedback?.let { snackbar.showSnackbar(strings.resolve(it).orEmpty()); onConsumeFeedback() }
    }
    Scaffold(
        modifier = Modifier.testTag("team-tasks-screen"),
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = { TopAppBar(
            title = { Text(strings[MobileString.TeamTasks]) },
            navigationIcon = { IconButton(onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, strings[MobileString.BackToParent]) } },
            actions = { IconButton(onRefresh, enabled = !state.loading && !state.refreshing) { Icon(Icons.Default.Refresh, strings[MobileString.RefreshDirectory]) } },
        ) },
    ) { padding -> Column(Modifier.fillMaxSize().padding(padding)) {
        TaskFilterRow(strings[MobileString.Status], TASK_STATUSES, state.status, onStatus, "status", strings)
        TaskFilterRow(strings[MobileString.Type], TASK_TYPES, state.type, onType, "type", strings)
        if (state.refreshing) LinearProgressIndicator(Modifier.fillMaxWidth())
        when {
            state.loading && state.tasks.isEmpty() -> Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            state.error != null && state.tasks.isEmpty() -> Column(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                Text(strings.resolve(state.error).orEmpty(), color = MaterialTheme.colorScheme.error); Button(onRefresh) { Text(strings[MobileString.Retry]) }
            }
            state.tasks.isEmpty() -> Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) { Text(strings[MobileString.TeamTasksEmpty]) }
            else -> LazyColumn(Modifier.weight(1f).testTag("team-tasks-list"), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                state.error?.let { error -> item {
                    Text(strings.resolve(error).orEmpty(), color = MaterialTheme.colorScheme.error)
                } }
                items(state.tasks, key = { it.id }) { task -> TeamTaskCard(
                    task, task.id == state.highlightedTaskId, state, strings, onRetry,
                    onDownload = {
                        pendingDownloadTask = task.id
                        downloadLauncher(DownloadRequest(task.resultFileName ?: "result", task.resultContentType))
                    },
                ) }
            }
        }
        Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Button(onPrevious, enabled = state.page > 1 && !state.loading, modifier = Modifier.testTag("team-tasks-previous")) { Text(strings[MobileString.PreviousPage]) }
            Text(strings.format(MobileString.PageSummary, state.page, state.pages, state.total))
            Button(onNext, enabled = state.page < state.pages && !state.loading, modifier = Modifier.testTag("team-tasks-next")) { Text(strings[MobileString.NextPage]) }
        }
    } }
}

@Composable
private fun TaskFilterRow(label: String, values: List<String?>, selected: String?, onSelect: (String?) -> Unit, kind: String, strings: MobileStrings) {
    LazyRow(Modifier.fillMaxWidth().testTag("team-tasks-filter-$kind"), contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        item { Text(label, Modifier.padding(top = 10.dp)) }
        items(values) { value -> FilterChip(selected == value, { onSelect(value) }, { Text(taskFilterLabel(value, strings)) }) }
    }
}

@Composable
private fun TeamTaskCard(
    task: TeamTask,
    highlighted: Boolean,
    state: TeamTasksState,
    strings: MobileStrings,
    onRetry: (Long) -> Unit,
    onDownload: () -> Unit,
) {
    Card(Modifier.fillMaxWidth().testTag("team-task-${task.id}")) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(taskTitle(task, strings), style = MaterialTheme.typography.titleMedium,
                color = if (highlighted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
            Text(strings.format(MobileString.TaskStatusProgress, taskStatusLabel(task.status, strings), task.progress.coerceIn(0, 100)))
            if (task.isActive) LinearProgressIndicator({ task.progress.coerceIn(0, 100) / 100f }, Modifier.fillMaxWidth())
            Text(task.createdAt ?: "—", style = MaterialTheme.typography.bodySmall)
            taskResult(task, strings)?.let { Text(it, color = if (task.status == "FAILED") MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant) }
            if (state.downloadingTaskId == task.id) {
                val total = state.downloadTotalBytes
                Text(if (total != null && total > 0) strings.format(MobileString.DownloadProgress, state.downloadedBytes * 100 / total)
                    else strings[MobileString.Downloading])
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                if (task.canRetry) TextButton({ onRetry(task.id) }, enabled = state.operatingTaskId == null,
                    modifier = Modifier.testTag("team-task-retry-${task.id}")) { Text(strings[MobileString.Retry]) }
                if (task.canDownload) TextButton(onDownload, enabled = state.downloadingTaskId == null,
                    modifier = Modifier.testTag("team-task-download-${task.id}")) { Icon(Icons.Default.Download, null); Text(strings[MobileString.Download]) }
            }
        }
    }
}

private fun taskTitle(task: TeamTask, strings: MobileStrings): String = strings[when (task.titleKey) {
    "task.auditExport.title" -> MobileString.TaskAuditExport
    "task.folderZip.title" -> MobileString.TaskFolderZip
    "task.onlyofficeSave.title" -> MobileString.TaskOnlyOfficeSave
    "task.thumbnail.title" -> MobileString.TaskThumbnail
    "task.teamCleanup.title" -> MobileString.TaskTeamCleanup
    else -> MobileString.TaskUnknown
}]

private fun taskResult(task: TeamTask, strings: MobileStrings): String? = when {
    task.errorMessageKey == "task.failed" -> strings.format(MobileString.TaskFailed, taskParam(task, "taskType") ?: task.taskType)
    task.resultMessageKey == "task.succeeded" -> strings.format(MobileString.TaskSucceeded, taskParam(task, "taskType") ?: task.taskType)
    task.resultMessageKey == "task.resultExpired" -> strings[MobileString.TaskResultExpired]
    task.errorMessageKey != null -> strings[MobileString.TaskFailedGeneric]
    else -> null
}

private fun taskParam(task: TeamTask, key: String): String? =
    (task.errorMessageParams[key] ?: task.resultMessageParams[key] ?: task.titleParams[key])?.jsonPrimitive?.contentOrNull

private fun taskFilterLabel(value: String?, strings: MobileStrings): String = if (value == null) strings[MobileString.All] else when (value) {
    in TASK_STATUSES.filterNotNull() -> taskStatusLabel(value, strings)
    "AUDIT_EXPORT" -> strings[MobileString.TaskAuditExport]
    "FOLDER_ZIP" -> strings[MobileString.TaskFolderZip]
    "ONLYOFFICE_SAVE" -> strings[MobileString.TaskOnlyOfficeSave]
    "THUMBNAIL" -> strings[MobileString.TaskThumbnail]
    "TEAM_CLEANUP" -> strings[MobileString.TaskTeamCleanup]
    else -> value
}

private fun taskStatusLabel(value: String, strings: MobileStrings): String = strings[when (value) {
    "PENDING" -> MobileString.Queued
    "RUNNING" -> MobileString.Running
    "SUCCEEDED" -> MobileString.Succeeded
    "FAILED" -> MobileString.Failed
    else -> MobileString.TaskUnknown
}]

private val TASK_STATUSES = listOf<String?>(null, "PENDING", "RUNNING", "SUCCEEDED", "FAILED")
private val TASK_TYPES = listOf<String?>(null, "AUDIT_EXPORT", "FOLDER_ZIP", "THUMBNAIL", "ONLYOFFICE_SAVE", "TEAM_CLEANUP")
