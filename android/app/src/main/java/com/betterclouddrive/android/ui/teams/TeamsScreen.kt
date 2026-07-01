package com.betterclouddrive.android.ui.teams

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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.betterclouddrive.android.data.repository.TeamRepository
import com.betterclouddrive.android.domain.model.Team
import com.betterclouddrive.android.ui.components.EmptyState
import com.betterclouddrive.android.ui.components.InputDialog
import com.betterclouddrive.android.ui.components.StorageBar
import com.betterclouddrive.android.ui.navigation.MainScaffold
import com.betterclouddrive.android.ui.navigation.Screen
import com.betterclouddrive.android.util.FormatUtil
import com.betterclouddrive.android.util.NetworkResult
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class TeamsUiState(
    val teams: List<Team> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null,
)

@HiltViewModel
class TeamsViewModel @Inject constructor(
    private val repository: TeamRepository,
) : ViewModel() {
    private val _uiState = MutableStateFlow(TeamsUiState())
    val uiState = _uiState.asStateFlow()

    fun load() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)
            when (val result = repository.listTeams()) {
                is NetworkResult.Success -> _uiState.value = TeamsUiState(
                    teams = result.data.sortedBy { it.name.lowercase() },
                    isLoading = false,
                )
                is NetworkResult.Error -> _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = result.message,
                )
                is NetworkResult.Loading -> Unit
            }
        }
    }

    fun create(name: String, onCreated: (Team) -> Unit) {
        viewModelScope.launch {
            when (val result = repository.createTeam(name)) {
                is NetworkResult.Success -> {
                    load()
                    onCreated(result.data)
                }
                is NetworkResult.Error -> _uiState.value = _uiState.value.copy(error = result.message)
                is NetworkResult.Loading -> Unit
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TeamsScreen(
    onOpenTeam: (Long) -> Unit,
    onNavigateMain: (String) -> Unit,
    viewModel: TeamsViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()
    var showCreate by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { viewModel.load() }

    MainScaffold(
        currentRoute = Screen.TEAMS,
        onNavigate = onNavigateMain,
        topBar = {
            TopAppBar(
                title = { Text("团队") },
                actions = {
                    IconButton(onClick = viewModel::load, modifier = Modifier.testTag("teams-refresh")) {
                        Icon(Icons.Default.Refresh, "刷新")
                    }
                    IconButton(onClick = { showCreate = true }, modifier = Modifier.testTag("teams-create")) {
                        Icon(Icons.Default.Add, "新建团队")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { padding ->
        when {
            uiState.isLoading -> {
                Box(modifier = Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }
            uiState.teams.isEmpty() -> {
                EmptyState(
                    icon = Icons.Default.Groups,
                    title = "暂无团队",
                    subtitle = "点击右上角 + 创建团队空间",
                    modifier = Modifier.padding(padding),
                )
            }
            else -> {
                LazyColumn(
                    modifier = Modifier.fillMaxSize().padding(padding).testTag("teams-list"),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(uiState.teams, key = { it.id }) { team ->
                        TeamCard(team = team, onClick = { onOpenTeam(team.id) })
                    }
                }
            }
        }
    }

    if (showCreate) {
        InputDialog(
            title = "新建团队",
            placeholder = "团队名称",
            onDismiss = { showCreate = false },
            onConfirm = { name ->
                viewModel.create(name) { team -> onOpenTeam(team.id) }
                showCreate = false
            },
            confirmText = "创建",
        )
    }
}

@Composable
private fun TeamCard(team: Team, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().testTag("team-card-${team.name}").clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Groups, null, tint = MaterialTheme.colorScheme.primary)
                Column(modifier = Modifier.weight(1f).padding(start = 12.dp)) {
                    Text(
                        text = team.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = "${team.role} · ${FormatUtil.formatFileSize(team.storageUsed)} / ${FormatUtil.formatFileSize(team.storageQuota)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(modifier = Modifier.height(10.dp))
            StorageBar(storageUsed = team.storageUsed, storageQuota = team.storageQuota)
        }
    }
}
