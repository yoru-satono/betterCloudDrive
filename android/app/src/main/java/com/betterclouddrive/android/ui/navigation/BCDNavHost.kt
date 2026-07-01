package com.betterclouddrive.android.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.betterclouddrive.android.ui.auth.*
import com.betterclouddrive.android.ui.files.*
import com.betterclouddrive.android.ui.favorites.FavoritesScreen
import com.betterclouddrive.android.ui.office.OfficeEditorScreen
import com.betterclouddrive.android.ui.preview.ImagePreviewScreen
import com.betterclouddrive.android.ui.preview.TextPreviewScreen
import com.betterclouddrive.android.ui.preview.VideoPreviewScreen
import com.betterclouddrive.android.ui.profile.*
import com.betterclouddrive.android.ui.recyclebin.RecycleBinScreen
import com.betterclouddrive.android.ui.search.SearchResultScreen
import com.betterclouddrive.android.ui.shares.SharesScreen
import com.betterclouddrive.android.ui.tags.TagsScreen
import com.betterclouddrive.android.ui.tags.TagFilesScreen
import com.betterclouddrive.android.ui.teams.TeamFilesScreen
import com.betterclouddrive.android.ui.teams.TeamInviteScreen
import com.betterclouddrive.android.ui.teams.TeamMembersScreen
import com.betterclouddrive.android.ui.teams.NotificationsScreen
import com.betterclouddrive.android.ui.teams.TeamRecycleBinScreen
import com.betterclouddrive.android.ui.teams.TeamSettingsScreen
import com.betterclouddrive.android.ui.teams.TeamsScreen
import com.betterclouddrive.android.ui.transfer.TransferQueueScreen
import com.betterclouddrive.android.ui.versions.VersionsScreen
import com.betterclouddrive.android.util.FileTypeHelper
import com.betterclouddrive.android.util.ServerUrlUtil

