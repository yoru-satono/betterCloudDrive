package com.betterclouddrive.android.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.navigation.NavHostController

enum class Workspace { PERSONAL, TEAM }

internal val LocalWorkspaceNavController = staticCompositionLocalOf<NavHostController?> { null }
internal val LocalActiveWorkspace = staticCompositionLocalOf<MutableState<Workspace>?> { null }

@Composable
fun WorkspaceSwitchAction(workspace: Workspace? = null) {
    val navController = LocalWorkspaceNavController.current ?: return
    val activeWorkspace = LocalActiveWorkspace.current ?: return
    LaunchedEffect(workspace) {
        if (workspace != null) activeWorkspace.value = workspace
    }
    val current = workspace ?: activeWorkspace.value
    val target = if (current == Workspace.PERSONAL) Workspace.TEAM else Workspace.PERSONAL
    val description = if (target == Workspace.TEAM) "切换到团队空间" else "切换到个人网盘"
    IconButton(
        onClick = {
            activeWorkspace.value = target
            val destination = if (target == Workspace.TEAM) Screen.TEAMS else Screen.FILES
            navController.navigate(destination) {
                launchSingleTop = true
                popUpTo(Screen.FILES) { inclusive = true }
            }
        },
        modifier = Modifier.testTag("workspace-switch-${target.name.lowercase()}"),
    ) {
        Icon(
            imageVector = if (target == Workspace.TEAM) Icons.Default.Groups else Icons.Default.Folder,
            contentDescription = description,
        )
    }
}
