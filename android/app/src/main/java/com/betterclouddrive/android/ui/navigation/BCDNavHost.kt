package com.betterclouddrive.android.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.betterclouddrive.android.ui.auth.*
import com.betterclouddrive.android.ui.files.*
import com.betterclouddrive.android.ui.favorites.FavoritesScreen
import com.betterclouddrive.android.ui.preview.ImagePreviewScreen
import com.betterclouddrive.android.ui.preview.TextPreviewScreen
import com.betterclouddrive.android.ui.preview.VideoPreviewScreen
import com.betterclouddrive.android.ui.profile.*
import com.betterclouddrive.android.ui.recyclebin.RecycleBinScreen
import com.betterclouddrive.android.ui.search.SearchResultScreen
import com.betterclouddrive.android.ui.shares.SharesScreen
import com.betterclouddrive.android.ui.tags.TagsScreen
import com.betterclouddrive.android.ui.tags.TagFilesScreen
import com.betterclouddrive.android.ui.transfer.TransferQueueScreen
import com.betterclouddrive.android.ui.versions.VersionsScreen
import com.betterclouddrive.android.util.FileTypeHelper
import com.betterclouddrive.android.util.ServerUrlUtil

@Composable
fun BCDNavHost(
    navController: NavHostController = rememberNavController(),
) {
    NavHost(
        navController = navController,
        startDestination = Screen.LOGIN,
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
                    navController.navigate(Screen.files(null)) {
                        popUpTo(Screen.LOGIN) { inclusive = true }
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
                onNavigateToEmailVerification = { navController.navigate(Screen.EMAIL_VERIFICATION) },
                onNavigateMain = { route -> navController.navigateTopLevel(route) },
                onLogout = {
                    navController.navigate(Screen.LOGIN) {
                        popUpTo(0) { inclusive = true }
                    }
                },
            )
        }

        // Email Verification
        composable(Screen.EMAIL_VERIFICATION) {
            EmailVerificationScreen(onNavigateBack = { navController.popBackStack() })
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
