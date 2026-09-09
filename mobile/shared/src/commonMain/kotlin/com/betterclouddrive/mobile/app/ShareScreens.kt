package com.betterclouddrive.mobile.app

import com.betterclouddrive.mobile.feature.shares.ShareManagementState

import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.betterclouddrive.mobile.core.localization.LocalMobileStrings
import com.betterclouddrive.mobile.core.localization.MobileString
import com.betterclouddrive.mobile.core.model.ShareExpirationFilter
import com.betterclouddrive.mobile.core.model.ShareLink
import com.betterclouddrive.mobile.core.model.UpdatePersonalShareRequest
import com.betterclouddrive.mobile.core.platform.ClipboardService
import com.betterclouddrive.mobile.core.platform.LocalDateTimeService
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

private enum class ShareExpiryChoice { NEVER, DAY_1, DAY_7, DAY_30, CUSTOM }
private enum class SharePasswordEdit { KEEP, SET, REMOVE }

@Composable
fun SharesScreen(
    state: ShareManagementState,
    dateTime: LocalDateTimeService,
    clipboard: ClipboardService,
    onQuery: (String) -> Unit,
    onFilters: (Boolean?, ShareExpirationFilter) -> Unit,
    onRefresh: () -> Unit,
    onLoadMore: () -> Unit,
    onOpen: (Long) -> Unit,
    onConsumeFeedback: () -> Unit,
    onBack: (() -> Unit)? = null,
) {
    val strings = LocalMobileStrings.current
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var passwordFilterOpen by remember { mutableStateOf(false) }
    var expiryFilterOpen by remember { mutableStateOf(false) }
    fun copy(value: String) {
        val success = clipboard.copyText(strings[MobileString.ShareLink], value)
        scope.launch {
            snackbar.showSnackbar(strings[if (success) MobileString.CopiedToClipboard else MobileString.ClipboardFailed])
        }
    }
    LaunchedEffect(state.feedback?.id) {
        state.feedback?.let {
            snackbar.showSnackbar(strings.resolve(it.message).orEmpty())
            onConsumeFeedback()
        }
    }
    Box(Modifier.fillMaxSize().testTag("shares-screen")) {
        Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (onBack != null) Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack, modifier = Modifier.testTag("team-shares-back")) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, strings[MobileString.BackToParent])
                }
                Text(strings[MobileString.TeamSharesTitle], style = MaterialTheme.typography.titleLarge)
            }
            OutlinedTextField(
                value = state.query,
                onValueChange = onQuery,
                label = { Text(strings[MobileString.SearchShares]) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().testTag("shares-search"),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Box {
                    OutlinedButton(onClick = { passwordFilterOpen = true }, modifier = Modifier.testTag("shares-password-filter")) {
                        Text(when (state.passwordProtected) {
                            true -> strings[MobileString.ShareFilterProtected]
                            false -> strings[MobileString.ShareFilterUnprotected]
                            null -> strings[MobileString.ShareFilterAll]
                        })
                    }
                    DropdownMenu(passwordFilterOpen, { passwordFilterOpen = false }) {
                        listOf<Boolean?>(null, true, false).forEach { value ->
                            DropdownMenuItem(
                                text = { Text(when (value) {
                                    true -> strings[MobileString.ShareFilterProtected]
                                    false -> strings[MobileString.ShareFilterUnprotected]
                                    null -> strings[MobileString.ShareFilterAll]
                                }) },
                                onClick = { passwordFilterOpen = false; onFilters(value, state.expiration) },
                            )
                        }
                    }
                }
                Box {
                    OutlinedButton(onClick = { expiryFilterOpen = true }, modifier = Modifier.testTag("shares-expiry-filter")) {
                        Text(expirationLabel(state.expiration))
                    }
                    DropdownMenu(expiryFilterOpen, { expiryFilterOpen = false }) {
                        ShareExpirationFilter.entries.forEach { value ->
                            DropdownMenuItem(
                                text = { Text(expirationLabel(value)) },
                                onClick = { expiryFilterOpen = false; onFilters(state.passwordProtected, value) },
                            )
                        }
                    }
                }
                IconButton(onClick = onRefresh, modifier = Modifier.testTag("shares-refresh")) {
                    Icon(Icons.Default.Refresh, strings[MobileString.RefreshDirectory])
                }
            }
            when {
                state.loading && state.items.isEmpty() -> CenterProgress()
                state.error != null && state.items.isEmpty() -> ShareError(strings.resolve(state.error).orEmpty(), onRefresh)
                state.items.isEmpty() -> ShareEmpty()
                else -> {
                    val listState = rememberLazyListState()
                    LaunchedEffect(listState, state.items.size, state.hasMore) {
                        snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }
                            .distinctUntilChanged()
                            .collect { if (state.hasMore && it >= state.items.lastIndex - 5) onLoadMore() }
                    }
                    LazyColumn(state = listState, verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.testTag("shares-list")) {
                        items(state.items, key = ShareLink::id) { share ->
                            ShareCard(share, dateTime, { onOpen(share.id) }, { copy(share.shareUrl) })
                        }
                        item {
                            when {
                                state.loadingMore -> Box(Modifier.fillMaxWidth().padding(12.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                                state.loadMoreError != null -> ShareError(strings.resolve(state.loadMoreError).orEmpty(), onLoadMore)
                            }
                        }
                    }
                }
            }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
    }
}

