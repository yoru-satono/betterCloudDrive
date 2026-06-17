package com.betterclouddrive.android.ui.navigation

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

@Composable
fun MainScaffold(
    currentRoute: String,
    onNavigate: (String) -> Unit,
    snackbarHostState: SnackbarHostState? = null,
    floatingActionButton: @Composable () -> Unit = {},
    topBar: @Composable () -> Unit = {},
    content: @Composable (PaddingValues) -> Unit,
) {
    val isTablet = LocalConfiguration.current.screenWidthDp >= 600
    if (isTablet) {
        Row(modifier = Modifier.fillMaxSize()) {
            MainNavigationRail(currentRoute = currentRoute, onNavigate = onNavigate)
            Scaffold(
                topBar = topBar,
                snackbarHost = {
                    if (snackbarHostState != null) SnackbarHost(snackbarHostState)
                },
                floatingActionButton = floatingActionButton,
                containerColor = MaterialTheme.colorScheme.background,
            ) { padding ->
                content(padding)
            }
        }
    } else {
        Scaffold(
            topBar = topBar,
            snackbarHost = {
                if (snackbarHostState != null) SnackbarHost(snackbarHostState)
            },
            floatingActionButton = floatingActionButton,
            bottomBar = {
                MainNavigationBar(currentRoute = currentRoute, onNavigate = onNavigate)
            },
            containerColor = MaterialTheme.colorScheme.background,
        ) { padding ->
            content(padding)
        }
    }
}

@Composable
fun MainNavigationBar(
    currentRoute: String,
    onNavigate: (String) -> Unit,
) {
    NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
        bottomNavItems.forEach { item ->
            val selected = currentRoute == item.baseRoute
            NavigationBarItem(
                modifier = Modifier.testTag("nav-${item.baseRoute}"),
                selected = selected,
                onClick = { if (!selected) onNavigate(item.route) },
                icon = {
                    Icon(
                        imageVector = if (selected) item.selectedIcon else item.unselectedIcon,
                        contentDescription = item.label,
                    )
                },
                label = { Text(item.label) },
            )
        }
    }
}

@Composable
private fun MainNavigationRail(
    currentRoute: String,
    onNavigate: (String) -> Unit,
) {
    NavigationRail(
        modifier = Modifier.fillMaxHeight(),
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Spacer(modifier = Modifier.height(16.dp))
        railNavItems.forEach { item ->
            val selected = currentRoute == item.baseRoute
            NavigationRailItem(
                modifier = Modifier.testTag("nav-${item.baseRoute}"),
                selected = selected,
                onClick = { if (!selected) onNavigate(item.route) },
                icon = {
                    Icon(
                        imageVector = if (selected) item.selectedIcon else item.unselectedIcon,
                        contentDescription = item.label,
                    )
                },
                label = { Text(item.label) },
            )
        }
    }
}
