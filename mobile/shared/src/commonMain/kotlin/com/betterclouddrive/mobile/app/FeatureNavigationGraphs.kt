package com.betterclouddrive.mobile.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import androidx.navigation.toRoute
import com.betterclouddrive.mobile.core.model.FileItem

internal fun NavGraphBuilder.personalBrowserGraph(
    container: AppContainer,
    onOpenFolder: (FileItem) -> Unit,
    onGoRoot: () -> Unit,
    onOpenTransfers: () -> Unit,
    onOpenPreview: (FileItem) -> Unit,
    onOpenVersions: (FileItem) -> Unit,
) {
    composable<PersonalRoute> {
        PersonalDestination(container, onOpenFolder, onGoRoot, onOpenTransfers, onOpenPreview, onOpenVersions)
    }
    composable<PersonalFolderRoute> {
        PersonalDestination(container, onOpenFolder, onGoRoot, onOpenTransfers, onOpenPreview, onOpenVersions)
    }
}

@Composable
private fun PersonalDestination(
    container: AppContainer,
    onOpenFolder: (FileItem) -> Unit,
    onGoRoot: () -> Unit,
    onOpenTransfers: () -> Unit,
    onOpenPreview: (FileItem) -> Unit,
    onOpenVersions: (FileItem) -> Unit,
) {
    val state by container.browserStore.state.collectAsState()
    val favorites by container.favoritesStore.state.collectAsState()
    PersonalSpaceScreen(
        state, favorites, container.documentPicker, container.browserStore::upload, container.browserStore::download,
        onOpenFolder, container.browserStore::selectFile, container.browserStore::loadMore,
        container.browserStore::updateScrollAnchor, container.browserStore::refresh, onGoRoot,
        container.browserStore::toggleSelection, container.browserStore::clearSelection,
        container.browserStore::createFolder, container.browserStore::rename, container.browserStore::delete,
        container.browserStore::openFolderPicker, container.browserStore::openPickerFolder, container.browserStore::pickerBack,
        container.browserStore::retryFolderPicker, container.browserStore::closeFolderPicker, container.browserStore::confirmFolderPicker,
        container.browserStore::consumeFeedback, container.browserStore::consumeTransferFeedback, onOpenTransfers, onOpenPreview,
        container.favoritesStore::loadStatus, container.favoritesStore::toggle, container.tagsStore::openEditor,
        container.sharesStore::openCreator,
        onOpenVersions,
    )
}

internal fun NavGraphBuilder.personalSearchGraph(
    navController: NavHostController,
    container: AppContainer,
    onOpenLocation: (FileItem) -> Unit,
    onOpenPreview: (FileItem) -> Unit,
    onOpenVersions: (FileItem) -> Unit,
) {
    composable<PersonalSearchRoute> {
        val state by container.searchStore.state.collectAsState()
        PersonalSearchScreen(
            state, container.documentPicker, onBack = navController::popBackStack,
            onQueryChanged = container.searchStore::updateQuery,
            onSubmit = container.searchStore::submit,
            onLoadMore = container.searchStore::loadMore,
            onRetry = container.searchStore::retry,
            onScrollAnchor = container.searchStore::updateScrollAnchor,
            onOpenFolder = { navController.navigate(PersonalFolderRoute(it.id)) },
            onOpenLocation = onOpenLocation,
            onPreview = onOpenPreview,
            onDownload = container.browserStore::download,
            onCreateShare = container.sharesStore::openCreator,
            onOpenVersions = onOpenVersions,
        )
    }
}

internal fun NavGraphBuilder.personalOrganizationGraph(
    navController: NavHostController,
    container: AppContainer,
    wide: Boolean,
    onOpenFolder: (FileItem) -> Unit,
    onOpenLocation: (FileItem) -> Unit,
    onOpenPreview: (FileItem) -> Unit,
    onOpenVersions: (FileItem) -> Unit,
) {
    composable<PersonalFavoritesRoute> {
        val state by container.favoritesStore.state.collectAsState()
        LaunchedEffect(Unit) { container.favoritesStore.activate() }
        FavoritesScreen(
            state, container.documentPicker, container.favoritesStore::updateQuery,
            container.favoritesStore::refresh, container.favoritesStore::loadMore,
            container.favoritesStore::updateScrollAnchor, onOpenFolder, onOpenLocation, onOpenPreview,
            container.browserStore::download, container.favoritesStore::toggle, container.tagsStore::openEditor,
            container.sharesStore::openCreator, container.favoritesStore::consumeFeedback,
            onOpenVersions,
        )
    }
    composable<PersonalTagsRoute> {
        val state by container.tagsStore.state.collectAsState()
        LaunchedEffect(Unit) { container.tagsStore.activate() }
        Row(Modifier.fillMaxSize()) {
            Box(Modifier.weight(if (wide) 0.42f else 1f)) {
                TagsScreen(
                    state, container.tagsStore::updateQuery, container.tagsStore::refresh,
                    onOpenTag = {
                        if (wide) container.tagsStore.activateFiles(it.id)
                        else navController.navigate(PersonalTagFilesRoute(it.id))
                    },
                    container.tagsStore::create, container.tagsStore::update,
                    container.tagsStore::delete, container.tagsStore::consumeFeedback,
                )
            }
            if (wide) {
                VerticalDivider()
                Box(Modifier.weight(0.58f).testTag("tags-wide-detail")) {
                    TagFilesDestination(state.activeTagId, state.tags.firstOrNull { it.id == state.activeTagId }, container, state,
                        onOpenFolder, onOpenLocation, onOpenPreview, onOpenVersions)
                }
            }
        }
    }
    composable<PersonalTagFilesRoute> { entry ->
        val state by container.tagsStore.state.collectAsState()
        val tagId = entry.toRoute<PersonalTagFilesRoute>().tagId
        LaunchedEffect(tagId) { container.tagsStore.activate(); container.tagsStore.activateFiles(tagId) }
        TagFilesDestination(tagId, state.tags.firstOrNull { it.id == tagId }, container, state,
            onOpenFolder, onOpenLocation, onOpenPreview, onOpenVersions)
    }
}

