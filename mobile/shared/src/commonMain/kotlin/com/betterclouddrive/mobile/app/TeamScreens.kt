@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package com.betterclouddrive.mobile.app

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.backhandler.BackHandler
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.betterclouddrive.mobile.core.localization.LocalMobileStrings
import com.betterclouddrive.mobile.core.localization.MobileString
import com.betterclouddrive.mobile.core.localization.UiMessage
import com.betterclouddrive.mobile.core.model.FileItem
import com.betterclouddrive.mobile.core.model.PreviewKind
import com.betterclouddrive.mobile.core.model.TeamRole
import com.betterclouddrive.mobile.core.model.TeamSummary
import com.betterclouddrive.mobile.core.platform.MediaPreviewRenderer
import com.betterclouddrive.mobile.core.platform.MediaPreviewRequest
import com.betterclouddrive.mobile.core.platform.OfficeRenderer
import com.betterclouddrive.mobile.feature.files.FileViewMode
import com.betterclouddrive.mobile.feature.files.PersonalFileSort
import com.betterclouddrive.mobile.feature.files.SortDirection
import com.betterclouddrive.mobile.feature.files.previewKind
import com.betterclouddrive.mobile.feature.teams.TeamBrowserState
import com.betterclouddrive.mobile.feature.teams.TeamPreviewState
import com.betterclouddrive.mobile.feature.teams.TeamSearchState
import com.betterclouddrive.mobile.feature.teams.TeamsState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TeamsScreen(
    state: TeamsState,
    wide: Boolean,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onLoadMore: () -> Unit,
    onCreate: (String) -> Unit,
    onClearCreateError: () -> Unit,
    onOpenTeam: (TeamSummary) -> Unit,
) {
    val strings = LocalMobileStrings.current
    var creating by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    BackHandler(onBack = onBack)
    Scaffold(
        modifier = Modifier.testTag("teams-screen"),
        topBar = { TopAppBar(
            title = { Text(strings[MobileString.TeamsTitle]) },
            navigationIcon = { IconButton(onBack, Modifier.testTag("teams-back")) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, strings[MobileString.BackToParent])
            } },
            actions = {
                IconButton(onRefresh, enabled = !state.loading && !state.refreshing, modifier = Modifier.testTag("teams-refresh")) {
                    Icon(Icons.Default.Refresh, strings[MobileString.RefreshDirectory])
                }
                Button(onClick = { onClearCreateError(); creating = true }, modifier = Modifier.testTag("teams-create")) {
                    Text(strings[MobileString.CreateTeam])
                }
            },
        ) },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when {
                state.loading && state.items.isEmpty() -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                state.error != null && state.items.isEmpty() -> TeamRetryPane(strings.resolve(state.error).orEmpty(), onRefresh)
                state.items.isEmpty() -> TeamEmptyPane(MobileString.TeamsEmpty, MobileString.TeamsEmptyDetail)
                else -> LazyColumn(
                    Modifier.fillMaxSize().testTag("teams-list"),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(state.items, key = TeamSummary::id) { team ->
                        TeamCard(team, onOpenTeam)
                        if (team.id == state.items.lastOrNull()?.id && state.hasMore) LaunchedEffect(team.id) { onLoadMore() }
                    }
                    if (state.loadingMore) item { CircularProgressIndicator(Modifier.padding(16.dp)) }
                    state.loadMoreError?.let { error -> item {
                        OutlinedButton(onLoadMore, Modifier.padding(16.dp).testTag("teams-load-more-retry")) {
                            Text(strings.resolve(error) ?: strings[MobileString.RetryLoadMore])
                        }
                    } }
                }
            }
        }
    }
    if (creating) TeamCreateOverlay(
        wide = wide,
        name = name,
        submitting = state.creating,
        error = state.createError,
        onName = { name = it },
        onSubmit = { onCreate(name) },
        onDismiss = { if (!state.creating) { creating = false; name = ""; onClearCreateError() } },
    )
}

