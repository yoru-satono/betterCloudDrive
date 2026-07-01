package com.betterclouddrive.android.ui.teams

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.betterclouddrive.android.data.local.TokenManager
import com.betterclouddrive.android.data.repository.TeamRepository
import com.betterclouddrive.android.domain.model.TeamInvite
import com.betterclouddrive.android.util.NetworkResult
import com.betterclouddrive.android.util.UiEvent
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class TeamInviteUiState(
    val invite: TeamInvite? = null,
    val isLoading: Boolean = false,
    val isSubmitting: Boolean = false,
    val isLoggedIn: Boolean = false,
    val error: String? = null,
)

@HiltViewModel
class TeamInviteViewModel @Inject constructor(
    private val repository: TeamRepository,
    private val tokenManager: TokenManager,
) : ViewModel() {
    private val _uiState = MutableStateFlow(TeamInviteUiState(isLoading = true))
    val uiState = _uiState.asStateFlow()

    private val _events = MutableSharedFlow<UiEvent>()
    val events = _events.asSharedFlow()

    fun load(token: String) {
        viewModelScope.launch {
            val isLoggedIn = tokenManager.getAccessToken() != null
            _uiState.value = _uiState.value.copy(isLoading = true, isLoggedIn = isLoggedIn, error = null)
            when (val result = repository.getInvite(token)) {
                is NetworkResult.Success -> _uiState.value = TeamInviteUiState(invite = result.data, isLoggedIn = isLoggedIn)
                is NetworkResult.Error -> _uiState.value = TeamInviteUiState(error = result.message, isLoggedIn = isLoggedIn)
                is NetworkResult.Loading -> Unit
            }
        }
    }

    fun accept(token: String) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isSubmitting = true, error = null)
            val invite = _uiState.value.invite
            when (val result = repository.acceptInvite(token)) {
                is NetworkResult.Success -> {
                    _uiState.value = _uiState.value.copy(
                        invite = invite?.copy(status = "ACCEPTED"),
                        isSubmitting = false,
                        isLoggedIn = true,
                    )
                    _events.emit(UiEvent.ShowSnackbar("已加入团队"))
                    invite?.teamId?.let { _events.emit(UiEvent.NavigateTo("teams/$it/files")) }
                }
                is NetworkResult.Error -> {
                    _uiState.value = _uiState.value.copy(isSubmitting = false, error = result.message)
                }
                is NetworkResult.Loading -> Unit
            }
        }
    }

    fun reject(token: String) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isSubmitting = true, error = null)
            val invite = _uiState.value.invite
            when (val result = repository.rejectInvite(token)) {
                is NetworkResult.Success -> {
                    _uiState.value = _uiState.value.copy(
                        invite = invite?.copy(status = "REJECTED"),
                        isSubmitting = false,
                        isLoggedIn = true,
                    )
                    _events.emit(UiEvent.ShowSnackbar("已拒绝邀请"))
                }
                is NetworkResult.Error -> {
                    _uiState.value = _uiState.value.copy(isSubmitting = false, error = result.message)
                }
                is NetworkResult.Loading -> Unit
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TeamInviteScreen(
    token: String,
    onNavigateBack: () -> Unit,
    onNavigateLogin: () -> Unit,
    onNavigateTeam: (Long) -> Unit,
    viewModel: TeamInviteViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val events by viewModel.events.collectAsState(initial = null)

    LaunchedEffect(token) {
        viewModel.load(token)
    }

    LaunchedEffect(uiState.error) {
        uiState.error?.let { snackbarHostState.showSnackbar(it) }
    }

    LaunchedEffect(events) {
        when (val event = events) {
            is UiEvent.ShowSnackbar -> snackbarHostState.showSnackbar(event.message)
            is UiEvent.NavigateTo -> uiState.invite?.teamId?.let(onNavigateTeam)
            UiEvent.NavigateBack, null -> Unit
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("团队邀请") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) { Icon(Icons.Default.ArrowBack, "返回") }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(24.dp)
                .testTag("team-invite-screen"),
            contentAlignment = Alignment.Center,
        ) {
            when {
                uiState.isLoading -> CircularProgressIndicator(modifier = Modifier.testTag("team-invite-loading"))
                uiState.invite != null -> TeamInviteCard(
                    invite = uiState.invite!!,
                    isSubmitting = uiState.isSubmitting,
                    isLoggedIn = uiState.isLoggedIn,
                    onAccept = { viewModel.accept(token) },
                    onReject = { viewModel.reject(token) },
                    onNavigateLogin = onNavigateLogin,
                    onNavigateTeam = { onNavigateTeam(uiState.invite!!.teamId) },
                )
                else -> TeamInviteError(message = uiState.error ?: "邀请不存在或已失效", onNavigateLogin = onNavigateLogin)
            }
        }
    }
}

@Composable
private fun TeamInviteCard(
    invite: TeamInvite,
    isSubmitting: Boolean,
    isLoggedIn: Boolean,
    onAccept: () -> Unit,
    onReject: () -> Unit,
    onNavigateLogin: () -> Unit,
    onNavigateTeam: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("team-invite-card"),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(Icons.Default.Groups, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Text(
                text = invite.teamName ?: "团队",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.testTag("team-invite-team-name"),
            )
            Text(
                text = "${invite.invitedByUsername ?: "团队管理员"} 邀请 ${invite.email} 以 ${invite.role.name} 身份加入。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            InviteMeta(label = "状态", value = invite.status.ifBlank { "-" })
            InviteMeta(label = "过期", value = invite.expiresAt ?: "-")
            Spacer(modifier = Modifier.height(8.dp))
            when (invite.status) {
                "PENDING" -> {
                    if (isLoggedIn) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = onAccept,
                                enabled = !isSubmitting,
                                modifier = Modifier.weight(1f).testTag("team-invite-accept"),
                            ) {
                                Text(if (isSubmitting) "处理中" else "接受邀请")
                            }
                            OutlinedButton(
                                onClick = onReject,
                                enabled = !isSubmitting,
                                modifier = Modifier.weight(1f).testTag("team-invite-reject"),
                            ) {
                                Text("拒绝")
                            }
                        }
                    } else {
                        Button(
                            onClick = onNavigateLogin,
                            modifier = Modifier.fillMaxWidth().testTag("team-invite-login-required"),
                        ) {
                            Text("登录后接受")
                        }
                    }
                }
                "ACCEPTED" -> Button(
                    onClick = onNavigateTeam,
                    modifier = Modifier.fillMaxWidth().testTag("team-invite-open-team"),
                ) {
                    Text("进入团队")
                }
                else -> Text(
                    text = "该邀请当前不可操作",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag("team-invite-inactive"),
                )
            }
            TextButton(onClick = onNavigateLogin, modifier = Modifier.testTag("team-invite-login")) {
                Text("切换账号登录")
            }
        }
    }
}

@Composable
private fun InviteMeta(label: String, value: String) {
    Column {
        Text(text = label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(text = value, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("team-invite-meta-$label"))
    }
}

@Composable
private fun TeamInviteError(message: String, onNavigateLogin: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(text = message, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("team-invite-error"))
        Button(onClick = onNavigateLogin, modifier = Modifier.testTag("team-invite-error-login")) {
            Text("登录")
        }
    }
}