@Composable
private fun TagFilesDestination(
    tagId: Long?,
    tag: com.betterclouddrive.mobile.core.model.PersonalTag?,
    container: AppContainer,
    state: com.betterclouddrive.mobile.feature.organization.TagsState,
    onOpenFolder: (FileItem) -> Unit,
    onOpenLocation: (FileItem) -> Unit,
    onOpenPreview: (FileItem) -> Unit,
    onOpenVersions: (FileItem) -> Unit,
) {
    if (tagId != null && state.activeTagId != tagId) return
    TagFilesScreen(
        tag, state, container.documentPicker, container.tagsStore::updateFilesQuery,
        container.tagsStore::refreshFiles, container.tagsStore::loadMoreFiles,
        container.tagsStore::updateFilesScrollAnchor, onOpenFolder, onOpenLocation, onOpenPreview,
        container.browserStore::download, container.tagsStore::openEditor, container.sharesStore::openCreator,
        container.tagsStore::removeFile, container.tagsStore::consumeFeedback,
        onOpenVersions,
    )
}

internal fun NavGraphBuilder.personalSharesGraph(
    navController: NavHostController,
    container: AppContainer,
    wide: Boolean,
    onOpenDetail: (Long) -> Unit,
) {
    composable<PersonalSharesRoute> {
        val state by container.sharesStore.state.collectAsState()
        LaunchedEffect(Unit) { container.sharesStore.activate() }
        DisposableEffect(Unit) { onDispose { container.sharesStore.closeDetail() } }
        Row(Modifier.fillMaxSize()) {
            Box(Modifier.weight(if (wide) 0.48f else 1f)) {
                SharesScreen(
                    state, container.localDateTime, container.clipboard,
                    container.sharesStore::updateQuery, container.sharesStore::setFilters,
                    container.sharesStore::refresh, container.sharesStore::loadMore,
                    onOpen = { if (wide) container.sharesStore.openDetail(it) else onOpenDetail(it) },
                    container.sharesStore::consumeFeedback,
                )
            }
            if (wide) {
                VerticalDivider()
                Box(Modifier.weight(0.52f).testTag("shares-wide-detail")) { ShareDetailDestination(container, state) }
            }
        }
    }
    composable<PersonalShareDetailRoute> { entry ->
        val state by container.sharesStore.state.collectAsState()
        val shareId = entry.toRoute<PersonalShareDetailRoute>().shareId
        var loaded by remember(shareId) { mutableStateOf(false) }
        LaunchedEffect(shareId) { container.sharesStore.openDetail(shareId) }
        LaunchedEffect(state.selected?.id, state.canceling) {
            if (state.selected?.id == shareId) loaded = true
            else if (loaded && !state.canceling) navController.popBackStack()
        }
        DisposableEffect(shareId) { onDispose { container.sharesStore.closeDetail() } }
        ShareDetailDestination(container, state)
    }
}

@Composable
private fun ShareDetailDestination(
    container: AppContainer,
    state: com.betterclouddrive.mobile.feature.shares.PersonalSharesState,
) = ShareDetailScreen(
    state, container.localDateTime, container.clipboard,
    container.sharesStore::togglePassword, container.sharesStore::update,
    container.sharesStore::cancel, container.sharesStore::consumeFeedback,
)

internal fun NavGraphBuilder.personalPreviewGraph(
    container: AppContainer,
    onClose: () -> Unit,
) {
    composable<PersonalPreviewRoute> { entry ->
        val state by container.previewStore.state.collectAsState()
        val fileId = entry.toRoute<PersonalPreviewRoute>().fileId
        LaunchedEffect(fileId) { container.previewStore.open(fileId) }
        PersonalPreviewScreen(
            state, container.documentPicker, container.officeRenderer, container.mediaPreviewRenderer,
            onClose, container.previewStore::retry, container.browserStore::download,
            container.previewStore::updatePosition, container.previewStore::reportRendererError,
        )
    }
}

internal fun NavGraphBuilder.personalRecoveryGraph(
    container: AppContainer,
    onOpenTransfers: () -> Unit,
) {
    composable<PersonalRecycleBinRoute> {
        val state by container.recycleBinStore.state.collectAsState()
        LaunchedEffect(Unit) { container.recycleBinStore.activate() }
        PersonalRecycleBinScreen(
            state,
            container.recycleBinStore::updateQuery,
            container.recycleBinStore::refresh,
            container.recycleBinStore::loadMore,
            container.recycleBinStore::restore,
            container.recycleBinStore::permanentlyDelete,
            container.recycleBinStore::empty,
            container.recycleBinStore::consumeFeedback,
        )
    }
    composable<PersonalVersionsRoute> { entry ->
        val state by container.versionsStore.state.collectAsState()
        val fileId = entry.toRoute<PersonalVersionsRoute>().fileId
        LaunchedEffect(fileId) { container.versionsStore.open(fileId) }
        PersonalVersionsScreen(
            state,
            container.documentPicker,
            container.versionsStore::retry,
            container.versionsStore::download,
            container.versionsStore::restore,
            container.versionsStore::delete,
            onOpenTransfers,
            container.versionsStore::consumeFeedback,
            container.versionsStore::consumeTransferFeedback,
        )
    }
}

