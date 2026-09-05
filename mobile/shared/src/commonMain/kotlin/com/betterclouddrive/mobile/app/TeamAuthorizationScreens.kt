@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.betterclouddrive.mobile.app

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
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
import com.betterclouddrive.mobile.core.model.TeamEffectivePermission
import com.betterclouddrive.mobile.core.model.TeamFilePermission
import com.betterclouddrive.mobile.core.model.TeamMember
import com.betterclouddrive.mobile.core.model.TeamPermissionSubjectType
import com.betterclouddrive.mobile.core.model.TeamRole
import com.betterclouddrive.mobile.core.model.UpdateTeamFilePermissionRequest
import com.betterclouddrive.mobile.feature.teams.TeamMembersState
import com.betterclouddrive.mobile.feature.teams.TeamPermissionsState

@Composable
fun TeamMembersScreen(
    state: TeamMembersState,
    wide: Boolean,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onLoadMore: () -> Unit,
    onAdd: (String, TeamRole) -> Unit,
    onUpdateRole: (TeamMember, TeamRole) -> Unit,
    onRemove: (TeamMember) -> Unit,
    onClearAddError: () -> Unit,
    onClearFeedback: () -> Unit,
) {
    val strings = LocalMobileStrings.current
    val snackbar = remember { SnackbarHostState() }
    var adding by remember { mutableStateOf(false) }
    var pendingRole by remember { mutableStateOf<Pair<TeamMember, TeamRole>?>(null) }
    var pendingRemove by remember { mutableStateOf<TeamMember?>(null) }
    LaunchedEffect(state.feedback) {
        state.feedback?.let {
            adding = false
            pendingRole = null
            pendingRemove = null
            snackbar.showSnackbar(strings.resolve(it).orEmpty())
            onClearFeedback()
        }
    }
    Scaffold(
        modifier = Modifier.testTag("team-members-screen"),
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = { TopAppBar(
            title = { Text(strings[MobileString.TeamMembers]) },
            navigationIcon = { IconButton(onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, strings[MobileString.BackToParent]) } },
            actions = {
                IconButton(onRefresh, enabled = !state.loading && !state.refreshing) { Icon(Icons.Default.Refresh, strings[MobileString.RefreshDirectory]) }
                if (state.canManageMembers) IconButton({ onClearAddError(); adding = true }, Modifier.testTag("team-member-add")) {
                    Icon(Icons.Default.Add, strings[MobileString.AddTeamMember])
                }
            },
        ) },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when {
                state.loading && state.items.isEmpty() -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                state.error != null && state.items.isEmpty() -> Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(strings.resolve(state.error).orEmpty(), color = MaterialTheme.colorScheme.error)
                    Button(onRefresh) { Text(strings[MobileString.Retry]) }
                }
                state.items.isEmpty() -> Text(strings[MobileString.TeamMembersEmpty], Modifier.align(Alignment.Center))
                else -> LazyColumn(Modifier.fillMaxSize().testTag("team-members-list")) {
                    items(state.items, key = TeamMember::userId) { member ->
                        TeamMemberRow(
                            member = member,
                            actorRole = state.actorRole,
                            busy = state.submittingUserId == member.userId,
                            onRole = { pendingRole = member to it },
                            onRemove = { pendingRemove = member },
                        )
                        if (member.userId == state.items.lastOrNull()?.userId && state.hasMore) LaunchedEffect(member.userId) { onLoadMore() }
                    }
                    if (state.loadingMore) item { CircularProgressIndicator(Modifier.padding(16.dp)) }
                    state.loadMoreError?.let { item { OutlinedButton(onLoadMore, Modifier.padding(16.dp)) { Text(strings[MobileString.RetryLoadMore]) } } }
                }
            }
        }
    }
    if (adding) TeamAddMemberOverlay(
        wide, state.actorRole, state.adding, state.addError,
        onDismiss = { if (!state.adding) { adding = false; onClearAddError() } },
        onSubmit = onAdd,
    )
    pendingRole?.let { (member, role) -> AlertDialog(
        onDismissRequest = { if (state.submittingUserId == null) pendingRole = null },
        title = { Text(strings[MobileString.ChangeMemberRoleTitle]) },
        text = { Text(strings.format(MobileString.ChangeMemberRoleMessage, member.username, strings[role.label()])) },
        confirmButton = { Button({ onUpdateRole(member, role); pendingRole = null }) { Text(strings[MobileString.Confirm]) } },
        dismissButton = { OutlinedButton({ pendingRole = null }) { Text(strings[MobileString.Cancel]) } },
    ) }
    pendingRemove?.let { member -> AlertDialog(
        onDismissRequest = { if (state.submittingUserId == null) pendingRemove = null },
        title = { Text(strings[MobileString.RemoveMemberTitle]) },
        text = { Text(strings.format(MobileString.RemoveMemberMessage, member.username)) },
        confirmButton = { Button({ onRemove(member); pendingRemove = null }, Modifier.testTag("team-member-remove-confirm")) {
            Text(strings[MobileString.Delete])
        } },
        dismissButton = { OutlinedButton({ pendingRemove = null }) { Text(strings[MobileString.Cancel]) } },
    ) }
}

