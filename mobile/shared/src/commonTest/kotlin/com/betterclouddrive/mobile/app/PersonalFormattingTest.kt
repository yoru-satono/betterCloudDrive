package com.betterclouddrive.mobile.app

import kotlin.test.Test
import kotlin.test.assertEquals

class PersonalFormattingTest {
    @Test
    fun fileSizeFormattingHandlesLegacyBoundaries() {
        assertEquals("0 B", formatFileSize(-1))
        assertEquals("0 B", formatFileSize(0))
        assertEquals("1 B", formatFileSize(1))
        assertEquals("1023 B", formatFileSize(1023))
        assertEquals("1 KiB", formatFileSize(1024))
        assertEquals("1023 KiB", formatFileSize(1_048_575))
        assertEquals("1 MiB", formatFileSize(1_048_576))
        assertEquals("1 GiB", formatFileSize(1_073_741_824))
    }

    @Test
    fun dateFormattingPreservesStableApiPrefixAndNullFallback() {
        assertEquals("2024-01-02 03:04:05", formatDate("2024-01-02T03:04:05"))
        assertEquals("2024-01-02 03:04:05", formatDate("2024-01-02T03:04:05Z"))
        assertEquals("—", formatDate(""))
        assertEquals("bad date", formatDate("bad date"))
        assertEquals("—", formatDate(null))
    }
}
