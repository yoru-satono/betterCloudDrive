package com.betterclouddrive.mobile.core.network

import com.betterclouddrive.mobile.core.model.ApiException
import com.betterclouddrive.mobile.core.model.StoredSession
import com.betterclouddrive.mobile.core.model.SessionRestoreResult
import com.betterclouddrive.mobile.core.model.CreatePersonalShareRequest
import com.betterclouddrive.mobile.core.model.ShareExpirationFilter
import com.betterclouddrive.mobile.core.model.UpdatePersonalShareRequest
import com.betterclouddrive.mobile.core.platform.SecureSessionStore
import com.betterclouddrive.mobile.core.platform.ServerSettingsStore
import com.betterclouddrive.mobile.feature.transfers.TransferScope
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.HttpMethod
import io.ktor.http.headersOf
import io.ktor.http.content.OutgoingContent
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertIs
import kotlin.test.assertTrue

class NetworkTestFacadeTest {
    @Test
    fun recycleBinVersionsAndVersionRangeDownloadUseStableContracts() = runTest {
        val observed = mutableListOf<Pair<HttpMethod, String>>()
        val engine = MockEngine { request ->
            observed += request.method to request.url.toString()
            when {
                request.url.encodedPath == "/api/v1/recycle-bin" && request.method == HttpMethod.Get ->
                    jsonResponse("""{"code":200,"data":{"records":[{"id":8,"fileName":"old.txt","fileType":"file","deletedAt":"2026-08-30T10:00:00"}],"total":1,"page":1,"size":50,"pages":1}}""")
                request.url.encodedPath == "/api/v1/files/9/versions" ->
                    jsonResponse("""{"code":200,"data":[{"id":3,"fileId":9,"versionNumber":2,"fileSize":4,"md5Hash":"abcd"}]}""")
                request.url.encodedPath.endsWith("/restore") && request.url.encodedPath.contains("versions") ->
                    jsonResponse("""{"code":200,"data":{"id":9,"fileName":"doc.txt","fileType":"file"}}""")
                request.url.encodedPath == "/api/v1/files/9/versions/2/download" -> {
                    assertEquals("bytes=4-", request.headers[HttpHeaders.Range])
                    respond("tail", HttpStatusCode.PartialContent, headersOf(
                        HttpHeaders.ContentRange to listOf("bytes 4-7/8"),
                        HttpHeaders.ContentLength to listOf("4"),
                    ))
                }
                else -> jsonResponse("""{"code":200}""")
            }
        }
        val client = NetworkTestFacade(
            HttpClient(engine), "https://drive.example.com",
            FakeSessionStore(StoredSession("access", "refresh", Long.MAX_VALUE, "https://drive.example.com")),
            FakeServerStore("https://drive.example.com"),
        )
        client.initialize()

        assertEquals("old.txt", client.listRecycleBin(" old ", 1, 50).records.single().fileName)
        client.restoreRecycleItem(8)
        client.permanentlyDeleteRecycleItem(8)
        client.emptyRecycleBin()
        assertEquals(2, client.listVersions(9).single().versionNumber)
        client.restoreVersion(9, 2)
        client.deleteVersion(9, 2)
        val body = client.download(TransferScope.Personal(null), 9, 4, 2) { _, _, channel ->
            val bytes = ByteArray(4); channel.readAvailable(bytes); bytes.decodeToString()
        }
        assertEquals("tail", body)
        assertTrue(observed.any { it.second.contains("/recycle-bin?q=old&page=1&size=50") })
        assertTrue(observed.any { it.first == HttpMethod.Get && it.second.contains("/files/9/versions/2/download") })
    }

    @Test
    fun publicAccountLifecycleRequestsAreUnauthenticatedAndPreserveExactLocale() = runTest {
        val observed = mutableListOf<Triple<HttpMethod, String, String>>()
        val engine = MockEngine { request ->
            val body = (request.body as OutgoingContent.ByteArrayContent).bytes().decodeToString()
            observed += Triple(request.method, request.url.encodedPath, body)
            assertNull(request.headers[HttpHeaders.Authorization])
            if (request.url.encodedPath.endsWith("/register")) {
                jsonResponse("""{"code":200,"data":{"userId":9,"username":"alice"}}""")
            } else jsonResponse("""{"code":200}""")
        }
        val client = NetworkTestFacade(HttpClient(engine), "https://drive.example.com")

        client.sendRegistrationCode("a@example.com", "zh-SG")
        val registered = client.register("alice", "Password1", "a@example.com", "123456", "zh-SG")
        client.forgotPassword("a@example.com")
        client.resetPassword("a@example.com", "654321", "newpass")

        assertEquals("alice", registered.username)
        assertEquals(
            listOf(
                Triple(HttpMethod.Post, "/api/v1/auth/register-code/send", """{"email":"a@example.com","preferredLocale":"zh-SG"}"""),
                Triple(HttpMethod.Post, "/api/v1/auth/register", """{"username":"alice","password":"Password1","email":"a@example.com","verificationCode":"123456","preferredLocale":"zh-SG"}"""),
                Triple(HttpMethod.Post, "/api/v1/auth/forgot-password", """{"email":"a@example.com"}"""),
                Triple(HttpMethod.Post, "/api/v1/auth/reset-password", """{"email":"a@example.com","code":"654321","newPassword":"newpass"}"""),
            ),
            observed,
        )
    }

