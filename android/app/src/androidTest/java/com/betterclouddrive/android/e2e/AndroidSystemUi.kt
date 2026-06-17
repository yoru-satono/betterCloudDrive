package com.betterclouddrive.android.e2e

import android.content.ContentValues
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class AndroidSystemUi {
    private val documentsUiPackage = "com.android.documentsui"
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    val device: UiDevice = UiDevice.getInstance(instrumentation)

    fun seedDownloadFile(fileName: String, content: String): File {
        val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        dir.mkdirs()
        if (!publishToDownloads(fileName, content)) {
            val file = File(dir, fileName)
            file.writeText(content)
            val latch = CountDownLatch(1)
            MediaScannerConnection.scanFile(
                instrumentation.targetContext,
                arrayOf(file.absolutePath),
                arrayOf("text/plain"),
            ) { _, _ -> latch.countDown() }
            latch.await(5, TimeUnit.SECONDS)
        }
        waitForDownloadsEntry(fileName)
        return File(dir, fileName)
    }

    fun pickFileFromSystemPicker(fileName: String, timeoutMs: Long = 30_000) {
        waitForDocumentsUi(timeoutMs)

        val deadline = System.currentTimeMillis() + timeoutMs
        var searchOpened = openDocumentSearch(fileName)
        while (System.currentTimeMillis() < deadline) {
            val candidate = findDocumentRow(fileName)
            if (candidate != null) {
                candidate.click()
                confirmOpenDocumentIfNeeded()
                return
            }

            if (!searchOpened && System.currentTimeMillis() > deadline - timeoutMs / 2) {
                searchOpened = openDocumentSearch(fileName)
            }

            device.swipe(
                device.displayWidth / 2,
                device.displayHeight * 3 / 4,
                device.displayWidth / 2,
                device.displayHeight / 4,
                12,
            )
            Thread.sleep(500)
        }
        throw AssertionError("Cannot find file '$fileName' in Android picker")
    }

    fun acceptCreateDocument(fileName: String, timeoutMs: Long = 15_000) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val save = findCreateDocumentAction()
            if (save != null) {
                save.click()
                acceptReplaceIfPrompted()
                return
            }
            Thread.sleep(500)
        }
        throw AssertionError("Cannot confirm CreateDocument picker for '$fileName'")
    }

    fun grantPermissionIfPrompted() {
        listOf("允许", "Allow", "仅这一次", "While using the app").forEach(::clickIfVisible)
    }

    private fun waitForDocumentsUi(timeoutMs: Long) {
        if (!device.wait(Until.hasObject(By.pkg(documentsUiPackage)), timeoutMs)) {
            throw AssertionError("Android DocumentsUI did not open")
        }
        device.waitForIdle()
    }

    private fun clickIfVisible(text: String): Boolean {
        val node = device.findObject(By.text(text)) ?: return false
        node.click()
        return true
    }

    private fun findCreateDocumentAction(): UiObject2? {
        val actionIds = listOf(
            "com.android.documentsui:id/action_menu_save",
            "com.android.documentsui:id/action_menu_select",
            "com.google.android.documentsui:id/action_menu_save",
            "com.google.android.documentsui:id/action_menu_select",
            "android:id/button1",
        )
        val byId = actionIds.firstNotNullOfOrNull { id ->
            device.findObject(By.res(id))?.takeIf { it.isEnabled && it.visibleBounds.height() > 0 }
        }
        if (byId != null) return byId

        val labels = listOf(
            "保存",
            "儲存",
            "存储",
            "Save",
            "SAVE",
            "确定",
            "確定",
            "OK",
            "选择",
            "選取",
            "Select",
            "建立",
            "Create",
        )
        return labels.firstNotNullOfOrNull { label ->
            findClickableTextOrDescription(label)
        }
    }

    private fun confirmOpenDocumentIfNeeded() {
        val deadline = System.currentTimeMillis() + 2_000
        while (System.currentTimeMillis() < deadline) {
            val action = findOpenDocumentAction()
            if (action != null) {
                action.click()
                return
            }
            if (!device.hasObject(By.pkg(documentsUiPackage))) return
            Thread.sleep(200)
        }
    }

    private fun findOpenDocumentAction(): UiObject2? {
        val actionIds = listOf(
            "com.android.documentsui:id/action_menu_open",
            "com.android.documentsui:id/action_menu_select",
            "android:id/button1",
        )
        val byId = actionIds.firstNotNullOfOrNull { id ->
            device.findObject(By.res(id))?.takeIf(::isVisibleEnabled)
        }
        if (byId != null) return byId

        val labels = listOf("打开", "開啟", "Open", "OPEN", "选择", "選取", "Select", "SELECT", "完成", "Done")
        return labels.firstNotNullOfOrNull { label -> findClickableTextOrDescription(label) }
    }

    private fun openDocumentSearch(fileName: String): Boolean {
        val existingSearchField = device.findObject(By.res("com.android.documentsui:id/search_src_text"))
        if (existingSearchField != null && isVisibleEnabled(existingSearchField)) {
            existingSearchField.text = fileName
            device.pressEnter()
            return true
        }

        val searchAction = device.findObject(By.res("com.android.documentsui:id/option_menu_search"))
            ?.let(::clickableAncestor)
            ?: findClickableTextOrDescription("搜索")
            ?: findClickableTextOrDescription("搜尋")
            ?: findClickableTextOrDescription("Search")
            ?: return false
        if (!isVisibleEnabled(searchAction)) return false
        searchAction.click()
        val searchField = device.wait(
            Until.findObject(By.res("com.android.documentsui:id/search_src_text")),
            2_000,
        ) ?: return false
        searchField.text = fileName
        device.pressEnter()
        return true
    }

    private fun acceptReplaceIfPrompted() {
        val labels = listOf("取代", "替换", "覆蓋", "覆盖", "Replace", "REPLACE", "确定", "確定", "OK")
        val deadline = System.currentTimeMillis() + 2_000
        while (System.currentTimeMillis() < deadline) {
            val node = labels.firstNotNullOfOrNull { label -> findClickableTextOrDescription(label) }
            if (node != null) {
                node.click()
                return
            }
            Thread.sleep(200)
        }
    }

    private fun findClickableTextOrDescription(label: String): UiObject2? {
        return sequenceOf(
            device.findObjects(By.text(label)).asSequence(),
            device.findObjects(By.textContains(label)).asSequence(),
            device.findObjects(By.desc(label)).asSequence(),
            device.findObjects(By.descContains(label)).asSequence(),
        ).flatten().firstOrNull(::isVisibleEnabled)
    }

    private fun findDocumentRow(fileName: String): UiObject2? {
        val exactTitle = device.findObjects(By.res("android:id/title")).firstOrNull {
            it.text == fileName || it.contentDescription == fileName
        }
        if (exactTitle != null) return clickableAncestor(exactTitle)

        val titleNode = device.findObjects(By.res("android:id/title")).firstOrNull {
            it.text == fileName || it.text?.contains(fileName) == true || it.contentDescription?.contains(fileName) == true
        }
        if (titleNode != null) return clickableAncestor(titleNode)

        val matchingNode = sequenceOf(
            device.findObjects(By.text(fileName)).asSequence(),
            device.findObjects(By.textContains(fileName)).asSequence(),
            device.findObjects(By.desc(fileName)).asSequence(),
            device.findObjects(By.descContains(fileName)).asSequence(),
        ).flatten().firstOrNull(::isVisibleEnabled)
        return matchingNode?.let(::clickableAncestor)
    }

    private fun clickableAncestor(node: UiObject2): UiObject2 {
        var current: UiObject2? = node
        while (current != null) {
            if (current.isClickable && isVisibleEnabled(current)) return current
            current = current.parent
        }
        return node
    }

    private fun isVisibleEnabled(node: UiObject2): Boolean {
        return node.isEnabled && node.visibleBounds.width() > 0 && node.visibleBounds.height() > 0
    }

    private fun waitForDownloadsEntry(fileName: String, timeoutMs: Long = 5_000): Boolean {
        val resolver = instrumentation.targetContext.contentResolver
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            resolver.query(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.MediaColumns.DISPLAY_NAME),
                "${MediaStore.MediaColumns.DISPLAY_NAME} = ?",
                arrayOf(fileName),
                null,
            )?.use { cursor ->
                if (cursor.moveToFirst()) return true
            }
            Thread.sleep(200)
        }
        return false
    }

    private fun publishToDownloads(fileName: String, content: String): Boolean {
        val resolver = instrumentation.targetContext.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return false
        return try {
            resolver.openOutputStream(uri)?.use { output ->
                output.write(content.toByteArray(Charsets.UTF_8))
            } ?: return false
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                resolver.update(
                    uri,
                    ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) },
                    null,
                    null,
                )
            }
            true
        } catch (_: Exception) {
            resolver.delete(uri, null, null)
            false
        }
    }
}