@Composable
private fun TeamCard(team: TeamSummary, onOpen: (TeamSummary) -> Unit) {
    val strings = LocalMobileStrings.current
    val fraction = if (team.storageQuota > 0) (team.storageUsed.toFloat() / team.storageQuota).coerceIn(0f, 1f) else 0f
    Card(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp).clickable { onOpen(team) }.testTag("team-${team.id}"),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Groups, null)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(team.name, style = MaterialTheme.typography.titleMedium)
                    Text(strings[team.role.stringKey()], style = MaterialTheme.typography.labelMedium)
                }
                Text(strings[MobileString.TeamReadOnly], style = MaterialTheme.typography.labelSmall)
            }
            LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
            Text(strings.format(MobileString.TeamQuota, formatFileSize(team.storageUsed), formatFileSize(team.storageQuota)))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TeamCreateOverlay(
    wide: Boolean,
    name: String,
    submitting: Boolean,
    error: UiMessage?,
    onName: (String) -> Unit,
    onSubmit: () -> Unit,
    onDismiss: () -> Unit,
) {
    val strings = LocalMobileStrings.current
    val content: @Composable () -> Unit = {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(strings[MobileString.CreateTeam], style = MaterialTheme.typography.titleLarge)
            OutlinedTextField(
                value = name, onValueChange = onName, enabled = !submitting,
                label = { Text(strings[MobileString.TeamName]) }, singleLine = true,
                isError = error != null, supportingText = { error?.let { Text(strings.resolve(it).orEmpty()) } },
                modifier = Modifier.fillMaxWidth().testTag("team-name"),
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                OutlinedButton(onDismiss, enabled = !submitting) { Text(strings[MobileString.Cancel]) }
                Spacer(Modifier.width(8.dp))
                Button(onSubmit, enabled = !submitting, modifier = Modifier.testTag("team-create-confirm")) {
                    if (submitting) CircularProgressIndicator(Modifier.width(18.dp).height(18.dp))
                    else Text(strings[MobileString.CreateTeam])
                }
            }
        }
    }
    if (wide) AlertDialog(onDismissRequest = onDismiss, confirmButton = {}, text = content)
    else ModalBottomSheet(onDismissRequest = onDismiss) { content() }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TeamBrowserScreen(
    state: TeamBrowserState,
    wide: Boolean,
    onBack: () -> Unit,
    onBackToTeams: () -> Unit,
    onBreadcrumb: (Long?) -> Unit,
    onOpenFolder: (FileItem) -> Unit,
    onOpenSearch: () -> Unit,
    onRetryWorkspace: () -> Unit,
    onRefresh: () -> Unit,
    onLoadMore: () -> Unit,
    onSort: (PersonalFileSort, SortDirection) -> Unit,
    onViewMode: (FileViewMode) -> Unit,
    onSelect: (FileItem?) -> Unit,
    onPreview: (FileItem) -> Unit,
) {
    val strings = LocalMobileStrings.current
    var sortMenu by remember { mutableStateOf(false) }
    BackHandler(onBack = onBack)
    Scaffold(
        modifier = Modifier.testTag("team-browser-screen"),
        topBar = { TopAppBar(
            title = { TeamBreadcrumb(state, onBreadcrumb) },
            navigationIcon = { IconButton(onBack, Modifier.testTag("team-browser-back")) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, strings[MobileString.BackToParent])
            } },
            actions = {
                IconButton(onOpenSearch, Modifier.testTag("team-search-open")) { Icon(Icons.Default.Search, strings[MobileString.TeamSearch]) }
                Box {
                    IconButton({ sortMenu = true }, Modifier.testTag("team-sort")) { Icon(Icons.AutoMirrored.Filled.Sort, strings[MobileString.Sort]) }
                    DropdownMenu(sortMenu, { sortMenu = false }) {
                        PersonalFileSort.entries.forEach { sort -> DropdownMenuItem(
                            text = { Text(strings[sort.stringKey()]) },
                            onClick = { onSort(sort, state.direction); sortMenu = false },
                            modifier = Modifier.testTag("team-sort-${sort.apiValue}"),
                        ) }
                        DropdownMenuItem(
                            text = { Text(strings[state.direction.stringKey()]) },
                            leadingIcon = { Icon(if (state.direction == SortDirection.ASCENDING) Icons.Default.ArrowUpward else Icons.Default.ArrowDownward, null) },
                            onClick = {
                                onSort(state.sort, if (state.direction == SortDirection.ASCENDING) SortDirection.DESCENDING else SortDirection.ASCENDING)
                                sortMenu = false
                            }, modifier = Modifier.testTag("team-sort-direction"),
                        )
                    }
                }
                IconButton({ onViewMode(if (state.viewMode == FileViewMode.LIST) FileViewMode.GRID else FileViewMode.LIST) }, Modifier.testTag("team-view-mode")) {
                    Icon(if (state.viewMode == FileViewMode.LIST) Icons.Default.GridView else Icons.AutoMirrored.Filled.List, strings[MobileString.SwitchView])
                }
                IconButton(onRefresh, Modifier.testTag("team-refresh")) { Icon(Icons.Default.Refresh, strings[MobileString.RefreshDirectory]) }
            },
        ) },
    ) { padding ->
        Row(Modifier.fillMaxSize().padding(padding)) {
            Box(Modifier.weight(if (wide && state.selectedFile != null) .62f else 1f).fillMaxSize()) {
                when {
                    state.workspaceLoading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                    state.workspaceError != null -> Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                        Text(strings.resolve(state.workspaceError).orEmpty(), color = MaterialTheme.colorScheme.error)
                        Button(onRetryWorkspace, Modifier.testTag("team-workspace-retry")) { Text(strings[MobileString.Retry]) }
                        OutlinedButton(onBackToTeams) { Text(strings[MobileString.BackToTeams]) }
                    }
                    else -> TeamDirectoryContent(state, onOpenFolder, onSelect, onLoadMore)
                }
            }
            if (wide && state.selectedFile != null) {
                HorizontalDivider(Modifier.width(1.dp).fillMaxSize())
                TeamFileDetails(state.selectedFile, onPreview, Modifier.weight(.38f).fillMaxSize())
            }
        }
    }
    if (!wide && state.selectedFile != null) ModalBottomSheet(onDismissRequest = { onSelect(null) }) {
        TeamFileDetails(state.selectedFile, onPreview, Modifier.fillMaxWidth())
    }
}

