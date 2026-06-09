package com.betterclouddrive.android.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
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
import com.betterclouddrive.android.ui.versions.VersionsScreen

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
        composable(
            route = Screen.FILES,
            arguments = listOf(navArgument("folderId") {
                type = NavType.StringType
                defaultValue = "null"
            }),
        ) { backStackEntry ->
            val folderId = backStackEntry.arguments?.getString("folderId")
                ?.toLongOrNull()
            FileBrowserScreen(
                folderId = folderId,
                onNavigateToPreview = { fileId -> navController.navigate(Screen.preview(fileId)) },
                onNavigateToSearch = { navController.navigate(Screen.SEARCH) },
                onNavigateToVersions = { fileId -> navController.navigate(Screen.versions(fileId)) },
                onNavigateToRecycleBin = { navController.navigate(Screen.RECYCLE_BIN) },
                onNavigateToProfile = { navController.navigate(Screen.PROFILE) },
                onNavigateToShares = { navController.navigate(Screen.SHARES) },
                onNavigateToFavorites = { navController.navigate(Screen.FAVORITES) },
                onNavigateToTags = { navController.navigate(Screen.TAGS) },
                onLogout = {
                    navController.navigate(Screen.LOGIN) {
                        popUpTo(0) { inclusive = true }
                    }
                },
            )
        }

        // Search
        composable(Screen.SEARCH) {
            SearchResultScreen(onNavigateBack = { navController.popBackStack() })
        }

        // Preview
        composable(
            route = Screen.PREVIEW,
            arguments = listOf(navArgument("fileId") { type = NavType.LongType }),
        ) { backStackEntry ->
            val fileId = backStackEntry.arguments?.getLong("fileId") ?: return@composable
            when {
                // We'll resolve file type inside the screen — for now default to Image
                else -> ImagePreviewScreen(fileId = fileId, onNavigateBack = { navController.popBackStack() })
            }
        }

        // Recycle Bin
        composable(Screen.RECYCLE_BIN) {
            RecycleBinScreen(onNavigateBack = { navController.popBackStack() })
        }

        // Shares
        composable(Screen.SHARES) {
            SharesScreen(onNavigateBack = { navController.popBackStack() })
        }

        // Favorites
        composable(Screen.FAVORITES) {
            FavoritesScreen(onNavigateBack = { navController.popBackStack() })
        }

        // Tags
        composable(Screen.TAGS) {
            TagsScreen(
                onNavigateBack = { navController.popBackStack() },
                onNavigateToTagFiles = { tagId -> navController.navigate(Screen.tagFiles(tagId)) },
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