@Composable
fun BCDNavHost(
    navController: NavHostController = rememberNavController(),
    initialTeamInviteToken: String? = null,
) {
    val initialInviteRoute = remember { initialTeamInviteToken?.let(Screen::teamInvite) }
    var handledInviteToken by remember { mutableStateOf(initialTeamInviteToken) }
    var postLoginRoute by remember { mutableStateOf<String?>(initialInviteRoute) }

    LaunchedEffect(initialTeamInviteToken) {
        val token = initialTeamInviteToken
        if (token != null && token != handledInviteToken) {
            handledInviteToken = token
            val route = Screen.teamInvite(token)
            postLoginRoute = route
            navController.navigate(route) { launchSingleTop = true }
        }
    }

    NavHost(
        navController = navController,
        startDestination = initialInviteRoute ?: Screen.LOGIN,
        enterTransition = { NavAnimations.enterTransition(this) },
        exitTransition = { NavAnimations.exitTransition(this) },
        popEnterTransition = { NavAnimations.popEnterTransition(this) },
        popExitTransition = { NavAnimations.popExitTransition(this) },
    ) {
        // Auth
        composable(Screen.LOGIN) {
            LoginScreen(
                onNavigateRegister = { navController.navigate(Screen.REGISTER) },
                onNavigateForgotPassword = { navController.navigate(Screen.FORGOT_PASSWORD) },
                onLoginSuccess = {
                    val destination = postLoginRoute ?: Screen.files(null)
                    postLoginRoute = null
                    navController.navigate(destination) {
                        popUpTo(Screen.LOGIN) { inclusive = true }
                        launchSingleTop = true
                    }
                },
            )
        }
        composable(Screen.REGISTER) {
            RegisterScreen(onNavigateBack = { navController.popBackStack() })
        }
        composable(Screen.FORGOT_PASSWORD) {
            ForgotPasswordScreen(onNavigateBack = { navController.popBackStack() })
        }
        composable(
            route = Screen.TEAM_INVITE,
            arguments = listOf(navArgument("token") { type = NavType.StringType }),
        ) { backStackEntry ->
            val token = backStackEntry.arguments?.getString("token") ?: return@composable
            TeamInviteScreen(
                token = token,
                onNavigateBack = {
                    if (!navController.popBackStack()) {
                        navController.navigate(Screen.LOGIN) { launchSingleTop = true }
                    }
                },
                onNavigateLogin = {
                    postLoginRoute = Screen.teamInvite(token)
                    navController.navigate(Screen.LOGIN) { launchSingleTop = true }
                },
                onNavigateTeam = { teamId ->
                    postLoginRoute = null
                    navController.navigate(Screen.teamFiles(teamId)) {
                        popUpTo(Screen.TEAM_INVITE) { inclusive = true }
                        launchSingleTop = true
                    }
                },
            )
        }

        // Main - Files
        composable(route = Screen.FILES) {
            FileBrowserScreen(
                folderId = null,
                onNavigateToPreview = { file -> navController.navigate(Screen.preview(file.id)) },
                onNavigateToSearch = { navController.navigate(Screen.SEARCH) },
                onNavigateToVersions = { fileId -> navController.navigate(Screen.versions(fileId)) },
                onNavigateToProfile = { navController.navigateTopLevel(Screen.PROFILE) },
                onNavigateToTransfers = { navController.navigateTopLevel(Screen.TRANSFERS) },
                onNavigateMain = { route -> navController.navigateTopLevel(route) },
            )
        }
        composable(
            route = Screen.FILES_FOLDER,
            arguments = listOf(navArgument("folderId") {
                type = NavType.StringType
            }),
        ) { backStackEntry ->
            val folderId = backStackEntry.arguments?.getString("folderId")
                ?.toLongOrNull()
            FileBrowserScreen(
                folderId = folderId,
                onNavigateToPreview = { file -> navController.navigate(Screen.preview(file.id)) },
                onNavigateToSearch = { navController.navigate(Screen.SEARCH) },
                onNavigateToVersions = { fileId -> navController.navigate(Screen.versions(fileId)) },
                onNavigateToProfile = { navController.navigateTopLevel(Screen.PROFILE) },
                onNavigateToTransfers = { navController.navigateTopLevel(Screen.TRANSFERS) },
                onNavigateMain = { route -> navController.navigateTopLevel(route) },
            )
        }

        // Search
        composable(Screen.SEARCH) {
            SearchResultScreen(
                onNavigateBack = { navController.popBackStack() },
                onOpenFile = { file -> navController.navigate(Screen.preview(file.id)) },
                onOpenFolder = { folder -> navController.navigateTopLevel(Screen.files(folder.id)) },
                onOpenLocation = { file -> navController.navigateTopLevel(Screen.files(file.parentId)) },
            )
        }

        // Preview
        composable(
            route = Screen.PREVIEW,
            arguments = listOf(navArgument("fileId") { type = NavType.LongType }),
        ) { backStackEntry ->
            val fileId = backStackEntry.arguments?.getLong("fileId") ?: return@composable
            PreviewRouter(fileId = fileId, onNavigateBack = { navController.popBackStack() })
        }

        // Recycle Bin
        composable(Screen.RECYCLE_BIN) {
            RecycleBinScreen(
                onNavigateBack = { navController.popBackStack() },
                onNavigateMain = { route -> navController.navigateTopLevel(route) },
            )
        }

        // Teams
        composable(Screen.TEAMS) {
            TeamsScreen(
                onOpenTeam = { teamId -> navController.navigate(Screen.teamFiles(teamId)) },
                onNavigateMain = { route -> navController.navigateTopLevel(route) },
            )
        }
        composable(
            route = Screen.TEAM_FILES,
            arguments = listOf(navArgument("teamId") { type = NavType.LongType }),
        ) { backStackEntry ->
            val teamId = backStackEntry.arguments?.getLong("teamId") ?: return@composable
            TeamFilesScreen(
                teamId = teamId,
                folderId = null,
                onNavigateBack = { navController.popBackStack() },
                onOpenFolderRoute = { id, folderId -> navController.navigate(Screen.teamFiles(id, folderId)) },
                onOpenPreview = { id, file -> navController.navigate(Screen.teamPreview(id, file.id)) },
                onOpenOffice = { id, file -> navController.navigate(Screen.teamOffice(id, file.id)) },
                onOpenMembers = { id -> navController.navigate(Screen.teamMembers(id)) },
                onOpenSettings = { id -> navController.navigate(Screen.teamSettings(id)) },
                onOpenRecycleBin = { id -> navController.navigate(Screen.teamRecycleBin(id)) },
                onOpenVersions = { id, fileId -> navController.navigate(Screen.teamVersions(id, fileId)) },
                onOpenNotifications = { navController.navigate(Screen.NOTIFICATIONS) },
                onNavigateTransfers = { navController.navigateTopLevel(Screen.TRANSFERS) },
            )
        }
        composable(
            route = Screen.TEAM_FOLDER,
            arguments = listOf(
                navArgument("teamId") { type = NavType.LongType },
                navArgument("folderId") { type = NavType.LongType },
            ),
        ) { backStackEntry ->
            val teamId = backStackEntry.arguments?.getLong("teamId") ?: return@composable
            val folderId = backStackEntry.arguments?.getLong("folderId")
            TeamFilesScreen(
                teamId = teamId,
                folderId = folderId,
                onNavigateBack = { navController.popBackStack() },
                onOpenFolderRoute = { id, nextFolderId -> navController.navigate(Screen.teamFiles(id, nextFolderId)) },
                onOpenPreview = { id, file -> navController.navigate(Screen.teamPreview(id, file.id)) },
                onOpenOffice = { id, file -> navController.navigate(Screen.teamOffice(id, file.id)) },
                onOpenMembers = { id -> navController.navigate(Screen.teamMembers(id)) },
                onOpenSettings = { id -> navController.navigate(Screen.teamSettings(id)) },
                onOpenRecycleBin = { id -> navController.navigate(Screen.teamRecycleBin(id)) },
                onOpenVersions = { id, fileId -> navController.navigate(Screen.teamVersions(id, fileId)) },
                onOpenNotifications = { navController.navigate(Screen.NOTIFICATIONS) },
                onNavigateTransfers = { navController.navigateTopLevel(Screen.TRANSFERS) },
            )
        }
        composable(
            route = Screen.TEAM_MEMBERS,
            arguments = listOf(navArgument("teamId") { type = NavType.LongType }),
        ) { backStackEntry ->
            val teamId = backStackEntry.arguments?.getLong("teamId") ?: return@composable
            TeamMembersScreen(teamId = teamId, onNavigateBack = { navController.popBackStack() })
        }
        composable(
            route = Screen.TEAM_SETTINGS,
            arguments = listOf(navArgument("teamId") { type = NavType.LongType }),
        ) { backStackEntry ->
            val teamId = backStackEntry.arguments?.getLong("teamId") ?: return@composable
            TeamSettingsScreen(
                teamId = teamId,
                onNavigateBack = { navController.popBackStack() },
                onNavigateRecycleBin = { id -> navController.navigate(Screen.teamRecycleBin(id)) },
                onNavigateTeams = { navController.navigateTopLevel(Screen.TEAMS) },
            )
        }
        composable(
            route = Screen.TEAM_RECYCLE_BIN,
            arguments = listOf(navArgument("teamId") { type = NavType.LongType }),
        ) { backStackEntry ->
            val teamId = backStackEntry.arguments?.getLong("teamId") ?: return@composable
            TeamRecycleBinScreen(teamId = teamId, onNavigateBack = { navController.popBackStack() })
        }
        composable(
            route = Screen.TEAM_VERSIONS,
            arguments = listOf(
                navArgument("teamId") { type = NavType.LongType },
                navArgument("fileId") { type = NavType.LongType },
            ),
        ) { backStackEntry ->
            val teamId = backStackEntry.arguments?.getLong("teamId") ?: return@composable
            val fileId = backStackEntry.arguments?.getLong("fileId") ?: return@composable
            VersionsScreen(fileId = fileId, teamId = teamId, onNavigateBack = { navController.popBackStack() })
        }
        composable(
            route = Screen.TEAM_OFFICE,
            arguments = listOf(
                navArgument("teamId") { type = NavType.LongType },
                navArgument("fileId") { type = NavType.LongType },
            ),
        ) { backStackEntry ->
            val teamId = backStackEntry.arguments?.getLong("teamId") ?: return@composable
            val fileId = backStackEntry.arguments?.getLong("fileId") ?: return@composable
            OfficeEditorScreen(teamId = teamId, fileId = fileId, onNavigateBack = { navController.popBackStack() })
        }
        composable(
            route = Screen.TEAM_PREVIEW,
            arguments = listOf(
                navArgument("teamId") { type = NavType.LongType },
                navArgument("fileId") { type = NavType.LongType },
            ),
        ) { backStackEntry ->
            val teamId = backStackEntry.arguments?.getLong("teamId") ?: return@composable
            val fileId = backStackEntry.arguments?.getLong("fileId") ?: return@composable
            TeamPreviewRouter(teamId = teamId, fileId = fileId, onNavigateBack = { navController.popBackStack() })
        }

        composable(Screen.NOTIFICATIONS) {
            NotificationsScreen(
                onNavigateBack = { navController.popBackStack() },
                onNavigateRoute = { route -> navController.navigate(route) { launchSingleTop = true } },
            )
        }

        composable(Screen.TRANSFERS) {
            TransferQueueScreen(
                onNavigate = { route -> navController.navigateTopLevel(route) },
                onNavigateBack = { navController.popBackStack() },
            )
        }

        // Shares
        composable(Screen.SHARES) {
            SharesScreen(
                onNavigateBack = { navController.popBackStack() },
                onNavigateMain = { route -> navController.navigateTopLevel(route) },
            )
        }

        // Favorites
        composable(Screen.FAVORITES) {
            FavoritesScreen(
                onNavigateBack = { navController.popBackStack() },
                onNavigateMain = { route -> navController.navigateTopLevel(route) },
            )
        }

        // Tags
        composable(Screen.TAGS) {
            TagsScreen(
                onNavigateBack = { navController.popBackStack() },
                onNavigateToTagFiles = { tagId -> navController.navigate(Screen.tagFiles(tagId)) },
                onNavigateMain = { route -> navController.navigateTopLevel(route) },
            )
        }
        composable(
            route = Screen.TAG_FILES,
            arguments = listOf(navArgument("tagId") { type = NavType.LongType }),
        ) { backStackEntry ->
            val tagId = backStackEntry.arguments?.getLong("tagId") ?: return@composable
            TagFilesScreen(tagId = tagId, onNavigateBack = { navController.popBackStack() })
        }

        // Versions
        composable(
            route = Screen.VERSIONS,
            arguments = listOf(navArgument("fileId") { type = NavType.LongType }),
        ) { backStackEntry ->
            val fileId = backStackEntry.arguments?.getLong("fileId") ?: return@composable
            VersionsScreen(fileId = fileId, onNavigateBack = { navController.popBackStack() })
        }

        // Profile
        composable(Screen.PROFILE) {
            ProfileScreen(
                onNavigateBack = { navController.popBackStack() },
                onNavigateMain = { route -> navController.navigateTopLevel(route) },
                onLogout = {
                    navController.navigate(Screen.LOGIN) {
                        popUpTo(0) { inclusive = true }
                    }
                },
            )
        }
    }
}

