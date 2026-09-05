package com.betterclouddrive.mobile.app

import kotlinx.serialization.Serializable

@Serializable
sealed interface AppRoute

@Serializable
data object PersonalRoute : AppRoute

@Serializable
data class PersonalFolderRoute(val folderId: Long) : AppRoute

@Serializable
data object PersonalSearchRoute : AppRoute

@Serializable
data object PersonalFavoritesRoute : AppRoute

@Serializable
data object PersonalTagsRoute : AppRoute

@Serializable
data class PersonalTagFilesRoute(val tagId: Long) : AppRoute

@Serializable
data object PersonalSharesRoute : AppRoute

@Serializable
data class PersonalShareDetailRoute(val shareId: Long) : AppRoute

@Serializable
data class PersonalPreviewRoute(val fileId: Long) : AppRoute

@Serializable
data object PersonalRecycleBinRoute : AppRoute

@Serializable
data class PersonalVersionsRoute(val fileId: Long) : AppRoute

@Serializable
data object TeamsRoute : AppRoute

@Serializable
data class TeamWorkspaceRoute(val teamId: Long) : AppRoute

@Serializable
data class TeamFolderRoute(val teamId: Long, val folderId: Long) : AppRoute

@Serializable
data class TeamSearchRoute(val teamId: Long) : AppRoute

@Serializable
data class TeamPreviewRoute(val teamId: Long, val fileId: Long) : AppRoute

@Serializable
data class TeamMembersRoute(val teamId: Long) : AppRoute

@Serializable
data object TransfersRoute : AppRoute

@Serializable
data object ProfileRoute : AppRoute

@Serializable
data object DiagnosticsRoute : AppRoute

val topLevelRoutes: List<AppRoute> = listOf(
    PersonalRoute,
    TeamsRoute,
    TransfersRoute,
    ProfileRoute,
)

fun availableRoutes(debugToolsEnabled: Boolean): Set<AppRoute> = buildSet {
    addAll(topLevelRoutes)
    if (debugToolsEnabled) add(DiagnosticsRoute)
}
