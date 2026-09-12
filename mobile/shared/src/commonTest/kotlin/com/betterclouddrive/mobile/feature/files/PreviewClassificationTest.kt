package com.betterclouddrive.mobile.feature.files

import com.betterclouddrive.mobile.core.model.FileItem
import com.betterclouddrive.mobile.core.model.PreviewKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PreviewClassificationTest {
    @Test
    fun officeWinsOverTextAndMimeFallsBackToExtension() {
        assertEquals(PreviewKind.OFFICE, FileItem(fileName = "sheet.csv", mimeType = "text/csv").previewKind())
        assertEquals(
            PreviewKind.OFFICE,
            FileItem(fileName = "download.bin", mimeType = "application/vnd.openxmlformats-officedocument.wordprocessingml.document").previewKind(),
        )
        assertEquals(PreviewKind.IMAGE, FileItem(fileName = "photo.bin", mimeType = "image/png").previewKind())
        assertEquals(PreviewKind.IMAGE, FileItem(fileName = "PHOTO.HEIC").previewKind())
        assertEquals(PreviewKind.TEXT, FileItem(fileName = "README.md", mimeType = "application/octet-stream").previewKind())
        assertEquals(PreviewKind.TEXT, FileItem(fileName = "config.yaml").previewKind())
        assertEquals(PreviewKind.VIDEO, FileItem(fileName = "clip.mp4").previewKind())
        assertEquals(PreviewKind.VIDEO, FileItem(fileName = "clip.webm").previewKind())
        assertEquals(PreviewKind.UNSUPPORTED, FileItem(fileName = "archive.zip").previewKind())
        assertEquals(PreviewKind.UNSUPPORTED, FileItem(fileName = "track.ogg").previewKind())
        assertEquals(PreviewKind.UNSUPPORTED, FileItem(fileName = "folder", fileType = "folder").previewKind())
    }

    @Test
    fun textPreviewRemovesUtf8BomAndReportsTruncationFromRemoteSize() {
        val bytes = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + "你好".encodeToByteArray()

        val complete = decodePersonalTextPreview(bytes, bytes.size.toLong())
        val truncated = decodePersonalTextPreview(bytes, bytes.size + 1L)

        assertEquals("你好", complete.content)
        assertFalse(complete.truncated)
        assertTrue(truncated.truncated)
    }
}
