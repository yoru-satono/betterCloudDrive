package com.betterclouddrive.mobile.app

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
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.betterclouddrive.mobile.core.localization.LocalMobileStrings
import com.betterclouddrive.mobile.core.localization.MobileString
import com.betterclouddrive.mobile.core.model.NotificationItem
import com.betterclouddrive.mobile.feature.teams.NotificationReadFilter
import com.betterclouddrive.mobile.feature.teams.NotificationTarget
import com.betterclouddrive.mobile.feature.teams.NotificationsState
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

@Composable
fun NotificationsScreen(
    state: NotificationsState,
    onRefresh: () -> Unit,
    onLoadMore: () -> Unit,
    onFilter: (NotificationReadFilter, String?) -> Unit,
    onOpen: (NotificationItem) -> Unit,
    onMarkAllRead: () -> Unit,
    onClearFeedback: () -> Unit,
) {
    val strings = LocalMobileStrings.current
    val snackbar = remember { SnackbarHostState() }
    var typeMenu by remember { mutableStateOf(false) }
    LaunchedEffect(state.feedback) {
        state.feedback?.let { snackbar.showSnackbar(strings.resolve(it).orEmpty()); onClearFeedback() }
    }
    Column(Modifier.fillMaxSize().testTag("notifications-screen")) {
        PrimaryTabRow(state.readFilter.ordinal) {
            NotificationReadFilter.entries.forEach { filter -> Tab(
                selected = state.readFilter == filter,
                onClick = { onFilter(filter, state.typeFilter) },
                text = { Text(strings[if (filter == NotificationReadFilter.ALL) MobileString.NotificationAll else MobileString.NotificationUnread]) },
                modifier = Modifier.testTag("notifications-${filter.name.lowercase()}"),
            ) }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.End) {
            Box {
                IconButton({ typeMenu = true }, Modifier.testTag("notifications-type-filter")) {
                    Icon(Icons.Default.FilterList, strings[MobileString.NotificationAll])
                }
                DropdownMenu(typeMenu, { typeMenu = false }) {
                    notificationTypes.forEach { type -> DropdownMenuItem(
                        text = { Text(type?.let { notificationTypeTitle(it, strings) } ?: strings[MobileString.NotificationAll]) },
                        onClick = { typeMenu = false; onFilter(state.readFilter, type) },
                    ) }
                }
            }
            IconButton(onMarkAllRead, enabled = state.unreadCount > 0 && !state.markingAll, modifier = Modifier.testTag("notifications-read-all")) {
                Icon(Icons.Default.DoneAll, strings[MobileString.NotificationMarkAllRead])
            }
        }
        Box(Modifier.fillMaxSize()) {
            when {
                state.loading && state.items.isEmpty() -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                state.error != null && state.items.isEmpty() -> Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(strings.resolve(state.error).orEmpty(), color = MaterialTheme.colorScheme.error)
                    Button(onRefresh) { Text(strings[MobileString.Retry]) }
                }
                state.items.isEmpty() -> Text(strings[MobileString.NotificationsEmpty], Modifier.align(Alignment.Center))
                else -> LazyColumn(Modifier.fillMaxSize().testTag("notifications-list")) {
                    items(state.items, key = NotificationItem::id) { item ->
                        Column(
                            Modifier.fillMaxWidth().clickable { onOpen(item) }.padding(16.dp).testTag("notification-${item.id}"),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Text(notificationTitle(item, strings), fontWeight = if (item.isRead) FontWeight.Normal else FontWeight.Bold)
                            Text(notificationContent(item, strings), style = MaterialTheme.typography.bodyMedium)
                            Text(item.createdAt ?: "—", style = MaterialTheme.typography.labelSmall)
                        }
                        HorizontalDivider()
                        if (item.id == state.items.lastOrNull()?.id && state.hasMore) LaunchedEffect(item.id) { onLoadMore() }
                    }
                    if (state.loadingMore) item { CircularProgressIndicator(Modifier.padding(16.dp)) }
                    state.loadMoreError?.let { item { OutlinedButton(onLoadMore, Modifier.padding(16.dp)) { Text(strings[MobileString.RetryLoadMore]) } } }
                }
            }
            SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
        }
    }
}

private val notificationTypes = listOf<String?>(
    null, "TEAM_INVITE", "TEAM_INVITE_ACCEPT", "TEAM_INVITE_REJECT", "TEAM_QUOTA_ALERT",
    "TEAM_ARCHIVE", "TEAM_UNARCHIVE", "TEAM_RESTORE", "TEAM_PERMISSION_UPDATE", "TEAM_DELETE",
    "TEAM_MEMBER_ADD", "TEAM_ROLE_UPDATE", "TEAM_MEMBER_REMOVE", "TEAM_OWNER_TRANSFER", "TEAM_OFFICE_SAVE_FAILED",
)