@Composable
private fun TeamMemberRow(
    member: TeamMember,
    actorRole: TeamRole,
    busy: Boolean,
    onRole: (TeamRole) -> Unit,
    onRemove: () -> Unit,
) {
    val strings = LocalMobileStrings.current
    var menu by remember { mutableStateOf(false) }
    val manageable = actorRole == TeamRole.OWNER && member.role != TeamRole.OWNER ||
        actorRole == TeamRole.ADMIN && member.role in setOf(TeamRole.EDITOR, TeamRole.VIEWER)
    Row(Modifier.fillMaxWidth().padding(16.dp).testTag("team-member-${member.userId}"), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(member.username, style = MaterialTheme.typography.titleMedium)
            Text(member.email.orEmpty(), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(strings[member.role.label()] + " · " + (member.createdAt ?: "—"), style = MaterialTheme.typography.labelMedium)
        }
        if (busy) CircularProgressIndicator(Modifier.width(24.dp).height(24.dp))
        else if (manageable) Box {
            IconButton(
                onClick = { menu = true },
                modifier = Modifier.testTag("team-member-more-${member.userId}"),
            ) { Icon(Icons.Default.MoreVert, strings[MobileString.MoreActions]) }
            DropdownMenu(menu, { menu = false }) {
                allowedRoles(actorRole).filter { it != member.role }.forEach { role -> DropdownMenuItem(
                    text = { Text(strings[role.label()]) },
                    onClick = { menu = false; onRole(role) },
                    modifier = Modifier.testTag("team-member-role-${member.userId}-${role.name.lowercase()}"),
                ) }
                DropdownMenuItem(
                    text = { Text(strings[MobileString.RemoveMemberTitle]) },
                    leadingIcon = { Icon(Icons.Default.Delete, null) },
                    onClick = { menu = false; onRemove() },
                    modifier = Modifier.testTag("team-member-remove-${member.userId}"),
                )
            }
        }
    }
    HorizontalDivider()
}

@Composable
private fun TeamAddMemberOverlay(
    wide: Boolean,
    actorRole: TeamRole,
    busy: Boolean,
    error: com.betterclouddrive.mobile.core.localization.UiMessage?,
    onDismiss: () -> Unit,
    onSubmit: (String, TeamRole) -> Unit,
) {
    val strings = LocalMobileStrings.current
    var username by remember { mutableStateOf("") }
    var role by remember { mutableStateOf(TeamRole.VIEWER) }
    var roleMenu by remember { mutableStateOf(false) }
    val content: @Composable () -> Unit = {
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(strings[MobileString.AddTeamMember], style = MaterialTheme.typography.titleLarge)
            OutlinedTextField(
                username, { username = it }, enabled = !busy, singleLine = true,
                label = { Text(strings[MobileString.MemberUsername]) },
                isError = error != null,
                supportingText = { error?.let { Text(strings.resolve(it).orEmpty()) } },
                modifier = Modifier.fillMaxWidth().testTag("team-member-username"),
            )
            Box {
                OutlinedButton({ roleMenu = true }, enabled = !busy) { Text(strings[role.label()]) }
                DropdownMenu(roleMenu, { roleMenu = false }) {
                    allowedRoles(actorRole).forEach { option -> DropdownMenuItem(
                        text = { Text(strings[option.label()]) },
                        onClick = { role = option; roleMenu = false },
                    ) }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                OutlinedButton(onDismiss, enabled = !busy) { Text(strings[MobileString.Cancel]) }
                Spacer(Modifier.width(8.dp))
                Button({ onSubmit(username, role) }, enabled = !busy && username.trim().isNotEmpty(), modifier = Modifier.testTag("team-member-add-confirm")) {
                    Text(strings[MobileString.AddTeamMember])
                }
            }
        }
    }
    if (wide) AlertDialog(onDismissRequest = onDismiss, confirmButton = {}, text = content)
    else ModalBottomSheet(onDismissRequest = onDismiss) { content() }
}

