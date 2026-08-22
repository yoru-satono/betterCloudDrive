package com.betterclouddrive.mobile.platform

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import com.betterclouddrive.mobile.core.platform.DocumentPicker
import com.betterclouddrive.mobile.core.platform.FileDigest
import com.betterclouddrive.mobile.core.platform.PickedDocument
import com.betterclouddrive.mobile.core.platform.PickedDestination
import com.betterclouddrive.mobile.core.platform.TransferFileAccess
import io.ktor.client.request.forms.InputProvider
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.io.asSource
import kotlinx.io.buffered
import java.io.File
import java.io.FilterInputStream
import java.io.InputStream
import java.security.MessageDigest

internal class AndroidDocumentPicker(private val context: Context) : DocumentPicker {
    @Composable
    override fun UploadButton(enabled: Boolean, onPicked: (PickedDocument) -> Unit) {
        var pendingDocument by remember { mutableStateOf<PickedDocument?>(null) }
        val notifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
            pendingDocument?.let(onPicked)
            pendingDocument = null
        }
        val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri?.let {
                val document = describe(it, read = true)
                if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                    pendingDocument = document
                    notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
                } else {
                    onPicked(document)
                }
            }
        }
        Button(enabled = enabled, onClick = {
            launcher.launch(arrayOf("*/*"))
        }) { Text("选择文件") }
    }

    @Composable
    override fun DownloadButton(fileName: String, enabled: Boolean, onPicked: (PickedDestination) -> Unit) {
        var pendingDestination by remember { mutableStateOf<PickedDestination?>(null) }
        val notifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
            pendingDestination?.let(onPicked)
            pendingDestination = null
        }
        val launcher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
            uri?.let {
                persist(it, Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)
                val destination = PickedDestination(it.toString(), fileName)
                if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                    pendingDestination = destination
                    notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
                } else {
                    onPicked(destination)
                }
            }
        }
        Button(enabled = enabled, onClick = {
            launcher.launch(fileName)
        }) { Text("下载") }
    }

    private fun describe(uri: Uri, read: Boolean): PickedDocument {
        if (read) persist(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        var name = "selected-file"
        var size = 0L
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME).takeIf { it >= 0 }?.let { name = cursor.getString(it) ?: name }
                cursor.getColumnIndex(OpenableColumns.SIZE).takeIf { it >= 0 && !cursor.isNull(it) }?.let { size = cursor.getLong(it) }
            }
        }
        return PickedDocument(uri.toString(), name, size, context.contentResolver.getType(uri))
    }

    private fun persist(uri: Uri, flags: Int) {
        runCatching { context.contentResolver.takePersistableUriPermission(uri, flags) }
    }
}

class AndroidTransferFileAccess(private val context: Context) : TransferFileAccess {
    private val tempDirectory = File(context.filesDir, "transfers").apply { mkdirs() }

    override suspend fun validateReadable(sourceHandle: String) = withContext(Dispatchers.IO) {
        val uri = Uri.parse(sourceHandle)
        check(hasPersistedPermission(uri, read = true)) { "文件提供方未授予持久读取权限" }
        open(sourceHandle).use { }
    }

    override suspend fun validateWritable(destinationHandle: String) = withContext(Dispatchers.IO) {
        val uri = Uri.parse(destinationHandle)
        check(hasPersistedPermission(uri, read = false)) { "文件提供方未授予持久写入权限" }
        context.contentResolver.openOutputStream(uri, "wa")?.use { }
            ?: error("无法写入下载目标")
    }

    override suspend fun computeDigest(sourceHandle: String): FileDigest = withContext(Dispatchers.IO) {
        val digest = MessageDigest.getInstance("MD5")
        var size = 0L
        open(sourceHandle).use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
                size += count
            }
        }
        FileDigest(digest.digest().toHex(), size)
    }

    override fun uploadPart(sourceHandle: String, offset: Long, length: Long, onBytes: (Long) -> Unit): InputProvider = InputProvider(length) {
        val input = open(sourceHandle)
        skipFully(input, offset)
        LimitedInputStream(input, length, onBytes).asSource().buffered()
    }

    override suspend fun tempLength(taskId: String): Long = withContext(Dispatchers.IO) { temp(taskId).let { if (it.exists()) it.length() else 0L } }

    override suspend fun tempMd5(taskId: String): String = withContext(Dispatchers.IO) {
        val digest = MessageDigest.getInstance("MD5")
        temp(taskId).inputStream().buffered().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        digest.digest().toHex()
    }

    override suspend fun resetTemp(taskId: String) = withContext(Dispatchers.IO) {
        temp(taskId).outputStream().use { }
    }

    override suspend fun appendTemp(taskId: String, channel: ByteReadChannel, onBytes: suspend (Long) -> Unit) = withContext(Dispatchers.IO) {
        java.io.FileOutputStream(temp(taskId), true).buffered().use { output ->
            val buffer = ByteArray(64 * 1024)
            var pendingProgress = 0L
            while (!channel.isClosedForRead) {
                val count = channel.readAvailable(buffer, 0, buffer.size)
                if (count == -1) break
                if (count == 0) continue
                output.write(buffer, 0, count)
                pendingProgress += count
                if (pendingProgress >= 256 * 1024) {
                    onBytes(pendingProgress)
                    pendingProgress = 0
                }
            }
            if (pendingProgress > 0) onBytes(pendingProgress)
        }
    }

    override suspend fun commitTemp(taskId: String, destinationHandle: String) = withContext(Dispatchers.IO) {
        val destination = Uri.parse(destinationHandle)
        context.contentResolver.openOutputStream(destination, "wt")?.use { output ->
            temp(taskId).inputStream().buffered().use { it.copyTo(output) }
        } ?: error("无法写入下载目标")
        Unit
    }

    override suspend fun deleteTemp(taskId: String) = withContext(Dispatchers.IO) { temp(taskId).delete(); Unit }

    override suspend fun deleteAllTemps() = withContext(Dispatchers.IO) {
        tempDirectory.listFiles()?.filter { it.isFile && it.name.endsWith(".part") }?.forEach { it.delete() }
        Unit
    }

    private fun open(handle: String): InputStream = context.contentResolver.openInputStream(Uri.parse(handle)) ?: error("无法读取所选文件")
    private fun temp(taskId: String): File = File(tempDirectory, "$taskId.part")

    private fun hasPersistedPermission(uri: Uri, read: Boolean): Boolean =
        context.contentResolver.persistedUriPermissions.any { permission ->
            permission.uri == uri && if (read) permission.isReadPermission else permission.isWritePermission
        }

    private fun skipFully(input: InputStream, offset: Long) {
        var remaining = offset
        while (remaining > 0) {
            val skipped = input.skip(remaining)
            if (skipped > 0) remaining -= skipped else if (input.read() == -1) error("文件长度不足") else remaining--
        }
    }
}

private class LimitedInputStream(
    input: InputStream,
    private var remaining: Long,
    private val onBytes: (Long) -> Unit,
) : FilterInputStream(input) {
    override fun read(): Int {
        if (remaining <= 0) return -1
        val value = super.read()
        if (value >= 0) {
            remaining--
            onBytes(1)
        }
        return value
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (remaining <= 0) return -1
        val count = super.read(buffer, offset, minOf(length.toLong(), remaining).toInt())
        if (count > 0) {
            remaining -= count
            onBytes(count.toLong())
        }
        return count
    }

    override fun close() = super.close()
}

private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
