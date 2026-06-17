package com.betterclouddrive.android.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.ui.graphics.vector.ImageVector

object Screen {
    // Auth
    const val LOGIN = "login"
    const val REGISTER = "register"
    const val FORGOT_PASSWORD = "forgotPassword"

    // Main
    const val FILES = "files"
    const val FILES_FOLDER = "files/{folderId}"
    const val SEARCH = "search"
    const val PREVIEW = "preview/{fileId}"
    const val RECYCLE_BIN = "recycleBin"
    const val SHARES = "shares"
    const val FAVORITES = "favorites"
    const val TAGS = "tags"
    const val TAG_FILES = "tagFiles/{tagId}"
    const val VERSIONS = "versions/{fileId}"
    const val PROFILE = "profile"
    const val EMAIL_VERIFICATION = "emailVerification"
    const val TRANSFERS = "transfers"

    fun files(folderId: Long? = null) = if (folderId != null) "files/$folderId" else FILES
    fun preview(fileId: Long) = "preview/$fileId"
    fun tagFiles(tagId: Long) = "tagFiles/$tagId"
    fun versions(fileId: Long) = "versions/$fileId"
}

data class BottomNavItem(
    val route: String,
    val baseRoute: String,
    val label: String,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector,
)

val bottomNavItems = listOf(
    BottomNavItem(Screen.files(null), "files", "文件", Icons.Filled.Folder, Icons.Outlined.Folder),
    BottomNavItem(Screen.SHARES, Screen.SHARES, "分享", Icons.Filled.Share, Icons.Outlined.Share),
    BottomNavItem(Screen.FAVORITES, Screen.FAVORITES, "收藏", Icons.Filled.Star, Icons.Outlined.Star),
    BottomNavItem(Screen.TAGS, Screen.TAGS, "标签", Icons.Filled.Label, Icons.Outlined.Label),
    BottomNavItem(Screen.PROFILE, Screen.PROFILE, "我的", Icons.Filled.Person, Icons.Outlined.Person),
)

val railNavItems = listOf(
    BottomNavItem(Screen.files(null), "files", "文件", Icons.Filled.Folder, Icons.Outlined.Folder),
    BottomNavItem(Screen.SHARES, Screen.SHARES, "分享", Icons.Filled.Share, Icons.Outlined.Share),
    BottomNavItem(Screen.FAVORITES, Screen.FAVORITES, "收藏", Icons.Filled.Star, Icons.Outlined.Star),
    BottomNavItem(Screen.TAGS, Screen.TAGS, "标签", Icons.Filled.Label, Icons.Outlined.Label),
    BottomNavItem(Screen.RECYCLE_BIN, Screen.RECYCLE_BIN, "回收站", Icons.Filled.Delete, Icons.Outlined.Delete),
    BottomNavItem(Screen.TRANSFERS, Screen.TRANSFERS, "传输", Icons.Filled.Sync, Icons.Outlined.Sync),
    BottomNavItem(Screen.PROFILE, Screen.PROFILE, "设置", Icons.Filled.Settings, Icons.Outlined.Settings),
)