    @Test
    fun profileAndLocaleUpdateParseCompleteAccountFields() = runTest {
        val engine = MockEngine { request ->
            if (request.url.encodedPath.endsWith("/preferences/locale")) {
                val body = (request.body as OutgoingContent.ByteArrayContent).bytes().decodeToString()
                assertEquals("""{"preferredLocale":"zh-CN"}""", body)
            }
            jsonResponse("""{"code":200,"data":{"id":7,"username":"alice","email":"a@example.com","emailVerified":true,"preferredLocale":"zh-CN","role":"ROLE_USER","status":1,"storageQuota":1073741824,"storageUsed":536870912,"webdavEnabled":false,"createdAt":"2026-08-25T10:00:00"}}""")
        }
        val client = NetworkTestFacade(
            HttpClient(engine), "https://drive.example.com",
            FakeSessionStore(StoredSession("access", "refresh", Long.MAX_VALUE, "https://drive.example.com")),
            FakeServerStore("https://drive.example.com"),
        )
        client.initialize()

        val profile = client.updatePreferredLocale("zh-CN")

        assertTrue(profile.emailVerified)
        assertEquals("zh-CN", profile.preferredLocale)
        assertEquals(536870912, profile.storageUsed)
        assertEquals("2026-08-25T10:00:00", profile.createdAt)
    }

    @Test
    fun personalFileMutationsUseStableEndpointsAndBodies() = runTest {
        val observed = mutableListOf<Triple<HttpMethod, String, String>>()
        val engine = MockEngine { request ->
            val outgoing = request.body as OutgoingContent.ByteArrayContent
            observed += Triple(request.method, request.url.encodedPath, outgoing.bytes().decodeToString())
            if (request.method == HttpMethod.Post && request.url.encodedPath == "/api/v1/files/folder") {
                jsonResponse("""{"code":200,"data":{"id":9,"parentId":7,"fileName":"Docs","fileType":"folder"}}""")
            } else if (request.method == HttpMethod.Put) {
                jsonResponse("""{"code":200,"data":{"id":9,"parentId":7,"fileName":"Renamed","fileType":"folder"}}""")
            } else {
                jsonResponse("""{"code":200}""")
            }
        }
        val client = NetworkTestFacade(
            HttpClient(engine), "https://drive.example.com",
            FakeSessionStore(StoredSession("access", "refresh", Long.MAX_VALUE, "https://drive.example.com")),
            FakeServerStore("https://drive.example.com"),
        )
        client.initialize()

        assertEquals("Docs", client.createPersonalFolder(7, "Docs").fileName)
        assertEquals("Renamed", client.renamePersonalFile(9, "Renamed").fileName)
        client.movePersonalFile(9, 11)
        client.copyPersonalFile(9, null)
        client.deletePersonalFiles(listOf(9, 9, 10))

        assertEquals(
            listOf(
                Triple(HttpMethod.Post, "/api/v1/files/folder", """{"parentId":7,"folderName":"Docs"}"""),
                Triple(HttpMethod.Put, "/api/v1/files/9", """{"newName":"Renamed"}"""),
                Triple(HttpMethod.Post, "/api/v1/files/9/move", """{"targetParentId":11}"""),
                Triple(HttpMethod.Post, "/api/v1/files/9/copy", "{}"),
                Triple(HttpMethod.Delete, "/api/v1/files", """{"fileIds":[9,10]}"""),
            ),
            observed,
        )
    }

