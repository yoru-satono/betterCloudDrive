@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.unit.dp
import com.betterclouddrive.mobile.core.localization.LocalMobileStrings
import com.betterclouddrive.mobile.core.localization.MobileString
import com.betterclouddrive.mobile.core.model.TeamRole
import com.betterclouddrive.mobile.core.model.TeamSummary
import com.betterclouddrive.mobile.feature.teams.TeamLifecycleOperation
import com.betterclouddrive.mobile.feature.teams.TeamManagementState

@Composable
fun TeamSettingsScreen(
    state: TeamManagementState,
    wide: Boolean,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onRename: (String) -> Unit,
    onLeave: () -> Unit,
    onDelete: () -> Unit,
    onClearNameError: () -> Unit,
    onClearFeedback: () -> Unit,
    onOpenAudit: () -> Unit,
    onOpenTasks: () -> Unit,
) {
    val strings = LocalMobileStrings.current
    val snackbar = remember { SnackbarHostState() }
    var pendingOperation by remember { mutableStateOf<TeamLifecycleOperation?>(null) }
    LaunchedEffect(state.feedback?.id) {
        state.feedback?.let { feedback ->
            if (!feedback.isError) pendingOperation = null
            snackbar.showSnackbar(strings.resolve(feedback.message).orEmpty())
            onClearFeedback()
        }
    }
    Scaffold(
        modifier = Modifier.testTag("team-settings-screen"),
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text(strings[MobileString.TeamSettings]) },
                navigationIcon = {
                    IconButton(onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, strings[MobileString.BackToParent])
                    }
                },
                actions = {
                    IconButton(onRefresh, enabled = !state.refreshing && !state.loading && !state.busy) {
                        Icon(Icons.Default.Refresh, strings[MobileString.RefreshDirectory])
                    }
                },
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            val team = state.team
            when {
                state.loading && team == null -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                state.loadError != null && team == null -> Column(
                    Modifier.align(Alignment.Center).padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(strings[MobileString.TeamSettingsLoadFailed], color = MaterialTheme.colorScheme.error)
                    Text(strings.resolve(state.loadError).orEmpty())
                    Button(onRefresh) { Text(strings[MobileString.Retry]) }
                }
                team != null -> TeamSettingsContent(
                    team = team,
                    state = state,
                    wide = wide,
                    onRename = onRename,
                    onClearNameError = onClearNameError,
                    onOperation = { pendingOperation = it },
                    onOpenAudit = onOpenAudit,
                    onOpenTasks = onOpenTasks,
                )
            }
        }
    }
    pendingOperation?.let { operation ->
        val team = state.team ?: return@let
        val deleting = operation == TeamLifecycleOperation.DELETE
        AlertDialog(
            onDismissRequest = { if (state.lifecycleOperation == null) pendingOperation = null },
            title = { Text(strings.format(
                if (deleting) MobileString.DeleteTeamTitle else MobileString.LeaveTeamTitle,
                team.name,
            )) },
            text = { Text(strings.format(
                if (deleting) MobileString.DeleteTeamMessage else MobileString.LeaveTeamMessage,
                team.name,
            )) },
            confirmButton = {
                Button(
                    onClick = if (deleting) onDelete else onLeave,
                    enabled = state.lifecycleOperation == null,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    modifier = Modifier.testTag(if (deleting) "team-delete-confirm" else "team-leave-confirm"),
                ) {
                    if (state.lifecycleOperation == operation) {
                        CircularProgressIndicator(Modifier.width(18.dp).height(18.dp), color = MaterialTheme.colorScheme.onError)
                    } else Text(strings[if (deleting) MobileString.DeleteTeam else MobileString.LeaveTeam])
                }
            },
            dismissButton = {
                OutlinedButton({ pendingOperation = null }, enabled = state.lifecycleOperation == null) {
                    Text(strings[MobileString.Cancel])
                }
            },
        )
    }
}

