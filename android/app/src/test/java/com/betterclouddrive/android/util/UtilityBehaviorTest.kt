package com.betterclouddrive.android.util

import org.junit.Assert.*
import org.junit.Test
import java.util.TimeZone

class UtilityBehaviorTest {
    @Test fun serverUrlsAreNormalizedAndBuiltSafely() {
        assertEquals("https://example.com", ServerUrlUtil.normalizeBaseUrl(" https://example.com/path/ ").getOrThrow())
        assertTrue(ServerUrlUtil.normalizeBaseUrl("").isFailure)
        assertTrue(ServerUrlUtil.normalizeBaseUrl("not a url").isFailure)
        assertEquals("http://localhost:8080/api/v1/", ServerUrlUtil.apiBaseUrl("http://localhost:8080").toString())
        assertEquals("http://localhost:8080/api/v1/preview/12", ServerUrlUtil.previewUrl("http://localhost:8080", 12))
        assertEquals("http://localhost:8080/api/v1/teams/7/preview/12", ServerUrlUtil.teamPreviewUrl("http://localhost:8080", 7, 12))
    }

    @Test fun fileTypesHandleKnownAndUnknownExtensions() {
        assertEquals("jpg", FileTypeHelper.getExtension("PHOTO.JPG"))
        assertEquals("", FileTypeHelper.getExtension("README"))
        assertTrue(FileTypeHelper.isImage("a.heic"))
        assertTrue(FileTypeHelper.isVideo("a.webm"))
        assertTrue(FileTypeHelper.isAudio("a.ogg"))
        assertTrue(FileTypeHelper.isText("a.yaml"))
        assertTrue(FileTypeHelper.isOffice("a.xlsx"))
        assertTrue(FileTypeHelper.isArchive("a.bz2"))
        assertFalse(FileTypeHelper.isImage("a.bin"))
        assertEquals("image/png", FileTypeHelper.getMimeType("a.png"))
        assertEquals("video/mp4", FileTypeHelper.getMimeType("a.mp4"))
        assertEquals("audio/mp3", FileTypeHelper.getMimeType("a.mp3"))
        assertEquals("application/pdf", FileTypeHelper.getMimeType("a.pdf"))
        assertEquals("application/octet-stream", FileTypeHelper.getMimeType("a.bin"))
        assertNotEquals(FileTypeHelper.getColorForExtension("a.pdf"), FileTypeHelper.getColorForExtension("a.unknown"))
    }

    @Test fun sizesAndDatesHandleBoundariesAndFallbacks() {
        assertEquals("0 B", FormatUtil.formatFileSize(-1))
        assertEquals("1 B", FormatUtil.formatFileSize(1))
        assertEquals("1 KB", FormatUtil.formatFileSize(1024))
        assertEquals("1 MB", FormatUtil.formatFileSize(1_048_576))
        assertEquals("1 GB", FormatUtil.formatFileSize(1_073_741_824))
        assertEquals("", FormatUtil.formatDate(null))
        assertEquals("bad date", FormatUtil.formatDate("bad date"))
        assertEquals("", FormatUtil.formatFullDate(""))
        assertEquals("2024-01-02 03:04:05", FormatUtil.formatFullDate("2024-01-02T03:04:05"))
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
        assertEquals("01-02 03:04", FormatUtil.formatDate("2024-01-02T03:04:05Z"))
        assertEquals("2024-01-02 03:04:05", FormatUtil.formatFullDate("2024-01-02T03:04:05Z"))
    }
}
