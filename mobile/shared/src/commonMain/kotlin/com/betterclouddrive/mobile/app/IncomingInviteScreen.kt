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
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.backhandler.BackHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.betterclouddrive.mobile.core.localization.LocalMobileStrings
import com.betterclouddrive.mobile.core.localization.MobileString
import com.betterclouddrive.mobile.core.model.TeamInviteStatus
import com.betterclouddrive.mobile.core.model.TeamRole
import com.betterclouddrive.mobile.feature.teams.IncomingInviteState

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
fun IncomingInviteScreen(
    state: IncomingInviteState,
    accountEmail: String?,
    loggedIn: Boolean,
    onRetry: () -> Unit,
    onLogin: () -> Unit,
    onRegister: () -> Unit,
    onAccept: () -> Unit,
    onReject: () -> Unit,
    onSwitchAccount: () -> Unit,
    onClose: () -> Unit,
    onContinue: () -> Unit,
) {
    val strings = LocalMobileStrings.current
    BackHandler(onBack = onClose)
    Box(Modifier.fillMaxSize().padding(24.dp).testTag("incoming-invite-screen"), contentAlignment = Alignment.Center) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(strings[MobileString.TeamInvitation], style = MaterialTheme.typography.headlineMedium)
            when {
                state.loading -> CircularProgressIndicator(Modifier.align(Alignment.CenterHorizontally))
                state.error != null && state.invite == null -> {
                    Text(strings.resolve(state.error) ?: strings[MobileString.InviteCheckServer], color = MaterialTheme.colorScheme.error)
                    Button(onRetry, Modifier.fillMaxWidth().testTag("incoming-invite-retry")) { Text(strings[MobileString.Retry]) }
                }
                state.invite != null -> {
                    val invite = state.invite
                    Text(invite.teamName.orEmpty().ifBlank { strings[MobileString.Teams] }, style = MaterialTheme.typography.titleLarge)
                    Text(strings.format(
                        MobileString.InvitedBy,
                        invite.invitedByUsername ?: "—",
                        invite.email,
                        strings[invite.role.label()],
                    ))
                    Text(strings.format(MobileString.InviteExpires, invite.expiresAt ?: "—"))
                    state.error?.let { Text(strings.resolve(it).orEmpty(), color = MaterialTheme.colorScheme.error) }
                    when {
                        state.acceptedTeamId != null -> {
                            Text(strings[MobileString.InviteAccepted], color = MaterialTheme.colorScheme.primary)
                            Button(onContinue, Modifier.fillMaxWidth().testTag("incoming-invite-continue")) { Text(strings[MobileString.Confirm]) }
                        }
                        state.rejected -> {
                            Text(strings[MobileString.InviteRejected], color = MaterialTheme.colorScheme.primary)
                            Button(onClose, Modifier.fillMaxWidth()) { Text(strings[MobileString.Close]) }
                        }
                        invite.status != TeamInviteStatus.PENDING -> {
                            Text(strings[invite.status.label()])
                            Button(onClose, Modifier.fillMaxWidth()) { Text(strings[MobileString.Close]) }
                        }
                        !loggedIn -> {
                            Button(onLogin, Modifier.fillMaxWidth().testTag("incoming-invite-login")) { Text(strings[MobileString.Login]) }
                            OutlinedButton(onRegister, Modifier.fillMaxWidth().testTag("incoming-invite-register")) { Text(strings[MobileString.Register]) }
                        }
                        !accountEmail.equals(invite.email, ignoreCase = true) -> {
                            Text(strings[MobileString.InviteEmailMismatch], color = MaterialTheme.colorScheme.error)
                            Button(onSwitchAccount, Modifier.fillMaxWidth().testTag("incoming-invite-switch-account")) {
                                Text(strings[MobileString.InviteSwitchAccount])
                            }
                        }
                        else -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onAccept, Modifier.weight(1f).testTag("incoming-invite-accept"), enabled = !state.submitting) {
                                Text(strings[MobileString.AcceptInvite])
                            }
                            OutlinedButton(onReject, Modifier.weight(1f).testTag("incoming-invite-reject"), enabled = !state.submitting) {
                                Text(strings[MobileString.RejectInvite])
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(4.dp))
            OutlinedButton(onClose, Modifier.fillMaxWidth()) { Text(strings[MobileString.Close]) }
        }
    }
}

private fun TeamRole.label(): MobileString = when (this) {
    TeamRole.OWNER -> MobileString.TeamRoleOwner
    TeamRole.ADMIN -> MobileString.TeamRoleAdmin
    TeamRole.EDITOR -> MobileString.TeamRoleEditor
    TeamRole.VIEWER -> MobileString.TeamRoleViewer
}

private fun TeamInviteStatus.label(): MobileString = when (this) {
    TeamInviteStatus.PENDING -> MobileString.InviteStatusPending
    TeamInviteStatus.ACCEPTED -> MobileString.InviteStatusAccepted
    TeamInviteStatus.REJECTED -> MobileString.InviteStatusRejected
    TeamInviteStatus.CANCELED -> MobileString.InviteStatusCanceled
    TeamInviteStatus.EXPIRED -> MobileString.InviteStatusExpired
}