private fun notificationTitle(item: NotificationItem, strings: com.betterclouddrive.mobile.core.localization.MobileStrings): String =
    when (item.titleKey) {
        "notification.teamInvite.title" -> strings[MobileString.NotificationTeamInviteTitle]
        "notification.teamInviteAccept.title" -> strings[MobileString.NotificationInviteAcceptedTitle]
        "notification.teamInviteReject.title" -> strings[MobileString.NotificationInviteDeclinedTitle]
        "notification.teamQuotaAlert.title" -> strings[MobileString.NotificationQuotaAlertTitle]
        "notification.teamArchive.title" -> strings[MobileString.NotificationTeamArchivedTitle]
        "notification.teamUnarchive.title" -> strings[MobileString.NotificationTeamUnarchivedTitle]
        "notification.teamRestore.title" -> strings[MobileString.NotificationTeamRestoredTitle]
        "notification.teamPermissionUpdate.title" -> strings[MobileString.NotificationPermissionChangedTitle]
        "notification.teamDelete.title" -> strings[MobileString.NotificationTeamDeletedTitle]
        "notification.teamMemberAdd.title" -> strings[MobileString.NotificationMemberAddedTitle]
        "notification.teamRoleUpdate.title" -> strings[MobileString.NotificationRoleChangedTitle]
        "notification.teamMemberRemove.title" -> strings[MobileString.NotificationMemberRemovedTitle]
        "notification.teamOwnerTransfer.title" -> strings[MobileString.NotificationOwnerTransferredTitle]
        "notification.teamOfficeSaveFailed.title" -> strings[MobileString.NotificationOfficeSaveFailedTitle]
        else -> strings[MobileString.GenericNotificationTitle]
    }

private fun notificationContent(item: NotificationItem, strings: com.betterclouddrive.mobile.core.localization.MobileStrings): String {
    fun value(key: String) = item.messageParams[key]?.jsonPrimitive?.contentOrNull
    val team = value("teamName") ?: strings[MobileString.Teams]
    val user = value("userName") ?: strings[MobileString.Profile]
    val file = value("fileName") ?: team
    return when (item.contentKey) {
        "notification.teamInvite.content" -> strings.format(MobileString.NotificationTeamInviteContent, value("teamName") ?: strings[MobileString.Teams])
        "notification.teamInviteAccept.content" -> strings.format(MobileString.NotificationInviteAcceptedContent, user, team)
        "notification.teamInviteReject.content" -> strings.format(MobileString.NotificationInviteDeclinedContent, user, team)
        "notification.teamQuotaAlert.content" -> strings.format(MobileString.NotificationQuotaAlertContent, team, value("threshold") ?: "—")
        "notification.teamArchive.content", "notification.teamUnarchive.content", "notification.teamRestore.content",
        "notification.teamDelete.content" -> strings.format(MobileString.NotificationTeamChangedContent, team)
        "notification.teamMemberAdd.content", "notification.teamRoleUpdate.content",
        "notification.teamMemberRemove.content", "notification.teamOwnerTransfer.content" ->
            strings.format(MobileString.NotificationMemberChangedContent, team, user)
        "notification.teamPermissionUpdate.content" -> strings.format(MobileString.NotificationPermissionChangedContent, file)
        "notification.teamOfficeSaveFailed.content" -> strings.format(MobileString.NotificationOfficeSaveFailedContent, file)
        else -> strings[MobileString.GenericNotificationContent]
    }
}

private fun notificationTypeTitle(
    type: String,
    strings: com.betterclouddrive.mobile.core.localization.MobileStrings,
): String = strings[when (type) {
    "TEAM_INVITE" -> MobileString.NotificationTeamInviteTitle
    "TEAM_INVITE_ACCEPT" -> MobileString.NotificationInviteAcceptedTitle
    "TEAM_INVITE_REJECT" -> MobileString.NotificationInviteDeclinedTitle
    "TEAM_QUOTA_ALERT" -> MobileString.NotificationQuotaAlertTitle
    "TEAM_ARCHIVE" -> MobileString.NotificationTeamArchivedTitle
    "TEAM_UNARCHIVE" -> MobileString.NotificationTeamUnarchivedTitle
    "TEAM_RESTORE" -> MobileString.NotificationTeamRestoredTitle
    "TEAM_PERMISSION_UPDATE" -> MobileString.NotificationPermissionChangedTitle
    "TEAM_DELETE" -> MobileString.NotificationTeamDeletedTitle
    "TEAM_MEMBER_ADD" -> MobileString.NotificationMemberAddedTitle
    "TEAM_ROLE_UPDATE" -> MobileString.NotificationRoleChangedTitle
    "TEAM_MEMBER_REMOVE" -> MobileString.NotificationMemberRemovedTitle
    "TEAM_OWNER_TRANSFER" -> MobileString.NotificationOwnerTransferredTitle
    "TEAM_OFFICE_SAVE_FAILED" -> MobileString.NotificationOfficeSaveFailedTitle
    else -> MobileString.GenericNotificationTitle
}]