private fun NavHostController.navigateTopLevel(route: String) {
    navigate(route) {
        launchSingleTop = true
        restoreState = true
        popUpTo(Screen.FILES) { saveState = true }
    }
}

@Composable
private fun PreviewRouter(
    fileId: Long,
    onNavigateBack: () -> Unit,
    viewModel: PreviewRouterViewModel = androidx.hilt.navigation.compose.hiltViewModel(),
) {
    val file by viewModel.file.collectAsState()
    val serverBaseUrl by viewModel.serverBaseUrl.collectAsState(initial = "")
    androidx.compose.runtime.LaunchedEffect(fileId) {
        viewModel.load(fileId)
    }
    val item = file
    val previewUrl = ServerUrlUtil.previewUrl(serverBaseUrl, fileId)
    when {
        item == null -> ImagePreviewScreen(previewUrl = previewUrl, onNavigateBack = onNavigateBack)
        FileTypeHelper.isVideo(item.fileName) -> VideoPreviewScreen(previewUrl = previewUrl, onNavigateBack = onNavigateBack)
        FileTypeHelper.isText(item.fileName) -> TextPreviewScreen(fileId = fileId, onNavigateBack = onNavigateBack)
        else -> ImagePreviewScreen(previewUrl = previewUrl, onNavigateBack = onNavigateBack)
    }
}

@Composable
private fun TeamPreviewRouter(
    teamId: Long,
    fileId: Long,
    onNavigateBack: () -> Unit,
    viewModel: TeamPreviewRouterViewModel = androidx.hilt.navigation.compose.hiltViewModel(),
) {
    val file by viewModel.file.collectAsState()
    val serverBaseUrl by viewModel.serverBaseUrl.collectAsState(initial = "")
    androidx.compose.runtime.LaunchedEffect(teamId, fileId) {
        viewModel.load(teamId, fileId)
    }
    val item = file
    val previewUrl = ServerUrlUtil.teamPreviewUrl(serverBaseUrl, teamId, fileId)
    when {
        item == null -> ImagePreviewScreen(previewUrl = previewUrl, onNavigateBack = onNavigateBack)
        FileTypeHelper.isVideo(item.fileName) -> VideoPreviewScreen(previewUrl = previewUrl, onNavigateBack = onNavigateBack)
        FileTypeHelper.isText(item.fileName) -> TextPreviewScreen(fileId = fileId, teamId = teamId, onNavigateBack = onNavigateBack)
        else -> ImagePreviewScreen(previewUrl = previewUrl, onNavigateBack = onNavigateBack)
    }
}