@Composable
fun TeamPermissionsOverlay(
    state: TeamPermissionsState,
    wide: Boolean,
    actorUserId: Long?,
    actorRole: TeamRole,
    onDismiss: () -> Unit,
    onRetry: () -> Unit,
    onSave: (UpdateTeamFilePermissionRequest) -> Unit,
    onDelete: (TeamFilePermission) -> Unit,
    onClearFeedback: () -> Unit,
) {
    val target = state.target ?: return
    val strings = LocalMobileStrings.current
    var editing by remember(target.id) { mutableStateOf<TeamFilePermission?>(null) }
    var adding by remember(target.id) { mutableStateOf(false) }
    LaunchedEffect(state.feedback) {
        if (state.feedback != null) { adding = false; editing = null }
    }
    val content: @Composable () -> Unit = {
        Column(
            Modifier.fillMaxWidth().padding(20.dp).verticalScroll(rememberScrollState()).testTag("team-permissions-sheet"),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(strings[MobileString.ManagePermissions], style = MaterialTheme.typography.titleLarge)
            Text(target.fileName, style = MaterialTheme.typography.titleMedium)
            when {
                state.loading -> CircularProgressIndicator()
                state.error != null && state.effective == null -> {
                    Text(strings.resolve(state.error).orEmpty(), color = MaterialTheme.colorScheme.error)
                    Button(onRetry) { Text(strings[MobileString.Retry]) }
                }
                else -> {
                    state.effective?.let { permission ->
                        Text(strings[MobileString.EffectivePermission], style = MaterialTheme.typography.titleMedium)
                        Text(permission.summary(strings))
                        Text(strings[MobileString.PermissionSourceChain], style = MaterialTheme.typography.titleMedium)
                        permission.sourceChain.forEach { source ->
                            val text = if (source.type == "BASE_ROLE") strings.format(MobileString.PermissionBaseRole, source.subjectRole ?: "—")
                            else if (source.inherited) strings.format(MobileString.PermissionInherited, source.fileName ?: "—")
                            else strings[MobileString.PermissionCurrentOverride]
                            Text(strings.format(MobileString.PermissionSourceDetail, text, source.bits(strings)))
                        }
                    }
                    if (state.effective?.canManage == true) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text(strings[MobileString.DirectPermissions], Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                            Button({ onClearFeedback(); adding = true }, Modifier.testTag("team-permission-add")) { Icon(Icons.Default.Add, null); Text(strings[MobileString.AddPermission]) }
                        }
                        if (state.direct.isEmpty()) Text("—")
                    state.direct.forEach { permission -> Card(
                        Modifier.fillMaxWidth().testTag("team-permission-${permission.id}"),
                    ) {
                            Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(permission.subjectName(state.members, strings))
                                    Text(permission.asEffective().summary(strings), style = MaterialTheme.typography.labelMedium)
                                }
                                IconButton(
                                    onClick = { onClearFeedback(); editing = permission },
                                    modifier = Modifier.testTag("team-permission-edit-${permission.id}"),
                                ) { Icon(Icons.Default.Edit, strings[MobileString.EditPermission]) }
                                IconButton(
                                    onClick = { onDelete(permission) },
                                    enabled = !state.submitting,
                                    modifier = Modifier.testTag("team-permission-delete-${permission.id}"),
                                ) { Icon(Icons.Default.Delete, strings[MobileString.Delete]) }
                            }
                        } }
                    }
                    state.error?.let { Text(strings.resolve(it).orEmpty(), color = MaterialTheme.colorScheme.error) }
                    state.feedback?.let { Text(strings.resolve(it).orEmpty(), color = MaterialTheme.colorScheme.primary) }
                }
            }
            OutlinedButton(onDismiss, enabled = !state.submitting, modifier = Modifier.align(Alignment.End)) { Text(strings[MobileString.Close]) }
        }
    }
    if (wide) AlertDialog(onDismissRequest = onDismiss, confirmButton = {}, text = content)
    else ModalBottomSheet(onDismissRequest = onDismiss) { content() }
    if (adding || editing != null) PermissionEditorDialog(
        existing = editing,
        members = state.members,
        actorUserId = actorUserId,
        actorRole = actorRole,
        busy = state.submitting,
        onDismiss = { if (!state.submitting) { adding = false; editing = null } },
        onSave = onSave,
    )
}

