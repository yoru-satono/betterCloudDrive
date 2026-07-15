package com.betterclouddrive.android.data.remote.interceptor

import com.betterclouddrive.android.data.local.ServerConfigStore
import com.betterclouddrive.android.data.local.TokenManager
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.mockito.kotlin.verify
import kotlinx.serialization.json.Json

class NetworkInterceptorsTest {
    private val tokens: TokenManager = mock()

    @Test fun authAddsBearerOnlyToProtectedEndpoints() = runTest {
        whenever(tokens.getAccessToken()).thenReturn("access")
        val server = MockWebServer().apply { start() }
        try {
            repeat(3) { server.enqueue(MockResponse().setBody("{}")) }
            val client = OkHttpClient.Builder().addInterceptor(AuthInterceptor(tokens)).build()
            client.newCall(Request.Builder().url(server.url("/api/v1/files")).build()).execute().close()
            assertEquals("Bearer access", server.takeRequest().getHeader("Authorization"))
            client.newCall(Request.Builder().url(server.url("/api/v1/auth/login")).build()).execute().close()
            assertNull(server.takeRequest().getHeader("Authorization"))
            client.newCall(Request.Builder().url(server.url("/api/v1/shares/access/code/files")).build()).execute().close()
            assertNull(server.takeRequest().getHeader("Authorization"))
        } finally { server.shutdown() }
    }

    @Test fun authLeavesProtectedRequestUntouchedWithoutToken() = runTest {
        whenever(tokens.getAccessToken()).thenReturn(null)
        val server = MockWebServer().apply { start(); enqueue(MockResponse()) }
        try {
            OkHttpClient.Builder().addInterceptor(AuthInterceptor(tokens)).build()
                .newCall(Request.Builder().url(server.url("/api/v1/files")).build()).execute().close()
            assertNull(server.takeRequest().getHeader("Authorization"))
        } finally { server.shutdown() }
    }

    @Test fun serverInterceptorReplacesOriginAndKeepsPath() = runTest {
        val target = MockWebServer().apply { start(); enqueue(MockResponse()) }
        val store: ServerConfigStore = mock()
        whenever(store.getServerBaseUrl()).thenReturn(target.url("/").toString())
        try {
            OkHttpClient.Builder().addInterceptor(ServerBaseUrlInterceptor(store)).build()
                .newCall(Request.Builder().url("http://example.invalid/api/v1/files?page=2").build()).execute().close()
            assertEquals("/api/v1/files?page=2", target.takeRequest().path)
        } finally { target.shutdown() }
    }

    @Test fun expiredTokenRefreshesPersistsAndRetriesOriginalRequest() = runTest {
        val server = MockWebServer().apply {
            start()
            enqueue(MockResponse().setResponseCode(401).setBody("""{"code":401001,"message":"expired","data":null}"""))
            enqueue(MockResponse().setBody("""{"code":200,"message":"ok","data":{"accessToken":"new-access","refreshToken":"new-refresh"}}"""))
            enqueue(MockResponse().setBody("ok"))
        }
        val store: ServerConfigStore = mock()
        whenever(tokens.getRefreshToken()).thenReturn("old-refresh")
        whenever(store.getServerBaseUrl()).thenReturn(server.url("/").toString())
        val json = Json { ignoreUnknownKeys = true }
        try {
            val client = OkHttpClient.Builder()
                .addInterceptor(TokenRefreshInterceptor(tokens, store, json))
                .build()
            val response = client.newCall(Request.Builder().url(server.url("/api/v1/files")).build()).execute()
            assertEquals(200, response.code)
            response.close()

            assertEquals("/api/v1/files", server.takeRequest().path)
            assertEquals("/api/v1/auth/refresh", server.takeRequest().path)
            assertEquals("Bearer new-access", server.takeRequest().getHeader("Authorization"))
            verify(tokens).saveTokens("new-access", "new-refresh")
        } finally { server.shutdown() }
    }

    @Test fun unrelatedUnauthorizedResponseIsReturnedWithoutRefresh() = runTest {
        val server = MockWebServer().apply {
            start(); enqueue(MockResponse().setResponseCode(401).setBody("""{"code":401002}"""))
        }
        val store: ServerConfigStore = mock()
        try {
            val response = OkHttpClient.Builder()
                .addInterceptor(TokenRefreshInterceptor(tokens, store, Json { ignoreUnknownKeys = true }))
                .build().newCall(Request.Builder().url(server.url("/api/v1/files")).build()).execute()
            assertEquals(401, response.code)
            response.close()
            assertEquals(1, server.requestCount)
        } finally { server.shutdown() }
    }

    @Test fun expiredTokenWithoutRefreshCredentialReturnsOriginalResponse() = runTest {
        val server = MockWebServer().apply {
            start(); enqueue(MockResponse().setResponseCode(401).setBody("""{"code":401001,"data":null}"""))
        }
        val store: ServerConfigStore = mock()
        whenever(tokens.getRefreshToken()).thenReturn(null)
        try {
            val response = OkHttpClient.Builder()
                .addInterceptor(TokenRefreshInterceptor(tokens, store, Json { ignoreUnknownKeys = true }))
                .build().newCall(Request.Builder().url(server.url("/api/v1/files")).build()).execute()
            assertEquals(401, response.code)
            response.close()
            assertEquals(1, server.requestCount)
        } finally { server.shutdown() }
    }
}