@Composable
private fun ShareCard(share: ShareLink, dateTime: LocalDateTimeService, onOpen: () -> Unit, onCopy: () -> Unit) {
    val strings = LocalMobileStrings.current
    Card(Modifier.fillMaxWidth().clickable(onClick = onOpen).testTag("share-row-${share.id}")) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Share, null)
                Text(share.fileName ?: strings[MobileString.ShareOriginalUnavailable], Modifier.weight(1f).padding(start = 8.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                IconButton(onClick = onCopy, modifier = Modifier.testTag("share-copy-${share.id}")) { Icon(Icons.Default.ContentCopy, strings[MobileString.CopyLink]) }
            }
            Text(share.shareUrl, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
            Text(if (share.maxVisits == null) strings.format(MobileString.ShareVisits, share.visitCount)
                else strings.format(MobileString.ShareVisitsLimited, share.visitCount, share.maxVisits))
            Text(strings[if (share.hasPassword) MobileString.ShareProtected else MobileString.ShareNoPassword])
            Text(shareStatus(share, dateTime))
            Text(share.expireAtEpochMs?.let(dateTime::formatDateTime) ?: strings[MobileString.ShareNeverExpires])
        }
    }
}

@Composable
fun ShareDetailScreen(
    state: ShareManagementState,
    dateTime: LocalDateTimeService,
    clipboard: ClipboardService,
    onRevealPassword: () -> Unit,
    onUpdate: (UpdatePersonalShareRequest) -> Unit,
    onCancel: () -> Unit,
    onConsumeFeedback: () -> Unit,
    onBack: (() -> Unit)? = null,
) {
    val strings = LocalMobileStrings.current
    val share = state.selected
    var editOpen by remember { mutableStateOf(false) }
    var cancelConfirm by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    fun copy(label: MobileString, value: String) {
        val ok = clipboard.copyText(strings[label], value)
        scope.launch { snackbar.showSnackbar(strings[if (ok) MobileString.CopiedToClipboard else MobileString.ClipboardFailed]) }
    }
    LaunchedEffect(state.feedback?.id) {
        state.feedback?.let { feedback ->
            if (!feedback.isError && feedback.message.key == MobileString.ShareUpdated) editOpen = false
            snackbar.showSnackbar(strings.resolve(feedback.message).orEmpty())
            onConsumeFeedback()
        }
    }
    Box(Modifier.fillMaxSize().testTag("share-detail")) {
        when {
            state.detailLoading && share == null -> ShareDetailFallback(onBack) { CenterProgress() }
            state.detailError != null && share == null -> ShareDetailFallback(onBack) {
                ShareError(strings.resolve(state.detailError).orEmpty()) {}
            }
            share == null -> ShareDetailFallback(onBack) { ShareEmpty() }
            else -> LazyColumn(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (onBack != null) item {
                    ShareDetailBack(onBack)
                }
                item { Text(share.fileName ?: strings[MobileString.ShareOriginalUnavailable], style = MaterialTheme.typography.headlineSmall) }
                item { ShareDetailRow(strings[MobileString.ShareLink], share.shareUrl) }
                item { ShareDetailRow(strings[MobileString.ShareCode], share.shareCode) }
                item { ShareDetailRow(strings[MobileString.FileId], share.fileId.toString()) }
                share.fileType?.let { item { ShareDetailRow(strings[MobileString.Type], it) } }
                share.fileSize?.let { item { ShareDetailRow(strings[MobileString.Size], formatFileSize(it)) } }
                item { ShareDetailRow(strings[MobileString.ShareExpiry], share.expireAtEpochMs?.let(dateTime::formatDateTime) ?: strings[MobileString.ShareNeverExpires]) }
                item { ShareDetailRow(strings[MobileString.ShareStatus], shareStatus(share, dateTime)) }
                item { ShareDetailRow(strings[MobileString.ShareMaxVisits], share.maxVisits?.toString() ?: strings[MobileString.ShareUnlimited]) }
                item { Text(strings.format(MobileString.ShareVisits, share.visitCount)) }
                item { Text(strings.format(MobileString.ShareDownloads, share.downloadCount)) }
                item {
                    if (share.hasPassword && state.selectedCanEdit) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(if (state.passwordVisible) state.revealedPassword.orEmpty() else "••••", Modifier.weight(1f))
                            TextButton(onClick = onRevealPassword, enabled = !state.passwordLoading, modifier = Modifier.testTag("share-password-toggle")) {
                                Icon(if (state.passwordVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility, null)
                                Text(strings[if (state.passwordVisible) MobileString.HidePassword else MobileString.RevealPassword])
                            }
                            if (state.passwordVisible && state.revealedPassword != null) IconButton(
                                onClick = { copy(MobileString.SharePassword, state.revealedPassword) },
                                modifier = Modifier.testTag("share-password-copy"),
                            ) { Icon(Icons.Default.ContentCopy, strings[MobileString.CopyPassword]) }
                        }
                    } else if (!share.hasPassword) Text(strings[MobileString.ShareNoPassword])
                }
                item { Button(onClick = { copy(MobileString.ShareLink, share.shareUrl) }, modifier = Modifier.fillMaxWidth().testTag("share-detail-copy")) { Text(strings[MobileString.CopyLink]) } }
                if (state.selectedCanEdit) {
                    item { OutlinedButton(onClick = { editOpen = true }, modifier = Modifier.fillMaxWidth().testTag("share-edit")) { Icon(Icons.Default.Edit, null); Text(strings[MobileString.EditShare]) } }
                    item { OutlinedButton(onClick = { cancelConfirm = true }, enabled = !state.canceling, modifier = Modifier.fillMaxWidth().testTag("share-cancel")) { Icon(Icons.Default.Delete, null); Text(strings[MobileString.CancelShare]) } }
                }
                state.detailError?.let { item { Text(strings.resolve(it).orEmpty(), color = MaterialTheme.colorScheme.error) } }
            }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
    }
    if (editOpen && share != null) ShareEditDialog(share, state, dateTime, { editOpen = false }) { request ->
        onUpdate(request)
    }
    if (cancelConfirm) AlertDialog(
        onDismissRequest = { if (!state.canceling) cancelConfirm = false },
        title = { Text(strings[MobileString.CancelShare]) },
        text = { Text(strings[MobileString.CancelShareMessage]) },
        confirmButton = { Button(onClick = { onCancel(); cancelConfirm = false }, enabled = !state.canceling) { Text(strings[MobileString.Confirm]) } },
        dismissButton = { TextButton(onClick = { cancelConfirm = false }) { Text(strings[MobileString.Cancel]) } },
    )
}