    @Test
    fun personalOrganizationRequestsUseStableEndpointsPagingAndAtomicTagBody() = runTest {
        val observed = mutableListOf<Triple<HttpMethod, String, String>>()
        val engine = MockEngine { request ->
            val body = (request.body as? OutgoingContent.ByteArrayContent)?.bytes()?.decodeToString().orEmpty()
            observed += Triple(request.method, request.url.toString(), body)
            when {
                request.url.encodedPath == "/api/v1/favorites" -> jsonResponse(
                    """{"code":200,"data":{"records":[{"id":7,"fileName":"report.pdf"}],"total":51,"page":2,"size":50,"pages":2}}""",
                )
                request.url.encodedPath.endsWith("/status") -> jsonResponse("""{"code":200,"data":true}""")
                request.url.encodedPath == "/api/v1/tags" && request.method == HttpMethod.Get -> jsonResponse(
                    """{"code":200,"data":[{"id":3,"tagName":"work","color":"#00d4aa","fileCount":105}]}""",
                )
                request.url.encodedPath == "/api/v1/tags" -> jsonResponse(
                    """{"code":200,"data":{"id":4,"tagName":"new","color":"#60a5fa","fileCount":0}}""",
                )
                request.url.encodedPath == "/api/v1/tags/3/files" && request.method == HttpMethod.Get -> jsonResponse(
                    """{"code":200,"data":{"records":[{"id":8,"fileName":"tagged.txt"}],"total":1,"page":1,"size":50,"pages":1}}""",
                )
                request.url.encodedPath == "/api/v1/files/7/tags" -> jsonResponse(
                    """{"code":200,"data":[{"id":3,"tagName":"work","color":"#00d4aa"}]}""",
                )
                request.url.encodedPath == "/api/v1/tags/3" && request.method == HttpMethod.Put -> jsonResponse(
                    """{"code":200,"data":{"id":3,"tagName":"renamed","color":"#a78bfa","fileCount":105}}""",
                )
                else -> jsonResponse("""{"code":200}""")
            }
        }
        val client = NetworkTestFacade(
            HttpClient(engine), "https://drive.example.com",
            FakeSessionStore(StoredSession("access", "refresh", Long.MAX_VALUE, "https://drive.example.com")),
            FakeServerStore("https://drive.example.com"),
        )
        client.initialize()

        assertEquals(51, client.listPersonalFavorites(" report ", 2, 50).total)
        assertTrue(client.isPersonalFavorite(7))
        client.addPersonalFavorite(7)
        client.removePersonalFavorite(7)
        assertEquals(105, client.listPersonalTags(" work ").single().fileCount)
        assertEquals("new", client.createPersonalTag("new", "#60a5fa").tagName)
        assertEquals("renamed", client.updatePersonalTag(3, "renamed", "#a78bfa").tagName)
        assertEquals(8, client.listPersonalFilesByTag(3, " tagged ", 1, 50).records.single().id)
        assertEquals(3, client.listPersonalFileTags(7).single().id)
        assertEquals(3, client.replacePersonalFileTags(7, listOf(3, 3)).single().id)
        client.removePersonalFileTag(3, 7)
        client.deletePersonalTag(3)

        assertTrue(observed.any { it.first == HttpMethod.Get && it.second.endsWith("/favorites?q=report&page=2&size=50") })
        assertTrue(observed.any { it.first == HttpMethod.Get && it.second.endsWith("/tags?q=work") })
        assertTrue(observed.any { it.first == HttpMethod.Get && it.second.endsWith("/tags/3/files?q=tagged&page=1&size=50") })
        assertTrue(observed.any {
            it.first == HttpMethod.Put && it.second.endsWith("/files/7/tags") && it.third == """{"tagIds":[3]}"""
        })
        assertTrue(observed.any { it.first == HttpMethod.Delete && it.second.endsWith("/tags/3/files/7") })
    }

