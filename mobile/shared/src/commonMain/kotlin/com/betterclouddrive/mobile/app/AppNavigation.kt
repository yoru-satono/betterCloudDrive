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
import androidx.compose.ui.Modifier
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
) {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val destination = backStackEntry?.destination
    val transfers by container.transferQueue.state.collectAsState()
    val isDiagnostics = destination.matches(DiagnosticsRoute)
    val isPreview = destination?.hasRoute<PersonalPreviewRoute>() == true
    val isSearch = destination?.hasRoute<PersonalSearchRoute>() == true

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

    fun navigateTopLevel(route: AppRoute) {
        if (route == PersonalRoute && destination.isPersonal() && destination?.hasRoute<PersonalRoute>() != true) {
            goPersonalRoot()
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
        )
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = wideLayoutOverride ?: (maxWidth >= 600.dp)
        if (wide && !isDiagnostics && !isPreview) {
            Row(Modifier.fillMaxSize().testTag("product-shell-wide")) {
                MainNavigationRail(destination, transfers.activeCount, ::navigateTopLevel)
                ShellScaffold(
                    destination, container.browserStore,
                    onBack = navController::popBackStack,
                    onBreadcrumb = ::openBreadcrumb,
                    onOpenSearch = ::openSearch,
                    onOpenFavorites = ::openFavorites,
                    onOpenTags = ::openTags,
                    onOpenShares = ::openShares,
                    onOpenRecycleBin = ::openRecycleBin,
                    showTopBar = !isSearch,
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
                showTopBar = !isSearch && !isPreview,
                bottomBar = { if (!isDiagnostics && !isPreview) MainNavigationBar(destination, transfers.activeCount, ::navigateTopLevel) },
                modifier = Modifier.fillMaxSize().testTag("product-shell-compact"),
                content = { Content(it, false) },
            )
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
        val shares by container.sharesStore.state.collectAsState()
        ShareCreatorOverlay(
            shares, wide, container.localDateTime, container.clipboard,
            container.sharesStore::create, container.sharesStore::closeCreator,
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
                        destination.isPersonalOrganization() -> BackButton(onBack, strings[MobileString.BackToParent], "organization-back")
                        destination.isPersonalFolder() -> BackButton(onBack, strings[MobileString.BackToParent], "personal-parent")
                    }
                },
                actions = {
                    if (destination.isPersonalDirectory()) PersonalTopActions(
                        browserState, browser, onOpenSearch, onOpenFavorites, onOpenTags, onOpenShares, onOpenRecycleBin,
                    )
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
) {
    NavHost(navController, startDestination = PersonalRoute, modifier = modifier) {
        personalBrowserGraph(container, onOpenFolder, onGoRoot, onOpenTransfers, onOpenPreview, onOpenVersions)
        personalSearchGraph(navController, container, onOpenSearchLocation, onOpenPreview, onOpenVersions)
        personalOrganizationGraph(navController, container, wide, onOpenFolder, onOpenSearchLocation, onOpenPreview, onOpenVersions)
        personalSharesGraph(navController, container, wide, onOpenShareDetail)
        personalPreviewGraph(container, onClosePreview)
        personalRecoveryGraph(container, onOpenTransfers)
        productUtilityGraph(container, debugToolsEnabled, onOpenDiagnostics)
    }
}

@Composable
private fun MainNavigationBar(destination: NavDestination?, activeTransfers: Int, onNavigate: (AppRoute) -> Unit) {
    val strings = LocalMobileStrings.current
    NavigationBar(modifier = Modifier.testTag("main-navigation-bar")) {
        topLevelItems.forEach { item ->
            val selected = destination.matches(item.route)
            NavigationBarItem(
                selected = selected,
                onClick = { if (!selected || (item.route == PersonalRoute && destination?.hasRoute<PersonalRoute>() != true)) onNavigate(item.route) },
                icon = { NavigationIcon(item, selected, activeTransfers, strings) },
                label = { Text(strings[item.label]) },
                modifier = Modifier.testTag(item.testTag),
            )
        }
    }
}

@Composable
private fun MainNavigationRail(destination: NavDestination?, activeTransfers: Int, onNavigate: (AppRoute) -> Unit) {
    val strings = LocalMobileStrings.current
    NavigationRail(modifier = Modifier.fillMaxHeight().testTag("main-navigation-rail")) {
        Spacer(Modifier.height(16.dp))
        topLevelItems.forEach { item ->
            val selected = destination.matches(item.route)
            NavigationRailItem(
                selected = selected,
                onClick = { if (!selected || (item.route == PersonalRoute && destination?.hasRoute<PersonalRoute>() != true)) onNavigate(item.route) },
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
    TeamsRoute -> this?.hasRoute<TeamsRoute>() == true
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
    matches(TeamsRoute) -> strings[MobileString.TeamsTitle]
    matches(TransfersRoute) -> strings[MobileString.TransferQueueTitle]
    matches(ProfileRoute) -> strings[MobileString.Profile]
    matches(DiagnosticsRoute) -> strings[MobileString.Diagnostics]
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
