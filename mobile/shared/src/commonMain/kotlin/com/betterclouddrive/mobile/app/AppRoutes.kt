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
data class PersonalPreviewRoute(val fileId: Long) : AppRoute

@Serializable
data object TeamsRoute : AppRoute

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