internal fun NavGraphBuilder.productUtilityGraph(
    container: AppContainer,
    debugToolsEnabled: Boolean,
    onOpenDiagnostics: () -> Unit,
    onNotificationTarget: (com.betterclouddrive.mobile.feature.teams.NotificationTarget) -> Unit,
) {
    composable<NotificationsRoute> {
        val state by container.notificationsStore.state.collectAsState()
        LaunchedEffect(Unit) { container.notificationsStore.activate() }
        NotificationsScreen(
            state = state,
            onRefresh = container.notificationsStore::refresh,
            onLoadMore = container.notificationsStore::loadMore,
            onFilter = container.notificationsStore::filter,
            onOpen = { item -> container.notificationsStore.markRead(item, onNotificationTarget) },
            onMarkAllRead = container.notificationsStore::markAllRead,
            onClearFeedback = container.notificationsStore::clearFeedback,
        )
    }
    composable<TransfersRoute> {
        val state by container.transferQueue.state.collectAsState()
        TransferQueueScreen(
            state, container.transferQueue::pause, container.transferQueue::resume,
            container.transferQueue::cancel, container.transferQueue::retry, container.transferQueue::clearFinished,
        )
    }
    composable<ProfileRoute> {
        val state by container.authStore.state.collectAsState()
        LaunchedEffect(Unit) { container.authStore.refreshProfile() }
        ProfileScreen(
            state, debugToolsEnabled, onOpenDiagnostics, container.authStore::refreshProfile,
            container.authStore::updateLocale, container.authStore::setThemeMode,
            container.authStore::requestServerSwitch, container.authStore::cancelServerSwitch,
            container.coordinator::confirmServerSwitch, container.coordinator::logout,
        )
    }
    if (debugToolsEnabled) composable<DiagnosticsRoute> {
        val state by container.diagnosticsStore.state.collectAsState()
        val auth by container.authStore.state.collectAsState()
        DiagnosticsScreen(
            state, auth.user?.username.orEmpty(), container.officeRenderer,
            { container.database.transferTaskDao().count() },
            container.diagnosticsStore::loadOffice, container.diagnosticsStore::updateStatus,
        )
    }
}