    @Test
    fun personalShareLifecycleUsesFiltersEpochAndExplicitClearFields() = runTest {
        val observed = mutableListOf<Triple<HttpMethod, String, String>>()
        val shareJson = """{"id":5,"fileId":7,"shareCode":"share123","shareUrl":"https://drive.example.com/s/share123","hasPassword":true,"expireAtEpochMs":1780000000000,"maxVisits":9,"visitCount":2,"downloadCount":1,"fileName":"report.pdf","fileType":"file","fileSize":42}"""
        val engine = MockEngine { request ->
            val body = (request.body as? OutgoingContent.ByteArrayContent)?.bytes()?.decodeToString().orEmpty()
            observed += Triple(request.method, request.url.toString(), body)
            when {
                request.url.encodedPath.endsWith("/password") -> jsonResponse("""{"code":200,"data":{"password":"safe-pass"}}""")
                request.method == HttpMethod.Delete -> jsonResponse("""{"code":200}""")
                request.url.encodedPath == "/api/v1/shares" && request.method == HttpMethod.Get ->
                    jsonResponse("""{"code":200,"data":{"records":[$shareJson],"total":105,"page":2,"size":50,"pages":3}}""")
                else -> jsonResponse("""{"code":200,"data":$shareJson}""")
            }
        }
        val client = NetworkTestFacade(
            HttpClient(engine), "https://drive.example.com",
            FakeSessionStore(StoredSession("access", "refresh", Long.MAX_VALUE, "https://drive.example.com")),
            FakeServerStore("https://drive.example.com"),
        )
        client.initialize()

        assertEquals("https://drive.example.com/s/share123", client.createPersonalShare(
            CreatePersonalShareRequest(7, "safe-pass", 1_780_000_000_000, 9),
        ).shareUrl)
        assertEquals(105, client.listPersonalShares(" report ", true, ShareExpirationFilter.ACTIVE, 2, 50).total)
        assertEquals("report.pdf", client.getPersonalShare(5).fileName)
        assertEquals("safe-pass", client.getPersonalSharePassword(5))
        client.updatePersonalShare(5, UpdatePersonalShareRequest(clearPassword = true, clearExpireAt = true, clearMaxVisits = true))
        client.cancelPersonalShare(5)

        assertTrue(observed.any { it.first == HttpMethod.Get && it.second.endsWith("/shares?q=report&passwordProtected=true&expirationStatus=ACTIVE&page=2&size=50") })
        assertTrue(observed.any { it.first == HttpMethod.Post && it.third == """{"fileId":7,"password":"safe-pass","expireAt":1780000000000,"maxVisits":9}""" })
        assertTrue(observed.any { it.first == HttpMethod.Put && it.third == """{"clearPassword":true,"clearExpireAt":true,"clearMaxVisits":true}""" })
        assertTrue(observed.any { it.first == HttpMethod.Delete && it.second.endsWith("/shares/5") })
    }

    @Test
    fun personalDirectoryQueryCarriesPagingAndSortAndParsesMetadata() = runTest {
        var requestedUrl = ""
        val engine = MockEngine { request ->
            requestedUrl = request.url.toString()
            jsonResponse(
                """{"code":200,"data":{"records":[{"id":9,"parentId":7,"fileName":"note.txt","fileSize":12,"versionCount":3,"createdAt":"2026-08-20T10:00:00","updatedAt":"2026-08-21T11:30:00"}],"total":101,"page":2,"size":50,"pages":3}}""",
            )
        }
        val client = NetworkTestFacade(
            HttpClient(engine), "https://drive.example.com",
            FakeSessionStore(StoredSession("access", "refresh", Long.MAX_VALUE, "https://drive.example.com")),
            FakeServerStore("https://drive.example.com"),
        )
        client.initialize()

        val page = client.listPersonalFiles(7, 2, 50, "updatedAt", "desc")

        assertEquals(
            "https://drive.example.com/api/v1/files?parentId=7&page=2&size=50&sortBy=updatedAt&order=desc",
            requestedUrl,
        )
        assertEquals(101, page.total)
        assertEquals(3, page.records.single().versionCount)
        assertEquals("2026-08-21T11:30:00", page.records.single().updatedAt)
    }

    @Test
    fun personalFolderMetadataUsesOwnedFileEndpoint() = runTest {
        var requestedPath = ""
        val engine = MockEngine { request ->
            requestedPath = request.url.encodedPath
            jsonResponse("""{"code":200,"data":{"id":8,"fileName":"目录","fileType":"folder"}}""")
        }
        val client = NetworkTestFacade(
            HttpClient(engine), "https://drive.example.com",
            FakeSessionStore(StoredSession("access", "refresh", Long.MAX_VALUE, "https://drive.example.com")),
            FakeServerStore("https://drive.example.com"),
        )
        client.initialize()

        val folder = client.getPersonalFile(8)

        assertEquals("/api/v1/files/8", requestedPath)
        assertEquals("目录", folder.fileName)
        assertTrue(folder.isFolder)
    }

