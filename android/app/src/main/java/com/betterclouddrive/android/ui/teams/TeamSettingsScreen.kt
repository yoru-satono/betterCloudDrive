package com.betterclouddrive.android.ui.teams

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.betterclouddrive.android.data.repository.TeamRepository
import com.betterclouddrive.android.domain.model.FileItem
import com.betterclouddrive.android.domain.model.ShareLink
import com.betterclouddrive.android.domain.model.Tag
import com.betterclouddrive.android.domain.model.Team
import com.betterclouddrive.android.domain.model.TeamInvite
import com.betterclouddrive.android.domain.model.TeamMember
import com.betterclouddrive.android.domain.model.TeamOperationLog
import com.betterclouddrive.android.domain.model.TeamRole
import com.betterclouddrive.android.ui.components.BCDDialog
import com.betterclouddrive.android.ui.components.EmptyState
import com.betterclouddrive.android.ui.components.StorageBar
import com.betterclouddrive.android.util.FormatUtil
import com.betterclouddrive.android.util.NetworkResult
import com.betterclouddrive.android.util.UiEvent
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

data class TeamSettingsUiState(
    val team: Team? = null,
    val members: List<TeamMember> = emptyList(),
    val invites: List<TeamInvite> = emptyList(),
    val shares: List<ShareLink> = emptyList(),
    val sharePasswords: Map<Long, String> = emptyMap(),
    val loadingPasswordShareId: Long? = null,
    val tags: List<Tag> = emptyList(),
    val favorites: List<FileItem> = emptyList(),
    val selectedTagName: String? = null,
    val tagFiles: List<FileItem> = emptyList(),
    val tagFilesLoading: Boolean = false,
    val auditLogs: List<TeamOperationLog> = emptyList(),
    val auditPage: Int = 1,
    val auditTotal: Long = 0,
    val auditLoading: Boolean = false,
    val auditLoadingMore: Boolean = false,
    val auditError: String? = null,
    val auditActorUserId: String = "",
    val auditAction: String = "",
    val auditTargetType: String = "",
    val auditResult: String = "",
    val auditStartAt: String = "",
    val auditEndAt: String = "",
    val auditExporting: Boolean = false,
    val isLoading: Boolean = false,
)

private const val TEAM_AUDIT_PAGE_SIZE = 20

private data class TeamAuditFilterParams(
    val actorUserId: Long? = null,
    val action: String? = null,
    val targetType: String? = null,
    val result: Int? = null,
    val startAt: String? = null,
    val endAt: String? = null,
)

