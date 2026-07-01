package com.betterclouddrive.android.ui.teams

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import com.betterclouddrive.android.data.repository.TeamRepository
import com.betterclouddrive.android.domain.model.Team
import com.betterclouddrive.android.domain.model.TeamMember
import com.betterclouddrive.android.domain.model.TeamRole
import com.betterclouddrive.android.ui.components.EmptyState
import com.betterclouddrive.android.util.NetworkResult
import com.betterclouddrive.android.util.UiEvent
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class TeamMembersUiState(
    val team: Team? = null,
    val members: List<TeamMember> = emptyList(),
    val isLoading: Boolean = false,
)

@HiltViewModel
class TeamMembersViewModel @Inject constructor(
    private val repository: TeamRepository,
) : ViewModel() {
    private val _uiState = MutableStateFlow(TeamMembersUiState())
    val uiState = _uiState.asStateFlow()

    private val _events = MutableSharedFlow<UiEvent>()
    val events = _events.asSharedFlow()

    fun load(teamId: Long) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true)
            val team = when (val result = repository.getTeam(teamId)) {
                is NetworkResult.Success -> result.data
                is NetworkResult.Error -> {
                    _events.emit(UiEvent.ShowSnackbar("团队加载失败: ${result.message}", true))
                    null
                }
                is NetworkResult.Loading -> null
            }
            when (val result = repository.listMembers(teamId)) {
                is NetworkResult.Success -> _uiState.value = TeamMembersUiState(
                    team = team,
                    members = result.data.sortedWith(compareBy<TeamMember> { roleRank(it.role) }.thenBy { it.username ?: "" }),
                    isLoading = false,
                )
                is NetworkResult.Error -> {
                    _uiState.value = _uiState.value.copy(team = team, isLoading = false)
                    _events.emit(UiEvent.ShowSnackbar("成员加载失败: ${result.message}", true))
                }
                is NetworkResult.Loading -> Unit
            }
        }
    }

    fun addMember(username: String, role: TeamRole) {
        val team = _uiState.value.team ?: return
        if (!team.canManageMembers) return
        viewModelScope.launch {
            when (val result = repository.addMember(team.id, username, role)) {
                is NetworkResult.Success -> {
                    _events.emit(UiEvent.ShowSnackbar("成员已添加"))
                    load(team.id)
                }
                is NetworkResult.Error -> _events.emit(UiEvent.ShowSnackbar("添加失败: ${result.message}", true))
                is NetworkResult.Loading -> Unit
            }
        }
    }

    fun updateRole(member: TeamMember, role: TeamRole) {
        val team = _uiState.value.team ?: return
        if (!team.canManageMembers || member.role == TeamRole.OWNER) return
        viewModelScope.launch {
            when (val result = repository.updateMember(team.id, member.userId, role)) {
                is NetworkResult.Success -> {
                    _events.emit(UiEvent.ShowSnackbar("角色已更新"))
                    load(team.id)
                }
                is NetworkResult.Error -> _events.emit(UiEvent.ShowSnackbar("更新失败: ${result.message}", true))
                is NetworkResult.Loading -> Unit
            }
        }
    }

    fun removeMember(member: TeamMember) {
        val team = _uiState.value.team ?: return
        if (!team.canManageMembers || member.role == TeamRole.OWNER) return
        viewModelScope.launch {
            when (val result = repository.removeMember(team.id, member.userId)) {
                is NetworkResult.Success -> {
                    _events.emit(UiEvent.ShowSnackbar("成员已移除"))
                    load(team.id)
                }
                is NetworkResult.Error -> _events.emit(UiEvent.ShowSnackbar("移除失败: ${result.message}", true))
                is NetworkResult.Loading -> Unit
            }
        }
    }

    private fun roleRank(role: TeamRole): Int = when (role) {
        TeamRole.OWNER -> 0
        TeamRole.ADMIN -> 1
        TeamRole.EDITOR -> 2
        TeamRole.VIEWER -> 3
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TeamMembersScreen(
    teamId: Long,
    onNavigateBack: () -> Unit,
    viewModel: TeamMembersViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()
    val events by viewModel.events.collectAsState(initial = null)
    val snackbarHostState = remember { SnackbarHostState() }
    var username by remember { mutableStateOf("") }
    var addRole by remember { mutableStateOf(TeamRole.VIEWER) }
    var addRoleMenuOpen by remember { mutableStateOf(false) }

    LaunchedEffect(teamId) { viewModel.load(teamId) }
    LaunchedEffect(events) {
        if (events is UiEvent.ShowSnackbar) snackbarHostState.showSnackbar((events as UiEvent.ShowSnackbar).message)
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("团队成员") },
                navigationIcon = { IconButton(onClick = onNavigateBack) { Icon(Icons.Default.ArrowBack, "返回") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp)) {
            if (uiState.team?.canManageMembers == true) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp).testTag("team-members-add"),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedTextField(
                        value = username,
                        onValueChange = { username = it },
                        placeholder = { Text("用户名") },
                        singleLine = true,
                        modifier = Modifier.weight(1f).testTag("team-members-username"),
                    )
                    Column {
                        TextButton(onClick = { addRoleMenuOpen = true }, modifier = Modifier.testTag("team-members-role")) {
                            Text(addRole.name)
                        }
                        DropdownMenu(expanded = addRoleMenuOpen, onDismissRequest = { addRoleMenuOpen = false }) {
                            listOf(TeamRole.VIEWER, TeamRole.EDITOR, TeamRole.ADMIN).forEach { role ->
                                DropdownMenuItem(
                                    text = { Text(role.name) },
                                    onClick = {
                                        addRole = role
                                        addRoleMenuOpen = false
                                    },
                                )
                            }
                        }
                    }
                    Button(
                        onClick = {
                            if (username.isNotBlank()) {
                                viewModel.addMember(username.trim(), addRole)
                                username = ""
                            }
                        },
                        modifier = Modifier.testTag("team-members-add-submit"),
                    ) {
                        Icon(Icons.Default.PersonAdd, null)
                    }
                }
            }

            if (uiState.members.isEmpty()) {
                EmptyState(
                    icon = Icons.Default.Groups,
                    title = "暂无成员",
                    modifier = Modifier.testTag("team-members-empty"),
                )
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize().testTag("team-members-list")) {
                    items(uiState.members, key = { it.userId }) { member ->
                        MemberRow(
                            member = member,
                            canManage = uiState.team?.canManageMembers == true,
                            onUpdateRole = { viewModel.updateRole(member, it) },
                            onRemove = { viewModel.removeMember(member) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MemberRow(
    member: TeamMember,
    canManage: Boolean,
    onUpdateRole: (TeamRole) -> Unit,
    onRemove: () -> Unit,
) {
    var roleMenuOpen by remember { mutableStateOf(false) }
    ListItem(
        headlineContent = {
            Text(
                member.username ?: "用户 ${member.userId}",
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        supportingContent = { Text(member.email ?: "-", maxLines = 1, overflow = TextOverflow.Ellipsis) },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column {
                    TextButton(
                        enabled = canManage && member.role != TeamRole.OWNER,
                        onClick = { roleMenuOpen = true },
                        modifier = Modifier.testTag("team-member-role-${member.userId}"),
                    ) {
                        Text(member.role.name)
                    }
                    DropdownMenu(expanded = roleMenuOpen, onDismissRequest = { roleMenuOpen = false }) {
                        listOf(TeamRole.VIEWER, TeamRole.EDITOR, TeamRole.ADMIN).forEach { role ->
                            DropdownMenuItem(
                                text = { Text(role.name) },
                                onClick = {
                                    roleMenuOpen = false
                                    onUpdateRole(role)
                                },
                            )
                        }
                    }
                }
                if (canManage && member.role != TeamRole.OWNER) {
                    IconButton(
                        onClick = onRemove,
                        modifier = Modifier.testTag("team-member-remove-${member.userId}"),
                    ) {
                        Icon(Icons.Default.Delete, "移除", tint = MaterialTheme.colorScheme.error)
                    }
                }
            }
        },
        modifier = Modifier.testTag("team-member-${member.username ?: member.userId}"),
    )
    Spacer(modifier = Modifier.height(2.dp))
}
