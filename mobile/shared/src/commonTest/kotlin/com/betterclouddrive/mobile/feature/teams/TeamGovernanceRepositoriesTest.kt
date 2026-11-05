package com.betterclouddrive.mobile.feature.teams

import com.betterclouddrive.mobile.core.model.ApiException
import com.betterclouddrive.mobile.core.model.StoredSession
import com.betterclouddrive.mobile.core.model.TeamTask
import com.betterclouddrive.mobile.core.network.AuthenticatedHttpClient
import com.betterclouddrive.mobile.core.network.TeamTasksApi
import com.betterclouddrive.mobile.core.platform.FileDigest
import com.betterclouddrive.mobile.core.platform.PickedDestination
import com.betterclouddrive.mobile.core.platform.SecureSessionStore
import com.betterclouddrive.mobile.core.platform.ServerSettingsStore
import com.betterclouddrive.mobile.core.platform.TransferFileAccess
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.forms.InputProvider
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TeamGovernanceRepositoriesTest {
    @Test
    fun taskResultStreamsThroughPrivateTempAndCommitsOnlyAfterLengthValidation() = runTest {
        val files = FakeResultFiles()
        val requests = mutableListOf<String>()
        val repository = repository(files, "csv!", "4") { requests += it.orEmpty() }
        val progress = mutableListOf<Pair<Long, Long?>>()

        val length = repository.download(
            3,
            TeamTask(id = 7, teamId = 3, taskType = "AUDIT_EXPORT", status = "SUCCEEDED", resultSizeBytes = 4),
            PickedDestination("content://audit", "audit.csv"),
        ) { downloaded, total -> progress += downloaded to total }

        assertEquals(4L, length)
        assertContentEquals("csv!".encodeToByteArray(), files.committed)
        assertEquals(4L, progress.single().first)
        assertEquals(4L, progress.single().second)
        assertEquals(listOf(""), requests)
        assertTrue(files.validated)
        assertTrue(files.released)
        assertTrue(files.deleted)
    }

    @Test
    fun taskResultLengthMismatchNeverCommitsAndStillCleansUpDestination() = runTest {
        val files = FakeResultFiles()
        val repository = repository(files, "short", "5")

        assertFailsWith<ApiException> {
            repository.download(
                3,
                TeamTask(id = 7, teamId = 3, taskType = "AUDIT_EXPORT", status = "SUCCEEDED", resultSizeBytes = 6),
                PickedDestination("content://audit", "audit.csv"),
            ) { _, _ -> }
        }

        assertFalse(files.committedOnce)
        assertTrue(files.released)
        assertTrue(files.deleted)
    }

    @Test
    fun taskResultValidationFailureStillReleasesDestinationPermission() = runTest {
        val files = FakeResultFiles().apply { failValidation = true }
        val repository = repository(files, "unused", "0")

        assertFailsWith<IllegalStateException> {
            repository.download(
                3,
                TeamTask(id = 7, teamId = 3, taskType = "AUDIT_EXPORT", status = "SUCCEEDED"),
                PickedDestination("content://audit", "audit.csv"),
            ) { _, _ -> }
        }

        assertTrue(files.released)
        assertTrue(files.deleted)
    }

    private suspend fun repository(
        files: FakeResultFiles,
        body: String,
        contentLength: String,
        onRange: (String?) -> Unit = {},
    ): TeamTasksRepositoryImpl {
        val server = "https://drive.example.com"
        val http = AuthenticatedHttpClient(
            HttpClient(MockEngine { request ->
                onRange(request.headers[HttpHeaders.Range])
                respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentLength, contentLength))
            }),
            server,
            MemorySessionStore(StoredSession("access", "refresh", Long.MAX_VALUE, server)),
            MemoryServerStore(server),
            allowCleartextServer = true,
        )
        http.initialize()
        return TeamTasksRepositoryImpl(TeamTasksApi(http), files)
    }
}

private class FakeResultFiles : TransferFileAccess {
    private var temp = byteArrayOf()
    var committed = byteArrayOf()
    var committedOnce = false
    var validated = false
    var released = false
    var deleted = false
    var failValidation = false

    override suspend fun validateReadable(sourceHandle: String) = Unit
    override suspend fun validateWritable(destinationHandle: String) {
        validated = true
        if (failValidation) error("destination unavailable")
    }
    override suspend fun computeDigest(sourceHandle: String) = FileDigest("unused", 0)
    override suspend fun computePartDigest(sourceHandle: String, offset: Long, length: Long): FileDigest = FileDigest("test-part-md5", length)
    override fun uploadPart(sourceHandle: String, offset: Long, length: Long, onBytes: (Long) -> Unit): InputProvider =
        error("not used")
    override suspend fun tempLength(taskId: String) = temp.size.toLong()
    override suspend fun tempMd5(taskId: String) = "unused"
    override suspend fun resetTemp(taskId: String) { temp = byteArrayOf() }
    override suspend fun appendTemp(taskId: String, channel: ByteReadChannel, onBytes: suspend (Long) -> Unit) {
        val buffer = ByteArray(16)
        while (true) {
            val count = channel.readAvailable(buffer)
            if (count < 0) break
            if (count == 0) continue
            temp += buffer.copyOf(count)
            onBytes(count.toLong())
        }
    }
    override suspend fun commitTemp(taskId: String, destinationHandle: String) {
        committed = temp.copyOf()
        committedOnce = true
    }
    override suspend fun deleteTemp(taskId: String) { temp = byteArrayOf(); deleted = true }
    override suspend fun deleteAllTemps() { temp = byteArrayOf() }
    override suspend fun releaseReadable(sourceHandle: String) = Unit
    override suspend fun releaseWritable(destinationHandle: String) { released = true }
}

private class MemorySessionStore(initial: StoredSession?) : SecureSessionStore {
    private var value = initial
    override suspend fun read(serverBaseUrl: String) = value?.takeIf { it.serverBaseUrl == serverBaseUrl }
    override suspend fun write(session: StoredSession) { value = session }
    override suspend fun clear() { value = null }
}

private class MemoryServerStore(initial: String) : ServerSettingsStore {
    private val state = MutableStateFlow(initial)
    override val serverBaseUrl: Flow<String> = state
    override suspend fun read() = state.value
    override suspend fun write(value: String) { state.value = value }
}