internal fun NavGraphBuilder.teamWorkspaceGraph(
    navController: NavHostController,
    container: AppContainer,
    wide: Boolean,
    onOpenTransfers: () -> Unit,
    onBackToPersonal: () -> Unit,
) {
    composable<TeamsRoute> {
        val state by container.teamsStore.state.collectAsState()
        LaunchedEffect(Unit) { container.teamsStore.activate() }
        LaunchedEffect(state.createdTeam?.id) {
            state.createdTeam?.let { created ->
                container.teamsStore.consumeCreatedTeam()
                navController.navigate(TeamWorkspaceRoute(created.id))
            }
        }
        TeamsScreen(
            state = state,
            wide = wide,
            onBack = onBackToPersonal,
            onRefresh = container.teamsStore::refresh,
            onLoadMore = container.teamsStore::loadMore,
            onCreate = container.teamsStore::create,
            onClearCreateError = container.teamsStore::clearCreateError,
            onClearFeedback = container.teamsStore::clearFeedback,
            onOpenTeam = { navController.navigate(TeamWorkspaceRoute(it.id)) },
        )
    }
    composable<TeamWorkspaceRoute> { entry ->
        TeamBrowserDestination(
            teamId = entry.toRoute<TeamWorkspaceRoute>().teamId,
            folderId = null,
            navController = navController,
            container = container,
            wide = wide,
            onOpenTransfers = onOpenTransfers,
        )
    }
    composable<TeamFolderRoute> { entry ->
        val route = entry.toRoute<TeamFolderRoute>()
        TeamBrowserDestination(route.teamId, route.folderId, navController, container, wide, onOpenTransfers)
    }
    composable<TeamSearchRoute> { entry ->
        val teamId = entry.toRoute<TeamSearchRoute>().teamId
        val state by container.teamSearchStore.state.collectAsState()
        val actions by container.teamFileActionsStore.state.collectAsState()
        val auth by container.authStore.state.collectAsState()
        val browser by container.teamBrowserStore.state.collectAsState()
        val team = browser.team?.takeIf { it.id == teamId }
            ?: container.teamsStore.state.value.items.firstOrNull { it.id == teamId }
        LaunchedEffect(teamId, team?.role, auth.user?.id) {
            container.teamSearchStore.activate(teamId)
            container.teamFileActionsStore.activate(team, teamId, null)
            container.teamSharesStore.bind(
                teamId, auth.user?.id, team?.role ?: com.betterclouddrive.mobile.core.model.TeamRole.VIEWER,
            )
        }
        TeamSearchScreen(
            state = state,
            actions = actions,
            documentPicker = container.documentPicker,
            onBack = navController::popBackStack,
            onQuery = container.teamSearchStore::updateQuery,
            onSubmit = container.teamSearchStore::submit,
            onRetry = container.teamSearchStore::retry,
            onLoadMore = container.teamSearchStore::loadMore,
            onOpenFolder = { navController.navigate(TeamFolderRoute(teamId, it.id)) },
            onOpenLocation = { file ->
                container.teamBrowserStore.prepareOpenLocation(file)
                navController.navigate(file.parentId?.let { TeamFolderRoute(teamId, it) } ?: TeamWorkspaceRoute(teamId))
            },
            onPreview = { navController.navigate(TeamPreviewRoute(teamId, it.id)) },
            onDownload = container.teamFileActionsStore::download,
            onRequestPermission = container.teamFileActionsStore::requestItemPermission,
            onCreateShare = container.teamSharesStore::openCreator,
            onOpenVersions = { navController.navigate(TeamVersionsRoute(teamId, it.id)) },
        )
    }
    composable<TeamPreviewRoute> { entry ->
        val route = entry.toRoute<TeamPreviewRoute>()
        val state by container.teamPreviewStore.state.collectAsState()
        LaunchedEffect(route.teamId, route.fileId) { container.teamPreviewStore.open(route.teamId, route.fileId) }
        DisposableEffect(route.teamId, route.fileId) { onDispose { container.teamPreviewStore.close() } }
        TeamPreviewScreen(
            state = state,
            documentPicker = container.documentPicker,
            officeRenderer = container.officeRenderer,
            mediaRenderer = container.mediaPreviewRenderer,
            onBack = navController::popBackStack,
            onRetry = container.teamPreviewStore::retry,
            onPositionChanged = container.teamPreviewStore::updatePosition,
            onRendererError = container.teamPreviewStore::reportRendererError,
            onDownload = container.teamFileActionsStore::download,
            onOpenVersions = { navController.navigate(TeamVersionsRoute(route.teamId, it.id)) },
        )
    }
    composable<TeamMembersRoute> { entry ->
        val teamId = entry.toRoute<TeamMembersRoute>().teamId
        val state by container.teamMembersStore.state.collectAsState()
        val invites by container.teamInvitesStore.state.collectAsState()
        val management by container.teamManagementStore.state.collectAsState()
        val browser by container.teamBrowserStore.state.collectAsState()
        val teams by container.teamsStore.state.collectAsState()
        val listedTeam = teams.items.firstOrNull { it.id == teamId }
        val summary = management.team?.takeIf { it.id == teamId }
            ?: browser.team?.takeIf { it.id == teamId }
            ?: listedTeam
        val role = summary?.role ?: com.betterclouddrive.mobile.core.model.TeamRole.VIEWER
        LaunchedEffect(teamId, role) {
            container.teamMembersStore.activate(teamId, role)
            container.teamInvitesStore.activate(teamId, role)
            container.teamManagementStore.bind(teamId, summary)
        }
        TeamMembersScreen(
            state = state,
            invites = invites,
            wide = wide,
            onBack = navController::popBackStack,
            onRefresh = container.teamMembersStore::refresh,
            onLoadMore = container.teamMembersStore::loadMore,
            onAdd = container.teamMembersStore::add,
            onUpdateRole = container.teamMembersStore::updateRole,
            onRemove = container.teamMembersStore::remove,
            onClearAddError = container.teamMembersStore::clearAddError,
            onClearFeedback = container.teamMembersStore::clearFeedback,
            onSelectTab = container.teamInvitesStore::selectTab,
            onRefreshInvites = container.teamInvitesStore::refresh,
            onLoadMoreInvites = container.teamInvitesStore::loadMore,
            onCreateInvite = container.teamInvitesStore::create,
            onResendInvite = container.teamInvitesStore::resend,
            onCancelInvite = container.teamInvitesStore::cancel,
            onClearInviteError = container.teamInvitesStore::clearCreateError,
            onClearInviteFeedback = container.teamInvitesStore::clearFeedback,
            management = management,
            onTransferOwnership = container.teamManagementStore::transferOwner,
            onClearManagementFeedback = container.teamManagementStore::clearFeedback,
        )
    }
    composable<TeamSettingsRoute> { entry ->
        val teamId = entry.toRoute<TeamSettingsRoute>().teamId
        val state by container.teamManagementStore.state.collectAsState()
        val browser by container.teamBrowserStore.state.collectAsState()
        val teams by container.teamsStore.state.collectAsState()
        val summary = browser.team?.takeIf { it.id == teamId } ?: teams.items.firstOrNull { it.id == teamId }
        LaunchedEffect(teamId) { container.teamManagementStore.activate(teamId, summary) }
        LaunchedEffect(state.departedTeamId) {
            if (state.departedTeamId == teamId) {
                navController.navigate(TeamsRoute) {
                    popUpTo<TeamsRoute> { inclusive = false }
                    launchSingleTop = true
                }
                container.teamManagementStore.consumeDeparture()
            }
        }
        TeamSettingsScreen(
            state = state,
            wide = wide,
            onBack = navController::popBackStack,
            onRefresh = container.teamManagementStore::refresh,
            onRename = container.teamManagementStore::rename,
            onLeave = container.teamManagementStore::leave,
            onDelete = container.teamManagementStore::delete,
            onClearNameError = container.teamManagementStore::clearNameError,
            onClearFeedback = container.teamManagementStore::clearFeedback,
            onOpenAudit = { navController.navigate(TeamAuditRoute(teamId)) },
            onOpenTasks = { navController.navigate(TeamTasksRoute(teamId)) },
        )
    }
    composable<TeamAuditRoute> { entry ->
        val teamId = entry.toRoute<TeamAuditRoute>().teamId
        val state by container.teamAuditStore.state.collectAsState()
        val management by container.teamManagementStore.state.collectAsState()
        val browser by container.teamBrowserStore.state.collectAsState()
        val teams by container.teamsStore.state.collectAsState()
        val role = management.team?.takeIf { it.id == teamId }?.role
            ?: browser.team?.takeIf { it.id == teamId }?.role
            ?: teams.items.firstOrNull { it.id == teamId }?.role
            ?: com.betterclouddrive.mobile.core.model.TeamRole.VIEWER
        LaunchedEffect(teamId, role) { container.teamAuditStore.activate(teamId, role) }
        LaunchedEffect(state.queuedTaskId) {
            state.queuedTaskId?.let { taskId ->
                navController.navigate(TeamTasksRoute(teamId, taskId))
                container.teamAuditStore.consumeQueuedTask()
            }
        }
        TeamAuditScreen(
            state = state,
            wide = wide,
            dateTime = container.localDateTime,
            onBack = navController::popBackStack,
            onRefresh = container.teamAuditStore::refresh,
            onActor = container.teamAuditStore::updateActorUserId,
            onAction = container.teamAuditStore::updateAction,
            onTargetType = container.teamAuditStore::updateTargetType,
            onResult = container.teamAuditStore::updateResult,
            onStartAt = container.teamAuditStore::updateStartAt,
            onEndAt = container.teamAuditStore::updateEndAt,
            onApply = container.teamAuditStore::applyFilters,
            onReset = container.teamAuditStore::resetFilters,
            onExport = container.teamAuditStore::export,
            onLoadMore = container.teamAuditStore::loadMore,
            onConsumeFeedback = container.teamAuditStore::consumeFeedback,
        )
    }
    composable<TeamTasksRoute> { entry ->
        val route = entry.toRoute<TeamTasksRoute>()
        val state by container.teamTasksStore.state.collectAsState()
        val management by container.teamManagementStore.state.collectAsState()
        val browser by container.teamBrowserStore.state.collectAsState()
        val teams by container.teamsStore.state.collectAsState()
        val role = management.team?.takeIf { it.id == route.teamId }?.role
            ?: browser.team?.takeIf { it.id == route.teamId }?.role
            ?: teams.items.firstOrNull { it.id == route.teamId }?.role
            ?: com.betterclouddrive.mobile.core.model.TeamRole.VIEWER
        LaunchedEffect(route.teamId, role, route.highlightedTaskId) {
            container.teamTasksStore.activate(route.teamId, role, route.highlightedTaskId)
        }
        DisposableEffect(route.teamId) {
            container.teamTasksStore.setVisible(true)
            onDispose { container.teamTasksStore.setVisible(false) }
        }
        TeamTasksScreen(
            state = state,
            documentPicker = container.documentPicker,
            onBack = navController::popBackStack,
            onRefresh = container.teamTasksStore::refresh,
            onStatus = container.teamTasksStore::setStatus,
            onType = container.teamTasksStore::setType,
            onPrevious = container.teamTasksStore::previousPage,
            onNext = container.teamTasksStore::nextPage,
            onRetry = container.teamTasksStore::retry,
            onDownload = container.teamTasksStore::download,
            onConsumeFeedback = container.teamTasksStore::consumeFeedback,
        )
    }
    composable<TeamFavoritesRoute> { entry ->
        val teamId = entry.toRoute<TeamFavoritesRoute>().teamId
        val state by container.teamFavoritesStore.state.collectAsState()
        val actions by container.teamFileActionsStore.state.collectAsState()
        val browser by container.teamBrowserStore.state.collectAsState()
        val teams by container.teamsStore.state.collectAsState()
        val auth by container.authStore.state.collectAsState()
        val team = browser.team?.takeIf { it.id == teamId }
            ?: teams.items.firstOrNull { it.id == teamId }
        LaunchedEffect(teamId, team?.role, auth.user?.id) {
            container.teamFavoritesStore.activate(teamId)
            container.teamTagsStore.bind(teamId, team?.role ?: com.betterclouddrive.mobile.core.model.TeamRole.VIEWER)
            container.teamFileActionsStore.activate(team, teamId, null)
            container.teamSharesStore.bind(teamId, auth.user?.id, team?.role ?: com.betterclouddrive.mobile.core.model.TeamRole.VIEWER)
        }
        FavoritesScreen(
            state = state,
            documentPicker = container.documentPicker,
            onQuery = container.teamFavoritesStore::updateQuery,
            onRefresh = container.teamFavoritesStore::refresh,
            onLoadMore = container.teamFavoritesStore::loadMore,
            onScrollAnchor = container.teamFavoritesStore::updateScrollAnchor,
            onOpenFolder = { navController.navigate(TeamFolderRoute(teamId, it.id)) },
            onOpenLocation = { file ->
                container.teamBrowserStore.prepareOpenLocation(file)
                navController.navigate(file.parentId?.let { TeamFolderRoute(teamId, it) } ?: TeamWorkspaceRoute(teamId))
            },
            onPreview = { navController.navigate(TeamPreviewRoute(teamId, it.id)) },
            onDownload = container.teamFileActionsStore::download,
            onToggleFavorite = container.teamFavoritesStore::toggle,
            onManageTags = container.teamTagsStore::openEditor,
            onCreateShare = container.teamSharesStore::openCreator,
            onConsumeFeedback = container.teamFavoritesStore::consumeFeedback,
            onOpenVersions = { navController.navigate(TeamVersionsRoute(teamId, it.id)) },
            onMenuOpen = { container.teamFileActionsStore.requestItemPermission(it) },
            canManageTags = { actions.itemPermissions[it.id]?.canWrite == true },
            canCreateShare = { actions.itemPermissions[it.id]?.canShare == true },
            onBack = navController::popBackStack,
        )
    }
    composable<TeamTagsRoute> { entry ->
        val teamId = entry.toRoute<TeamTagsRoute>().teamId
        val state by container.teamTagsStore.state.collectAsState()
        val canManage by container.teamTagsStore.canManage.collectAsState()
        val browser by container.teamBrowserStore.state.collectAsState()
        val teams by container.teamsStore.state.collectAsState()
        val auth by container.authStore.state.collectAsState()
        val team = browser.team?.takeIf { it.id == teamId }
            ?: teams.items.firstOrNull { it.id == teamId }
        LaunchedEffect(teamId, team?.role, auth.user?.id) {
            container.teamTagsStore.activate(teamId, team?.role ?: com.betterclouddrive.mobile.core.model.TeamRole.VIEWER)
            container.teamSharesStore.bind(teamId, auth.user?.id, team?.role ?: com.betterclouddrive.mobile.core.model.TeamRole.VIEWER)
        }
        Row(Modifier.fillMaxSize()) {
            Box(Modifier.weight(if (wide) 0.42f else 1f)) {
                TagsScreen(
                    state = state,
                    onQuery = container.teamTagsStore::updateQuery,
                    onRefresh = container.teamTagsStore::refresh,
                    onOpenTag = {
                        if (wide) container.teamTagsStore.activateFiles(it.id)
                        else navController.navigate(TeamTagFilesRoute(teamId, it.id))
                    },
                    onCreate = container.teamTagsStore::create,
                    onUpdate = container.teamTagsStore::update,
                    onDelete = container.teamTagsStore::delete,
                    onConsumeFeedback = container.teamTagsStore::consumeFeedback,
                    canManage = canManage,
                    showFileCount = false,
                    onBack = navController::popBackStack,
                )
            }
            if (wide) {
                VerticalDivider()
                Box(Modifier.weight(0.58f).testTag("team-tags-wide-detail")) {
                    TeamTagFilesDestination(teamId, state.activeTagId, container, state, navController)
                }
            }
        }
    }
    composable<TeamTagFilesRoute> { entry ->
        val route = entry.toRoute<TeamTagFilesRoute>()
        val state by container.teamTagsStore.state.collectAsState()
        val browser by container.teamBrowserStore.state.collectAsState()
        val teams by container.teamsStore.state.collectAsState()
        val auth by container.authStore.state.collectAsState()
        val team = browser.team?.takeIf { it.id == route.teamId }
            ?: teams.items.firstOrNull { it.id == route.teamId }
        LaunchedEffect(route.teamId, route.tagId, team?.role, auth.user?.id) {
            container.teamTagsStore.activate(route.teamId, team?.role ?: com.betterclouddrive.mobile.core.model.TeamRole.VIEWER)
            container.teamTagsStore.activateFiles(route.tagId)
            container.teamFileActionsStore.activate(team, route.teamId, null)
            container.teamSharesStore.bind(route.teamId, auth.user?.id, team?.role ?: com.betterclouddrive.mobile.core.model.TeamRole.VIEWER)
        }
        TeamTagFilesDestination(route.teamId, route.tagId, container, state, navController)
    }
    composable<TeamSharesRoute> { entry ->
        val teamId = entry.toRoute<TeamSharesRoute>().teamId
        val state by container.teamSharesStore.state.collectAsState()
        val auth by container.authStore.state.collectAsState()
        val browser by container.teamBrowserStore.state.collectAsState()
        val teams by container.teamsStore.state.collectAsState()
        val role = browser.team?.takeIf { it.id == teamId }?.role
            ?: teams.items.firstOrNull { it.id == teamId }?.role
            ?: com.betterclouddrive.mobile.core.model.TeamRole.VIEWER
        LaunchedEffect(teamId, auth.user?.id, role) {
            container.teamSharesStore.activate(teamId, auth.user?.id, role)
        }
        DisposableEffect(teamId) { onDispose { container.teamSharesStore.closeDetail() } }
        Row(Modifier.fillMaxSize()) {
            Box(Modifier.weight(if (wide) 0.48f else 1f)) {
                SharesScreen(
                    state = state,
                    dateTime = container.localDateTime,
                    clipboard = container.clipboard,
                    onQuery = container.teamSharesStore::updateQuery,
                    onFilters = container.teamSharesStore::setFilters,
                    onRefresh = container.teamSharesStore::refresh,
                    onLoadMore = container.teamSharesStore::loadMore,
                    onOpen = {
                        if (wide) container.teamSharesStore.openDetail(it)
                        else navController.navigate(TeamShareDetailRoute(teamId, it))
                    },
                    onConsumeFeedback = container.teamSharesStore::consumeFeedback,
                    onBack = navController::popBackStack,
                )
            }
            if (wide) {
                VerticalDivider()
                Box(Modifier.weight(0.52f).testTag("team-shares-wide-detail")) {
                    TeamShareDetailDestination(container, state)
                }
            }
        }
    }
    composable<TeamShareDetailRoute> { entry ->
        val route = entry.toRoute<TeamShareDetailRoute>()
        val state by container.teamSharesStore.state.collectAsState()
        val auth by container.authStore.state.collectAsState()
        val browser by container.teamBrowserStore.state.collectAsState()
        val teams by container.teamsStore.state.collectAsState()
        val role = browser.team?.takeIf { it.id == route.teamId }?.role
            ?: teams.items.firstOrNull { it.id == route.teamId }?.role
            ?: com.betterclouddrive.mobile.core.model.TeamRole.VIEWER
        var loaded by remember(route.teamId, route.shareId) { mutableStateOf(false) }
        LaunchedEffect(route.teamId, route.shareId, auth.user?.id, role) {
            container.teamSharesStore.activate(route.teamId, auth.user?.id, role)
            container.teamSharesStore.openDetail(route.shareId)
        }
        LaunchedEffect(state.selected?.id, state.canceling) {
            if (state.selected?.id == route.shareId) loaded = true
            else if (loaded && !state.canceling) navController.popBackStack()
        }
        DisposableEffect(route.teamId, route.shareId) { onDispose { container.teamSharesStore.closeDetail() } }
        TeamShareDetailDestination(container, state, navController::popBackStack)
    }
    composable<TeamRecycleBinRoute> { entry ->
        val teamId = entry.toRoute<TeamRecycleBinRoute>().teamId
        val state by container.teamRecycleBinStore.state.collectAsState()
        val browser by container.teamBrowserStore.state.collectAsState()
        val teams by container.teamsStore.state.collectAsState()
        val role = browser.team?.takeIf { it.id == teamId }?.role
            ?: teams.items.firstOrNull { it.id == teamId }?.role
            ?: com.betterclouddrive.mobile.core.model.TeamRole.VIEWER
        LaunchedEffect(teamId, role) { container.teamRecycleBinStore.activate(teamId, role) }
        TeamRecycleBinScreen(
            state = state,
            onQuery = container.teamRecycleBinStore::updateQuery,
            onRefresh = container.teamRecycleBinStore::refresh,
            onLoadMore = container.teamRecycleBinStore::loadMore,
            onRequestPermission = container.teamRecycleBinStore::requestPermission,
            onRestore = container.teamRecycleBinStore::restore,
            onPermanentlyDelete = container.teamRecycleBinStore::permanentlyDelete,
            onEmpty = container.teamRecycleBinStore::empty,
            onConsumeFeedback = container.teamRecycleBinStore::consumeFeedback,
            onClearPermissionError = container.teamRecycleBinStore::clearPermissionError,
        )
    }
    composable<TeamVersionsRoute> { entry ->
        val route = entry.toRoute<TeamVersionsRoute>()
        val state by container.teamVersionsStore.state.collectAsState()
        LaunchedEffect(route.teamId, route.fileId) { container.teamVersionsStore.open(route.teamId, route.fileId) }
        TeamVersionsScreen(
            state = state,
            documentPicker = container.documentPicker,
            onRetry = container.teamVersionsStore::retry,
            onDownload = container.teamVersionsStore::download,
            onRestore = container.teamVersionsStore::restore,
            onDelete = container.teamVersionsStore::delete,
            onOpenTransfers = onOpenTransfers,
            onConsumeFeedback = container.teamVersionsStore::consumeFeedback,
            onConsumeTransferFeedback = container.teamVersionsStore::consumeTransferFeedback,
        )
    }
}

