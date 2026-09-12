package com.betterclouddrive.mobile.app

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Label
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.navigation.NavDestination
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.betterclouddrive.mobile.core.model.FileItem
import com.betterclouddrive.mobile.core.localization.LocalMobileStrings
import com.betterclouddrive.mobile.core.localization.MobileString
import com.betterclouddrive.mobile.core.localization.MobileStrings
import com.betterclouddrive.mobile.core.localization.MobileLocale
import com.betterclouddrive.mobile.core.localization.UiMessage
import com.betterclouddrive.mobile.core.platform.ThemeMode
import com.betterclouddrive.mobile.feature.files.PersonalBrowserStore
import com.betterclouddrive.mobile.feature.files.PersonalFilesState
import com.betterclouddrive.mobile.feature.transfers.TransferStatus
import com.betterclouddrive.mobile.feature.files.FileViewMode
import com.betterclouddrive.mobile.feature.files.PersonalFileSort
import com.betterclouddrive.mobile.feature.files.SortDirection

private data class TopLevelItem(
    val route: AppRoute,
    val label: MobileString,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector,
    val testTag: String,
)

private val topLevelItems = listOf(
    TopLevelItem(PersonalRoute, MobileString.Personal, Icons.Filled.Folder, Icons.Outlined.Folder, "nav-personal"),
    TopLevelItem(TeamsRoute, MobileString.Teams, Icons.Filled.Groups, Icons.Outlined.Groups, "nav-teams"),
    TopLevelItem(TransfersRoute, MobileString.Transfers, Icons.Filled.Sync, Icons.Outlined.Sync, "nav-transfers"),
    TopLevelItem(ProfileRoute, MobileString.Profile, Icons.Filled.Person, Icons.Outlined.Person, "nav-profile"),
)