@Composable
private fun PermissionEditorDialog(
    existing: TeamFilePermission?,
    members: List<TeamMember>,
    actorUserId: Long?,
    actorRole: TeamRole,
    busy: Boolean,
    onDismiss: () -> Unit,
    onSave: (UpdateTeamFilePermissionRequest) -> Unit,
) {
    val strings = LocalMobileStrings.current
    var subjectType by remember(existing?.id) { mutableStateOf(existing?.subjectType ?: TeamPermissionSubjectType.USER) }
    var userId by remember(existing?.id) { mutableStateOf(existing?.subjectUserId ?: members.firstOrNull()?.userId) }
    var role by remember(existing?.id) { mutableStateOf(existing?.subjectRole ?: TeamRole.VIEWER) }
    var canRead by remember(existing?.id) { mutableStateOf(existing?.canRead ?: true) }
    var canWrite by remember(existing?.id) { mutableStateOf(existing?.canWrite ?: false) }
    var canShare by remember(existing?.id) { mutableStateOf(existing?.canShare ?: false) }
    var canDelete by remember(existing?.id) { mutableStateOf(existing?.canDelete ?: false) }
    var canManage by remember(existing?.id) { mutableStateOf(existing?.canManage ?: false) }
    var subjectMenu by remember { mutableStateOf(false) }
    var targetMenu by remember { mutableStateOf(false) }
    val selfLock = !canManage && ((subjectType == TeamPermissionSubjectType.USER && userId == actorUserId) ||
        (subjectType == TeamPermissionSubjectType.ROLE && role == actorRole))
    fun toggleAdvanced(value: Boolean, apply: (Boolean) -> Unit) { apply(value); if (value) canRead = true }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(strings[if (existing == null) MobileString.AddPermission else MobileString.EditPermission]) },
        text = { Column(Modifier.fillMaxWidth().heightIn(max = 520.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Box {
                OutlinedButton(
                    onClick = { subjectMenu = true },
                    enabled = existing == null && !busy,
                    modifier = Modifier.testTag("team-permission-subject-type"),
                ) {
                    Text(strings[if (subjectType == TeamPermissionSubjectType.USER) MobileString.PermissionSubjectUser else MobileString.PermissionSubjectRole])
                }
                DropdownMenu(subjectMenu, { subjectMenu = false }) {
                    TeamPermissionSubjectType.entries.forEach { type -> DropdownMenuItem(
                        text = { Text(strings[if (type == TeamPermissionSubjectType.USER) MobileString.PermissionSubjectUser else MobileString.PermissionSubjectRole]) },
                        onClick = { subjectType = type; subjectMenu = false },
                    ) }
                }
            }
            Box {
                val label = if (subjectType == TeamPermissionSubjectType.USER)
                    members.firstOrNull { it.userId == userId }?.username ?: strings[MobileString.SelectMember]
                else strings[role.label()]
                OutlinedButton(
                    onClick = { targetMenu = true },
                    enabled = existing == null && !busy,
                    modifier = Modifier.testTag("team-permission-subject-target"),
                ) { Text(label) }
                DropdownMenu(targetMenu, { targetMenu = false }) {
                    if (subjectType == TeamPermissionSubjectType.USER) members.forEach { member -> DropdownMenuItem(
                        text = { Text(member.username) }, onClick = { userId = member.userId; targetMenu = false },
                    ) } else listOf(TeamRole.ADMIN, TeamRole.EDITOR, TeamRole.VIEWER).forEach { option -> DropdownMenuItem(
                        text = { Text(strings[option.label()]) }, onClick = { role = option; targetMenu = false },
                    ) }
                }
            }
            PermissionCheck(MobileString.PermissionRead, "read", canRead) { value ->
                canRead = value
                if (!value) { canWrite = false; canShare = false; canDelete = false; canManage = false }
            }
            PermissionCheck(MobileString.PermissionWrite, "write", canWrite) { toggleAdvanced(it) { value -> canWrite = value } }
            PermissionCheck(MobileString.PermissionShare, "share", canShare) { toggleAdvanced(it) { value -> canShare = value } }
            PermissionCheck(MobileString.PermissionDelete, "delete", canDelete) { toggleAdvanced(it) { value -> canDelete = value } }
            PermissionCheck(MobileString.PermissionManage, "manage", canManage) { toggleAdvanced(it) { value -> canManage = value } }
            if (selfLock) Text(strings[MobileString.PermissionSelfLock], color = MaterialTheme.colorScheme.error)
        } },
        confirmButton = { Button(
            onClick = { onSave(UpdateTeamFilePermissionRequest(
                subjectType = subjectType,
                subjectUserId = userId.takeIf { subjectType == TeamPermissionSubjectType.USER },
                subjectRole = role.takeIf { subjectType == TeamPermissionSubjectType.ROLE },
                canRead = canRead, canWrite = canWrite, canShare = canShare, canDelete = canDelete, canManage = canManage,
            )) },
            enabled = !busy && !selfLock && (subjectType == TeamPermissionSubjectType.ROLE || userId != null),
            modifier = Modifier.testTag("team-permission-save"),
        ) { Text(strings[MobileString.Confirm]) } },
        dismissButton = { OutlinedButton(onDismiss, enabled = !busy) { Text(strings[MobileString.Cancel]) } },
    )
}