@HiltViewModel
class TeamSettingsViewModel @Inject constructor(
    private val repository: TeamRepository,
    @ApplicationContext private val context: Context,
) : ViewModel() {
    private val _uiState = MutableStateFlow(TeamSettingsUiState())
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
            val members = when (val result = repository.listMembers(teamId)) {
                is NetworkResult.Success -> result.data.sortedWith(compareBy<TeamMember> { roleRank(it.role) }.thenBy { it.username ?: "" })
                is NetworkResult.Error -> {
                    _events.emit(UiEvent.ShowSnackbar("成员加载失败: ${result.message}", true))
                    emptyList()
                }
                is NetworkResult.Loading -> emptyList()
            }
            val shares = when (val result = repository.listShares(teamId)) {
                is NetworkResult.Success -> result.data.records
                is NetworkResult.Error -> {
                    _events.emit(UiEvent.ShowSnackbar("分享加载失败: ${result.message}", true))
                    emptyList()
                }
                is NetworkResult.Loading -> emptyList()
            }
            val tags = when (val result = repository.listTags(teamId)) {
                is NetworkResult.Success -> result.data.sortedBy { it.tagName.lowercase() }
                is NetworkResult.Error -> {
                    _events.emit(UiEvent.ShowSnackbar("团队标签加载失败: ${result.message}", true))
                    emptyList()
                }
                is NetworkResult.Loading -> emptyList()
            }
            val favorites = when (val result = repository.listFavorites(teamId, size = 100)) {
                is NetworkResult.Success -> result.data.records
                is NetworkResult.Error -> {
                    _events.emit(UiEvent.ShowSnackbar("团队收藏加载失败: ${result.message}", true))
                    emptyList()
                }
                is NetworkResult.Loading -> emptyList()
            }
            val invites = if (team?.canManageMembers == true) {
                when (val result = repository.listInvites(teamId)) {
                    is NetworkResult.Success -> result.data
                    is NetworkResult.Error -> {
                        _events.emit(UiEvent.ShowSnackbar("邀请加载失败: ${result.message}", true))
                        emptyList()
                    }
                    is NetworkResult.Loading -> emptyList()
                }
            } else {
                emptyList()
            }
            var auditTotal = 0L
            var auditError: String? = null
            val auditLogs = if (team?.canManageMembers == true) {
                when (val result = repository.listAuditLogs(teamId, size = TEAM_AUDIT_PAGE_SIZE)) {
                    is NetworkResult.Success -> {
                        auditTotal = result.data.total
                        result.data.records
                    }
                    is NetworkResult.Error -> {
                        auditError = result.message
                        _events.emit(UiEvent.ShowSnackbar("审计日志加载失败: ${result.message}", true))
                        emptyList()
                    }
                    is NetworkResult.Loading -> emptyList()
                }
            } else {
                emptyList()
            }
            _uiState.value = TeamSettingsUiState(
                team = team,
                members = members,
                invites = invites,
                shares = shares,
                tags = tags,
                favorites = favorites,
                auditLogs = auditLogs,
                auditPage = 1,
                auditTotal = auditTotal,
                auditError = auditError,
                isLoading = false,
            )
        }
    }

    fun retryAuditLogs() {
        val team = _uiState.value.team ?: return
        if (!team.canManageMembers || _uiState.value.auditLoading) return
        val filters = parseAuditFiltersOrEmit() ?: return
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(auditLoading = true, auditError = null)
            loadAuditPage(team.id, filters, page = 1, append = false)
        }
    }

    fun loadMoreAuditLogs() {
        val state = _uiState.value
        val team = state.team ?: return
        if (!team.canManageMembers || state.auditLoading || state.auditLoadingMore) return
        if (state.auditLogs.size.toLong() >= state.auditTotal) return
        val filters = parseAuditFiltersOrEmit() ?: return
        val nextPage = state.auditPage + 1
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(auditLoadingMore = true, auditError = null)
            loadAuditPage(team.id, filters, page = nextPage, append = true)
        }
    }

    fun updateAuditFilters(
        actorUserId: String? = null,
        action: String? = null,
        targetType: String? = null,
        result: String? = null,
        startAt: String? = null,
        endAt: String? = null,
    ) {
        val state = _uiState.value
        _uiState.value = state.copy(
            auditActorUserId = actorUserId ?: state.auditActorUserId,
            auditAction = action ?: state.auditAction,
            auditTargetType = targetType ?: state.auditTargetType,
            auditResult = result ?: state.auditResult,
            auditStartAt = startAt ?: state.auditStartAt,
            auditEndAt = endAt ?: state.auditEndAt,
        )
    }

    fun applyAuditFilters() {
        val team = _uiState.value.team ?: return
        if (!team.canManageMembers || _uiState.value.auditLoading) return
        val filters = parseAuditFiltersOrEmit() ?: return
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                auditLogs = emptyList(),
                auditPage = 1,
                auditTotal = 0,
                auditLoading = true,
                auditLoadingMore = false,
                auditError = null,
            )
            loadAuditPage(team.id, filters, page = 1, append = false)
        }
    }

    fun resetAuditFilters() {
        val team = _uiState.value.team ?: return
        if (!team.canManageMembers || _uiState.value.auditLoading) return
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                auditActorUserId = "",
                auditAction = "",
                auditTargetType = "",
                auditResult = "",
                auditStartAt = "",
                auditEndAt = "",
                auditLogs = emptyList(),
                auditPage = 1,
                auditTotal = 0,
                auditLoading = true,
                auditLoadingMore = false,
                auditError = null,
            )
            loadAuditPage(team.id, TeamAuditFilterParams(), page = 1, append = false)
        }
    }

    fun exportAuditLogs(targetUri: Uri) {
        val team = _uiState.value.team ?: return
        if (!team.canManageMembers || _uiState.value.auditExporting) return
        val filters = parseAuditFiltersOrEmit() ?: return
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(auditExporting = true)
            try {
                withContext(Dispatchers.IO) {
                    val body = repository.exportAuditLogs(
                        teamId = team.id,
                        actorUserId = filters.actorUserId,
                        action = filters.action,
                        targetType = filters.targetType,
                        result = filters.result,
                        startAt = filters.startAt,
                        endAt = filters.endAt,
                    )
                    context.contentResolver.openOutputStream(targetUri, "wt")?.use { output ->
                        body.byteStream().use { input -> input.copyTo(output) }
                    } ?: throw IllegalStateException("无法写入目标位置")
                }
                _events.emit(UiEvent.ShowSnackbar("审计 CSV 已导出"))
            } catch (e: Exception) {
                _events.emit(UiEvent.ShowSnackbar("导出失败: ${e.message}", true))
            } finally {
                _uiState.value = _uiState.value.copy(auditExporting = false)
            }
        }
    }

    private fun parseAuditFiltersOrEmit(): TeamAuditFilterParams? {
        val state = _uiState.value
        val actorText = state.auditActorUserId.trim()
        val actorUserId = if (actorText.isBlank()) {
            null
        } else {
            actorText.toLongOrNull() ?: return auditFilterError("用户 ID 必须是数字")
        }
        val resultText = state.auditResult.trim()
        val result = when (resultText) {
            "" -> null
            "1", "成功" -> 1
            "0", "失败" -> 0
            else -> return auditFilterError("结果请输入 1/0、成功/失败或留空")
        }
        return TeamAuditFilterParams(
            actorUserId = actorUserId,
            action = state.auditAction.trim().takeIf { it.isNotEmpty() },
            targetType = state.auditTargetType.trim().takeIf { it.isNotEmpty() },
            result = result,
            startAt = state.auditStartAt.trim().takeIf { it.isNotEmpty() },
            endAt = state.auditEndAt.trim().takeIf { it.isNotEmpty() },
        )
    }

    private fun auditFilterError(message: String): TeamAuditFilterParams? {
        viewModelScope.launch {
            _events.emit(UiEvent.ShowSnackbar(message, true))
        }
        return null
    }

    private suspend fun loadAuditPage(
        teamId: Long,
        filters: TeamAuditFilterParams,
        page: Int,
        append: Boolean,
    ) {
        when (val result = repository.listAuditLogs(
            teamId = teamId,
            actorUserId = filters.actorUserId,
            action = filters.action,
            targetType = filters.targetType,
            result = filters.result,
            startAt = filters.startAt,
            endAt = filters.endAt,
            page = page,
            size = TEAM_AUDIT_PAGE_SIZE,
        )) {
            is NetworkResult.Success -> _uiState.value = _uiState.value.copy(
                auditLogs = if (append) _uiState.value.auditLogs + result.data.records else result.data.records,
                auditPage = result.data.page,
                auditTotal = result.data.total,
                auditLoading = false,
                auditLoadingMore = false,
                auditError = null,
            )
            is NetworkResult.Error -> {
                _uiState.value = _uiState.value.copy(
                    auditLoading = false,
                    auditLoadingMore = false,
                    auditError = result.message,
                )
                _events.emit(UiEvent.ShowSnackbar("审计日志加载失败: ${result.message}", true))
            }
            is NetworkResult.Loading -> Unit
        }
    }

    fun saveTeamName(name: String) {
        val team = _uiState.value.team ?: return
        if (!team.canManageMembers || name.isBlank()) return
        viewModelScope.launch {
            when (val result = repository.updateTeam(team.id, name.trim())) {
                is NetworkResult.Success -> {
                    _events.emit(UiEvent.ShowSnackbar("团队名称已更新"))
                    load(team.id)
                }
                is NetworkResult.Error -> _events.emit(UiEvent.ShowSnackbar("更新失败: ${result.message}", true))
                is NetworkResult.Loading -> Unit
            }
        }
    }

    fun addMember(username: String, role: TeamRole) {
        val team = _uiState.value.team ?: return
        if (!team.canManageMembers || username.isBlank()) return
        viewModelScope.launch {
            when (val result = repository.addMember(team.id, username.trim(), role)) {
                is NetworkResult.Success -> {
                    _events.emit(UiEvent.ShowSnackbar("成员已添加"))
                    load(team.id)
                }
                is NetworkResult.Error -> _events.emit(UiEvent.ShowSnackbar("添加失败: ${result.message}", true))
                is NetworkResult.Loading -> Unit
            }
        }
    }

    fun createInvite(email: String, role: TeamRole) {
        val team = _uiState.value.team ?: return
        if (!team.canManageMembers || email.isBlank()) return
        viewModelScope.launch {
            when (val result = repository.createInvite(team.id, email.trim(), role)) {
                is NetworkResult.Success -> {
                    _events.emit(UiEvent.ShowSnackbar("邀请已发送"))
                    load(team.id)
                }
                is NetworkResult.Error -> _events.emit(UiEvent.ShowSnackbar("邀请失败: ${result.message}", true))
                is NetworkResult.Loading -> Unit
            }
        }
    }

    fun resendInvite(invite: TeamInvite) {
        val team = _uiState.value.team ?: return
        if (!team.canManageMembers) return
        viewModelScope.launch {
            when (val result = repository.resendInvite(team.id, invite.id)) {
                is NetworkResult.Success -> {
                    _events.emit(UiEvent.ShowSnackbar("邀请已重发"))
                    load(team.id)
                }
                is NetworkResult.Error -> _events.emit(UiEvent.ShowSnackbar("重发失败: ${result.message}", true))
                is NetworkResult.Loading -> Unit
            }
        }
    }

    fun cancelInvite(invite: TeamInvite) {
        val team = _uiState.value.team ?: return
        if (!team.canManageMembers) return
        viewModelScope.launch {
            when (val result = repository.cancelInvite(team.id, invite.id)) {
                is NetworkResult.Success -> {
                    _events.emit(UiEvent.ShowSnackbar("邀请已取消"))
                    load(team.id)
                }
                is NetworkResult.Error -> _events.emit(UiEvent.ShowSnackbar("取消失败: ${result.message}", true))
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

    fun transferOwner(member: TeamMember) {
        val team = _uiState.value.team ?: return
        if (team.role != TeamRole.OWNER || member.role == TeamRole.OWNER) return
        viewModelScope.launch {
            when (val result = repository.transferOwner(team.id, member.userId)) {
                is NetworkResult.Success -> {
                    _events.emit(UiEvent.ShowSnackbar("团队所有者已转让"))
                    load(team.id)
                }
                is NetworkResult.Error -> _events.emit(UiEvent.ShowSnackbar("转让失败: ${result.message}", true))
                is NetworkResult.Loading -> Unit
            }
        }
    }

    fun cancelShare(share: ShareLink) {
        val team = _uiState.value.team ?: return
        if (!team.canManageMembers) return
        viewModelScope.launch {
            when (val result = repository.cancelShare(team.id, share.id)) {
                is NetworkResult.Success -> {
                    _events.emit(UiEvent.ShowSnackbar("分享已取消"))
                    load(team.id)
                }
                is NetworkResult.Error -> _events.emit(UiEvent.ShowSnackbar("取消失败: ${result.message}", true))
                is NetworkResult.Loading -> Unit
            }
        }
    }

    fun revealSharePassword(share: ShareLink) {
        val team = _uiState.value.team ?: return
        if (!team.canManageMembers || !share.hasPassword) return
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(loadingPasswordShareId = share.id)
            when (val result = repository.getSharePassword(team.id, share.id)) {
                is NetworkResult.Success -> _uiState.value = _uiState.value.copy(
                    sharePasswords = _uiState.value.sharePasswords + (share.id to (result.data.password ?: "")),
                    loadingPasswordShareId = null,
                )
                is NetworkResult.Error -> {
                    _uiState.value = _uiState.value.copy(loadingPasswordShareId = null)
                    _events.emit(UiEvent.ShowSnackbar("密码读取失败: ${result.message}", true))
                }
                is NetworkResult.Loading -> Unit
            }
        }
    }

    fun updateShare(
        share: ShareLink,
        password: String?,
        expireAt: Long?,
        maxVisits: Int?,
        clearPassword: Boolean = false,
        clearExpireAt: Boolean = false,
        clearMaxVisits: Boolean = false,
    ) {
        val team = _uiState.value.team ?: return
        if (!team.canManageMembers) return
        viewModelScope.launch {
            when (val result = repository.updateShare(
                teamId = team.id,
                shareId = share.id,
                password = if (clearPassword) null else password?.trim()?.takeIf { it.isNotEmpty() },
                expireAt = if (clearExpireAt) null else expireAt,
                maxVisits = if (clearMaxVisits) null else maxVisits,
                clearPassword = clearPassword,
                clearExpireAt = clearExpireAt,
                clearMaxVisits = clearMaxVisits,
            )) {
                is NetworkResult.Success -> {
                    _events.emit(UiEvent.ShowSnackbar("分享已更新"))
                    load(team.id)
                }
                is NetworkResult.Error -> _events.emit(UiEvent.ShowSnackbar("更新分享失败: ${result.message}", true))
                is NetworkResult.Loading -> Unit
            }
        }
    }

    fun createTag(name: String, color: String) {
        val team = _uiState.value.team ?: return
        if (!team.canManageMembers || name.isBlank()) return
        viewModelScope.launch {
            when (val result = repository.createTag(team.id, name.trim(), color.ifBlank { "#1890ff" })) {
                is NetworkResult.Success -> {
                    _events.emit(UiEvent.ShowSnackbar("团队标签已创建"))
                    load(team.id)
                }
                is NetworkResult.Error -> _events.emit(UiEvent.ShowSnackbar("创建标签失败: ${result.message}", true))
                is NetworkResult.Loading -> Unit
            }
        }
    }

    fun deleteTag(tag: Tag) {
        val team = _uiState.value.team ?: return
        if (!team.canManageMembers) return
        viewModelScope.launch {
            when (val result = repository.deleteTag(team.id, tag.id)) {
                is NetworkResult.Success -> {
                    _events.emit(UiEvent.ShowSnackbar("团队标签已删除"))
                    load(team.id)
                }
                is NetworkResult.Error -> _events.emit(UiEvent.ShowSnackbar("删除标签失败: ${result.message}", true))
                is NetworkResult.Loading -> Unit
            }
        }
    }

    fun loadTagFiles(tag: Tag) {
        val team = _uiState.value.team ?: return
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                selectedTagName = tag.tagName,
                tagFilesLoading = true,
            )
            when (val result = repository.listFilesByTag(team.id, tag.id, size = 100)) {
                is NetworkResult.Success -> _uiState.value = _uiState.value.copy(
                    selectedTagName = tag.tagName,
                    tagFiles = result.data.records,
                    tagFilesLoading = false,
                )
                is NetworkResult.Error -> {
                    _uiState.value = _uiState.value.copy(tagFilesLoading = false)
                    _events.emit(UiEvent.ShowSnackbar("标签文件加载失败: ${result.message}", true))
                }
                is NetworkResult.Loading -> Unit
            }
        }
    }

    fun closeTagFiles() {
        _uiState.value = _uiState.value.copy(selectedTagName = null, tagFiles = emptyList(), tagFilesLoading = false)
    }

    fun leaveTeam(onFinished: () -> Unit) {
        val team = _uiState.value.team ?: return
        if (team.role == TeamRole.OWNER) return
        viewModelScope.launch {
            when (val result = repository.leaveTeam(team.id)) {
                is NetworkResult.Success -> onFinished()
                is NetworkResult.Error -> _events.emit(UiEvent.ShowSnackbar("退出失败: ${result.message}", true))
                is NetworkResult.Loading -> Unit
            }
        }
    }

    fun deleteTeam(onFinished: () -> Unit) {
        val team = _uiState.value.team ?: return
        if (team.role != TeamRole.OWNER) return
        viewModelScope.launch {
            when (val result = repository.deleteTeam(team.id)) {
                is NetworkResult.Success -> onFinished()
                is NetworkResult.Error -> _events.emit(UiEvent.ShowSnackbar("删除失败: ${result.message}", true))
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
fun TeamSettingsScreen(
    teamId: Long,
    onNavigateBack: () -> Unit,
    onNavigateRecycleBin: (Long) -> Unit,
    onNavigateTeams: () -> Unit,
    viewModel: TeamSettingsViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()
    val events by viewModel.events.collectAsState(initial = null)
    val snackbarHostState = remember { SnackbarHostState() }
    var selectedTab by remember { mutableIntStateOf(0) }
    var teamName by remember(uiState.team?.id, uiState.team?.name) { mutableStateOf(uiState.team?.name.orEmpty()) }
    var showLeaveConfirm by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    val auditExportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if (uri != null) viewModel.exportAuditLogs(uri)
    }

    LaunchedEffect(teamId) { viewModel.load(teamId) }
    LaunchedEffect(events) {
        if (events is UiEvent.ShowSnackbar) snackbarHostState.showSnackbar((events as UiEvent.ShowSnackbar).message)
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("团队设置") },
                navigationIcon = { IconButton(onClick = onNavigateBack) { Icon(Icons.Default.ArrowBack, "返回") } },
                actions = {
                    IconButton(onClick = { onNavigateRecycleBin(teamId) }, modifier = Modifier.testTag("team-settings-recycle")) {
                        Icon(Icons.Default.Delete, "团队回收站")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { padding ->
        if (uiState.isLoading) {
            Box(modifier = Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            return@Scaffold
        }

        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            ScrollableTabRow(selectedTabIndex = selectedTab, modifier = Modifier.testTag("team-settings-tabs")) {
                listOf(
                    "设置" to "overview",
                    "成员" to "members",
                    "邀请" to "invites",
                    "分享" to "shares",
                    "收藏" to "favorites",
                    "标签" to "tags",
                    "审计" to "audit",
                ).forEachIndexed { index, (title, tag) ->
                    Tab(
                        selected = selectedTab == index,
                        onClick = {
                            selectedTab = index
                            if (tag == "audit") {
                                viewModel.retryAuditLogs()
                            }
                        },
                        text = { Text(title) },
                        modifier = Modifier.testTag("team-settings-tab-$tag"),
                    )
                }
            }
            when (selectedTab) {
                0 -> TeamSettingsOverview(
                    team = uiState.team,
                    teamName = teamName,
                    onTeamNameChange = { teamName = it },
                    onSaveName = { viewModel.saveTeamName(teamName) },
                    onLeave = { showLeaveConfirm = true },
                    onDelete = { showDeleteConfirm = true },
                )
                1 -> TeamSettingsMembers(
                    team = uiState.team,
                    members = uiState.members,
                    onAdd = viewModel::addMember,
                    onUpdateRole = viewModel::updateRole,
                    onRemove = viewModel::removeMember,
                    onTransfer = viewModel::transferOwner,
                )
                2 -> TeamSettingsInvites(
                    team = uiState.team,
                    invites = uiState.invites,
                    onCreate = viewModel::createInvite,
                    onResend = viewModel::resendInvite,
                    onCancel = viewModel::cancelInvite,
                )
                3 -> TeamSettingsShares(
                    team = uiState.team,
                    shares = uiState.shares,
                    passwords = uiState.sharePasswords,
                    loadingPasswordId = uiState.loadingPasswordShareId,
                    onCancel = viewModel::cancelShare,
                    onRevealPassword = viewModel::revealSharePassword,
                    onUpdate = viewModel::updateShare,
                )
                4 -> TeamSettingsFavorites(
                    files = uiState.favorites,
                )
                5 -> TeamSettingsTags(
                    team = uiState.team,
                    tags = uiState.tags,
                    selectedTagName = uiState.selectedTagName,
                    tagFiles = uiState.tagFiles,
                    tagFilesLoading = uiState.tagFilesLoading,
                    onCreate = viewModel::createTag,
                    onDelete = viewModel::deleteTag,
                    onOpenTagFiles = viewModel::loadTagFiles,
                    onCloseTagFiles = viewModel::closeTagFiles,
                )
                else -> TeamSettingsAuditLogs(
                    team = uiState.team,
                    logs = uiState.auditLogs,
                    total = uiState.auditTotal,
                    loading = uiState.auditLoading,
                    loadingMore = uiState.auditLoadingMore,
                    error = uiState.auditError,
                    actorUserId = uiState.auditActorUserId,
                    action = uiState.auditAction,
                    targetType = uiState.auditTargetType,
                    result = uiState.auditResult,
                    startAt = uiState.auditStartAt,
                    endAt = uiState.auditEndAt,
                    exporting = uiState.auditExporting,
                    onActorUserIdChange = { viewModel.updateAuditFilters(actorUserId = it) },
                    onActionChange = { viewModel.updateAuditFilters(action = it) },
                    onTargetTypeChange = { viewModel.updateAuditFilters(targetType = it) },
                    onResultChange = { viewModel.updateAuditFilters(result = it) },
                    onStartAtChange = { viewModel.updateAuditFilters(startAt = it) },
                    onEndAtChange = { viewModel.updateAuditFilters(endAt = it) },
                    onApplyFilters = viewModel::applyAuditFilters,
                    onResetFilters = viewModel::resetAuditFilters,
                    onExport = { auditExportLauncher.launch("team-${uiState.team?.id ?: teamId}-audit.csv") },
                    onRetry = viewModel::retryAuditLogs,
                    onLoadMore = viewModel::loadMoreAuditLogs,
                )
            }
        }
    }

    if (showLeaveConfirm) {
        BCDDialog(
            title = "退出团队",
            onDismiss = { showLeaveConfirm = false },
            onConfirm = {
                showLeaveConfirm = false
                viewModel.leaveTeam(onNavigateTeams)
            },
            confirmText = "退出",
        ) {
            Text("退出后将无法访问该团队文件。")
        }
    }
    if (showDeleteConfirm) {
        BCDDialog(
            title = "删除团队",
            onDismiss = { showDeleteConfirm = false },
            onConfirm = {
                showDeleteConfirm = false
                viewModel.deleteTeam(onNavigateTeams)
            },
            confirmText = "删除",
        ) {
            Text("团队和团队文件将被删除。")
        }
    }
}

@Composable
private fun TeamSettingsOverview(
    team: Team?,
    teamName: String,
    onTeamNameChange: (String) -> Unit,
    onSaveName: () -> Unit,
    onLeave: () -> Unit,
    onDelete: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp).testTag("team-settings-overview"),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = team?.name ?: "团队",
            style = MaterialTheme.typography.titleLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = "${team?.role ?: ""} · ${FormatUtil.formatFileSize(team?.storageUsed ?: 0)} / ${FormatUtil.formatFileSize(team?.storageQuota ?: 0)}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (team != null) {
            StorageBar(storageUsed = team.storageUsed, storageQuota = team.storageQuota, modifier = Modifier.testTag("team-settings-quota"))
            val percent = if (team.storageQuota > 0) team.storageUsed.toFloat() / team.storageQuota.toFloat() else 0f
            if (percent >= 0.8f) {
                Text(
                    text = when {
                        percent >= 1f -> "团队空间已用尽，上传和文档保存可能失败"
                        percent >= 0.9f -> "团队空间接近上限"
                        else -> "团队空间已超过 80%"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (percent >= 0.9f) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.tertiary,
                    modifier = Modifier.testTag("team-settings-quota-warning"),
                )
            }
        }
        if (team?.canManageMembers == true) {
            OutlinedTextField(
                value = teamName,
                onValueChange = onTeamNameChange,
                label = { Text("团队名称") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().testTag("team-settings-name"),
            )
            Button(onClick = onSaveName, modifier = Modifier.testTag("team-settings-save-name")) {
                Icon(Icons.Default.Settings, null)
                Text("保存")
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        if (team?.role == TeamRole.OWNER) {
            TextButton(onClick = onDelete, modifier = Modifier.testTag("team-settings-delete-team")) {
                Text("删除团队", color = MaterialTheme.colorScheme.error)
            }
        } else {
            TextButton(onClick = onLeave, modifier = Modifier.testTag("team-settings-leave-team")) {
                Text("退出团队", color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
private fun TeamSettingsMembers(
    team: Team?,
    members: List<TeamMember>,
    onAdd: (String, TeamRole) -> Unit,
    onUpdateRole: (TeamMember, TeamRole) -> Unit,
    onRemove: (TeamMember) -> Unit,
    onTransfer: (TeamMember) -> Unit,
) {
    var username by remember { mutableStateOf("") }
    var addRole by remember { mutableStateOf(TeamRole.VIEWER) }
    var roleMenuOpen by remember { mutableStateOf(false) }
    Column(modifier = Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (team?.canManageMembers == true) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = username,
                    onValueChange = { username = it },
                    placeholder = { Text("用户名") },
                    singleLine = true,
                    modifier = Modifier.weight(1f).testTag("team-settings-member-username"),
                )
                Column {
                    TextButton(onClick = { roleMenuOpen = true }) { Text(addRole.name) }
                    DropdownMenu(expanded = roleMenuOpen, onDismissRequest = { roleMenuOpen = false }) {
                        listOf(TeamRole.VIEWER, TeamRole.EDITOR, TeamRole.ADMIN).forEach { role ->
                            DropdownMenuItem(
                                text = { Text(role.name) },
                                onClick = {
                                    addRole = role
                                    roleMenuOpen = false
                                },
                            )
                        }
                    }
                }
                Button(
                    onClick = {
                        onAdd(username, addRole)
                        username = ""
                    },
                    modifier = Modifier.testTag("team-settings-member-add"),
                ) {
                    Icon(Icons.Default.PersonAdd, null)
                }
            }
        }
        if (members.isEmpty()) {
            EmptyState(icon = Icons.Default.Groups, title = "暂无成员")
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize().testTag("team-settings-members-list")) {
                items(members, key = { it.userId }) { member ->
                    SettingsMemberRow(
                        team = team,
                        member = member,
                        onUpdateRole = { onUpdateRole(member, it) },
                        onRemove = { onRemove(member) },
                        onTransfer = { onTransfer(member) },
                    )
                }
            }
        }
    }
}

@Composable
private fun SettingsMemberRow(
    team: Team?,
    member: TeamMember,
    onUpdateRole: (TeamRole) -> Unit,
    onRemove: () -> Unit,
    onTransfer: () -> Unit,
) {
    var roleMenuOpen by remember { mutableStateOf(false) }
    ListItem(
        headlineContent = { Text(member.username ?: "用户 ${member.userId}", maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = { Text(member.email ?: "-", maxLines = 1, overflow = TextOverflow.Ellipsis) },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column {
                    TextButton(
                        enabled = team?.canManageMembers == true && member.role != TeamRole.OWNER,
                        onClick = { roleMenuOpen = true },
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
                if (team?.role == TeamRole.OWNER && member.role != TeamRole.OWNER) {
                    TextButton(onClick = onTransfer, modifier = Modifier.testTag("team-settings-transfer-${member.userId}")) {
                        Text("转让")
                    }
                }
                if (team?.canManageMembers == true && member.role != TeamRole.OWNER) {
                    IconButton(onClick = onRemove, modifier = Modifier.testTag("team-settings-remove-${member.userId}")) {
                        Icon(Icons.Default.Delete, "移除", tint = MaterialTheme.colorScheme.error)
                    }
                }
            }
        },
        modifier = Modifier.testTag("team-settings-member-${member.userId}"),
    )
}

@Composable
private fun TeamSettingsInvites(
    team: Team?,
    invites: List<TeamInvite>,
    onCreate: (String, TeamRole) -> Unit,
    onResend: (TeamInvite) -> Unit,
    onCancel: (TeamInvite) -> Unit,
) {
    if (team?.canManageMembers != true) {
        EmptyState(
            icon = Icons.Default.PersonAdd,
            title = "无邀请管理权限",
            modifier = Modifier.fillMaxSize().testTag("team-settings-invites-readonly"),
        )
        return
    }

    var email by remember { mutableStateOf("") }
    var inviteRole by remember { mutableStateOf(TeamRole.VIEWER) }
    var roleMenuOpen by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(16.dp).testTag("team-settings-invites-list"),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("团队邀请", style = MaterialTheme.typography.titleMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = email,
                        onValueChange = { email = it },
                        placeholder = { Text("成员邮箱") },
                        singleLine = true,
                        modifier = Modifier.weight(1f).testTag("team-settings-invite-email"),
                    )
                    Column {
                        TextButton(onClick = { roleMenuOpen = true }) { Text(inviteRole.name) }
                        DropdownMenu(expanded = roleMenuOpen, onDismissRequest = { roleMenuOpen = false }) {
                            listOf(TeamRole.VIEWER, TeamRole.EDITOR, TeamRole.ADMIN).forEach { role ->
                                DropdownMenuItem(
                                    text = { Text(role.name) },
                                    onClick = {
                                        inviteRole = role
                                        roleMenuOpen = false
                                    },
                                )
                            }
                        }
                    }
                    Button(
                        onClick = {
                            onCreate(email, inviteRole)
                            email = ""
                        },
                        modifier = Modifier.testTag("team-settings-invite-create"),
                    ) {
                        Text("邀请")
                    }
                }
                if (invites.isEmpty()) {
                    Text(
                        text = "暂无待处理邀请",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        items(invites, key = { it.id }) { invite ->
            ListItem(
                headlineContent = { Text(invite.email, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                supportingContent = {
                    Text(
                        "${invite.role.name} · ${invite.status} · 过期 ${invite.expiresAt ?: "-"}",
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                trailingContent = {
                    if (invite.status == "PENDING") {
                        Row {
                            TextButton(
                                onClick = { onResend(invite) },
                                modifier = Modifier.testTag("team-settings-invite-resend-${invite.id}"),
                            ) {
                                Text("重发")
                            }
                            TextButton(
                                onClick = { onCancel(invite) },
                                modifier = Modifier.testTag("team-settings-invite-cancel-${invite.id}"),
                            ) {
                                Text("取消", color = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                },
                modifier = Modifier.testTag("team-settings-invite-${invite.id}"),
            )
        }
    }
}

@Composable
private fun TeamSettingsShares(
    team: Team?,
    shares: List<ShareLink>,
    passwords: Map<Long, String>,
    loadingPasswordId: Long?,
    onCancel: (ShareLink) -> Unit,
    onRevealPassword: (ShareLink) -> Unit,
    onUpdate: (ShareLink, String?, Long?, Int?, Boolean, Boolean, Boolean) -> Unit,
) {
    val clipboard = LocalClipboardManager.current
    var detailShare by remember { mutableStateOf<ShareLink?>(null) }
    var editShare by remember { mutableStateOf<ShareLink?>(null) }
    var editPassword by remember { mutableStateOf("") }
    var editExpireAt by remember { mutableStateOf("") }
    var editMaxVisits by remember { mutableStateOf("") }
    var editClearPassword by remember { mutableStateOf(false) }
    var editClearExpireAt by remember { mutableStateOf(false) }
    var editClearMaxVisits by remember { mutableStateOf(false) }

    detailShare?.let { share ->
        AlertDialog(
            onDismissRequest = { detailShare = null },
            title = { Text("分享详情") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("分享码: ${share.shareCode}")
                    Text("文件 ID: ${share.fileId}")
                    Text("访问: ${share.visitCount}${share.maxVisits?.let { "/$it" } ?: ""}")
                    Text("下载: ${share.downloadCount}")
                    Text("密码: ${if (share.hasPassword) passwords[share.id] ?: "有密码" else "无密码"}")
                    Text("状态: ${if (share.isCanceled) "已取消" else "有效"}")
                }
            },
            confirmButton = {
                TextButton(onClick = { detailShare = null }, modifier = Modifier.testTag("team-settings-share-detail-close")) {
                    Text("关闭")
                }
            },
        )
    }

    editShare?.let { share ->
        BCDDialog(
            title = "编辑团队分享",
            onDismiss = { editShare = null },
            onConfirm = {
                val expireAt = if (editClearExpireAt) null else editExpireAt.trim().takeIf { it.isNotEmpty() }?.toLongOrNull()
                val visits = if (editClearMaxVisits) null else editMaxVisits.trim().takeIf { it.isNotEmpty() }?.toIntOrNull()
                onUpdate(share, editPassword, expireAt, visits, editClearPassword, editClearExpireAt, editClearMaxVisits)
                editShare = null
            },
            confirmText = "保存",
        ) {
            OutlinedTextField(
                value = editPassword,
                onValueChange = { editPassword = it },
                placeholder = { Text("新密码，留空不修改") },
                singleLine = true,
                enabled = !editClearPassword,
                modifier = Modifier.fillMaxWidth().testTag("team-settings-share-edit-password"),
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(
                    checked = editClearPassword,
                    onCheckedChange = { editClearPassword = it },
                    modifier = Modifier.testTag("team-settings-share-clear-password"),
                )
                Text("移除访问密码")
            }
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = editExpireAt,
                onValueChange = { editExpireAt = it.filter { char -> char.isDigit() } },
                placeholder = { Text("过期时间 Unix 毫秒，留空不修改") },
                singleLine = true,
                enabled = !editClearExpireAt,
                modifier = Modifier.fillMaxWidth().testTag("team-settings-share-edit-expire-at"),
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(
                    checked = editClearExpireAt,
                    onCheckedChange = { editClearExpireAt = it },
                    modifier = Modifier.testTag("team-settings-share-clear-expire-at"),
                )
                Text("清空过期时间")
            }
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = editMaxVisits,
                onValueChange = { editMaxVisits = it.filter { char -> char.isDigit() } },
                placeholder = { Text("访问次数限制，留空不修改") },
                singleLine = true,
                enabled = !editClearMaxVisits,
                modifier = Modifier.fillMaxWidth().testTag("team-settings-share-edit-max-visits"),
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(
                    checked = editClearMaxVisits,
                    onCheckedChange = { editClearMaxVisits = it },
                    modifier = Modifier.testTag("team-settings-share-clear-max-visits"),
                )
                Text("清空访问次数限制")
            }
        }
    }

    if (shares.isEmpty()) {
        EmptyState(
            icon = Icons.Default.Link,
            title = "暂无团队分享",
            subtitle = "在团队文件中长按文件可创建分享",
            modifier = Modifier.fillMaxSize().testTag("team-settings-shares-empty"),
        )
        return
    }
    LazyColumn(modifier = Modifier.fillMaxSize().padding(16.dp).testTag("team-settings-shares-list")) {
        items(shares, key = { it.id }) { share ->
            ListItem(
                headlineContent = { Text(share.shareCode, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                supportingContent = {
                    Text(
                        "文件 ${share.fileId} · 访问 ${share.visitCount}${share.maxVisits?.let { "/$it" } ?: ""} · 下载 ${share.downloadCount}",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                trailingContent = {
                    Column(horizontalAlignment = Alignment.End) {
                        Row {
                            TextButton(
                                onClick = { detailShare = share },
                                modifier = Modifier.testTag("team-settings-share-detail-${share.id}"),
                            ) {
                                Text("详情")
                            }
                            TextButton(
                                onClick = {
                                    clipboard.setText(AnnotatedString(share.shareCode))
                                },
                                modifier = Modifier.testTag("team-settings-copy-share-${share.id}"),
                            ) {
                                Text("复制")
                            }
                        }
                        Row {
                            if (share.hasPassword && team?.canManageMembers == true) {
                                TextButton(
                                    onClick = { onRevealPassword(share) },
                                    enabled = loadingPasswordId != share.id,
                                    modifier = Modifier.testTag("team-settings-share-password-${share.id}"),
                                ) {
                                    Text(if (loadingPasswordId == share.id) "读取中" else "密码")
                                }
                            }
                            if (team?.canManageMembers == true) {
                                TextButton(
                                    onClick = {
                                        editShare = share
                                        editExpireAt = ""
                                        editMaxVisits = share.maxVisits?.toString() ?: ""
                                        editPassword = ""
                                        editClearPassword = false
                                        editClearExpireAt = false
                                        editClearMaxVisits = false
                                    },
                                    modifier = Modifier.testTag("team-settings-edit-share-${share.id}"),
                                ) {
                                    Text("编辑")
                                }
                                TextButton(
                                    onClick = { onCancel(share) },
                                    modifier = Modifier.testTag("team-settings-cancel-share-${share.id}"),
                                ) {
                                    Text("取消", color = MaterialTheme.colorScheme.error)
                                }
                            }
                        }
                    }
                },
                modifier = Modifier.testTag("team-settings-share-${share.id}"),
            )
        }
    }
}

@Composable
private fun TeamSettingsFavorites(files: List<FileItem>) {
    if (files.isEmpty()) {
        EmptyState(
            icon = Icons.Default.FolderOpen,
            title = "暂无团队收藏",
            subtitle = "在团队文件中长按文件可加入收藏",
            modifier = Modifier.fillMaxSize().testTag("team-settings-favorites-empty"),
        )
        return
    }
    LazyColumn(modifier = Modifier.fillMaxSize().padding(16.dp).testTag("team-settings-favorites-list")) {
        items(files, key = { it.id }) { file ->
            TeamSettingsFileRow(file = file, tag = "team-settings-favorite-${file.id}")
        }
    }
}

@Composable
private fun TeamSettingsFileRow(file: FileItem, tag: String) {
    ListItem(
        headlineContent = { Text(file.fileName, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = {
            Text(
                if (file.isFolder) "文件夹" else "${FormatUtil.formatFileSize(file.fileSize)} · 版本 ${file.versionCount}",
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        leadingContent = { Icon(Icons.Default.FolderOpen, null) },
        modifier = Modifier.testTag(tag),
    )
}

@Composable
private fun TeamSettingsTags(
    team: Team?,
    tags: List<Tag>,
    selectedTagName: String?,
    tagFiles: List<FileItem>,
    tagFilesLoading: Boolean,
    onCreate: (String, String) -> Unit,
    onDelete: (Tag) -> Unit,
    onOpenTagFiles: (Tag) -> Unit,
    onCloseTagFiles: () -> Unit,
) {
    var tagName by remember { mutableStateOf("") }
    var tagColor by remember { mutableStateOf("#1890ff") }
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(16.dp).testTag("team-settings-tags-list"),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("团队标签", style = MaterialTheme.typography.titleMedium)
                if (team?.canManageMembers == true) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = tagName,
                            onValueChange = { tagName = it },
                            placeholder = { Text("标签名称") },
                            singleLine = true,
                            modifier = Modifier.weight(1f).testTag("team-settings-tag-name"),
                        )
                        OutlinedTextField(
                            value = tagColor,
                            onValueChange = { tagColor = it },
                            placeholder = { Text("#1890ff") },
                            singleLine = true,
                            modifier = Modifier.weight(1f).testTag("team-settings-tag-color"),
                        )
                        Button(
                            onClick = {
                                onCreate(tagName, tagColor)
                                tagName = ""
                            },
                            modifier = Modifier.testTag("team-settings-tag-create"),
                        ) {
                            Text("新建")
                        }
                    }
                }
                if (tags.isEmpty()) {
                    Text(
                        text = "暂无团队标签",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        items(tags, key = { "tag-${it.id}" }) { tag ->
            ListItem(
                headlineContent = { Text(tag.tagName, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                supportingContent = { Text(tag.color, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                trailingContent = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton(
                            onClick = { onOpenTagFiles(tag) },
                            modifier = Modifier.testTag("team-settings-tag-open-${tag.id}"),
                        ) {
                            Text("查看")
                        }
                        if (team?.canManageMembers == true) {
                            IconButton(
                                onClick = { onDelete(tag) },
                                modifier = Modifier.testTag("team-settings-tag-delete-${tag.id}"),
                            ) {
                                Icon(Icons.Default.Delete, "删除标签", tint = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                },
                modifier = Modifier.testTag("team-settings-tag-${tag.id}"),
            )
        }
        selectedTagName?.let { name ->
            item {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("标签文件：$name", style = MaterialTheme.typography.titleSmall)
                    TextButton(onClick = onCloseTagFiles, modifier = Modifier.testTag("team-settings-tag-files-close")) {
                        Text("关闭")
                    }
                }
            }
            if (tagFilesLoading) {
                item {
                    Box(modifier = Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(modifier = Modifier.testTag("team-settings-tag-files-loading"))
                    }
                }
            } else if (tagFiles.isEmpty()) {
                item {
                    Text(
                        "暂无标签文件",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.testTag("team-settings-tag-files-empty"),
                    )
                }
            } else {
                items(tagFiles, key = { "tag-file-${it.id}" }) { file ->
                    TeamSettingsFileRow(file = file, tag = "team-settings-tag-file-${file.id}")
                }
            }
        }
    }
}

@Composable
private fun TeamSettingsAuditLogs(
    team: Team?,
    logs: List<TeamOperationLog>,
    total: Long,
    loading: Boolean,
    loadingMore: Boolean,
    error: String?,
    actorUserId: String,
    action: String,
    targetType: String,
    result: String,
    startAt: String,
    endAt: String,
    exporting: Boolean,
    onActorUserIdChange: (String) -> Unit,
    onActionChange: (String) -> Unit,
    onTargetTypeChange: (String) -> Unit,
    onResultChange: (String) -> Unit,
    onStartAtChange: (String) -> Unit,
    onEndAtChange: (String) -> Unit,
    onApplyFilters: () -> Unit,
    onResetFilters: () -> Unit,
    onExport: () -> Unit,
    onRetry: () -> Unit,
    onLoadMore: () -> Unit,
) {
    if (team?.canManageMembers != true) {
        EmptyState(
            icon = Icons.Default.Settings,
            title = "无审计日志权限",
            modifier = Modifier.fillMaxSize().testTag("team-settings-audit-readonly"),
        )
        return
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp).testTag("team-settings-audit-panel"),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        TeamSettingsAuditFilters(
            actorUserId = actorUserId,
            action = action,
            targetType = targetType,
            result = result,
            startAt = startAt,
            endAt = endAt,
            exporting = exporting,
            onActorUserIdChange = onActorUserIdChange,
            onActionChange = onActionChange,
            onTargetTypeChange = onTargetTypeChange,
            onResultChange = onResultChange,
            onStartAtChange = onStartAtChange,
            onEndAtChange = onEndAtChange,
            onApply = onApplyFilters,
            onReset = onResetFilters,
            onExport = onExport,
        )

        when {
            loading && logs.isEmpty() -> {
                Box(
                    modifier = Modifier.fillMaxWidth().weight(1f).testTag("team-settings-audit-loading"),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }
            }
            error != null && logs.isEmpty() -> {
                Column(
                    modifier = Modifier.fillMaxWidth().weight(1f).testTag("team-settings-audit-error"),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text("审计日志加载失败: $error", color = MaterialTheme.colorScheme.error)
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(onClick = onRetry, modifier = Modifier.testTag("team-settings-audit-retry")) {
                        Text("重试")
                    }
                }
            }
            logs.isEmpty() -> {
                EmptyState(
                    icon = Icons.Default.Settings,
                    title = "暂无团队审计日志",
                    modifier = Modifier.fillMaxWidth().weight(1f).testTag("team-settings-audit-empty"),
                )
            }
            else -> {
                LazyColumn(modifier = Modifier.fillMaxWidth().weight(1f).testTag("team-settings-audit-list")) {
                    item {
                        Text(
                            "已显示 ${logs.size} / $total 条",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(bottom = 8.dp).testTag("team-settings-audit-count"),
                        )
                    }
                    items(logs, key = { it.id }) { log ->
                        ListItem(
                            headlineContent = { Text(log.action, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            supportingContent = {
                                Text(
                                    "${log.createdAt ?: "-"} · ${log.targetType ?: "-"} ${log.targetId ?: ""} · 状态 ${log.statusCode ?: "-"}",
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            },
                            trailingContent = {
                                Text(
                                    if (log.result == 1) "成功" else "失败",
                                    color = if (log.result == 1) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                                )
                            },
                            modifier = Modifier.testTag("team-settings-audit-${log.id}"),
                        )
                    }
                    if (error != null) {
                        item {
                            Column(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                Text("加载失败: $error", color = MaterialTheme.colorScheme.error)
                                TextButton(onClick = onLoadMore, modifier = Modifier.testTag("team-settings-audit-load-more-retry")) {
                                    Text("重试")
                                }
                            }
                        }
                    } else if (loadingMore) {
                        item {
                            Box(modifier = Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator(modifier = Modifier.testTag("team-settings-audit-loading-more"))
                            }
                        }
                    } else if (logs.size.toLong() < total) {
                        item {
                            Box(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
                                TextButton(onClick = onLoadMore, modifier = Modifier.testTag("team-settings-audit-load-more")) {
                                    Text("加载更多")
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TeamSettingsAuditFilters(
    actorUserId: String,
    action: String,
    targetType: String,
    result: String,
    startAt: String,
    endAt: String,
    exporting: Boolean,
    onActorUserIdChange: (String) -> Unit,
    onActionChange: (String) -> Unit,
    onTargetTypeChange: (String) -> Unit,
    onResultChange: (String) -> Unit,
    onStartAtChange: (String) -> Unit,
    onEndAtChange: (String) -> Unit,
    onApply: () -> Unit,
    onReset: () -> Unit,
    onExport: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth().testTag("team-settings-audit-filters"),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("筛选审计日志", style = MaterialTheme.typography.titleSmall)
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = actorUserId,
                onValueChange = { onActorUserIdChange(it.filter { char -> char.isDigit() }) },
                placeholder = { Text("用户 ID") },
                singleLine = true,
                modifier = Modifier.weight(1f).testTag("team-settings-audit-filter-user"),
            )
            OutlinedTextField(
                value = action,
                onValueChange = onActionChange,
                placeholder = { Text("动作，如 INVITE") },
                singleLine = true,
                modifier = Modifier.weight(1f).testTag("team-settings-audit-filter-action"),
            )
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = targetType,
                onValueChange = onTargetTypeChange,
                placeholder = { Text("目标，如 FILE") },
                singleLine = true,
                modifier = Modifier.weight(1f).testTag("team-settings-audit-filter-target"),
            )
            OutlinedTextField(
                value = result,
                onValueChange = onResultChange,
                placeholder = { Text("1成功/0失败") },
                singleLine = true,
                modifier = Modifier.weight(1f).testTag("team-settings-audit-filter-result"),
            )
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = startAt,
                onValueChange = onStartAtChange,
                placeholder = { Text("开始时间 ISO") },
                singleLine = true,
                modifier = Modifier.weight(1f).testTag("team-settings-audit-filter-start"),
            )
            OutlinedTextField(
                value = endAt,
                onValueChange = onEndAtChange,
                placeholder = { Text("结束时间 ISO") },
                singleLine = true,
                modifier = Modifier.weight(1f).testTag("team-settings-audit-filter-end"),
            )
        }
        Text(
            text = "时间格式示例：2026-06-27T12:59:14",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = onApply, modifier = Modifier.testTag("team-settings-audit-filter-apply")) {
                Text("筛选")
            }
            TextButton(onClick = onReset, modifier = Modifier.testTag("team-settings-audit-filter-reset")) {
                Text("重置")
            }
            TextButton(
                onClick = onExport,
                enabled = !exporting,
                modifier = Modifier.testTag("team-settings-audit-export"),
            ) {
                Text(if (exporting) "导出中" else "导出 CSV")
            }
        }
    }
}
