package com.betterclouddrive.android.ui.navigation

import android.net.Uri
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.ui.graphics.vector.ImageVector

object Screen {
    // Auth
    const val LOGIN = "login"
    const val REGISTER = "register"
    const val FORGOT_PASSWORD = "forgotPassword"
    const val TEAM_INVITE = "teamInvites/{token}"

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
    const val TEAMS = "teams"
    const val TEAM_FILES = "teams/{teamId}/files"
    const val TEAM_FOLDER = "teams/{teamId}/files/{folderId}"
    const val TEAM_MEMBERS = "teams/{teamId}/members"
    const val TEAM_SETTINGS = "teams/{teamId}/settings"
    const val TEAM_TASKS = "teams/{teamId}/tasks"
    const val TEAM_RECYCLE_BIN = "teams/{teamId}/recycleBin"
    const val TEAM_VERSIONS = "teams/{teamId}/versions/{fileId}"
    const val TEAM_OFFICE = "teams/{teamId}/office/{fileId}"
    const val TEAM_PREVIEW = "teams/{teamId}/preview/{fileId}"
    const val NOTIFICATIONS = "notifications"
    const val PROFILE = "profile"
    const val TRANSFERS = "transfers"

    fun files(folderId: Long? = null) = if (folderId != null) "files/$folderId" else FILES
    fun teamInvite(token: String) = "teamInvites/${Uri.encode(token)}"
    fun preview(fileId: Long) = "preview/$fileId"
    fun tagFiles(tagId: Long) = "tagFiles/$tagId"
    fun versions(fileId: Long) = "versions/$fileId"
    fun teamFiles(teamId: Long, folderId: Long? = null) = if (folderId != null) "teams/$teamId/files/$folderId" else "teams/$teamId/files"
    fun teamMembers(teamId: Long) = "teams/$teamId/members"
    fun teamSettings(teamId: Long) = "teams/$teamId/settings"
    fun teamTasks(teamId: Long) = "teams/$teamId/tasks"
    fun teamRecycleBin(teamId: Long) = "teams/$teamId/recycleBin"
    fun teamVersions(teamId: Long, fileId: Long) = "teams/$teamId/versions/$fileId"
    fun teamOffice(teamId: Long, fileId: Long) = "teams/$teamId/office/$fileId"
    fun teamPreview(teamId: Long, fileId: Long) = "teams/$teamId/preview/$fileId"
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
