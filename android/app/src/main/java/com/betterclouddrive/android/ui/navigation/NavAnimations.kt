package com.betterclouddrive.android.ui.navigation

import androidx.compose.animation.*
import androidx.compose.animation.core.tween

object NavAnimations {
    val enterTransition: AnimatedContentTransitionScope<*>.() -> EnterTransition = {
        fadeIn(tween(300)) + slideInHorizontally(tween(300)) { it / 4 }
    }

    val exitTransition: AnimatedContentTransitionScope<*>.() -> ExitTransition = {
        fadeOut(tween(300)) + slideOutHorizontally(tween(300)) { -it / 4 }
    }

    val popEnterTransition: AnimatedContentTransitionScope<*>.() -> EnterTransition = {
        fadeIn(tween(300)) + slideInHorizontally(tween(300)) { -it / 4 }
    }

    val popExitTransition: AnimatedContentTransitionScope<*>.() -> ExitTransition = {
        fadeOut(tween(300)) + slideOutHorizontally(tween(300)) { it / 4 }
    }
}
