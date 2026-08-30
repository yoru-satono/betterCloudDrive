package com.betterclouddrive.mobile.feature.transfers

import com.betterclouddrive.mobile.core.database.TransferTaskDao
import com.betterclouddrive.mobile.core.database.TransferTaskEntity
import com.betterclouddrive.mobile.core.model.StoredSession
import com.betterclouddrive.mobile.core.network.NetworkTestFacade
import com.betterclouddrive.mobile.core.platform.SecureSessionStore
import com.betterclouddrive.mobile.core.platform.ServerSettingsStore
import com.betterclouddrive.mobile.core.platform.TransferFileAccess
import com.betterclouddrive.mobile.core.platform.PickedDocument
import com.betterclouddrive.mobile.core.platform.FileDigest
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.forms.InputProvider
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.ContentType
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.io.Buffer
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class TransferEngineTest {
    @Test
    fun uploadResumesMissingChunksAndRetriesIdempotentCompleteAfter429() = runTest {
        val source = ByteArray((DEFAULT_CHUNK_SIZE + 17).toInt()) { (it % 251).toByte() }
        var initCalls = 0
        var statusCalls = 0
        var chunkCalls = 0
        var completeCalls = 0
        val backend = authenticatedBackend(MockEngine { request ->
            when {
                request.url.encodedPath.endsWith("/upload/instant") -> jsonResponse(
                    """{"code":419010,"messageKey":"error.instantUploadMiss"}""",
                )
                request.url.encodedPath.endsWith("/upload/init") -> {
                    initCalls++
                    jsonResponse("""{"code":200,"data":{"sessionId":"session-1","chunkSize":5242880,"totalChunks":2}}""")
                }
                request.url.encodedPath.endsWith("/status") -> {
                    statusCalls++
                    if (statusCalls == 1) {
                        jsonResponse("""{"code":200,"data":{"sessionId":"session-1","totalChunks":2,"uploadedChunks":1,"missingChunks":[1]}}""")
                    } else {
                        jsonResponse("""{"code":200,"data":{"sessionId":"session-1","totalChunks":2,"uploadedChunks":2,"missingChunks":[]}}""")
                    }
                }
                request.url.encodedPath.endsWith("/chunk") -> {
                    chunkCalls++
                    assertEquals("1", request.url.parameters["chunkNumber"])
                    jsonResponse("""{"code":200,"data":{"chunkNumber":1}}""")
                }
                request.url.encodedPath.endsWith("/complete") -> {
                    completeCalls++
                    if (completeCalls == 1) jsonResponse("""{"code":429,"messageKey":"error.rateLimited"}""")
                    else jsonResponse("""{"code":200,"data":{"fileId":99}}""")
                }
                else -> error("Unexpected request ${request.url}")
            }
        })
        val repository = TransferRepository(FakeTransferDao(), now = { 1 })
        val task = repository.enqueueUpload(
            backend.serverBaseUrl, PickedDocument("source", "large.bin", source.size.toLong()), TransferScope.Personal(),
        )
        val engine = TransferEngine(backend, repository, FakeTransferFiles(source = source))

        assertEquals(TransferRunResult.RETRY, engine.run(task.id))
        assertEquals(TransferRunResult.COMPLETE, engine.run(task.id))

        val completed = repository.find(task.id)
        assertEquals(TransferStatus.SUCCEEDED, completed?.status)
        assertEquals(99, completed?.remoteResultFileId)
        assertEquals(1, initCalls)
        assertEquals(1, chunkCalls)
        assertEquals(2, completeCalls)
    }

    @Test
    fun zeroByteUploadCanFinishThroughInstantUpload() = runTest {
        val backend = authenticatedBackend(MockEngine { request ->
            assertEquals("/api/v1/upload/instant", request.url.encodedPath)
            jsonResponse("""{"code":200,"data":{"fileId":5,"instant":true}}""")
        })
        val repository = TransferRepository(FakeTransferDao(), now = { 1 })
        val task = repository.enqueueUpload(
            backend.serverBaseUrl, PickedDocument("empty", "empty.bin", 0), TransferScope.Personal(),
        )

        TransferEngine(backend, repository, FakeTransferFiles(source = byteArrayOf())).run(task.id)

        assertEquals(TransferStatus.SUCCEEDED, repository.find(task.id)?.status)
        assertEquals(0, repository.find(task.id)?.bytesDone)
    }

    @Test
    fun downloadResumesFromPartFileWithValidatedContentRange() = runTest {
        val expected = "abcdefgh".encodeToByteArray()
        var range: String? = null
        val backend = authenticatedBackend(MockEngine { request ->
            range = request.headers[HttpHeaders.Range]
            respond(
                expected.copyOfRange(3, expected.size),
                HttpStatusCode.PartialContent,
                headersOf(HttpHeaders.ContentRange, "bytes 3-7/8"),
            )
        })
        val dao = FakeTransferDao()
        val files = FakeTransferFiles("abc".encodeToByteArray())
        val repository = TransferRepository(dao, now = { 1 })
        val task = repository.enqueueDownload(
            backend.serverBaseUrl, 42, "range.bin", expected.size.toLong(), "destination",
            TransferScope.Personal(), "application/octet-stream",
        )

        val result = TransferEngine(backend, repository, files).run(task.id)

        assertEquals(TransferRunResult.COMPLETE, result)
        assertEquals("bytes=3-", range)
        assertContentEquals(expected, files.committed)
        assertEquals(TransferStatus.SUCCEEDED, repository.find(task.id)?.status)
    }

    @Test
    fun downloadRestartsWhenServerIgnoresRange() = runTest {
        val expected = "abcdefgh".encodeToByteArray()
        val ranges = mutableListOf<String?>()
        val backend = authenticatedBackend(MockEngine { request ->
            ranges += request.headers[HttpHeaders.Range]
            respond(expected, HttpStatusCode.OK)
        })
        val dao = FakeTransferDao()
        val files = FakeTransferFiles("abc".encodeToByteArray())
        val repository = TransferRepository(dao, now = { 1 })
        val task = repository.enqueueDownload(
            backend.serverBaseUrl, 42, "restart.bin", expected.size.toLong(), "destination",
            TransferScope.Personal(), null,
        )

        TransferEngine(backend, repository, files).run(task.id)

        assertEquals(listOf<String?>("bytes=3-"), ranges)
        assertContentEquals(expected, files.committed)
        assertEquals(1, files.resetCount)
    }

    @Test
    fun downloadAccepts416OnlyWhenPartAlreadyMatchesTotalLength() = runTest {
        val expected = "complete".encodeToByteArray()
        val backend = authenticatedBackend(MockEngine {
            respond(
                byteArrayOf(),
                HttpStatusCode.RequestedRangeNotSatisfiable,
                headersOf(HttpHeaders.ContentRange, "bytes */${expected.size}"),
            )
        })
        val repository = TransferRepository(FakeTransferDao(), now = { 1 })
        val files = FakeTransferFiles(expected)
        val task = repository.enqueueDownload(
            backend.serverBaseUrl, 42, "complete.bin", expected.size.toLong(), "destination", TransferScope.Personal(), null,
        )

        TransferEngine(backend, repository, files).run(task.id)

        assertContentEquals(expected, files.committed)
        assertEquals(TransferStatus.SUCCEEDED, repository.find(task.id)?.status)
    }

    @Test
    fun temporaryServerFailureRetriesAndPermanentRangeErrorFails() = runTest {
        val expected = "abcdefgh".encodeToByteArray()
        var calls = 0
        val retryBackend = authenticatedBackend(MockEngine {
            calls++
            if (calls == 1) respond(byteArrayOf(), HttpStatusCode.ServiceUnavailable)
            else respond(expected, HttpStatusCode.OK)
        })
        val retryRepository = TransferRepository(FakeTransferDao(), now = { 1 })
        val retryTask = retryRepository.enqueueDownload(
            retryBackend.serverBaseUrl, 42, "retry.bin", expected.size.toLong(), "destination", TransferScope.Personal(), null,
        )
        val retryEngine = TransferEngine(retryBackend, retryRepository, FakeTransferFiles())
        assertEquals(TransferRunResult.RETRY, retryEngine.run(retryTask.id))
        assertEquals(TransferRunResult.COMPLETE, retryEngine.run(retryTask.id))
        assertEquals(TransferStatus.SUCCEEDED, retryRepository.find(retryTask.id)?.status)

        val invalidBackend = authenticatedBackend(MockEngine {
            respond("def".encodeToByteArray(), HttpStatusCode.PartialContent, headersOf(HttpHeaders.ContentRange, "bytes 2-4/8"))
        })
        val invalidRepository = TransferRepository(FakeTransferDao(), now = { 2 })
        val invalidTask = invalidRepository.enqueueDownload(
            invalidBackend.serverBaseUrl, 42, "invalid.bin", expected.size.toLong(), "destination", TransferScope.Personal(), null,
        )
        val result = TransferEngine(invalidBackend, invalidRepository, FakeTransferFiles("abc".encodeToByteArray())).run(invalidTask.id)
        assertEquals(TransferRunResult.COMPLETE, result)
        assertEquals(TransferStatus.FAILED, invalidRepository.find(invalidTask.id)?.status)
    }

    @Test
    fun pausedTaskCannotBeResurrectedByLateProgress() = runTest {
        val repository = TransferRepository(FakeTransferDao(), now = { 1 })
        val task = repository.enqueueUpload(
            "https://drive.example.com", PickedDocument("source", "race.bin", 10), TransferScope.Personal(),
        )
        repository.update(task.id) { it.copy(status = TransferStatus.RUNNING) }
        repository.update(task.id) { it.copy(status = TransferStatus.PAUSED) }

        repository.updateActive(task.id) { it.copy(status = TransferStatus.RUNNING, bytesDone = 5) }

        assertEquals(TransferStatus.PAUSED, repository.find(task.id)?.status)
        assertEquals(0, repository.find(task.id)?.bytesDone)
    }

    @Test
    fun downloadIntegrityMismatchDeletesPartAndFailsPermanently() = runTest {
        val expected = "content".encodeToByteArray()
        val backend = authenticatedBackend(MockEngine { respond(expected, HttpStatusCode.OK) })
        val repository = TransferRepository(FakeTransferDao(), now = { 1 })
        val task = repository.enqueueDownload(
            backend.serverBaseUrl, 42, "bad.bin", expected.size.toLong(), "destination",
            TransferScope.Personal(), null,
        )
        repository.update(task.id) { it.copy(md5Hash = "expected-md5") }
        val files = FakeTransferFiles(tempDigest = "different-md5")

        assertEquals(TransferRunResult.COMPLETE, TransferEngine(backend, repository, files).run(task.id))

        assertEquals(TransferStatus.FAILED, repository.find(task.id)?.status)
        assertEquals("error.integrity", repository.find(task.id)?.errorKey)
        assertEquals(1, files.deleteCount)
    }

    private suspend fun authenticatedBackend(engine: MockEngine): NetworkTestFacade {
        val server = "https://drive.example.com"
        return NetworkTestFacade(
            HttpClient(engine), server,
            TestSessionStore(StoredSession("access", "refresh", Long.MAX_VALUE, server)),
            TestServerStore(server),
        ).also { it.initialize() }
    }

    private fun io.ktor.client.engine.mock.MockRequestHandleScope.jsonResponse(content: String) = respond(
        content,
        HttpStatusCode.OK,
        headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
    )
}