    @Test
    fun streamingDownloadRefreshesOnceAndConsumesInsidePreparedResponse() = runTest {
        var downloadCalls = 0
        var refreshCalls = 0
        val store = FakeSessionStore(
            StoredSession("old-access", "refresh-1", Long.MAX_VALUE, "https://drive.example.com"),
        )
        val engine = MockEngine { request ->
            when (request.url.encodedPath) {
                "/api/v1/auth/refresh" -> {
                    refreshCalls++
                    jsonResponse("""{"code":200,"data":{"accessToken":"new-access","refreshToken":"refresh-2","expiresIn":1800}}""")
                }
                "/api/v1/download/7" -> {
                    downloadCalls++
                    assertEquals("bytes=3-", request.headers[HttpHeaders.Range])
                    if (request.headers[HttpHeaders.Authorization] == "Bearer old-access") {
                        jsonResponse("""{"code":401001,"messageKey":"error.tokenExpired"}""", HttpStatusCode.Unauthorized)
                    } else {
                        assertEquals("Bearer new-access", request.headers[HttpHeaders.Authorization])
                        respond(
                            "stream-body",
                            HttpStatusCode.PartialContent,
                            headersOf(HttpHeaders.ContentRange, "bytes 3-13/14"),
                        )
                    }
                }
                else -> error("Unexpected request ${request.url}")
            }
        }
        val client = NetworkTestFacade(
            HttpClient(engine), "https://drive.example.com", store,
            FakeServerStore("https://drive.example.com"),
        )
        client.initialize()

        var consumedBytes = 0
        client.download(TransferScope.Personal(), fileId = 7, rangeStart = 3) { status, headers, channel ->
            assertEquals(HttpStatusCode.PartialContent, status)
            assertEquals("bytes 3-13/14", headers[HttpHeaders.ContentRange])
            val buffer = ByteArray(4)
            while (!channel.isClosedForRead) {
                val count = channel.readAvailable(buffer, 0, buffer.size)
                if (count < 0) break
                consumedBytes += count
            }
        }

        assertEquals(11, consumedBytes)
        assertEquals(2, downloadCalls)
        assertEquals(1, refreshCalls)
        assertEquals("new-access", store.value?.accessToken)
        assertEquals("refresh-2", store.value?.refreshToken)
    }

    @Test
    fun streamingDownloadCancellationPropagatesWithoutInvalidatingSession() = runTest {
        val original = StoredSession("access", "refresh", Long.MAX_VALUE, "https://drive.example.com")
        val store = FakeSessionStore(original)
        val client = NetworkTestFacade(
            HttpClient(MockEngine { respond("stream") }), "https://drive.example.com", store,
            FakeServerStore("https://drive.example.com"),
        )
        client.initialize()

        assertFailsWith<CancellationException> {
            client.download(TransferScope.Personal(), fileId = 7, rangeStart = 0) { _, _, _ ->
                throw CancellationException("stop streaming")
            }
        }

        assertEquals(original, store.value)
    }

    @Test
    fun streamingDownloadSecondUnauthorizedResponseIsNotReplayedAgain() = runTest {
        var downloadCalls = 0
        var refreshCalls = 0
        var consumeCalls = 0
        val store = FakeSessionStore(
            StoredSession("old-access", "refresh-1", Long.MAX_VALUE, "https://drive.example.com"),
        )
        val engine = MockEngine { request ->
            when (request.url.encodedPath) {
                "/api/v1/auth/refresh" -> {
                    refreshCalls++
                    jsonResponse("""{"code":200,"data":{"accessToken":"new-access","refreshToken":"refresh-2","expiresIn":1800}}""")
                }
                else -> {
                    downloadCalls++
                    jsonResponse("""{"code":401001,"messageKey":"error.tokenExpired"}""", HttpStatusCode.Unauthorized)
                }
            }
        }
        val client = NetworkTestFacade(
            HttpClient(engine), "https://drive.example.com", store,
            FakeServerStore("https://drive.example.com"),
        )
        client.initialize()

        assertFailsWith<com.betterclouddrive.mobile.core.model.SessionExpiredException> {
            client.download(TransferScope.Personal(), fileId = 7, rangeStart = 0) { _, _, _ ->
                consumeCalls++
            }
        }

        assertEquals(2, downloadCalls)
        assertEquals(1, refreshCalls)
        assertEquals(0, consumeCalls)
        assertNull(store.value)
    }

    @Test
    fun loginSendsJsonAndLoadsCurrentUser() = runTest {
        var requestContentType: String? = null
        var requestBody = ""
        val engine = MockEngine { request ->
            when (request.url.encodedPath) {
                "/api/v1/auth/login" -> {
                    val outgoing = request.body as OutgoingContent.ByteArrayContent
                    requestContentType = outgoing.contentType?.toString() ?: request.headers[HttpHeaders.ContentType]
                    requestBody = outgoing.bytes().decodeToString()
                    jsonResponse("""{"code":200,"data":{"accessToken":"access","refreshToken":"refresh","expiresIn":1800}}""")
                }
                "/api/v1/auth/me" -> jsonResponse("""{"code":200,"data":{"id":1,"username":"stage1_user"}}""")
                else -> error("Unexpected request ${request.url}")
            }
        }

        val profile = NetworkTestFacade(HttpClient(engine), "https://drive.example.com")
            .login("stage1_user", "stage1_password")

        assertEquals("stage1_user", profile.username)
        assertEquals(ContentType.Application.Json.toString(), requestContentType)
        val json = Json.parseToJsonElement(requestBody).jsonObject
        assertEquals("stage1_user", json.getValue("username").jsonPrimitive.content)
        assertEquals("stage1_password", json.getValue("password").jsonPrimitive.content)
    }