@Composable
private fun TeamSettingsContent(
    team: TeamSummary,
    state: TeamManagementState,
    wide: Boolean,
    onRename: (String) -> Unit,
    onClearNameError: () -> Unit,
    onOperation: (TeamLifecycleOperation) -> Unit,
    onOpenAudit: () -> Unit,
    onOpenTasks: () -> Unit,
) {
    val scroll = rememberScrollState()
    if (wide) {
        Row(
            Modifier.fillMaxSize().verticalScroll(scroll).padding(24.dp).testTag("team-settings-wide"),
            horizontalArrangement = Arrangement.spacedBy(24.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                TeamOverviewCard(team)
                TeamNameCard(team, state, onRename, onClearNameError)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                TeamGovernanceCard(state, onOpenAudit, onOpenTasks)
                TeamDangerCard(state, onOperation)
            }
        }
    } else {
        Column(
            Modifier.fillMaxSize().verticalScroll(scroll).padding(16.dp).testTag("team-settings-phone"),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            TeamOverviewCard(team)
            TeamNameCard(team, state, onRename, onClearNameError)
            TeamDangerCard(state, onOperation)
            TeamGovernanceCard(state, onOpenAudit, onOpenTasks)
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun TeamGovernanceCard(
    state: TeamManagementState,
    onOpenAudit: () -> Unit,
    onOpenTasks: () -> Unit,
) {
    val strings = LocalMobileStrings.current
    Card(Modifier.fillMaxWidth().testTag("team-governance-card")) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(strings[MobileString.TeamGovernance], style = MaterialTheme.typography.titleLarge)
            Text(strings[MobileString.TeamGovernanceHelp])
            OutlinedButton(onOpenTasks, Modifier.fillMaxWidth().testTag("team-tasks-open")) {
                Text(strings[MobileString.TeamTasks])
            }
            if (state.team?.role == TeamRole.OWNER || state.team?.role == TeamRole.ADMIN) {
                OutlinedButton(onOpenAudit, Modifier.fillMaxWidth().testTag("team-audit-open")) {
                    Text(strings[MobileString.TeamAuditLogs])
                }
            }
        }
    }
}

@Composable
private fun TeamOverviewCard(team: TeamSummary) {
    val strings = LocalMobileStrings.current
    Card(Modifier.fillMaxWidth().testTag("team-settings-overview")) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(strings[MobileString.TeamOverview], style = MaterialTheme.typography.titleLarge)
            Text(team.name, style = MaterialTheme.typography.titleMedium)
            Text(strings.format(MobileString.TeamRoleValue, strings[MobileString.Role], strings[team.role.labelKey()]))
            Text(strings.format(
                MobileString.TeamQuota,
                formatFileSize(team.storageUsed),
                formatFileSize(team.storageQuota),
            ))
        }
    }
}

@Composable
private fun TeamNameCard(
    team: TeamSummary,
    state: TeamManagementState,
    onRename: (String) -> Unit,
    onClearNameError: () -> Unit,
) {
    val strings = LocalMobileStrings.current
    var name by remember(team.id, team.name) { mutableStateOf(team.name) }
    val normalized = name.trim()
    val locallyInvalid = normalized.isEmpty() || normalized.length > TEAM_NAME_MAX
    Card(Modifier.fillMaxWidth().testTag("team-name-card")) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(strings[MobileString.TeamNameSettings], style = MaterialTheme.typography.titleLarge)
            OutlinedTextField(
                value = name,
                onValueChange = { name = it; onClearNameError() },
                enabled = state.canRename && !state.busy,
                singleLine = true,
                label = { Text(strings[MobileString.TeamName]) },
                supportingText = {
                    Text(state.nameError?.let { strings.resolve(it).orEmpty() } ?: strings[MobileString.TeamNameHelp])
                },
                isError = locallyInvalid || state.nameError != null,
                modifier = Modifier.fillMaxWidth().testTag("team-name-input"),
            )
            Button(
                onClick = { onRename(name) },
                enabled = state.canRename && !state.busy && !locallyInvalid && normalized != team.name,
                modifier = Modifier.testTag("team-name-save"),
            ) {
                if (state.savingName) CircularProgressIndicator(Modifier.width(18.dp).height(18.dp))
                else Text(strings[MobileString.Save])
            }
        }
    }
}

@Composable
private fun TeamDangerCard(
    state: TeamManagementState,
    onOperation: (TeamLifecycleOperation) -> Unit,
) {
    val strings = LocalMobileStrings.current
    Card(Modifier.fillMaxWidth().testTag("team-danger-card")) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(strings[MobileString.DangerZone], style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.error)
            if (state.canDelete) {
                Text(strings[MobileString.DeleteTeamHelp])
                Button(
                    { onOperation(TeamLifecycleOperation.DELETE) },
                    enabled = !state.busy,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    modifier = Modifier.testTag("team-delete"),
                ) { Text(strings[MobileString.DeleteTeam]) }
            } else {
                Text(strings[MobileString.LeaveTeamHelp])
                Button(
                    { onOperation(TeamLifecycleOperation.LEAVE) },
                    enabled = state.canLeave && !state.busy,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    modifier = Modifier.testTag("team-leave"),
                ) { Text(strings[MobileString.LeaveTeam]) }
            }
        }
    }
}

private fun TeamRole.labelKey(): MobileString = when (this) {
    TeamRole.OWNER -> MobileString.TeamRoleOwner
    TeamRole.ADMIN -> MobileString.TeamRoleAdmin
    TeamRole.EDITOR -> MobileString.TeamRoleEditor
    TeamRole.VIEWER -> MobileString.TeamRoleViewer
}

private const val TEAM_NAME_MAX = 80