private class FakeTransferDao : TransferTaskDao {
    private val values = MutableStateFlow<List<TransferTaskEntity>>(emptyList())
    override fun observeAll(): Flow<List<TransferTaskEntity>> = values
    override suspend fun count(): Int = values.value.size
    override suspend fun find(id: String): TransferTaskEntity? = values.value.firstOrNull { it.id == id }
    override suspend fun all(): List<TransferTaskEntity> = values.value
    override suspend fun upsert(value: TransferTaskEntity) {
        values.value = values.value.filterNot { it.id == value.id } + value
    }
    override suspend fun delete(id: String) { values.value = values.value.filterNot { it.id == id } }
    override suspend fun deleteAll() { values.value = emptyList() }
}

private class FakeTransferFiles(
    initial: ByteArray = byteArrayOf(),
    private val source: ByteArray = byteArrayOf(),
    private val tempDigest: String = "unused-md5",
) : TransferFileAccess {
    private var part = initial
    var committed: ByteArray = byteArrayOf()
    var resetCount = 0
    var deleteCount = 0

    override suspend fun validateReadable(sourceHandle: String) = Unit
    override suspend fun validateWritable(destinationHandle: String) = Unit
    override suspend fun releaseReadable(sourceHandle: String) = Unit
    override suspend fun releaseWritable(destinationHandle: String) = Unit
    override suspend fun computeDigest(sourceHandle: String): FileDigest = FileDigest("test-md5", source.size.toLong())
    override fun uploadPart(sourceHandle: String, offset: Long, length: Long, onBytes: (Long) -> Unit): InputProvider = InputProvider(length) {
        onBytes(length)
        Buffer().apply { write(source.copyOfRange(offset.toInt(), (offset + length).toInt())) }
    }
    override suspend fun tempLength(taskId: String): Long = part.size.toLong()
    override suspend fun tempMd5(taskId: String): String = tempDigest
    override suspend fun resetTemp(taskId: String) { part = byteArrayOf(); resetCount++ }
    override suspend fun appendTemp(taskId: String, channel: ByteReadChannel, onBytes: suspend (Long) -> Unit) {
        val chunks = mutableListOf<Byte>()
        val buffer = ByteArray(16)
        while (!channel.isClosedForRead) {
            val count = channel.readAvailable(buffer, 0, buffer.size)
            if (count < 0) break
            repeat(count) { chunks += buffer[it] }
        }
        val appended = chunks.toByteArray()
        part += appended
        onBytes(appended.size.toLong())
    }
    override suspend fun commitTemp(taskId: String, destinationHandle: String) { committed = part.copyOf() }
    override suspend fun deleteTemp(taskId: String) { part = byteArrayOf(); deleteCount++ }
    override suspend fun deleteAllTemps() { part = byteArrayOf() }
}

private class TestSessionStore(initial: StoredSession?) : SecureSessionStore {
    private var value = initial
    override suspend fun read(serverBaseUrl: String): StoredSession? = value?.takeIf { it.serverBaseUrl == serverBaseUrl }
    override suspend fun write(session: StoredSession) { value = session }
    override suspend fun clear() { value = null }
}

private class TestServerStore(initial: String) : ServerSettingsStore {
    private val value = MutableStateFlow(initial)
    override val serverBaseUrl: Flow<String> = value
    override suspend fun read(): String = value.value
    override suspend fun write(value: String) { this.value.value = value }
}