    @Test
    fun concurrentExpiredRequestsUseOneRefreshAndRotatedTokens() = runTest {
        var refreshCount = 0
        val sessionStore = FakeSessionStore(
            StoredSession("expired-access", "refresh-1", Long.MAX_VALUE, "https://drive.example.com"),
        )
        val engine = MockEngine { request ->
            when (request.url.encodedPath) {
                "/api/v1/auth/refresh" -> {
                    refreshCount++
                    jsonResponse("""{"code":200,"data":{"accessToken":"access-2","refreshToken":"refresh-2","expiresIn":1800}}""")
                }
                "/api/v1/files" -> {
                    if (request.headers[HttpHeaders.Authorization] == "Bearer expired-access") {
                        jsonResponse("""{"code":401001,"messageKey":"error.tokenExpired"}""", HttpStatusCode.Unauthorized)
                    } else {
                        assertEquals("Bearer access-2", request.headers[HttpHeaders.Authorization])
                        jsonResponse("""{"code":200,"data":{"records":[],"total":0}}""")
                    }
                }
                else -> error("Unexpected request ${request.url}")
            }
        }
        val client = NetworkTestFacade(
            HttpClient(engine),
            "https://drive.example.com",
            sessionStore,
            FakeServerStore("https://drive.example.com"),
            now = { 100_000 },
        )
        client.initialize()

        listOf(async { client.listRootFiles() }, async { client.listRootFiles() }).awaitAll()

        assertEquals(1, refreshCount)
        assertEquals("access-2", sessionStore.value?.accessToken)
        assertEquals("refresh-2", sessionStore.value?.refreshToken)
    }

    @Test
    fun revokedSessionIsClearedAndNotRetried() = runTest {
        val sessionStore = FakeSessionStore(
            StoredSession("revoked", "refresh", Long.MAX_VALUE, "https://drive.example.com"),
        )
        val engine = MockEngine {
            jsonResponse("""{"code":401002,"messageKey":"error.tokenRevoked"}""", HttpStatusCode.Unauthorized)
        }
        val client = NetworkTestFacade(
            HttpClient(engine), "https://drive.example.com", sessionStore, FakeServerStore("https://drive.example.com"),
        )
        client.initialize()

        assertFailsWith<com.betterclouddrive.mobile.core.model.SessionExpiredException> { client.listRootFiles() }
        assertNull(sessionStore.value)
    }

    @Test
    fun semanticErrorKeepsStableKey() = runTest {
        val engine = MockEngine { request ->
            if (request.url.encodedPath.endsWith("/auth/login")) {
                jsonResponse("""{"code":200,"data":{"accessToken":"access","refreshToken":"refresh"}}""")
            } else if (request.url.encodedPath.endsWith("/auth/me")) {
                jsonResponse("""{"code":200,"data":{"id":1,"username":"user"}}""")
            } else {
                jsonResponse("""{"code":419001,"messageKey":"error.storageQuotaExceeded"}""")
            }
        }
        val client = NetworkTestFacade(HttpClient(engine), "https://drive.example.com")
        client.login("user", "password")

        val error = assertFailsWith<ApiException> { client.listRootFiles() }
        assertEquals(419001, error.code)
        assertEquals("error.storageQuotaExceeded", error.messageKey)
    }

    @Test
    fun loadTeamOfficeRequestsSignedMobileReadOnlyConfig() = runTest {
        var requestedUrl = ""
        val engine = MockEngine { request ->
            val content = when {
                request.url.encodedPath.endsWith("/auth/login") ->
                    """{"code":200,"data":{"accessToken":"access","refreshToken":"refresh"}}"""
                request.url.encodedPath.endsWith("/auth/me") ->
                    """{"code":200,"data":{"id":1,"username":"user"}}"""
                else -> {
                    requestedUrl = request.url.toString()
                    """{"code":200,"data":{"documentServerUrl":"https://office.example.com","editable":false,"config":{"type":"mobile","document":{},"editorConfig":{"mode":"view"},"token":"signed"}}}"""
                }
            }
            jsonResponse(content)
        }
        val client = NetworkTestFacade(HttpClient(engine), "https://drive.example.com")
        client.login("user", "password")

        val config = client.loadTeamOffice(teamId = 10, fileId = 7)

        assertEquals("https://drive.example.com/api/v1/teams/10/office/files/7/config?mobile=true", requestedUrl)
        assertFalse(config.editable)
        assertEquals("mobile", config.config.getValue("type").jsonPrimitive.content)
    }