@Composable
private fun TeamShareDetailDestination(
    container: AppContainer,
    state: com.betterclouddrive.mobile.feature.shares.TeamSharesState,
    onBack: (() -> Unit)? = null,
) = ShareDetailScreen(
    state = state,
    dateTime = container.localDateTime,
    clipboard = container.clipboard,
    onRevealPassword = container.teamSharesStore::togglePassword,
    onUpdate = container.teamSharesStore::update,
    onCancel = container.teamSharesStore::cancel,
    onConsumeFeedback = container.teamSharesStore::consumeFeedback,
    onBack = onBack,
)

@Composable
private fun TeamTagFilesDestination(
    teamId: Long,
    tagId: Long?,
    container: AppContainer,
    state: com.betterclouddrive.mobile.feature.organization.TagsState,
    navController: NavHostController,
) {
    val actions by container.teamFileActionsStore.state.collectAsState()
    if (tagId != null && state.activeTagId != tagId) return
    TagFilesScreen(
        tag = state.tags.firstOrNull { it.id == tagId },
        state = state,
        documentPicker = container.documentPicker,
        onQuery = container.teamTagsStore::updateFilesQuery,
        onRefresh = container.teamTagsStore::refreshFiles,
        onLoadMore = container.teamTagsStore::loadMoreFiles,
        onScrollAnchor = container.teamTagsStore::updateFilesScrollAnchor,
        onOpenFolder = { navController.navigate(TeamFolderRoute(teamId, it.id)) },
        onOpenLocation = { file ->
            container.teamBrowserStore.prepareOpenLocation(file)
            navController.navigate(file.parentId?.let { TeamFolderRoute(teamId, it) } ?: TeamWorkspaceRoute(teamId))
        },
        onPreview = { navController.navigate(TeamPreviewRoute(teamId, it.id)) },
        onDownload = container.teamFileActionsStore::download,
        onManageTags = container.teamTagsStore::openEditor,
        onCreateShare = container.teamSharesStore::openCreator,
        onRemove = container.teamTagsStore::removeFile,
        onConsumeFeedback = container.teamTagsStore::consumeFeedback,
        onOpenVersions = { navController.navigate(TeamVersionsRoute(teamId, it.id)) },
        onMenuOpen = container.teamFileActionsStore::requestItemPermission,
        canManageTags = { actions.itemPermissions[it.id]?.canWrite == true },
        canCreateShare = { actions.itemPermissions[it.id]?.canShare == true },
        onBack = navController::popBackStack,
    )
}