@Composable
fun ProductShell(
    container: AppContainer,
    debugToolsEnabled: Boolean,
    wideLayoutOverride: Boolean? = null,
    initialTeamId: Long? = null,
    onInitialTeamConsumed: () -> Unit = {},
) {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val destination = backStackEntry?.destination
    val transfers by container.transferQueue.state.collectAsState()
    val notifications by container.notificationsStore.state.collectAsState()
    val isDiagnostics = destination.matches(DiagnosticsRoute)
    val isPreview = destination?.hasRoute<PersonalPreviewRoute>() == true || destination?.hasRoute<TeamPreviewRoute>() == true
    val isSearch = destination?.hasRoute<PersonalSearchRoute>() == true || destination?.hasRoute<TeamSearchRoute>() == true
    val isTeamFeature = destination.isTeam()
    val isNotifications = destination?.hasRoute<NotificationsRoute>() == true
    var notificationSource by rememberSaveable { mutableStateOf("personal") }

    LaunchedEffect(initialTeamId) {
        initialTeamId?.let {
            navController.navigate(TeamWorkspaceRoute(it)) { launchSingleTop = true }
            onInitialTeamConsumed()
        }
    }

    LaunchedEffect(backStackEntry) {
        when {
            destination?.hasRoute<PersonalRoute>() == true -> container.browserStore.activate(null)
            destination?.hasRoute<PersonalFolderRoute>() == true -> {
                container.browserStore.activate(backStackEntry!!.toRoute<PersonalFolderRoute>().folderId)
            }
        }
    }

    fun goPersonalRoot() {
        navController.navigate(PersonalRoute) {
            popUpTo<PersonalRoute> { inclusive = false }
            launchSingleTop = true
        }
    }

    fun goTeamsRoot() {
        navController.navigate(TeamsRoute) {
            popUpTo<TeamsRoute> { inclusive = false }
            launchSingleTop = true
        }
    }

    fun navigateTopLevel(route: AppRoute) {
        if (route == PersonalRoute && destination.isPersonal() && destination?.hasRoute<PersonalRoute>() != true) {
            goPersonalRoot()
            return
        }
        if (route == TeamsRoute && destination.isTeam() && destination?.hasRoute<TeamsRoute>() != true) {
            goTeamsRoot()
            return
        }
        container.browserStore.clearTransientUi()
        navController.navigate(route) {
            popUpTo<PersonalRoute> { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }

    fun openFolder(file: FileItem) {
        container.browserStore.openFolder(file)
        navController.navigate(PersonalFolderRoute(file.id))
    }

    fun openBreadcrumb(folderId: Long?) {
        if (folderId == null) {
            goPersonalRoot()
        } else if (!navController.popBackStack(PersonalFolderRoute(folderId), inclusive = false)) {
            navController.navigate(PersonalFolderRoute(folderId)) {
                popUpTo<PersonalRoute> { inclusive = false }
                launchSingleTop = true
            }
        }
    }

    fun openTransfers() = navigateTopLevel(TransfersRoute)
    fun openSearch() = navController.navigate(PersonalSearchRoute) { launchSingleTop = true }
    fun openFavorites() = navController.navigate(PersonalFavoritesRoute) { launchSingleTop = true }
    fun openTags() = navController.navigate(PersonalTagsRoute) { launchSingleTop = true }
    fun openShares() = navController.navigate(PersonalSharesRoute) { launchSingleTop = true }
    fun openRecycleBin() = navController.navigate(PersonalRecycleBinRoute) { launchSingleTop = true }
    fun openNotifications() {
        notificationSource = when {
            destination.isTeam() -> "teams"
            destination.matches(TransfersRoute) -> "transfers"
            destination.matches(ProfileRoute) -> "profile"
            else -> "personal"
        }
        navController.navigate(NotificationsRoute) { launchSingleTop = true }
    }
    val selectedTopLevelOverride = if (isNotifications) when (notificationSource) {
        "teams" -> TeamsRoute
        "transfers" -> TransfersRoute
        "profile" -> ProfileRoute
        else -> PersonalRoute
    } else null
    fun openVersions(file: FileItem) = navController.navigate(PersonalVersionsRoute(file.id))
    fun openPreview(file: FileItem) = navController.navigate(PersonalPreviewRoute(file.id))
    fun closePreview() {
        container.previewStore.close()
        navController.popBackStack()
    }
    fun openSearchLocation(file: FileItem) {
        container.browserStore.prepareOpenLocation(file)
        val route: AppRoute = file.parentId?.let(::PersonalFolderRoute) ?: PersonalRoute
        navController.navigate(route) { launchSingleTop = true }
    }

    @Composable
    fun Content(modifier: Modifier, wide: Boolean) {
        AppNavHost(
            navController, modifier, container, debugToolsEnabled, wide,
            onOpenFolder = ::openFolder,
            onGoRoot = ::goPersonalRoot,
            onOpenDiagnostics = { navController.navigate(DiagnosticsRoute) },
            onOpenTransfers = ::openTransfers,
            onOpenSearch = ::openSearch,
            onOpenPreview = ::openPreview,
            onClosePreview = ::closePreview,
            onOpenSearchLocation = ::openSearchLocation,
            onOpenShareDetail = { navController.navigate(PersonalShareDetailRoute(it)) },
            onOpenVersions = ::openVersions,
            onNotificationTarget = { target ->
                when (target) {
                    is com.betterclouddrive.mobile.feature.teams.NotificationTarget.Invite -> container.incomingInviteStore.open(target.token)
                    is com.betterclouddrive.mobile.feature.teams.NotificationTarget.TeamRoot -> navController.navigate(TeamWorkspaceRoute(target.teamId))
                    is com.betterclouddrive.mobile.feature.teams.NotificationTarget.TeamFolder -> navController.navigate(TeamFolderRoute(target.teamId, target.folderId))
                    is com.betterclouddrive.mobile.feature.teams.NotificationTarget.TeamMembers -> navController.navigate(TeamMembersRoute(target.teamId))
                    com.betterclouddrive.mobile.feature.teams.NotificationTarget.Unsupported -> Unit
                }
            },
        )
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = wideLayoutOverride ?: (maxWidth >= 600.dp)
        if (wide && !isDiagnostics && !isPreview) {
            Row(Modifier.fillMaxSize().testTag("product-shell-wide")) {
                MainNavigationRail(destination, selectedTopLevelOverride, transfers.activeCount, ::navigateTopLevel)
                ShellScaffold(
                    destination, container.browserStore,
                    onBack = navController::popBackStack,
                    onBreadcrumb = ::openBreadcrumb,
                    onOpenSearch = ::openSearch,
                    onOpenFavorites = ::openFavorites,
                    onOpenTags = ::openTags,
                    onOpenShares = ::openShares,
                    onOpenRecycleBin = ::openRecycleBin,
                    onOpenNotifications = ::openNotifications,
                    unreadNotifications = notifications.unreadCount,
                    showTopBar = !isSearch && !isTeamFeature,
                    modifier = Modifier.weight(1f),
                    content = { Content(it, true) },
                )
            }
        } else {
            ShellScaffold(
                destination, container.browserStore,
                onBack = navController::popBackStack,
                onBreadcrumb = ::openBreadcrumb,
                onOpenSearch = ::openSearch,
                onOpenFavorites = ::openFavorites,
                onOpenTags = ::openTags,
                onOpenShares = ::openShares,
                onOpenRecycleBin = ::openRecycleBin,
                onOpenNotifications = ::openNotifications,
                unreadNotifications = notifications.unreadCount,
                showTopBar = !isSearch && !isPreview && !isTeamFeature,
                bottomBar = { if (!isDiagnostics && !isPreview) MainNavigationBar(destination, selectedTopLevelOverride, transfers.activeCount, ::navigateTopLevel) },
                modifier = Modifier.fillMaxSize().testTag("product-shell-compact"),
                content = { Content(it, false) },
            )
        }
        if (isTeamFeature && !isPreview && !isDiagnostics && !isNotifications) {
            Box(Modifier.align(Alignment.TopEnd).padding(top = 4.dp, end = 8.dp)) {
                NotificationBell(notifications.unreadCount, ::openNotifications)
            }
        }
        val editor by container.tagsStore.editor.collectAsState()
        editor?.let {
            FileTagEditorOverlay(
                editor = it,
                wide = wide,
                onToggle = container.tagsStore::toggleEditorTag,
                onSave = container.tagsStore::saveEditor,
                onCreate = container.tagsStore::createInEditor,
                onDismiss = container.tagsStore::closeEditor,
            )
        }
        val teamEditor by container.teamTagsStore.editor.collectAsState()
        val canCreateTeamTags by container.teamTagsStore.canManage.collectAsState()
        teamEditor?.let {
            FileTagEditorOverlay(
                editor = it,
                wide = wide,
                onToggle = container.teamTagsStore::toggleEditorTag,
                onSave = container.teamTagsStore::saveEditor,
                onCreate = container.teamTagsStore::createInEditor,
                onDismiss = container.teamTagsStore::closeEditor,
                canCreate = canCreateTeamTags,
            )
        }
        val shares by container.sharesStore.state.collectAsState()
        ShareCreatorOverlay(
            shares, wide, container.localDateTime, container.clipboard,
            container.sharesStore::create, container.sharesStore::closeCreator,
        )
        val teamShares by container.teamSharesStore.state.collectAsState()
        ShareCreatorOverlay(
            teamShares, wide, container.localDateTime, container.clipboard,
            container.teamSharesStore::create, container.teamSharesStore::closeCreator,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ShellScaffold(
    destination: NavDestination?,
    browser: PersonalBrowserStore,
    onBack: () -> Unit,
    onBreadcrumb: (Long?) -> Unit,
    onOpenSearch: () -> Unit,
    onOpenFavorites: () -> Unit,
    onOpenTags: () -> Unit,
    onOpenShares: () -> Unit,
    onOpenRecycleBin: () -> Unit,
    onOpenNotifications: () -> Unit,
    unreadNotifications: Long,
    showTopBar: Boolean = true,
    modifier: Modifier = Modifier,
    bottomBar: @Composable () -> Unit = {},
    content: @Composable (Modifier) -> Unit,
) {
    val strings = LocalMobileStrings.current
    val browserState by browser.state.collectAsState()
    Scaffold(
        modifier = modifier,
        topBar = {
            if (showTopBar) TopAppBar(
                title = {
                    if (destination.isPersonalDirectory()) {
                        BreadcrumbBar(browserState, onBreadcrumb)
                    } else {
                        Text(destination.title(strings))
                    }
                },
                navigationIcon = {
                    when {
                        destination.matches(DiagnosticsRoute) -> BackButton(onBack, strings[MobileString.BackToProfile], "diagnostics-back")
                        destination.matches(NotificationsRoute) -> BackButton(onBack, strings[MobileString.BackToParent], "notifications-back")
                        destination.isPersonalOrganization() -> BackButton(onBack, strings[MobileString.BackToParent], "organization-back")
                        destination.isPersonalFolder() -> BackButton(onBack, strings[MobileString.BackToParent], "personal-parent")
                    }
                },
                actions = {
                    if (destination.isPersonalDirectory()) PersonalTopActions(
                        browserState, browser, onOpenSearch, onOpenFavorites, onOpenTags, onOpenShares, onOpenRecycleBin,
                    )
                    if (!destination.matches(NotificationsRoute)) NotificationBell(unreadNotifications, onOpenNotifications)
                },
            )
        },
        bottomBar = bottomBar,
    ) { padding -> content(Modifier.fillMaxSize().padding(padding)) }
}

@Composable
private fun BackButton(onClick: () -> Unit, description: String, tag: String) {
    IconButton(onClick = onClick, modifier = Modifier.testTag(tag)) {
        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = description)
    }
}

@Composable
private fun BreadcrumbBar(state: PersonalFilesState, onClick: (Long?) -> Unit) {
    val strings = LocalMobileStrings.current
    val scrollState = rememberScrollState()
    LaunchedEffect(state.breadcrumb.size, scrollState.maxValue) {
        scrollState.animateScrollTo(scrollState.maxValue)
    }
    Row(
        Modifier.horizontalScroll(scrollState).testTag("personal-breadcrumb"),
    ) {
        state.breadcrumb.forEachIndexed { index, item ->
            if (index > 0) Text(" / ")
            Text(
                item.name.ifEmpty { if (item.id == null) strings[MobileString.RootDirectory] else strings[MobileString.FolderPlaceholder] },
                color = if (index == state.breadcrumb.lastIndex) {
                    androidx.compose.material3.MaterialTheme.colorScheme.onSurface
                } else {
                    androidx.compose.material3.MaterialTheme.colorScheme.primary
                },
                modifier = Modifier
                    .testTag("personal-breadcrumb-${item.id ?: "root"}")
                    .let { base ->
                        if (index == state.breadcrumb.lastIndex) base else base.clickable { onClick(item.id) }
                    },
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun PersonalTopActions(
    state: PersonalFilesState,
    store: PersonalBrowserStore,
    onOpenSearch: () -> Unit,
    onOpenFavorites: () -> Unit,
    onOpenTags: () -> Unit,
    onOpenShares: () -> Unit,
    onOpenRecycleBin: () -> Unit,
) {
    val strings = LocalMobileStrings.current
    var sortExpanded by remember { mutableStateOf(false) }
    var organizeExpanded by remember { mutableStateOf(false) }
    IconButton(onClick = onOpenSearch, modifier = Modifier.testTag("personal-search")) {
        Icon(Icons.Default.Search, contentDescription = strings[MobileString.SearchFiles])
    }
    Box {
        IconButton(
            onClick = { sortExpanded = true },
            modifier = Modifier.testTag("personal-sort"),
        ) { Icon(Icons.AutoMirrored.Filled.Sort, contentDescription = strings[MobileString.Sort]) }
        DropdownMenu(expanded = sortExpanded, onDismissRequest = { sortExpanded = false }) {
            PersonalFileSort.entries.forEach { sort ->
                DropdownMenuItem(
                    text = {
                        val label = strings[sort.stringKey()]
                        Text(if (sort == state.sort) "✓ $label" else label)
                    },
                    onClick = {
                        store.changeSort(sort, state.direction)
                        sortExpanded = false
                    },
                    modifier = Modifier.testTag("personal-sort-${sort.apiValue}"),
                )
            }
            DropdownMenuItem(
                text = { Text(strings[state.direction.stringKey()]) },
                leadingIcon = {
                    Icon(
                        if (state.direction == SortDirection.ASCENDING) Icons.Default.ArrowUpward else Icons.Default.ArrowDownward,
                        contentDescription = null,
                    )
                },
                onClick = {
                    store.changeSort(
                        state.sort,
                        if (state.direction == SortDirection.ASCENDING) SortDirection.DESCENDING else SortDirection.ASCENDING,
                    )
                    sortExpanded = false
                },
                modifier = Modifier.testTag("personal-sort-direction"),
            )
        }
    }
    IconButton(
        onClick = {
            store.changeViewMode(
                if (state.viewMode == FileViewMode.LIST) FileViewMode.GRID else FileViewMode.LIST,
            )
        },
        modifier = Modifier.testTag("personal-view-mode"),
    ) {
        Icon(
            if (state.viewMode == FileViewMode.LIST) Icons.Default.GridView else Icons.AutoMirrored.Filled.List,
            contentDescription = strings[MobileString.SwitchView],
        )
    }
    IconButton(
        onClick = store::refresh,
        enabled = !state.current.initialLoading && !state.current.refreshing,
        modifier = Modifier.testTag("personal-refresh"),
    ) { Icon(Icons.Default.Refresh, contentDescription = strings[MobileString.RefreshDirectory]) }
    Box {
        IconButton(onClick = { organizeExpanded = true }, modifier = Modifier.testTag("personal-organize")) {
            Icon(Icons.Default.MoreVert, contentDescription = strings[MobileString.Organize])
        }
        DropdownMenu(expanded = organizeExpanded, onDismissRequest = { organizeExpanded = false }) {
            DropdownMenuItem(
                text = { Text(strings[MobileString.FavoritesTitle]) },
                leadingIcon = { Icon(Icons.Default.Star, null) },
                onClick = { organizeExpanded = false; onOpenFavorites() },
                modifier = Modifier.testTag("personal-open-favorites"),
            )
            DropdownMenuItem(
                text = { Text(strings[MobileString.TagsTitle]) },
                leadingIcon = { Icon(Icons.Default.Label, null) },
                onClick = { organizeExpanded = false; onOpenTags() },
                modifier = Modifier.testTag("personal-open-tags"),
            )
            DropdownMenuItem(
                text = { Text(strings[MobileString.SharesTitle]) },
                leadingIcon = { Icon(Icons.Default.Share, null) },
                onClick = { organizeExpanded = false; onOpenShares() },
                modifier = Modifier.testTag("personal-open-shares"),
            )
            DropdownMenuItem(
                text = { Text(strings[MobileString.RecycleBinTitle]) },
                leadingIcon = { Icon(Icons.Default.DeleteSweep, null) },
                onClick = { organizeExpanded = false; onOpenRecycleBin() },
                modifier = Modifier.testTag("personal-open-recycle-bin"),
            )
        }
    }
}

@Composable
private fun NotificationBell(unread: Long, onOpen: () -> Unit) {
    val strings = LocalMobileStrings.current
    IconButton(onClick = onOpen, modifier = Modifier.testTag("notifications-bell")) {
        if (unread > 0) BadgedBox(
            badge = { Badge { Text(if (unread > 99) "99+" else unread.toString()) } },
            modifier = Modifier.testTag("notifications-badge"),
        ) { Icon(Icons.Default.Notifications, strings[MobileString.Notifications]) }
        else Icon(Icons.Default.Notifications, strings[MobileString.Notifications])
    }
}

@Composable
private fun AppNavHost(
    navController: NavHostController,
    modifier: Modifier,
    container: AppContainer,
    debugToolsEnabled: Boolean,
    wide: Boolean,
    onOpenFolder: (FileItem) -> Unit,
    onGoRoot: () -> Unit,
    onOpenDiagnostics: () -> Unit,
    onOpenTransfers: () -> Unit,
    onOpenSearch: () -> Unit,
    onOpenPreview: (FileItem) -> Unit,
    onClosePreview: () -> Unit,
    onOpenSearchLocation: (FileItem) -> Unit,
    onOpenShareDetail: (Long) -> Unit,
    onOpenVersions: (FileItem) -> Unit,
    onNotificationTarget: (com.betterclouddrive.mobile.feature.teams.NotificationTarget) -> Unit,
) {
    NavHost(navController, startDestination = PersonalRoute, modifier = modifier) {
        personalBrowserGraph(container, onOpenFolder, onGoRoot, onOpenTransfers, onOpenPreview, onOpenVersions)
        personalSearchGraph(navController, container, onOpenSearchLocation, onOpenPreview, onOpenVersions)
        personalOrganizationGraph(navController, container, wide, onOpenFolder, onOpenSearchLocation, onOpenPreview, onOpenVersions)
        personalSharesGraph(navController, container, wide, onOpenShareDetail)
        personalPreviewGraph(container, onClosePreview)
        personalRecoveryGraph(container, onOpenTransfers)
        teamWorkspaceGraph(navController, container, wide, onOpenTransfers) {
            navController.navigate(PersonalRoute) {
                popUpTo<PersonalRoute> { inclusive = false }
                launchSingleTop = true
            }
        }
        productUtilityGraph(container, debugToolsEnabled, onOpenDiagnostics, onNotificationTarget)
    }
}

@Composable
private fun MainNavigationBar(destination: NavDestination?, selectedOverride: AppRoute?, activeTransfers: Int, onNavigate: (AppRoute) -> Unit) {
    val strings = LocalMobileStrings.current
    NavigationBar(modifier = Modifier.testTag("main-navigation-bar")) {
        topLevelItems.forEach { item ->
            val selected = selectedOverride?.let { it == item.route } ?: destination.matches(item.route)
            NavigationBarItem(
                selected = selected,
                onClick = {
                    if (!selected ||
                        (item.route == PersonalRoute && destination?.hasRoute<PersonalRoute>() != true) ||
                        (item.route == TeamsRoute && destination?.hasRoute<TeamsRoute>() != true)
                    ) onNavigate(item.route)
                },
                icon = { NavigationIcon(item, selected, activeTransfers, strings) },
                label = { Text(strings[item.label]) },
                modifier = Modifier.testTag(item.testTag),
            )
        }
    }
}

@Composable
private fun MainNavigationRail(destination: NavDestination?, selectedOverride: AppRoute?, activeTransfers: Int, onNavigate: (AppRoute) -> Unit) {
    val strings = LocalMobileStrings.current
    NavigationRail(modifier = Modifier.fillMaxHeight().testTag("main-navigation-rail")) {
        Spacer(Modifier.height(16.dp))
        topLevelItems.forEach { item ->
            val selected = selectedOverride?.let { it == item.route } ?: destination.matches(item.route)
            NavigationRailItem(
                selected = selected,
                onClick = {
                    if (!selected ||
                        (item.route == PersonalRoute && destination?.hasRoute<PersonalRoute>() != true) ||
                        (item.route == TeamsRoute && destination?.hasRoute<TeamsRoute>() != true)
                    ) onNavigate(item.route)
                },
                icon = { NavigationIcon(item, selected, activeTransfers, strings) },
                label = { Text(strings[item.label]) },
                modifier = Modifier.testTag(item.testTag),
            )
        }
    }
}

@Composable
private fun NavigationIcon(item: TopLevelItem, selected: Boolean, activeTransfers: Int, strings: MobileStrings) {
    if (item.route == TransfersRoute && activeTransfers > 0) {
        BadgedBox(
            modifier = Modifier.testTag("nav-transfers-badge"),
            badge = { Badge { Text(activeTransfers.coerceAtMost(99).toString()) } },
        ) {
            Icon(if (selected) item.selectedIcon else item.unselectedIcon, strings[item.label])
        }
    } else {
        Icon(if (selected) item.selectedIcon else item.unselectedIcon, strings[item.label])
    }
}

private val List<com.betterclouddrive.mobile.feature.transfers.TransferTask>.activeCount: Int
    get() = count { it.status !in setOf(
        TransferStatus.SUCCEEDED,
        TransferStatus.FAILED,
        TransferStatus.CANCELED,
    ) }

private fun NavDestination?.matches(route: AppRoute): Boolean = when (route) {
    PersonalRoute -> isPersonal()
    is PersonalFolderRoute -> this?.hasRoute<PersonalFolderRoute>() == true
    PersonalSearchRoute -> this?.hasRoute<PersonalSearchRoute>() == true
    PersonalFavoritesRoute -> this?.hasRoute<PersonalFavoritesRoute>() == true
    PersonalTagsRoute -> this?.hasRoute<PersonalTagsRoute>() == true
    is PersonalTagFilesRoute -> this?.hasRoute<PersonalTagFilesRoute>() == true
    PersonalSharesRoute -> this?.hasRoute<PersonalSharesRoute>() == true
    is PersonalShareDetailRoute -> this?.hasRoute<PersonalShareDetailRoute>() == true
    is PersonalPreviewRoute -> this?.hasRoute<PersonalPreviewRoute>() == true
    PersonalRecycleBinRoute -> this?.hasRoute<PersonalRecycleBinRoute>() == true
    is PersonalVersionsRoute -> this?.hasRoute<PersonalVersionsRoute>() == true
    TeamsRoute -> isTeam()
    is TeamWorkspaceRoute -> this?.hasRoute<TeamWorkspaceRoute>() == true
    is TeamFolderRoute -> this?.hasRoute<TeamFolderRoute>() == true
    is TeamSearchRoute -> this?.hasRoute<TeamSearchRoute>() == true
    is TeamPreviewRoute -> this?.hasRoute<TeamPreviewRoute>() == true
    is TeamMembersRoute -> this?.hasRoute<TeamMembersRoute>() == true
    is TeamSettingsRoute -> this?.hasRoute<TeamSettingsRoute>() == true
    is TeamAuditRoute -> this?.hasRoute<TeamAuditRoute>() == true
    is TeamTasksRoute -> this?.hasRoute<TeamTasksRoute>() == true
    is TeamFavoritesRoute -> this?.hasRoute<TeamFavoritesRoute>() == true
    is TeamTagsRoute -> this?.hasRoute<TeamTagsRoute>() == true
    is TeamTagFilesRoute -> this?.hasRoute<TeamTagFilesRoute>() == true
    is TeamSharesRoute -> this?.hasRoute<TeamSharesRoute>() == true
    is TeamShareDetailRoute -> this?.hasRoute<TeamShareDetailRoute>() == true
    is TeamRecycleBinRoute -> this?.hasRoute<TeamRecycleBinRoute>() == true
    is TeamVersionsRoute -> this?.hasRoute<TeamVersionsRoute>() == true
    NotificationsRoute -> this?.hasRoute<NotificationsRoute>() == true
    TransfersRoute -> this?.hasRoute<TransfersRoute>() == true
    ProfileRoute -> this?.hasRoute<ProfileRoute>() == true
    DiagnosticsRoute -> this?.hasRoute<DiagnosticsRoute>() == true
}

private fun NavDestination?.isPersonal(): Boolean =
    this?.hasRoute<PersonalRoute>() == true || this?.hasRoute<PersonalFolderRoute>() == true ||
        this?.hasRoute<PersonalSearchRoute>() == true || this?.hasRoute<PersonalPreviewRoute>() == true ||
        this?.hasRoute<PersonalFavoritesRoute>() == true || this?.hasRoute<PersonalTagsRoute>() == true ||
        this?.hasRoute<PersonalTagFilesRoute>() == true
        || this?.hasRoute<PersonalSharesRoute>() == true || this?.hasRoute<PersonalShareDetailRoute>() == true ||
        this?.hasRoute<PersonalRecycleBinRoute>() == true || this?.hasRoute<PersonalVersionsRoute>() == true

private fun NavDestination?.isTeam(): Boolean =
    this?.hasRoute<TeamsRoute>() == true || this?.hasRoute<TeamWorkspaceRoute>() == true ||
        this?.hasRoute<TeamFolderRoute>() == true || this?.hasRoute<TeamSearchRoute>() == true ||
        this?.hasRoute<TeamPreviewRoute>() == true || this?.hasRoute<TeamMembersRoute>() == true ||
        this?.hasRoute<TeamSettingsRoute>() == true ||
        this?.hasRoute<TeamAuditRoute>() == true || this?.hasRoute<TeamTasksRoute>() == true ||
        this?.hasRoute<TeamFavoritesRoute>() == true || this?.hasRoute<TeamTagsRoute>() == true ||
        this?.hasRoute<TeamTagFilesRoute>() == true || this?.hasRoute<TeamSharesRoute>() == true ||
        this?.hasRoute<TeamShareDetailRoute>() == true || this?.hasRoute<TeamRecycleBinRoute>() == true ||
        this?.hasRoute<TeamVersionsRoute>() == true

private fun NavDestination?.isPersonalDirectory(): Boolean =
    this?.hasRoute<PersonalRoute>() == true || this?.hasRoute<PersonalFolderRoute>() == true

private fun NavDestination?.isPersonalFolder(): Boolean = this?.hasRoute<PersonalFolderRoute>() == true

private fun NavDestination?.isPersonalOrganization(): Boolean =
    this?.hasRoute<PersonalFavoritesRoute>() == true || this?.hasRoute<PersonalTagsRoute>() == true ||
        this?.hasRoute<PersonalTagFilesRoute>() == true
        || this?.hasRoute<PersonalSharesRoute>() == true || this?.hasRoute<PersonalShareDetailRoute>() == true ||
        this?.hasRoute<PersonalRecycleBinRoute>() == true || this?.hasRoute<PersonalVersionsRoute>() == true

private fun NavDestination?.title(strings: MobileStrings): String = when {
    matches(PersonalSearchRoute) -> strings[MobileString.SearchFiles]
    matches(PersonalFavoritesRoute) -> strings[MobileString.FavoritesTitle]
    matches(PersonalTagsRoute) || this?.hasRoute<PersonalTagFilesRoute>() == true -> strings[MobileString.TagsTitle]
    matches(PersonalSharesRoute) || this?.hasRoute<PersonalShareDetailRoute>() == true -> strings[MobileString.SharesTitle]
    matches(PersonalRecycleBinRoute) -> strings[MobileString.RecycleBinTitle]
    this?.hasRoute<PersonalVersionsRoute>() == true -> strings[MobileString.VersionHistory]
    this?.hasRoute<TeamMembersRoute>() == true -> strings[MobileString.TeamMembers]
    this?.hasRoute<TeamSettingsRoute>() == true -> strings[MobileString.TeamSettings]
    this?.hasRoute<TeamAuditRoute>() == true -> strings[MobileString.TeamAuditLogs]
    this?.hasRoute<TeamTasksRoute>() == true -> strings[MobileString.TeamTasks]
    this?.hasRoute<TeamFavoritesRoute>() == true -> strings[MobileString.FavoritesTitle]
    this?.hasRoute<TeamTagsRoute>() == true || this?.hasRoute<TeamTagFilesRoute>() == true -> strings[MobileString.TagsTitle]
    this?.hasRoute<TeamSharesRoute>() == true || this?.hasRoute<TeamShareDetailRoute>() == true -> strings[MobileString.TeamSharesTitle]
    this?.hasRoute<TeamRecycleBinRoute>() == true -> strings[MobileString.RecycleBinTitle]
    this?.hasRoute<TeamVersionsRoute>() == true -> strings[MobileString.VersionHistory]
    matches(TeamsRoute) -> strings[MobileString.TeamsTitle]
    matches(TransfersRoute) -> strings[MobileString.TransferQueueTitle]
    matches(ProfileRoute) -> strings[MobileString.Profile]
    matches(DiagnosticsRoute) -> strings[MobileString.Diagnostics]
    matches(NotificationsRoute) -> strings[MobileString.Notifications]
    else -> strings[MobileString.AppName]
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