    @Test
    fun searchTicketTextRangeAndPersonalOfficeUseStableContracts() = runTest {
        val observed = mutableListOf<Pair<String, String?>>()
        val engine = MockEngine { request ->
            observed += request.url.toString() to request.headers[HttpHeaders.Range]
            when (request.url.encodedPath) {
                "/api/v1/files/search" -> jsonResponse(
                    """{"code":200,"data":{"records":[{"id":7,"fileName":"note.txt"}],"total":1,"page":2,"size":50,"pages":2}}""",
                )
                "/api/v1/download/7/ticket" -> jsonResponse(
                    """{"code":200,"data":{"url":"/api/v1/download/7?ticket=secret"}}""",
                )
                "/api/v1/office/files/7/config" -> jsonResponse(
                    """{"code":200,"data":{"documentServerUrl":"https://office.example.com","editable":false,"config":{"type":"mobile"}}}""",
                )
                "/api/v1/download/7" -> respond(
                    "hello",
                    HttpStatusCode.PartialContent,
                    headersOf(HttpHeaders.ContentType, ContentType.Text.Plain.toString()),
                )
                else -> error("unexpected ${request.url}")
            }
        }
        val client = NetworkTestFacade(
            HttpClient(engine), "https://drive.example.com",
            FakeSessionStore(StoredSession("access", "refresh", Long.MAX_VALUE, "https://drive.example.com")),
            FakeServerStore("https://drive.example.com"),
        )
        client.initialize()

        assertEquals(7, client.searchPersonalFiles("note", 2, 50).records.single().id)
        assertEquals("https://drive.example.com/api/v1/download/7?ticket=secret", client.createPersonalDownloadTicket(7))
        assertFalse(client.loadPersonalOffice(7).editable)
        assertEquals("hello", client.readPersonalText(7, 512 * 1024).decodeToString())

        assertTrue(observed.any { it.first.endsWith("/files/search?q=note&page=2&size=50") })
        assertTrue(observed.any { it.first.endsWith("/office/files/7/config?mobile=true") })
        assertTrue(observed.any { it.first.endsWith("/download/7") && it.second == "bytes=0-524287" })
    }

    @Test
    fun restoreRefreshesExpiringSessionBeforeLoadingUser() = runTest {
        val store = FakeSessionStore(StoredSession("old", "refresh-1", 1, "https://drive.example.com"))
        val engine = MockEngine { request ->
            when (request.url.encodedPath) {
                "/api/v1/auth/refresh" -> jsonResponse(
                    """{"code":200,"data":{"accessToken":"new","refreshToken":"refresh-2","expiresIn":1800}}""",
                )
                "/api/v1/auth/me" -> {
                    assertEquals("Bearer new", request.headers[HttpHeaders.Authorization])
                    jsonResponse("""{"code":200,"data":{"id":7,"username":"restored"}}""")
                }
                else -> error("Unexpected request ${request.url}")
            }
        }
        val client = NetworkTestFacade(
            HttpClient(engine), "https://drive.example.com", store,
            FakeServerStore("https://drive.example.com"), now = { 100_000 },
        )

        val restored = client.restoreUser() as SessionRestoreResult.Restored
        assertEquals("restored", restored.user.username)
        assertEquals("refresh-2", store.value?.refreshToken)
    }

    @Test
    fun switchingServerClearsServerBoundSession() = runTest {
        val store = FakeSessionStore(StoredSession("access", "refresh", Long.MAX_VALUE, "https://one.example.com"))
        val client = NetworkTestFacade(
            HttpClient(MockEngine { error("network should not be used") }),
            "https://one.example.com", store, FakeServerStore("https://one.example.com"),
        )
        client.initialize()

        client.updateServer("https://two.example.com/").getOrThrow()

        assertEquals("https://two.example.com", client.serverBaseUrl)
        assertNull(store.value)
    }

    @Test
    fun logoutAlwaysClearsLocalSessionWhenServerFails() = runTest {
        val store = FakeSessionStore(StoredSession("access", "refresh", Long.MAX_VALUE, "https://drive.example.com"))
        val client = NetworkTestFacade(
            HttpClient(MockEngine { error("offline") }), "https://drive.example.com", store,
            FakeServerStore("https://drive.example.com"),
        )
        client.initialize()

        client.logout()

        assertNull(store.value)
    }

