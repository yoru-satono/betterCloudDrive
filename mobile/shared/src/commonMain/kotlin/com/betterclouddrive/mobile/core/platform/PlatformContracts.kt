package com.betterclouddrive.mobile.core.platform

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.betterclouddrive.mobile.core.model.OfficeConfig
import com.betterclouddrive.mobile.core.model.PreviewKind
import com.betterclouddrive.mobile.core.localization.UiMessage
import com.betterclouddrive.mobile.core.localization.MobileLocale
import com.betterclouddrive.mobile.core.model.StoredSession
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

data class UploadPickResult(
    val documents: List<PickedDocument>,
    val rejectedCount: Int = 0,
    val overflowCount: Int = 0,
)

data class DownloadRequest(
    val fileName: String,
    val mimeType: String? = null,
)

data class FileDigest(val md5: String, val size: Long)

data class TransferScheduleSpec(
    val batchId: String,
    val taskIds: List<String>,
    val uploadBytes: Long,
    val downloadBytes: Long,
)

interface DocumentPicker {
    @Composable
    fun rememberUploadLauncher(maxItems: Int, onPicked: (UploadPickResult) -> Unit): () -> Unit

    @Composable
    fun rememberDownloadLauncher(onPicked: (PickedDestination) -> Unit): (DownloadRequest) -> Unit
}

enum class ThemeMode { SYSTEM, LIGHT, DARK }

interface ThemePreferenceStore {
    val themeMode: Flow<ThemeMode>
    suspend fun setThemeMode(mode: ThemeMode)
}

interface ApplicationLocaleController {
    fun currentLocale(): MobileLocale
    fun applyLocale(locale: MobileLocale)
}

interface ClipboardService {
    fun copyText(label: String, value: String): Boolean
}

interface LocalDateTimeService {
    fun nowEpochMillis(): Long
    fun localDateTimeToEpochMillis(selectedUtcDateMillis: Long, hour: Int, minute: Int): Long
    fun formatDateTime(epochMillis: Long): String
    fun hourOfDay(epochMillis: Long): Int = 12
    fun minuteOfHour(epochMillis: Long): Int = 0
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
    suspend fun releaseReadable(sourceHandle: String)
    suspend fun releaseWritable(destinationHandle: String)
}

interface TransferScheduler {
    suspend fun schedule(spec: TransferScheduleSpec): Boolean
    suspend fun cancelAll()
    suspend fun scheduledTaskIds(): Set<String>
}

interface OfficeRenderer {
    @Composable
    fun Render(config: OfficeConfig, modifier: Modifier, onStatus: (UiMessage) -> Unit)
}

data class MediaPreviewRequest(
    val kind: PreviewKind,
    val url: String,
    val fileKey: String,
    val initialPositionMillis: Long = 0,
)

interface MediaPreviewRenderer {
    @Composable
    fun Render(
        request: MediaPreviewRequest,
        modifier: Modifier,
        onPositionChanged: (Long) -> Unit,
        onError: (UiMessage) -> Unit,
    )
}