@Composable
private fun TeamDirectoryContent(
    state: TeamBrowserState,
    onOpenFolder: (FileItem) -> Unit,
    onSelect: (FileItem) -> Unit,
    onLoadMore: () -> Unit,
) {
    val strings = LocalMobileStrings.current
    val current = state.current
    when {
        current.initialLoading && current.items.isEmpty() -> Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }
        current.error != null && current.items.isEmpty() -> TeamRetryPane(strings.resolve(current.error).orEmpty(), onLoadMore)
        current.hasLoaded && current.items.isEmpty() -> TeamEmptyPane(MobileString.TeamDirectoryEmpty, MobileString.TeamDirectoryEmptyDetail)
        state.viewMode == FileViewMode.LIST -> LazyColumn(Modifier.fillMaxSize().testTag("team-file-list")) {
            items(current.items, key = FileItem::id) { file ->
                TeamFileRow(file) { if (file.isFolder) onOpenFolder(file) else onSelect(file) }
                if (file.id == current.items.lastOrNull()?.id && current.hasMore) LaunchedEffect(file.id) { onLoadMore() }
            }
            teamLoadMoreFooter(current.loadingMore, current.loadMoreError, onLoadMore)
        }
        else -> LazyVerticalGrid(GridCells.Adaptive(150.dp), Modifier.fillMaxSize().testTag("team-file-grid")) {
            items(current.items, key = FileItem::id) { file ->
                TeamFileCard(file) { if (file.isFolder) onOpenFolder(file) else onSelect(file) }
                if (file.id == current.items.lastOrNull()?.id && current.hasMore) LaunchedEffect(file.id) { onLoadMore() }
            }
            if (current.loadingMore) item { CircularProgressIndicator(Modifier.padding(16.dp)) }
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.teamLoadMoreFooter(
    loading: Boolean, error: UiMessage?, retry: () -> Unit,
) {
    if (loading) item { CircularProgressIndicator(Modifier.padding(16.dp)) }
    if (error != null) item { OutlinedButton(retry, Modifier.padding(16.dp).testTag("team-load-more-retry")) {
        Text(LocalMobileStrings.current[MobileString.RetryLoadMore])
    } }
}

@Composable
private fun TeamFileRow(file: FileItem, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp).testTag("team-file-${file.id}"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(if (file.isFolder) Icons.Default.Folder else Icons.Default.Description, null)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) { Text(file.fileName, maxLines = 1, overflow = TextOverflow.Ellipsis); Text(formatFileSize(file.fileSize)) }
    }
    HorizontalDivider()
}

