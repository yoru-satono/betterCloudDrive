package com.betterclouddrive.mobile.designsystem

import com.betterclouddrive.mobile.core.platform.ThemeMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ThemeBehaviorTest {
    @Test
    fun invalidStoredModeFallsBackToSystem() {
        assertEquals(ThemeMode.SYSTEM, ThemeMode.fromStorage("sepia"))
        assertEquals(ThemeMode.SYSTEM, ThemeMode.fromStorage(null))
        assertEquals(ThemeMode.DARK, ThemeMode.fromStorage("DARK"))
    }

    @Test
    fun explicitModeOverridesSystemAndSystemModeFollowsIt() {
        assertTrue(resolveDarkTheme(ThemeMode.SYSTEM, systemDark = true))
        assertFalse(resolveDarkTheme(ThemeMode.SYSTEM, systemDark = false))
        assertTrue(resolveDarkTheme(ThemeMode.DARK, systemDark = false))
        assertFalse(resolveDarkTheme(ThemeMode.LIGHT, systemDark = true))
    }
}