@Composable
private fun TeamBrowserDestination(
    teamId: Long,
    folderId: Long?,
    navController: NavHostController,
    container: AppContainer,
    wide: Boolean,
    onOpenTransfers: () -> Unit,
) {
    val state by container.teamBrowserStore.state.collectAsState()
    val actions by container.teamFileActionsStore.state.collectAsState()
    val permissions by container.teamPermissionsStore.state.collectAsState()
    val favorites by container.teamFavoritesStore.state.collectAsState()
    val auth by container.authStore.state.collectAsState()
    LaunchedEffect(teamId, folderId) { container.teamBrowserStore.activate(teamId, folderId) }
    LaunchedEffect(teamId, folderId, state.team?.role, auth.user?.id) {
        container.teamFileActionsStore.activate(state.team, teamId, folderId)
        container.teamFavoritesStore.bind(teamId)
        container.teamTagsStore.bind(teamId, state.team?.role ?: com.betterclouddrive.mobile.core.model.TeamRole.VIEWER)
        container.teamSharesStore.bind(teamId, auth.user?.id, state.team?.role ?: com.betterclouddrive.mobile.core.model.TeamRole.VIEWER)
    }
    TeamBrowserScreen(
        state = state,
        actions = actions,
        permissions = permissions,
        wide = wide,
        actorUserId = auth.user?.id,
        documentPicker = container.documentPicker,
        onBack = navController::popBackStack,
        onBackToTeams = {
            navController.navigate(TeamsRoute) {
                popUpTo<TeamsRoute> { inclusive = false }
                launchSingleTop = true
            }
        },
        onBreadcrumb = { target ->
            container.teamFileActionsStore.clearTransientUi()
            val route: AppRoute = target?.let { TeamFolderRoute(teamId, it) } ?: TeamWorkspaceRoute(teamId)
            navController.navigate(route) { launchSingleTop = true }
        },
        onOpenFolder = { file ->
            container.teamFileActionsStore.clearTransientUi()
            container.teamBrowserStore.openFolder(file)
            navController.navigate(TeamFolderRoute(teamId, file.id))
        },
        onOpenSearch = { navController.navigate(TeamSearchRoute(teamId)) },
        onOpenMembers = { navController.navigate(TeamMembersRoute(teamId)) },
        onOpenSettings = { navController.navigate(TeamSettingsRoute(teamId)) },
        onOpenFavorites = { navController.navigate(TeamFavoritesRoute(teamId)) },
        onOpenTags = { navController.navigate(TeamTagsRoute(teamId)) },
        onRetryWorkspace = { container.teamBrowserStore.activate(teamId, folderId) },
        onRefresh = { container.teamFileActionsStore.clearSelection(); container.teamBrowserStore.refresh() },
        onLoadMore = container.teamBrowserStore::loadMore,
        onSort = { sort, direction -> container.teamFileActionsStore.clearSelection(); container.teamBrowserStore.changeSort(sort, direction) },
        onViewMode = container.teamBrowserStore::changeViewMode,
        onSelect = container.teamBrowserStore::selectFile,
        onPreview = { navController.navigate(TeamPreviewRoute(teamId, it.id)) },
        onRequestPermission = container.teamFileActionsStore::requestItemPermission,
        onToggleSelection = container.teamFileActionsStore::toggleSelection,
        onClearSelection = container.teamFileActionsStore::clearSelection,
        onCreateFolder = container.teamFileActionsStore::createFolder,
        onRename = container.teamFileActionsStore::rename,
        onDelete = container.teamFileActionsStore::delete,
        onOpenMovePicker = container.teamFileActionsStore::openMovePicker,
        onOpenPickerFolder = container.teamFileActionsStore::openPickerFolder,
        onPickerBack = container.teamFileActionsStore::pickerBack,
        onRetryPicker = container.teamFileActionsStore::retryPicker,
        onClosePicker = container.teamFileActionsStore::closePicker,
        onConfirmMove = container.teamFileActionsStore::confirmMove,
        onUpload = container.teamFileActionsStore::upload,
        onDownload = container.teamFileActionsStore::download,
        onConsumeFeedback = container.teamFileActionsStore::consumeFeedback,
        onConsumeTransferFeedback = container.teamFileActionsStore::consumeTransferFeedback,
        onClearPermissionError = container.teamFileActionsStore::clearPermissionError,
        onManagePermissions = { container.teamPermissionsStore.open(teamId, it) },
        onRetryPermissions = container.teamPermissionsStore::retry,
        onSavePermission = container.teamPermissionsStore::save,
        onDeletePermission = container.teamPermissionsStore::delete,
        onClosePermissions = container.teamPermissionsStore::close,
        onClearPermissionsFeedback = container.teamPermissionsStore::clearFeedback,
        onOpenTransfers = onOpenTransfers,
        favorites = favorites,
        onLoadFavoriteStatus = container.teamFavoritesStore::loadStatus,
        onToggleFavorite = container.teamFavoritesStore::toggle,
        onConsumeFavoriteFeedback = container.teamFavoritesStore::consumeFeedback,
        onManageTags = container.teamTagsStore::openEditor,
        onCreateShare = container.teamSharesStore::openCreator,
        onOpenShares = { navController.navigate(TeamSharesRoute(teamId)) },
        onOpenVersions = { navController.navigate(TeamVersionsRoute(teamId, it.id)) },
        onOpenRecycleBin = { navController.navigate(TeamRecycleBinRoute(teamId)) },
    )
}
