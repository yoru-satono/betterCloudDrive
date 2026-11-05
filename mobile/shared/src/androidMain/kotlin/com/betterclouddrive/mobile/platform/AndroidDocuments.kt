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
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import com.betterclouddrive.mobile.core.platform.DocumentPicker
import com.betterclouddrive.mobile.core.platform.DownloadRequest
import com.betterclouddrive.mobile.core.platform.FileDigest
import com.betterclouddrive.mobile.core.platform.PickedDocument
import com.betterclouddrive.mobile.core.platform.PickedDestination
import com.betterclouddrive.mobile.core.platform.TransferFileAccess
import com.betterclouddrive.mobile.core.platform.UploadPickResult
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
    override fun rememberUploadLauncher(maxItems: Int, onPicked: (UploadPickResult) -> Unit): () -> Unit {
        require(maxItems > 0) { "document.invalidSelectionLimit" }
        var pendingResult by remember { mutableStateOf<UploadPickResult?>(null) }
        val notifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
            pendingResult?.let(onPicked)
            pendingResult = null
        }
        val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
            if (uris.isEmpty()) return@rememberLauncherForActivityResult
            val unique = uris.distinctBy(Uri::toString)
            val selected = unique.take(maxItems)
            var rejected = 0
            val documents = selected.mapNotNull { uri ->
                runCatching { describe(uri, read = true) }.getOrElse {
                    release(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    rejected++
                    null
                }
            }
            val result = UploadPickResult(
                documents = documents,
                rejectedCount = rejected,
                overflowCount = (unique.size - maxItems).coerceAtLeast(0),
            )
            if (documents.isNotEmpty() && needsNotificationPermission()) {
                pendingResult = result
                notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                onPicked(result)
            }
        }
        return { launcher.launch(arrayOf("*/*")) }
    }

    @Composable
    override fun rememberDownloadLauncher(onPicked: (PickedDestination) -> Unit): (DownloadRequest) -> Unit {
        var pendingRequest by remember { mutableStateOf<DownloadRequest?>(null) }
        var pendingDestination by remember { mutableStateOf<PickedDestination?>(null) }
        val notifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
            pendingDestination?.let(onPicked)
            pendingDestination = null
        }
        val launcher = rememberLauncherForActivityResult(CreateDownloadDocument()) { uri ->
            uri?.let {
                persist(it, Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)
                val destination = PickedDestination(it.toString(), pendingRequest?.fileName ?: "download")
                if (needsNotificationPermission()) {
                    pendingDestination = destination
                    notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
                } else {
                    onPicked(destination)
                }
            }
            pendingRequest = null
        }
        return { request ->
            pendingRequest = request
            launcher.launch(request)
        }
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

    private fun release(uri: Uri, flags: Int) {
        runCatching { context.contentResolver.releasePersistableUriPermission(uri, flags) }
    }

    private fun needsNotificationPermission(): Boolean =
        Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
}

private class CreateDownloadDocument : ActivityResultContract<DownloadRequest, Uri?>() {
    override fun createIntent(context: Context, input: DownloadRequest): Intent =
        Intent(Intent.ACTION_CREATE_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType(input.mimeType?.takeIf(String::isNotBlank) ?: "application/octet-stream")
            .putExtra(Intent.EXTRA_TITLE, input.fileName)

    override fun parseResult(resultCode: Int, intent: Intent?): Uri? =
        if (resultCode == android.app.Activity.RESULT_OK) intent?.data else null
}

class AndroidTransferFileAccess(private val context: Context) : TransferFileAccess {
    private val tempDirectory = File(context.filesDir, "transfers").apply { mkdirs() }

    override suspend fun validateReadable(sourceHandle: String) = withContext(Dispatchers.IO) {
        val uri = Uri.parse(sourceHandle)
        check(hasPersistedPermission(uri, read = true)) { "document.readPermissionMissing" }
        open(sourceHandle).use { }
    }

    override suspend fun validateWritable(destinationHandle: String) = withContext(Dispatchers.IO) {
        val uri = Uri.parse(destinationHandle)
        check(hasPersistedPermission(uri, read = false)) { "document.writePermissionMissing" }
        context.contentResolver.openOutputStream(uri, "wa")?.use { }
            ?: error("document.destinationWriteFailed")
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

    override suspend fun computePartDigest(sourceHandle: String, offset: Long, length: Long): FileDigest = withContext(Dispatchers.IO) {
        require(offset >= 0 && length >= 0)
        val digest = MessageDigest.getInstance("MD5")
        var remaining = length
        open(sourceHandle).use { input ->
            skipFully(input, offset)
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (remaining > 0) {
                val count = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                check(count >= 0) { "transfer.sourceChanged" }
                digest.update(buffer, 0, count)
                remaining -= count
            }
        }
        FileDigest(digest.digest().toHex(), length)
    }

    override fun uploadPart(sourceHandle: String, offset: Long, length: Long, onBytes: (Long) -> Unit): InputProvider = InputProvider(length) {
        val input = open(sourceHandle)
        try {
            skipFully(input, offset)
            LimitedInputStream(input, length, onBytes).asSource().buffered()
        } catch (error: Throwable) {
            input.close()
            throw error
        }
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
        } ?: error("document.destinationWriteFailed")
        Unit
    }

    override suspend fun deleteTemp(taskId: String) = withContext(Dispatchers.IO) { temp(taskId).delete(); Unit }

    override suspend fun deleteAllTemps() = withContext(Dispatchers.IO) {
        tempDirectory.listFiles()?.filter { it.isFile && it.name.endsWith(".part") }?.forEach { it.delete() }
        Unit
    }

    override suspend fun releaseReadable(sourceHandle: String) = withContext(Dispatchers.IO) {
        runCatching {
            context.contentResolver.releasePersistableUriPermission(
                Uri.parse(sourceHandle), Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        }
        Unit
    }

    override suspend fun releaseWritable(destinationHandle: String) = withContext(Dispatchers.IO) {
        runCatching {
            context.contentResolver.releasePersistableUriPermission(
                Uri.parse(destinationHandle), Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        }
        Unit
    }

    private fun open(handle: String): InputStream = context.contentResolver.openInputStream(Uri.parse(handle)) ?: error("document.sourceReadFailed")
    private fun temp(taskId: String): File = File(tempDirectory, "$taskId.part")

    private fun hasPersistedPermission(uri: Uri, read: Boolean): Boolean =
        context.contentResolver.persistedUriPermissions.any { permission ->
            permission.uri == uri && if (read) permission.isReadPermission else permission.isWritePermission
        }

    private fun skipFully(input: InputStream, offset: Long) {
        var remaining = offset
        while (remaining > 0) {
            val skipped = input.skip(remaining)
            if (skipped > 0) remaining -= skipped else if (input.read() == -1) error("document.unexpectedEnd") else remaining--
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