@Composable
private fun PermissionCheck(label: MobileString, tag: String, checked: Boolean, onChecked: (Boolean) -> Unit) {
    val strings = LocalMobileStrings.current
    Row(
        Modifier.fillMaxWidth().clickable { onChecked(!checked) }.testTag("team-permission-$tag"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked, onChecked); Text(strings[label])
    }
}

private fun allowedRoles(actorRole: TeamRole): List<TeamRole> =
    if (actorRole == TeamRole.OWNER) listOf(TeamRole.ADMIN, TeamRole.EDITOR, TeamRole.VIEWER)
    else listOf(TeamRole.EDITOR, TeamRole.VIEWER)

private fun TeamRole.label(): MobileString = when (this) {
    TeamRole.OWNER -> MobileString.TeamRoleOwner
    TeamRole.ADMIN -> MobileString.TeamRoleAdmin
    TeamRole.EDITOR -> MobileString.TeamRoleEditor
    TeamRole.VIEWER -> MobileString.TeamRoleViewer
}

private fun TeamEffectivePermission.summary(strings: com.betterclouddrive.mobile.core.localization.MobileStrings): String = listOfNotNull(
    strings[MobileString.PermissionRead].takeIf { canRead },
    strings[MobileString.PermissionWrite].takeIf { canWrite },
    strings[MobileString.PermissionShare].takeIf { canShare },
    strings[MobileString.PermissionDelete].takeIf { canDelete },
    strings[MobileString.PermissionManage].takeIf { canManage },
).ifEmpty { listOf("—") }.joinToString(" · ")

private fun com.betterclouddrive.mobile.core.model.TeamPermissionSource.bits(
    strings: com.betterclouddrive.mobile.core.localization.MobileStrings,
) = TeamEffectivePermission(canRead, canWrite, canShare, canDelete, canManage).summary(strings)

private fun TeamFilePermission.asEffective() = TeamEffectivePermission(canRead, canWrite, canShare, canDelete, canManage)

private fun TeamFilePermission.subjectName(
    members: List<TeamMember>,
    strings: com.betterclouddrive.mobile.core.localization.MobileStrings,
): String = if (subjectType == TeamPermissionSubjectType.USER) {
    members.firstOrNull { it.userId == subjectUserId }?.username ?: "#${subjectUserId ?: 0}"
} else strings[subjectRole?.label() ?: MobileString.SelectRole]
