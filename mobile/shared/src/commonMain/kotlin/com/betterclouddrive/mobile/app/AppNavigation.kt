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
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Search
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import com.betterclouddrive.mobile.core.platform.DocumentPicker
import com.betterclouddrive.mobile.core.platform.OfficeRenderer
import com.betterclouddrive.mobile.core.platform.MediaPreviewRenderer
import com.betterclouddrive.mobile.core.platform.UploadPickResult
import com.betterclouddrive.mobile.feature.files.FileViewMode
import com.betterclouddrive.mobile.feature.files.PersonalFileSort
import com.betterclouddrive.mobile.feature.files.SortDirection

data class ProductShellActions(
    val activatePersonalRoute: (Long?) -> Unit,
    val prepareOpenFolder: (FileItem) -> Unit,
    val refreshFiles: () -> Unit,
    val loadMoreFiles: () -> Unit,
    val changeFileSort: (PersonalFileSort, SortDirection) -> Unit,
    val changeFileViewMode: (FileViewMode) -> Unit,
    val selectPersonalFile: (FileItem?) -> Unit,
    val togglePersonalSelection: (Long) -> Unit,
    val clearPersonalSelection: () -> Unit,
    val clearPersonalTransientUi: () -> Unit,
    val createPersonalFolder: (String) -> Unit,
    val renamePersonalFile: (FileItem, String) -> Unit,
    val deletePersonalFiles: (Set<Long>) -> Unit,
    val openFolderPicker: (FileItem, FileMutationKind) -> Unit,
    val openPickerFolder: (FileItem) -> Unit,
    val pickerBack: () -> Unit,
    val retryFolderPicker: () -> Unit,
    val closeFolderPicker: () -> Unit,
    val confirmFolderPicker: () -> Unit,
    val consumeFileFeedback: () -> Unit,
    val updatePersonalScrollAnchor: (Long?) -> Unit,
    val updateSearchQuery: (String) -> Unit,
    val submitSearch: () -> Unit,
    val retrySearch: () -> Unit,
    val loadMoreSearch: () -> Unit,
    val updateSearchScrollAnchor: (Long?) -> Unit,
    val prepareOpenSearchLocation: (FileItem) -> Unit,
    val openPreview: (Long) -> Unit,
    val retryPreview: () -> Unit,
    val closePreview: () -> Unit,
    val updatePreviewPosition: (Long) -> Unit,
    val reportPreviewError: (UiMessage) -> Unit,
    val upload: (Long?, UploadPickResult) -> Unit,
    val consumeTransferFeedback: () -> Unit,
    val download: (FileItem, String) -> Unit,
    val pause: (String) -> Unit,
    val resume: (String) -> Unit,
    val cancel: (String) -> Unit,
    val retry: (String) -> Unit,
    val clearFinishedTransfers: () -> Unit,
    val refreshProfile: () -> Unit = {},
    val updateAccountLocale: (MobileLocale) -> Unit = {},
    val setThemeMode: (ThemeMode) -> Unit = {},
    val requestServerSwitch: (String) -> Unit = {},
    val cancelServerSwitch: () -> Unit = {},
    val confirmServerSwitch: () -> Unit = {},
    val logout: () -> Unit,
    val loadDiagnosticOffice: (Long, Long) -> Unit,
    val updateDiagnosticStatus: (UiMessage) -> Unit,
)

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
    state: AppUiState,
    documentPicker: DocumentPicker,
    officeRenderer: OfficeRenderer,
    mediaPreviewRenderer: MediaPreviewRenderer,
    roomTaskCount: suspend () -> Int,
    actions: ProductShellActions,
    debugToolsEnabled: Boolean,
) {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val destination = backStackEntry?.destination
    val isDiagnostics = destination.matches(DiagnosticsRoute)
    val isPreview = destination?.hasRoute<PersonalPreviewRoute>() == true
    val isSearch = destination?.hasRoute<PersonalSearchRoute>() == true

    LaunchedEffect(backStackEntry) {
        when {
            destination?.hasRoute<PersonalRoute>() == true -> actions.activatePersonalRoute(null)
            destination?.hasRoute<PersonalFolderRoute>() == true -> {
                actions.activatePersonalRoute(backStackEntry!!.toRoute<PersonalFolderRoute>().folderId)
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
        if (route == PersonalRoute && destination.isPersonalFolder()) {
            goPersonalRoot()
            return
        }
        actions.clearPersonalTransientUi()
        navController.navigate(route) {
            popUpTo<PersonalRoute> { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }

    fun openFolder(file: FileItem) {
        actions.prepareOpenFolder(file)
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
    fun openPreview(file: FileItem) = navController.navigate(PersonalPreviewRoute(file.id))
    fun closePreview() {
        actions.closePreview()
        navController.popBackStack()
    }
    fun openSearchLocation(file: FileItem) {
        actions.prepareOpenSearchLocation(file)
        val route: AppRoute = file.parentId?.let(::PersonalFolderRoute) ?: PersonalRoute
        navController.navigate(route) { launchSingleTop = true }
    }

    @Composable
    fun Content(modifier: Modifier) {
        AppNavHost(
            navController, modifier, state, documentPicker, officeRenderer, mediaPreviewRenderer, roomTaskCount,
            actions, debugToolsEnabled,
            onOpenFolder = ::openFolder,
            onGoRoot = ::goPersonalRoot,
            onOpenDiagnostics = { navController.navigate(DiagnosticsRoute) },
            onOpenTransfers = ::openTransfers,
            onOpenSearch = ::openSearch,
            onOpenPreview = ::openPreview,
            onClosePreview = ::closePreview,
            onOpenSearchLocation = ::openSearchLocation,
        )
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth >= 600.dp
        if (wide && !isDiagnostics && !isPreview) {
            Row(Modifier.fillMaxSize().testTag("product-shell-wide")) {
                MainNavigationRail(destination, state.activeTransferCount, ::navigateTopLevel)
                ShellScaffold(
                    destination, state, actions,
                    onBack = navController::popBackStack,
                    onBreadcrumb = ::openBreadcrumb,
                    onOpenSearch = ::openSearch,
                    showTopBar = !isSearch,
                    modifier = Modifier.weight(1f),
                    content = { Content(it) },
                )
            }
        } else {
            ShellScaffold(
                destination, state, actions,
                onBack = navController::popBackStack,
                onBreadcrumb = ::openBreadcrumb,
                onOpenSearch = ::openSearch,
                showTopBar = !isSearch && !isPreview,
                bottomBar = { if (!isDiagnostics && !isPreview) MainNavigationBar(destination, state.activeTransferCount, ::navigateTopLevel) },
                modifier = Modifier.fillMaxSize().testTag("product-shell-compact"),
                content = { Content(it) },
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ShellScaffold(
    destination: NavDestination?,
    state: AppUiState,
    actions: ProductShellActions,
    onBack: () -> Unit,
    onBreadcrumb: (Long?) -> Unit,
    onOpenSearch: () -> Unit,
    showTopBar: Boolean = true,
    modifier: Modifier = Modifier,
    bottomBar: @Composable () -> Unit = {},
    content: @Composable (Modifier) -> Unit,
) {
    val strings = LocalMobileStrings.current
    Scaffold(
        modifier = modifier,
        topBar = {
            if (showTopBar) TopAppBar(
                title = {
                    if (destination.isPersonal()) {
                        BreadcrumbBar(state.personalFiles, onBreadcrumb)
                    } else {
                        Text(destination.title(strings))
                    }
                },
                navigationIcon = {
                    when {
                        destination.matches(DiagnosticsRoute) -> BackButton(onBack, strings[MobileString.BackToProfile], "diagnostics-back")
                        destination.isPersonalFolder() -> BackButton(onBack, strings[MobileString.BackToParent], "personal-parent")
                    }
                },
                actions = {
                    if (destination.isPersonalDirectory()) PersonalTopActions(state.personalFiles, actions, onOpenSearch)
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
private fun BreadcrumbBar(state: PersonalFilesUiState, onClick: (Long?) -> Unit) {
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
private fun PersonalTopActions(state: PersonalFilesUiState, actions: ProductShellActions, onOpenSearch: () -> Unit) {
    val strings = LocalMobileStrings.current
    var sortExpanded by remember { mutableStateOf(false) }
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
                        actions.changeFileSort(sort, state.direction)
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
                    actions.changeFileSort(
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
            actions.changeFileViewMode(
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
        onClick = actions.refreshFiles,
        enabled = !state.current.initialLoading && !state.current.refreshing,
        modifier = Modifier.testTag("personal-refresh"),
    ) { Icon(Icons.Default.Refresh, contentDescription = strings[MobileString.RefreshDirectory]) }
}

@Composable
private fun AppNavHost(
    navController: NavHostController,
    modifier: Modifier,
    state: AppUiState,
    documentPicker: DocumentPicker,
    officeRenderer: OfficeRenderer,
    mediaPreviewRenderer: MediaPreviewRenderer,
    roomTaskCount: suspend () -> Int,
    actions: ProductShellActions,
    debugToolsEnabled: Boolean,
    onOpenFolder: (FileItem) -> Unit,
    onGoRoot: () -> Unit,
    onOpenDiagnostics: () -> Unit,
    onOpenTransfers: () -> Unit,
    onOpenSearch: () -> Unit,
    onOpenPreview: (FileItem) -> Unit,
    onClosePreview: () -> Unit,
    onOpenSearchLocation: (FileItem) -> Unit,
) {
    @Composable
    fun Personal() {
        PersonalSpaceScreen(
            state.personalFiles, documentPicker, actions.upload, actions.download,
            onOpenFolder, actions.selectPersonalFile, actions.loadMoreFiles,
            actions.updatePersonalScrollAnchor, actions.refreshFiles, onGoRoot,
            actions.togglePersonalSelection, actions.clearPersonalSelection,
            actions.createPersonalFolder, actions.renamePersonalFile, actions.deletePersonalFiles,
            actions.openFolderPicker, actions.openPickerFolder, actions.pickerBack,
            actions.retryFolderPicker, actions.closeFolderPicker, actions.confirmFolderPicker,
            actions.consumeFileFeedback, actions.consumeTransferFeedback, onOpenTransfers, onOpenPreview,
        )
    }

    NavHost(navController, startDestination = PersonalRoute, modifier = modifier) {
        composable<PersonalRoute> { Personal() }
        composable<PersonalFolderRoute> { Personal() }
        composable<PersonalSearchRoute> {
            PersonalSearchScreen(
                state.search, documentPicker, onBack = navController::popBackStack,
                onQueryChanged = actions.updateSearchQuery,
                onSubmit = actions.submitSearch,
                onLoadMore = actions.loadMoreSearch,
                onRetry = actions.retrySearch,
                onScrollAnchor = actions.updateSearchScrollAnchor,
                onOpenFolder = { navController.navigate(PersonalFolderRoute(it.id)) },
                onOpenLocation = onOpenSearchLocation,
                onPreview = onOpenPreview,
                onDownload = actions.download,
            )
        }
        composable<PersonalPreviewRoute> { entry ->
            val fileId = entry.toRoute<PersonalPreviewRoute>().fileId
            LaunchedEffect(fileId) { actions.openPreview(fileId) }
            PersonalPreviewScreen(
                state.preview, documentPicker, officeRenderer, mediaPreviewRenderer,
                onBack = onClosePreview,
                onRetry = actions.retryPreview,
                onDownload = actions.download,
                onPositionChanged = actions.updatePreviewPosition,
                onRendererError = actions.reportPreviewError,
            )
        }
        composable<TeamsRoute> { TeamsPlaceholderScreen() }
        composable<TransfersRoute> {
            TransferQueueScreen(
                state.transfers, actions.pause, actions.resume, actions.cancel, actions.retry,
                actions.clearFinishedTransfers,
            )
        }
        composable<ProfileRoute> {
            LaunchedEffect(Unit) { actions.refreshProfile() }
            ProfileScreen(
                state = state,
                debugToolsEnabled = debugToolsEnabled,
                onOpenDiagnostics = onOpenDiagnostics,
                onRefresh = actions.refreshProfile,
                onLocale = actions.updateAccountLocale,
                onTheme = actions.setThemeMode,
                onRequestServerSwitch = actions.requestServerSwitch,
                onCancelServerSwitch = actions.cancelServerSwitch,
                onConfirmServerSwitch = actions.confirmServerSwitch,
                onLogout = actions.logout,
            )
        }
        if (debugToolsEnabled) {
            composable<DiagnosticsRoute> {
                DiagnosticsScreen(
                    state, officeRenderer, roomTaskCount,
                    actions.loadDiagnosticOffice, actions.updateDiagnosticStatus,
                )
            }
        }
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
                onClick = { if (!selected || (item.route == PersonalRoute && destination.isPersonalFolder())) onNavigate(item.route) },
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
                onClick = { if (!selected || (item.route == PersonalRoute && destination.isPersonalFolder())) onNavigate(item.route) },
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

private val AppUiState.activeTransferCount: Int
    get() = transfers.count { it.status !in setOf(
        com.betterclouddrive.mobile.feature.transfers.TransferStatus.SUCCEEDED,
        com.betterclouddrive.mobile.feature.transfers.TransferStatus.FAILED,
        com.betterclouddrive.mobile.feature.transfers.TransferStatus.CANCELED,
    ) }

private fun NavDestination?.matches(route: AppRoute): Boolean = when (route) {
    PersonalRoute -> isPersonal()
    is PersonalFolderRoute -> this?.hasRoute<PersonalFolderRoute>() == true
    PersonalSearchRoute -> this?.hasRoute<PersonalSearchRoute>() == true
    is PersonalPreviewRoute -> this?.hasRoute<PersonalPreviewRoute>() == true
    TeamsRoute -> this?.hasRoute<TeamsRoute>() == true
    TransfersRoute -> this?.hasRoute<TransfersRoute>() == true
    ProfileRoute -> this?.hasRoute<ProfileRoute>() == true
    DiagnosticsRoute -> this?.hasRoute<DiagnosticsRoute>() == true
}

private fun NavDestination?.isPersonal(): Boolean =
    this?.hasRoute<PersonalRoute>() == true || this?.hasRoute<PersonalFolderRoute>() == true ||
        this?.hasRoute<PersonalSearchRoute>() == true || this?.hasRoute<PersonalPreviewRoute>() == true

private fun NavDestination?.isPersonalDirectory(): Boolean =
    this?.hasRoute<PersonalRoute>() == true || this?.hasRoute<PersonalFolderRoute>() == true

private fun NavDestination?.isPersonalFolder(): Boolean = this?.hasRoute<PersonalFolderRoute>() == true

private fun NavDestination?.title(strings: MobileStrings): String = when {
    matches(PersonalSearchRoute) -> strings[MobileString.SearchFiles]
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