@Composable
private fun TeamFileCard(file: FileItem, onClick: () -> Unit) {
    Card(Modifier.padding(8.dp).clickable(onClick = onClick).testTag("team-grid-${file.id}")) {
        Column(Modifier.fillMaxWidth().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(if (file.isFolder) Icons.Default.Folder else Icons.Default.Description, null)
            Text(file.fileName, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(formatFileSize(file.fileSize), style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun TeamFileDetails(file: FileItem?, onPreview: (FileItem) -> Unit, modifier: Modifier) {
    val strings = LocalMobileStrings.current
    if (file == null) return
    Column(modifier.padding(20.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(file.fileName, style = MaterialTheme.typography.titleLarge)
        Text(strings[MobileString.TeamReadOnly], color = MaterialTheme.colorScheme.secondary)
        TeamDetailRow(strings[MobileString.Type], file.mimeType ?: file.fileType)
        TeamDetailRow(strings[MobileString.Size], formatFileSize(file.fileSize))
        TeamDetailRow(strings[MobileString.CreatedAt], formatDate(file.createdAt))
        TeamDetailRow(strings[MobileString.UpdatedAt], formatDate(file.updatedAt))
        TeamDetailRow("MD5", file.md5Hash ?: "—")
        if (!file.isFolder && file.previewKind() != PreviewKind.UNSUPPORTED) Button(
            onClick = { onPreview(file) }, modifier = Modifier.testTag("team-preview-${file.id}"),
        ) { Icon(Icons.Default.Visibility, null); Text(strings[MobileString.Preview]) }
    }
}

@Composable
private fun TeamDetailRow(label: String, value: String) {
    Column { Text(label, style = MaterialTheme.typography.labelMedium); Text(value) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TeamSearchScreen(
    state: TeamSearchState,
    onBack: () -> Unit,
    onQuery: (String) -> Unit,
    onSubmit: () -> Unit,
    onRetry: () -> Unit,
    onLoadMore: () -> Unit,
    onOpenFolder: (FileItem) -> Unit,
    onOpenLocation: (FileItem) -> Unit,
    onPreview: (FileItem) -> Unit,
) {
    val strings = LocalMobileStrings.current
    var selectedUnsupported by remember { mutableStateOf<FileItem?>(null) }
    BackHandler(onBack = onBack)
    Scaffold(
        modifier = Modifier.testTag("team-search-screen"),
        topBar = { TopAppBar(
            title = { OutlinedTextField(
                state.query, onQuery, singleLine = true, placeholder = { Text(strings[MobileString.TeamSearchHint]) },
                modifier = Modifier.fillMaxWidth().testTag("team-search-query"),
            ) },
            navigationIcon = { IconButton(onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, strings[MobileString.BackToParent]) } },
            actions = { IconButton(onSubmit) { Icon(Icons.Default.Search, strings[MobileString.Search]) } },
        ) },
    ) { padding -> Box(Modifier.fillMaxSize().padding(padding)) {
        when {
            state.submittedQuery.isEmpty() && !state.searching -> TeamEmptyPane(MobileString.TeamSearch, MobileString.TeamSearchInitial)
            state.searching && state.items.isEmpty() -> CircularProgressIndicator(Modifier.align(Alignment.Center))
            state.error != null && state.items.isEmpty() -> TeamRetryPane(strings.resolve(state.error).orEmpty(), onRetry)
            state.items.isEmpty() -> TeamEmptyPane(
                MobileString.TeamSearchEmpty,
                MobileString.SearchNoResults,
                state.submittedQuery,
            )
            else -> LazyColumn(Modifier.fillMaxSize().testTag("team-search-results")) {
                items(state.items, key = FileItem::id) { file ->
                    Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
                        TeamFileRow(file) {
                            if (file.isFolder) onOpenFolder(file)
                            else if (file.previewKind() != PreviewKind.UNSUPPORTED) onPreview(file)
                            else selectedUnsupported = file
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton({ onOpenLocation(file) }, Modifier.testTag("team-open-location-${file.id}")) { Text(strings[MobileString.OpenLocation]) }
                            if (!file.isFolder && file.previewKind() != PreviewKind.UNSUPPORTED) OutlinedButton({ onPreview(file) }) { Text(strings[MobileString.Preview]) }
                        }
                    }
                    if (file.id == state.items.lastOrNull()?.id && state.hasMore) LaunchedEffect(file.id) { onLoadMore() }
                }
                if (state.loadingMore) item { CircularProgressIndicator(Modifier.padding(16.dp)) }
                state.loadMoreError?.let { error -> item {
                    OutlinedButton(onLoadMore, Modifier.padding(16.dp).testTag("team-search-load-more-retry")) {
                        Text(strings.resolve(error) ?: strings[MobileString.RetryLoadMore])
                    }
                } }
            }
        }
    } }
    selectedUnsupported?.let { file -> ModalBottomSheet(onDismissRequest = { selectedUnsupported = null }) {
        TeamFileDetails(file, onPreview, Modifier.fillMaxWidth())
    } }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TeamPreviewScreen(
    state: TeamPreviewState,
    officeRenderer: OfficeRenderer,
    mediaRenderer: MediaPreviewRenderer,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    onPositionChanged: (Long) -> Unit,
    onRendererError: (UiMessage) -> Unit,
) {
    val strings = LocalMobileStrings.current
    var officeStatus by remember(state.teamId, state.fileId) { mutableStateOf<UiMessage?>(null) }
    BackHandler(onBack = onBack)
    Scaffold(
        modifier = Modifier.testTag("team-preview-screen"),
        topBar = { TopAppBar(
            title = { Column { Text(state.file?.fileName ?: strings[MobileString.Preview]); Text(strings[MobileString.TeamReadOnly], style = MaterialTheme.typography.labelSmall) } },
            navigationIcon = { IconButton(onBack, Modifier.testTag("team-preview-back")) { Icon(Icons.AutoMirrored.Filled.ArrowBack, strings[MobileString.BackToParent]) } },
        ) },
    ) { padding -> Box(Modifier.fillMaxSize().padding(padding), Alignment.Center) {
        when {
            state.loading -> CircularProgressIndicator()
            state.error != null -> TeamRetryPane(strings.resolve(state.error).orEmpty(), onRetry)
            state.kind == PreviewKind.TEXT -> Column(Modifier.fillMaxSize().padding(16.dp)) {
                if (state.textTruncated) Text(strings[MobileString.PreviewTruncated], color = MaterialTheme.colorScheme.secondary)
                if (state.text.isNullOrEmpty()) Box(Modifier.fillMaxSize(), Alignment.Center) { Text(strings[MobileString.PreviewEmptyText]) }
                else SelectionContainer { Text(state.text, fontFamily = FontFamily.Monospace, modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).testTag("team-text-preview")) }
            }
            state.kind == PreviewKind.OFFICE && state.office != null -> Column(Modifier.fillMaxSize()) {
                officeStatus?.let { Text(strings.resolve(it).orEmpty(), Modifier.padding(8.dp).testTag("team-office-status")) }
                officeRenderer.Render(state.office, Modifier.fillMaxSize().weight(1f), onStatus = { officeStatus = it })
            }
            state.kind in setOf(PreviewKind.IMAGE, PreviewKind.VIDEO) && state.mediaUrl != null -> mediaRenderer.Render(
                MediaPreviewRequest(
                    kind = requireNotNull(state.kind), url = state.mediaUrl,
                    fileKey = "team-${state.teamId}-${state.fileId}-${state.file?.md5Hash ?: state.file?.versionCount}",
                    initialPositionMillis = state.playbackPositionMillis,
                ), Modifier.fillMaxSize().testTag("team-media-preview"), onPositionChanged, onRendererError,
            )
            else -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Default.Description, null); Text(strings[MobileString.PreviewUnsupported]); Text(strings[MobileString.TeamReadOnly])
            }
        }
    } }
}

@Composable
private fun TeamBreadcrumb(state: TeamBrowserState, onBreadcrumb: (Long?) -> Unit) {
    val strings = LocalMobileStrings.current
    Row(Modifier.horizontalScroll(rememberScrollState()).testTag("team-breadcrumb")) {
        state.breadcrumb.forEachIndexed { index, entry ->
            if (index > 0) Text(" / ")
            Text(
                entry.name.ifEmpty { state.team?.name ?: strings[MobileString.TeamRoot] },
                color = if (index == state.breadcrumb.lastIndex) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.primary,
                modifier = Modifier.let { if (index == state.breadcrumb.lastIndex) it else it.clickable { onBreadcrumb(entry.id) } }
                    .testTag("team-breadcrumb-${entry.id ?: "root"}"),
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun TeamRetryPane(message: String, retry: () -> Unit) {
    val strings = LocalMobileStrings.current
    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Text(message, color = MaterialTheme.colorScheme.error)
        Button(retry, Modifier.testTag("team-retry")) { Text(strings[MobileString.Retry]) }
    }
}

@Composable
private fun TeamEmptyPane(title: MobileString, detail: MobileString, vararg detailArguments: Any?) {
    val strings = LocalMobileStrings.current
    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Icon(Icons.Default.Groups, null)
        Text(strings[title], style = MaterialTheme.typography.titleMedium)
        Text(strings.format(detail, *detailArguments))
    }
}

private fun TeamRole.stringKey(): MobileString = when (this) {
    TeamRole.OWNER -> MobileString.TeamRoleOwner
    TeamRole.ADMIN -> MobileString.TeamRoleAdmin
    TeamRole.EDITOR -> MobileString.TeamRoleEditor
    TeamRole.VIEWER -> MobileString.TeamRoleViewer
}

private fun PersonalFileSort.stringKey(): MobileString = when (this) {
    PersonalFileSort.NAME -> MobileString.SortName
    PersonalFileSort.UPDATED_AT -> MobileString.SortUpdated
    PersonalFileSort.SIZE -> MobileString.SortSize
}

private fun SortDirection.stringKey(): MobileString = when (this) {
    SortDirection.ASCENDING -> MobileString.SortAscending
    SortDirection.DESCENDING -> MobileString.SortDescending
}
