package com.betterclouddrive.mobile.core.platform

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.betterclouddrive.mobile.core.model.OfficeConfig
import com.betterclouddrive.mobile.core.model.StoredSession
import com.betterclouddrive.mobile.feature.transfers.TransferDirection
import io.ktor.client.HttpClient
import io.ktor.client.request.forms.InputProvider
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.flow.Flow

interface HttpClientFactory {
    fun create(): HttpClient
}

interface ServerSettingsStore {
    val serverBaseUrl: Flow<String>
    suspend fun read(): String
    suspend fun write(value: String)
}

interface SecureSessionStore {
    suspend fun read(serverBaseUrl: String): StoredSession?
    suspend fun write(session: StoredSession)
    suspend fun clear()
}

data class PickedDocument(
    val handle: String,
    val displayName: String,
    val size: Long,
    val mimeType: String? = null,
)

data class PickedDestination(
    val handle: String,
    val displayName: String,
)

data class FileDigest(val md5: String, val size: Long)

data class TransferScheduleSpec(
    val taskId: String,
    val direction: TransferDirection,
    val remainingBytes: Long,
)

interface DocumentPicker {
    @Composable
    fun UploadButton(enabled: Boolean, onPicked: (PickedDocument) -> Unit)

    @Composable
    fun DownloadButton(fileName: String, enabled: Boolean, onPicked: (PickedDestination) -> Unit)
}

interface TransferFileAccess {
    suspend fun validateReadable(sourceHandle: String)
    suspend fun validateWritable(destinationHandle: String)
    suspend fun computeDigest(sourceHandle: String): FileDigest
    fun uploadPart(sourceHandle: String, offset: Long, length: Long, onBytes: (Long) -> Unit): InputProvider
    suspend fun tempLength(taskId: String): Long
    suspend fun tempMd5(taskId: String): String
    suspend fun resetTemp(taskId: String)
    suspend fun appendTemp(taskId: String, channel: ByteReadChannel, onBytes: suspend (Long) -> Unit)
    suspend fun commitTemp(taskId: String, destinationHandle: String)
    suspend fun deleteTemp(taskId: String)
    suspend fun deleteAllTemps()
}

interface TransferScheduler {
    suspend fun schedule(spec: TransferScheduleSpec): Boolean
    suspend fun cancel(taskId: String)
    suspend fun cancelAll()
    suspend fun scheduledTaskIds(): Set<String>
}

interface OfficeRenderer {
    @Composable
    fun Render(config: OfficeConfig, modifier: Modifier, onStatus: (String) -> Unit)
}
