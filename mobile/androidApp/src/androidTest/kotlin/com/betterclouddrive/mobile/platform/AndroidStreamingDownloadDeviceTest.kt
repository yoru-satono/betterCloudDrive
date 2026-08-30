package com.betterclouddrive.mobile.platform

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.betterclouddrive.mobile.core.model.StoredSession
import com.betterclouddrive.mobile.core.network.AuthenticatedHttpClient
import com.betterclouddrive.mobile.core.network.TransferApi
import com.betterclouddrive.mobile.core.platform.SecureSessionStore
import com.betterclouddrive.mobile.core.platform.ServerSettingsStore
import com.betterclouddrive.mobile.feature.transfers.TransferScope
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class AndroidStreamingDownloadDeviceTest {
    @Test
    fun okhttpResponseWritesBeforeCompletionAndResumesWithRange() = runBlocking {
        val payload = ByteArray(2 * 1024 * 1024) { index -> (index % 251).toByte() }
        val server = MockWebServer()
        server.start()
        val baseUrl = server.url("/").toString().removeSuffix("/")
        val sessionStore = DeviceSessionStore(
            StoredSession("access", "refresh", Long.MAX_VALUE, baseUrl),
        )
        val http = AuthenticatedHttpClient(
            HttpClient(OkHttp),
            initialServerBaseUrl = baseUrl,
            secureSessionStore = sessionStore,
            serverSettingsStore = DeviceServerStore(baseUrl),
            allowCleartextServer = true,
        )
        val files = AndroidTransferFileAccess(
            androidx.test.core.app.ApplicationProvider.getApplicationContext(),
        )
        val taskId = "stream-device-${System.nanoTime()}"
        try {
            http.initialize()
            val client = TransferApi(http)
            server.enqueue(
                MockResponse.Builder()
                    .code(200)
                    .body(Buffer().write(payload))
                    .throttleBody(64 * 1024L, 50, TimeUnit.MILLISECONDS)
                    .build(),
            )

            val firstAttempt = launch(Dispatchers.IO) {
                client.download(TransferScope.Personal(), fileId = 7, rangeStart = 0) { status, _, channel ->
                    assertEquals(HttpStatusCode.OK, status)
                    files.appendTemp(taskId, channel) { }
                }
            }
            val partialLength = withTimeout(5_000) {
                var length: Long
                do {
                    delay(20)
                    length = files.tempLength(taskId)
                } while (length == 0L)
                length
            }
            assertTrue("响应尚未结束时临时文件应已写入", partialLength in 1 until payload.size.toLong())
            firstAttempt.cancelAndJoin()

            val resumeOffset = files.tempLength(taskId)
            assertTrue(resumeOffset in 1 until payload.size.toLong())
            val remaining = payload.copyOfRange(resumeOffset.toInt(), payload.size)
            server.enqueue(
                MockResponse.Builder()
                    .code(206)
                    .addHeader(HttpHeaders.ContentRange, "bytes $resumeOffset-${payload.lastIndex}/${payload.size}")
                    .body(Buffer().write(remaining))
                    .build(),
            )

            client.download(TransferScope.Personal(), fileId = 7, rangeStart = resumeOffset) { status, headers, channel ->
                assertEquals(HttpStatusCode.PartialContent, status)
                assertEquals(
                    "bytes $resumeOffset-${payload.lastIndex}/${payload.size}",
                    headers[HttpHeaders.ContentRange],
                )
                files.appendTemp(taskId, channel) { }
            }

            val firstRequest = server.takeRequest(5, TimeUnit.SECONDS)
            val secondRequest = server.takeRequest(5, TimeUnit.SECONDS)
            assertNotNull(firstRequest)
            assertNotNull(secondRequest)
            assertNull(firstRequest!!.headers[HttpHeaders.Range])
            assertEquals("bytes=$resumeOffset-", secondRequest!!.headers[HttpHeaders.Range])
            assertEquals(payload.size.toLong(), files.tempLength(taskId))
            assertEquals(payload.md5(), files.tempMd5(taskId))
        } finally {
            files.deleteTemp(taskId)
            server.close()
        }
    }

    private fun ByteArray.md5(): String = MessageDigest.getInstance("MD5")
        .digest(this)
        .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
}

private class DeviceSessionStore(initial: StoredSession?) : SecureSessionStore {
    private var value = initial
    override suspend fun read(serverBaseUrl: String): StoredSession? = value?.takeIf { it.serverBaseUrl == serverBaseUrl }
    override suspend fun write(session: StoredSession) { value = session }
    override suspend fun clear() { value = null }
}

private class DeviceServerStore(initial: String) : ServerSettingsStore {
    private val state = MutableStateFlow(initial)
    override val serverBaseUrl: Flow<String> = state
    override suspend fun read(): String = state.value
    override suspend fun write(value: String) { state.value = value }
}