@Composable
private fun ShareDetailFallback(onBack: (() -> Unit)?, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        if (onBack != null) ShareDetailBack(onBack)
        Box(Modifier.fillMaxWidth().weight(1f)) { content() }
    }
}

@Composable
private fun ShareDetailBack(onBack: () -> Unit) {
    val strings = LocalMobileStrings.current
    Row(Modifier.padding(horizontal = 20.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack, modifier = Modifier.testTag("team-share-detail-back")) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, strings[MobileString.BackToParent])
        }
        Text(strings[MobileString.TeamSharesTitle], style = MaterialTheme.typography.titleLarge)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShareCreatorOverlay(
    state: ShareManagementState,
    wide: Boolean,
    dateTime: LocalDateTimeService,
    clipboard: ClipboardService,
    onCreate: (String?, Long?, Int?) -> Unit,
    onDismiss: () -> Unit,
) {
    if (state.createTarget == null) return
    val content: @Composable () -> Unit = {
        ShareCreateContent(state, dateTime, clipboard, onCreate, onDismiss)
    }
    if (wide) AlertDialog(
        onDismissRequest = { if (!state.creating) onDismiss() },
        title = { Text(LocalMobileStrings.current[MobileString.CreateShare]) },
        text = content,
        confirmButton = {},
    ) else ModalBottomSheet(onDismissRequest = { if (!state.creating) onDismiss() }, modifier = Modifier.testTag("share-create-sheet")) {
        Column(Modifier.padding(20.dp)) { content() }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ShareCreateContent(
    state: ShareManagementState,
    dateTime: LocalDateTimeService,
    clipboard: ClipboardService,
    onCreate: (String?, Long?, Int?) -> Unit,
    onDismiss: () -> Unit,
) {
    val strings = LocalMobileStrings.current
    var password by remember { mutableStateOf("") }
    var maxVisits by remember { mutableStateOf("") }
    var expiry by remember { mutableStateOf(ShareExpiryChoice.NEVER) }
    var customDate by remember { mutableStateOf<Long?>(null) }
    var hour by remember { mutableStateOf("12") }
    var minute by remember { mutableStateOf("00") }
    var dateDialog by remember { mutableStateOf(false) }
    val created = state.created
    if (created != null) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.testTag("share-created")) {
            Text(strings[MobileString.ShareCreated], style = MaterialTheme.typography.titleLarge)
            Text(created.shareUrl)
            Button(onClick = { clipboard.copyText(strings[MobileString.ShareLink], created.shareUrl) }, modifier = Modifier.fillMaxWidth()) { Text(strings[MobileString.CopyLink]) }
            OutlinedButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) { Text(strings[MobileString.Close]) }
        }
        return
    }
    val normalizedPassword = password.trim()
    val parsedMaxVisits = maxVisits.toIntOrNull()
    val expires = expiryEpoch(expiry, customDate, hour, minute, dateTime)
    val localError = when {
        normalizedPassword.isNotEmpty() && normalizedPassword.length !in 4..16 -> MobileString.ErrorSharePasswordLength
        maxVisits.isNotEmpty() && (parsedMaxVisits == null || parsedMaxVisits < 1) -> MobileString.ErrorShareMaxVisits
        expiry == ShareExpiryChoice.CUSTOM && customDate == null -> MobileString.ShareChooseDate
        expires != null && expires <= dateTime.nowEpochMillis() -> MobileString.ErrorShareExpiryPast
        else -> null
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.testTag("share-create-form")) {
        Text(state.createTarget?.fileName.orEmpty(), style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(password, { password = it }, label = { Text(strings[MobileString.SharePassword]) }, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth().testTag("share-create-password"))
        OutlinedTextField(maxVisits, { maxVisits = it.filter(Char::isDigit) }, label = { Text(strings[MobileString.ShareMaxVisits]) }, placeholder = { Text(strings[MobileString.ShareUnlimited]) }, modifier = Modifier.fillMaxWidth().testTag("share-create-max-visits"))
        ShareExpiryChoices(expiry) { expiry = it }
        if (expiry == ShareExpiryChoice.CUSTOM) {
            OutlinedButton(onClick = { dateDialog = true }, modifier = Modifier.fillMaxWidth().testTag("share-create-date")) {
                Text(customDate?.let(dateTime::formatDateTime) ?: strings[MobileString.ShareChooseDate])
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(hour, { hour = it.filter(Char::isDigit).take(2) }, label = { Text(strings[MobileString.ShareHour]) }, modifier = Modifier.weight(1f))
                OutlinedTextField(minute, { minute = it.filter(Char::isDigit).take(2) }, label = { Text(strings[MobileString.ShareMinute]) }, modifier = Modifier.weight(1f))
            }
        }
        localError?.let { Text(strings[it], color = MaterialTheme.colorScheme.error) }
            ?: state.createError?.let { Text(strings.resolve(it).orEmpty(), color = MaterialTheme.colorScheme.error) }
        Button(
            onClick = {
                onCreate(normalizedPassword.takeIf(String::isNotEmpty), expires, parsedMaxVisits)
            },
            enabled = !state.creating && localError == null,
            modifier = Modifier.fillMaxWidth().testTag("share-create-submit"),
        ) { if (state.creating) CircularProgressIndicator() else Text(strings[MobileString.CreateShare]) }
        TextButton(onClick = onDismiss, enabled = !state.creating, modifier = Modifier.fillMaxWidth()) { Text(strings[MobileString.Cancel]) }
    }
    if (dateDialog) {
        val picker = rememberDatePickerState(initialSelectedDateMillis = customDate ?: dateTime.nowEpochMillis())
        DatePickerDialog(
            onDismissRequest = { dateDialog = false },
            confirmButton = { TextButton(onClick = { customDate = picker.selectedDateMillis; dateDialog = false }) { Text(strings[MobileString.Confirm]) } },
            dismissButton = { TextButton(onClick = { dateDialog = false }) { Text(strings[MobileString.Cancel]) } },
        ) { DatePicker(picker) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ShareEditDialog(
    share: ShareLink,
    state: ShareManagementState,
    dateTime: LocalDateTimeService,
    onDismiss: () -> Unit,
    onSave: (UpdatePersonalShareRequest) -> Unit,
) {
    val strings = LocalMobileStrings.current
    var passwordMode by remember { mutableStateOf(SharePasswordEdit.KEEP) }
    var password by remember { mutableStateOf("") }
    var maxVisits by remember { mutableStateOf(share.maxVisits?.toString().orEmpty()) }
    var clearMax by remember { mutableStateOf(share.maxVisits == null) }
    var expiry by remember { mutableStateOf(if (share.expireAtEpochMs == null) ShareExpiryChoice.NEVER else ShareExpiryChoice.CUSTOM) }
    var customDate by remember { mutableStateOf(share.expireAtEpochMs) }
    var hour by remember { mutableStateOf(share.expireAtEpochMs?.let(dateTime::hourOfDay)?.toString() ?: "12") }
    var minute by remember { mutableStateOf(share.expireAtEpochMs?.let(dateTime::minuteOfHour)?.toString()?.padStart(2, '0') ?: "00") }
    var dateDialog by remember { mutableStateOf(false) }
    val normalizedPassword = password.trim()
    val parsedMaxVisits = maxVisits.toIntOrNull()
    val expires = expiryEpoch(expiry, customDate, hour, minute, dateTime)
    val localError = when {
        passwordMode == SharePasswordEdit.SET && normalizedPassword.length !in 4..16 -> MobileString.ErrorSharePasswordLength
        !clearMax && (parsedMaxVisits == null || parsedMaxVisits < 1) -> MobileString.ErrorShareMaxVisits
        expiry == ShareExpiryChoice.CUSTOM && customDate == null -> MobileString.ShareChooseDate
        expires != null && expires <= dateTime.nowEpochMillis() -> MobileString.ErrorShareExpiryPast
        else -> null
    }
    AlertDialog(
        onDismissRequest = { if (!state.updating) onDismiss() },
        title = { Text(strings[MobileString.EditShare]) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SharePasswordEdit.entries.forEach { mode ->
                    Row(Modifier.clickable { passwordMode = mode }, verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(passwordMode == mode, { passwordMode = mode })
                        Text(strings[when (mode) {
                            SharePasswordEdit.KEEP -> MobileString.ShareKeepPassword
                            SharePasswordEdit.SET -> MobileString.ShareSetPassword
                            SharePasswordEdit.REMOVE -> MobileString.ShareRemovePassword
                        }])
                    }
                }
                if (passwordMode == SharePasswordEdit.SET) OutlinedTextField(password, { password = it }, label = { Text(strings[MobileString.SharePassword]) })
                OutlinedTextField(maxVisits, { maxVisits = it.filter(Char::isDigit); clearMax = false }, enabled = !clearMax, label = { Text(strings[MobileString.ShareMaxVisits]) })
                Row(Modifier.clickable { clearMax = !clearMax }, verticalAlignment = Alignment.CenterVertically) { RadioButton(clearMax, { clearMax = !clearMax }); Text(strings[MobileString.ShareClearMaxVisits]) }
                ShareExpiryChoices(expiry) { expiry = it }
                if (expiry == ShareExpiryChoice.CUSTOM) {
                    OutlinedButton(onClick = { dateDialog = true }, modifier = Modifier.fillMaxWidth()) {
                        Text(customDate?.let(dateTime::formatDateTime) ?: strings[MobileString.ShareChooseDate])
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(hour, { hour = it.filter(Char::isDigit).take(2) }, label = { Text(strings[MobileString.ShareHour]) }, modifier = Modifier.weight(1f))
                        OutlinedTextField(minute, { minute = it.filter(Char::isDigit).take(2) }, label = { Text(strings[MobileString.ShareMinute]) }, modifier = Modifier.weight(1f))
                    }
                }
                localError?.let { Text(strings[it], color = MaterialTheme.colorScheme.error) }
                    ?: state.updateError?.let { Text(strings.resolve(it).orEmpty(), color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = { Button(onClick = {
            onSave(UpdatePersonalShareRequest(
                password = normalizedPassword.takeIf { passwordMode == SharePasswordEdit.SET },
                expireAt = expires,
                maxVisits = parsedMaxVisits.takeUnless { clearMax },
                clearPassword = passwordMode == SharePasswordEdit.REMOVE,
                clearExpireAt = expiry == ShareExpiryChoice.NEVER,
                clearMaxVisits = clearMax,
            ))
        }, enabled = !state.updating && localError == null) { Text(strings[MobileString.Save]) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(strings[MobileString.Cancel]) } },
    )
    if (dateDialog) {
        val picker = rememberDatePickerState(initialSelectedDateMillis = customDate ?: dateTime.nowEpochMillis())
        DatePickerDialog(
            onDismissRequest = { dateDialog = false },
            confirmButton = { TextButton(onClick = { customDate = picker.selectedDateMillis; dateDialog = false }) { Text(strings[MobileString.Confirm]) } },
            dismissButton = { TextButton(onClick = { dateDialog = false }) { Text(strings[MobileString.Cancel]) } },
        ) { DatePicker(picker) }
    }
}

@Composable
private fun ShareExpiryChoices(selected: ShareExpiryChoice, onSelect: (ShareExpiryChoice) -> Unit) {
    val strings = LocalMobileStrings.current
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        listOf(
            ShareExpiryChoice.NEVER to MobileString.ShareNeverExpires,
            ShareExpiryChoice.DAY_1 to MobileString.ShareOneDay,
            ShareExpiryChoice.DAY_7 to MobileString.ShareSevenDays,
            ShareExpiryChoice.DAY_30 to MobileString.ShareThirtyDays,
            ShareExpiryChoice.CUSTOM to MobileString.ShareCustomExpiry,
        ).forEach { (choice, label) ->
            TextButton(onClick = { onSelect(choice) }, modifier = Modifier.weight(1f)) {
                Text((if (selected == choice) "✓ " else "") + strings[label], maxLines = 2)
            }
        }
    }
}

private fun expiryEpoch(
    choice: ShareExpiryChoice,
    customDate: Long?,
    hour: String,
    minute: String,
    dateTime: LocalDateTimeService,
): Long? = when (choice) {
    ShareExpiryChoice.NEVER -> null
    ShareExpiryChoice.DAY_1 -> dateTime.nowEpochMillis() + 86_400_000L
    ShareExpiryChoice.DAY_7 -> dateTime.nowEpochMillis() + 7 * 86_400_000L
    ShareExpiryChoice.DAY_30 -> dateTime.nowEpochMillis() + 30 * 86_400_000L
    ShareExpiryChoice.CUSTOM -> customDate?.let {
        dateTime.localDateTimeToEpochMillis(it, hour.toIntOrNull()?.coerceIn(0, 23) ?: 0, minute.toIntOrNull()?.coerceIn(0, 59) ?: 0)
    }
}

@Composable private fun ShareDetailRow(label: String, value: String) { Column { Text(label, style = MaterialTheme.typography.labelMedium); Text(value) } }
@Composable private fun CenterProgress() = Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
@Composable private fun ShareEmpty() { val s = LocalMobileStrings.current; Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Column(horizontalAlignment = Alignment.CenterHorizontally) { Text(s[MobileString.SharesEmpty]); Text(s[MobileString.SharesEmptyDetail]) } } }
@Composable private fun ShareError(message: String, retry: () -> Unit) { Column(Modifier.fillMaxWidth().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) { Text(message, color = MaterialTheme.colorScheme.error); OutlinedButton(retry) { Text(LocalMobileStrings.current[MobileString.Retry]) } } }

@Composable
private fun expirationLabel(filter: ShareExpirationFilter): String {
    val strings = LocalMobileStrings.current
    return strings[when (filter) {
        ShareExpirationFilter.ALL -> MobileString.ShareFilterAll
        ShareExpirationFilter.ACTIVE -> MobileString.ShareActive
        ShareExpirationFilter.EXPIRED -> MobileString.ShareExpired
        ShareExpirationFilter.NEVER -> MobileString.ShareFilterNever
    }]
}

@Composable
private fun shareStatus(share: ShareLink, dateTime: LocalDateTimeService): String {
    val strings = LocalMobileStrings.current
    return strings[when {
        share.isCanceled -> MobileString.ShareCanceled
        share.expireAtEpochMs != null && share.expireAtEpochMs < dateTime.nowEpochMillis() -> MobileString.ShareExpired
        else -> MobileString.ShareActive
    }]
}