    @Test
    fun cancellationDuringRestoreIsNotConvertedToOfflineState() = runTest {
        val store = FakeSessionStore(StoredSession("access", "refresh", Long.MAX_VALUE, "https://drive.example.com"))
        val client = NetworkTestFacade(
            HttpClient(MockEngine { throw CancellationException("cancel test") }),
            "https://drive.example.com", store, FakeServerStore("https://drive.example.com"),
        )

        assertFailsWith<CancellationException> { client.restoreUser() }
    }

    @Test
    fun loginRollsBackTokensWhenCurrentUserCannotBeLoaded() = runTest {
        val store = FakeSessionStore()
        val engine = MockEngine { request ->
            if (request.url.encodedPath.endsWith("/auth/login")) {
                jsonResponse("""{"code":200,"data":{"accessToken":"access","refreshToken":"refresh"}}""")
            } else {
                error("offline")
            }
        }
        val client = NetworkTestFacade(
            HttpClient(engine), "https://drive.example.com", store, FakeServerStore("https://drive.example.com"),
        )

        assertFailsWith<Throwable> { client.login("user", "password") }
        assertNull(store.value)
    }

    @Test
    fun refreshFailureInvalidatesSession() = runTest {
        val store = FakeSessionStore(StoredSession("old", "refresh", 1, "https://drive.example.com"))
        val client = NetworkTestFacade(
            HttpClient(MockEngine { error("offline") }), "https://drive.example.com", store,
            FakeServerStore("https://drive.example.com"), now = { 100_000 },
        )

        assertIs<SessionRestoreResult.Invalidated>(client.restoreUser())
        assertNull(store.value)
    }

    @Test
    fun transientRestoreFailureKeepsEncryptedSessionForRetry() = runTest {
        val original = StoredSession("access", "refresh", Long.MAX_VALUE, "https://drive.example.com")
        val store = FakeSessionStore(original)
        val client = NetworkTestFacade(
            HttpClient(MockEngine { error("offline") }), "https://drive.example.com", store,
            FakeServerStore("https://drive.example.com"),
        )

        assertIs<SessionRestoreResult.RetryableFailure>(client.restoreUser())
        assertEquals(original, store.value)
    }

    @Test
    fun secondExpiredResponseIsNotReplayedAgainAndClearsSession() = runTest {
        var fileCalls = 0
        var refreshCalls = 0
        val store = FakeSessionStore(StoredSession("old", "refresh", Long.MAX_VALUE, "https://drive.example.com"))
        val engine = MockEngine { request ->
            when (request.url.encodedPath) {
                "/api/v1/auth/refresh" -> {
                    refreshCalls++
                    jsonResponse("""{"code":200,"data":{"accessToken":"new","refreshToken":"rotated"}}""")
                }
                else -> {
                    fileCalls++
                    jsonResponse("""{"code":401001,"messageKey":"error.tokenExpired"}""", HttpStatusCode.Unauthorized)
                }
            }
        }
        val client = NetworkTestFacade(
            HttpClient(engine), "https://drive.example.com", store, FakeServerStore("https://drive.example.com"),
        )
        client.initialize()

        assertFailsWith<com.betterclouddrive.mobile.core.model.SessionExpiredException> { client.listRootFiles() }
        assertEquals(2, fileCalls)
        assertEquals(1, refreshCalls)
        assertNull(store.value)
    }

    @Test
    fun productionPolicyRejectsCleartextServer() {
        val client = NetworkTestFacade(
            HttpClient(MockEngine { error("unused") }), "https://drive.example.com",
            allowCleartextServer = false,
        )
        assertFalse(client.normalizeServer("http://drive.example.com").isSuccess)
        assertEquals("https://drive.example.com", client.normalizeServer("https://drive.example.com/").getOrThrow())
    }

    private fun io.ktor.client.engine.mock.MockRequestHandleScope.jsonResponse(
        content: String,
        status: HttpStatusCode = HttpStatusCode.OK,
    ) = respond(content, status, headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()))
}

private class FakeSessionStore(initial: StoredSession? = null) : SecureSessionStore {
    var value: StoredSession? = initial
    override suspend fun read(serverBaseUrl: String): StoredSession? = value?.takeIf { it.serverBaseUrl == serverBaseUrl }
    override suspend fun write(session: StoredSession) { value = session }
    override suspend fun clear() { value = null }
}

private class FakeServerStore(initial: String) : ServerSettingsStore {
    private val state = MutableStateFlow(initial)
    override val serverBaseUrl: Flow<String> = state
    override suspend fun read(): String = state.value
    override suspend fun write(value: String) { state.value = value }
}
