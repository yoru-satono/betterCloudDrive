package com.betterclouddrive.android.data.remote

import com.betterclouddrive.android.data.remote.dto.CopyRequest
import com.betterclouddrive.android.data.remote.dto.InitUploadRequest
import com.betterclouddrive.android.data.remote.dto.MoveRequest
import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Retrofit
import java.util.concurrent.TimeUnit

class ApiServiceContractTest {
    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        isLenient = true
    }

    @Test
    fun moveAndCopyRequestsSerializeRootTargetAsNull() {
        assertEquals(
            """{"targetParentId":null}""",
            json.encodeToString(MoveRequest.serializer(), MoveRequest(null)),
        )
        assertEquals(
            """{"targetParentId":null}""",
            json.encodeToString(CopyRequest.serializer(), CopyRequest(null)),
        )
    }

    @Test
    fun listSharedFilesSendsPasswordQueryWhenProvided() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(pageResponse(page = 2, size = 50))
            api(server).listSharedFiles(
                shareCode = "abc123",
                parentId = 5,
                page = 2,
                size = 50,
                password = "pw",
            )

            val request = server.takeRequest()
            assertEquals("/shares/access/abc123/files?parentId=5&page=2&size=50&password=pw", request.path)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun listSharedFilesOmitsPasswordQueryWhenAbsent() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(pageResponse())
            api(server).listSharedFiles(shareCode = "abc123")

            val request = server.takeRequest()
            assertEquals("/shares/access/abc123/files?page=1&size=20", request.path)
            assertNull(request.requestUrl?.queryParameter("password"))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun initUploadSendsZeroChunksForZeroByteFile() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(
                MockResponse()
                    .setHeader("Content-Type", "application/json")
                    .setBody("""{"code":200,"message":"success","data":{"sessionId":"empty-session","chunkSize":5242880,"totalChunks":0}}"""),
            )
            api(server).initUpload(
                InitUploadRequest(
                    parentId = null,
                    fileName = "empty.txt",
                    fileSize = 0,
                    md5Hash = "d41d8cd98f00b204e9800998ecf8427e",
                    totalChunks = 0,
                ),
            )

            val request = server.takeRequest()
            val body = request.body.readUtf8()
            assertEquals("/upload/init", request.path)
            assertEquals("POST", request.method)
            assertTrue(body.contains(""""fileSize":0"""))
            assertTrue(body.contains(""""totalChunks":0"""))
        } finally {
            server.shutdown()
        }
    }

    private fun api(server: MockWebServer): ApiService {
        return Retrofit.Builder()
            .baseUrl(server.url("/"))
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(ApiService::class.java)
    }

    private fun pageResponse(page: Int = 1, size: Int = 20): MockResponse {
        return MockResponse()
            .setHeader("Content-Type", "application/json")
            .setBody(
                """
                {
                  "code": 200,
                  "message": "success",
                  "data": {
                    "records": [],
                    "total": 0,
                    "page": $page,
                    "size": $size,
                    "pages": 0
                  }
                }
                """.trimIndent(),
            )
    }
}
