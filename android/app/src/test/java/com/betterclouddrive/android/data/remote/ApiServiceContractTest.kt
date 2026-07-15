package com.betterclouddrive.android.data.remote

import com.betterclouddrive.android.data.remote.dto.AddTeamMemberRequest
import com.betterclouddrive.android.data.remote.dto.BatchDeleteRequest
import com.betterclouddrive.android.data.remote.dto.CopyRequest
import com.betterclouddrive.android.data.remote.dto.CreateFolderRequest
import com.betterclouddrive.android.data.remote.dto.CreateShareRequest
import com.betterclouddrive.android.data.remote.dto.CreateTagRequest
import com.betterclouddrive.android.data.remote.dto.CreateTeamInviteRequest
import com.betterclouddrive.android.data.remote.dto.CreateTeamRequest
import com.betterclouddrive.android.data.remote.dto.InitUploadRequest
import com.betterclouddrive.android.data.remote.dto.MoveRequest
import com.betterclouddrive.android.data.remote.dto.RenameRequest
import com.betterclouddrive.android.data.remote.dto.TagFilesRequest
import com.betterclouddrive.android.data.remote.dto.TransferTeamOwnerRequest
import com.betterclouddrive.android.data.remote.dto.UpdateShareRequest
import com.betterclouddrive.android.data.remote.dto.UpdateTeamFilePermissionRequest
import com.betterclouddrive.android.data.remote.dto.UpdateTeamMemberRequest
import com.betterclouddrive.android.data.remote.dto.UpdateTeamRequest
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
    fun updateShareRequestSerializesExplicitClearFlags() {
        assertEquals(
            """{"clearPassword":true,"clearExpireAt":true,"clearMaxVisits":true}""",
            json.encodeToString(
                UpdateShareRequest.serializer(),
                UpdateShareRequest(clearPassword = true, clearExpireAt = true, clearMaxVisits = true),
            ),
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

    @Test
    fun teamFileListUsesTeamScopedPathAndParentQuery() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            repeat(3) {
                server.enqueue(pageResponse())
            }
            api(server).listTeamFiles(teamId = 7, parentId = 9, page = 2, size = 50)

            val request = server.takeRequest()
            assertEquals("/teams/7/files?parentId=9&page=2&size=50&sortBy=fileName&order=asc", request.path)
            assertEquals("GET", request.method)

            api(server).searchTeamFiles(teamId = 7, query = "contract", page = 2, size = 30)
            assertEquals("/teams/7/files/search?q=contract&page=2&size=30", server.takeRequest().path)

            api(server).getTeamFile(teamId = 7, fileId = 11)
            assertEquals("/teams/7/files/11", server.takeRequest().path)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun teamUploadInitUsesTeamScopedEndpoint() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(
                MockResponse()
                    .setHeader("Content-Type", "application/json")
                    .setBody("""{"code":200,"message":"success","data":{"sessionId":"team-session","chunkSize":5242880,"totalChunks":1}}"""),
            )
            api(server).initTeamUpload(
                teamId = 7,
                request = InitUploadRequest(
                    parentId = 9,
                    fileName = "team.txt",
                    fileSize = 4,
                    md5Hash = "abcd",
                    totalChunks = 1,
                ),
            )

            val request = server.takeRequest()
            val body = request.body.readUtf8()
            assertEquals("/teams/7/upload/init", request.path)
            assertEquals("POST", request.method)
            assertTrue(body.contains(""""parentId":9"""))
            assertTrue(body.contains(""""fileName":"team.txt""""))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun teamOfficeConfigUsesTeamScopedEndpoint() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(
                MockResponse()
                    .setHeader("Content-Type", "application/json")
                    .setBody("""{"code":200,"message":"success","data":{"documentServerUrl":"http://127.0.0.1:8082","editable":true,"config":{}}}"""),
            )
            api(server).getTeamOfficeConfig(teamId = 7, fileId = 11)

            val request = server.takeRequest()
            assertEquals("/teams/7/office/files/11/config", request.path)
            assertEquals("GET", request.method)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun teamSettingsRecycleShareAndVersionRoutesUseTeamScopedPaths() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            repeat(20) {
                server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody("""{"code":200,"message":"success","data":null}"""))
            }

            api(server).listTeams()
            assertEquals("/teams?page=1&size=20", server.takeRequest().path)

            api(server).createTeam(CreateTeamRequest("Product"))
            val createTeam = server.takeRequest()
            assertEquals("/teams", createTeam.path)
            assertEquals("""{"name":"Product"}""", createTeam.body.readUtf8())

            api(server).getTeam(7)
            assertEquals("/teams/7", server.takeRequest().path)

            api(server).updateTeam(7, UpdateTeamRequest("Product Docs"))
            val updateTeam = server.takeRequest()
            assertEquals("/teams/7", updateTeam.path)
            assertEquals("PUT", updateTeam.method)
            assertEquals("""{"name":"Product Docs"}""", updateTeam.body.readUtf8())

            api(server).deleteTeam(7)
            assertEquals("/teams/7", server.takeRequest().path)

            api(server).leaveTeam(7)
            assertEquals("/teams/7/leave", server.takeRequest().path)

            api(server).transferTeamOwner(7, TransferTeamOwnerRequest(12))
            assertEquals("/teams/7/owner", server.takeRequest().path)

            api(server).listTeamRecycleBin(7, page = 2, size = 30)
            assertEquals("/teams/7/recycle-bin?page=2&size=30", server.takeRequest().path)

            api(server).restoreTeamFile(7, 9)
            assertEquals("/teams/7/recycle-bin/9/restore", server.takeRequest().path)

            api(server).permanentDeleteTeamFile(7, 9)
            assertEquals("/teams/7/recycle-bin/9", server.takeRequest().path)

            api(server).emptyTeamRecycleBin(7)
            assertEquals("/teams/7/recycle-bin", server.takeRequest().path)

            api(server).createTeamShare(7, CreateShareRequest(fileId = 9))
            assertEquals("/teams/7/shares", server.takeRequest().path)

            api(server).listTeamShares(7, page = 1, size = 50)
            assertEquals("/teams/7/shares?page=1&size=50", server.takeRequest().path)

            api(server).updateTeamShare(
                7,
                10,
                UpdateShareRequest(clearPassword = true, clearExpireAt = true, clearMaxVisits = true),
            )
            val updateShare = server.takeRequest()
            assertEquals("/teams/7/shares/10", updateShare.path)
            assertEquals("PUT", updateShare.method)
            assertEquals("""{"clearPassword":true,"clearExpireAt":true,"clearMaxVisits":true}""", updateShare.body.readUtf8())

            api(server).cancelTeamShare(7, 10)
            assertEquals("/teams/7/shares/10", server.takeRequest().path)

            api(server).restoreTeamVersion(7, 9, 2)
            assertEquals("/teams/7/files/9/versions/2/restore", server.takeRequest().path)

            api(server).restoreVersion(9, 2)
            assertEquals("/files/9/versions/2/restore", server.takeRequest().path)

            api(server).downloadTeamVersion(7, 9, 2)
            assertEquals("/teams/7/files/9/versions/2/download", server.takeRequest().path)

            api(server).downloadVersion(9, 2)
            assertEquals("/files/9/versions/2/download", server.takeRequest().path)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun teamInviteAuditFavoriteAndTagRoutesUseTeamScopedPaths() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            repeat(33) {
                server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody("""{"code":200,"message":"success","data":null}"""))
            }

            api(server).listTeamMembers(7)
            assertEquals("/teams/7/members?page=1&size=20", server.takeRequest().path)

            api(server).addTeamMember(7, AddTeamMemberRequest(username = "chain", role = "EDITOR"))
            val addMember = server.takeRequest()
            assertEquals("/teams/7/members", addMember.path)
            assertEquals("""{"username":"chain","role":"EDITOR"}""", addMember.body.readUtf8())

            api(server).updateTeamMember(7, 12, UpdateTeamMemberRequest(role = "ADMIN"))
            val updateMember = server.takeRequest()
            assertEquals("/teams/7/members/12", updateMember.path)
            assertEquals("PUT", updateMember.method)
            assertEquals("""{"role":"ADMIN"}""", updateMember.body.readUtf8())

            api(server).removeTeamMember(7, 12)
            assertEquals("/teams/7/members/12", server.takeRequest().path)

            api(server).createTeamInvite(7, CreateTeamInviteRequest(email = "member@test.local", role = "EDITOR"))
            assertEquals("/teams/7/invites", server.takeRequest().path)

            api(server).listTeamInvites(7)
            assertEquals("/teams/7/invites?page=1&size=20", server.takeRequest().path)

            api(server).resendTeamInvite(7, 3)
            assertEquals("/teams/7/invites/3/resend", server.takeRequest().path)

            api(server).cancelTeamInvite(7, 3)
            assertEquals("/teams/7/invites/3", server.takeRequest().path)

            api(server).getTeamInvite("invite-token")
            assertEquals("/team-invites/invite-token", server.takeRequest().path)

            api(server).acceptTeamInvite("invite-token")
            assertEquals("/team-invites/invite-token/accept", server.takeRequest().path)

            api(server).rejectTeamInvite("invite-token")
            assertEquals("/team-invites/invite-token/reject", server.takeRequest().path)

            api(server).listTeamAuditLogs(
                7,
                actorUserId = 12,
                action = "INVITE",
                targetType = "USER",
                result = 1,
                startAt = "2026-06-27T12:00:00",
                endAt = "2026-06-27T13:00:00",
                page = 2,
                size = 30,
            )
            assertEquals(
                "/teams/7/audit-logs?actorUserId=12&action=INVITE&targetType=USER&result=1&startAt=2026-06-27T12%3A00%3A00&endAt=2026-06-27T13%3A00%3A00&page=2&size=30",
                server.takeRequest().path,
            )

            api(server).exportTeamAuditLogs(
                7,
                actorUserId = 12,
                action = "INVITE",
                targetType = "USER",
                result = 1,
                startAt = "2026-06-27T12:00:00",
                endAt = "2026-06-27T13:00:00",
            )
            assertEquals(
                "/teams/7/audit-logs/export?actorUserId=12&action=INVITE&targetType=USER&result=1&startAt=2026-06-27T12%3A00%3A00&endAt=2026-06-27T13%3A00%3A00",
                server.takeRequest().path,
            )

            api(server).getTeamFileEffectivePermission(7, 9)
            assertEquals("/teams/7/files/9/effective-permission", server.takeRequest().path)

            api(server).listTeamFilePermissions(7, 9)
            assertEquals("/teams/7/files/9/permissions", server.takeRequest().path)

            api(server).upsertTeamFilePermission(
                7,
                9,
                UpdateTeamFilePermissionRequest(
                    subjectType = "ROLE",
                    subjectRole = "VIEWER",
                    canRead = true,
                    canWrite = false,
                    canShare = false,
                    canDelete = false,
                    canManage = false,
                ),
            )
            assertEquals("/teams/7/files/9/permissions", server.takeRequest().path)

            api(server).deleteTeamFilePermission(7, 9, 4)
            assertEquals("/teams/7/files/9/permissions/4", server.takeRequest().path)

            api(server).addTeamFavorite(7, 9)
            assertEquals("/teams/7/favorites/9", server.takeRequest().path)

            api(server).removeTeamFavorite(7, 9)
            assertEquals("/teams/7/favorites/9", server.takeRequest().path)

            api(server).getTeamFavoriteStatus(7, 9)
            assertEquals("/teams/7/favorites/9/status", server.takeRequest().path)

            api(server).listTeamFavorites(7, query = "plan", page = 2, size = 30)
            assertEquals("/teams/7/favorites?q=plan&page=2&size=30", server.takeRequest().path)

            api(server).createTeamTag(7, CreateTagRequest(tagName = "合同", color = "#00aa88"))
            assertEquals("/teams/7/tags", server.takeRequest().path)

            api(server).listTeamTags(7, query = "合")
            assertEquals("/teams/7/tags?q=%E5%90%88", server.takeRequest().path)

            api(server).updateTeamTag(7, 5, com.betterclouddrive.android.data.remote.dto.UpdateTagRequest(tagName = "合同归档", color = "#00aa88"))
            assertEquals("/teams/7/tags/5", server.takeRequest().path)

            api(server).deleteTeamTag(7, 5)
            assertEquals("/teams/7/tags/5", server.takeRequest().path)

            api(server).tagTeamFiles(7, 5, TagFilesRequest(listOf(9)))
            assertEquals("/teams/7/tags/5/files", server.takeRequest().path)

            api(server).untagTeamFile(7, 5, 9)
            assertEquals("/teams/7/tags/5/files/9", server.takeRequest().path)

            api(server).listTeamFilesByTag(7, 5, query = "doc", page = 2, size = 30)
            assertEquals("/teams/7/tags/5/files?q=doc&page=2&size=30", server.takeRequest().path)

            api(server).listNotifications(page = 2, size = 30, unreadOnly = true)
            assertEquals("/notifications?page=2&size=30&unreadOnly=true", server.takeRequest().path)

            api(server).listNotifications(page = 1, size = 20, unreadOnly = false, type = "TEAM_QUOTA_ALERT")
            assertEquals("/notifications?page=1&size=20&unreadOnly=false&type=TEAM_QUOTA_ALERT", server.takeRequest().path)

            api(server).getNotificationUnreadCount()
            assertEquals("/notifications/unread-count", server.takeRequest().path)

            api(server).markNotificationRead(11)
            assertEquals("/notifications/11/read", server.takeRequest().path)

            api(server).markAllNotificationsRead()
            assertEquals("/notifications/read-all", server.takeRequest().path)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun teamDownloadPreviewAndMutationRoutesUseTeamScopedPaths() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            repeat(10) {
                server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody("""{"code":200,"message":"success","data":null}"""))
            }

            api(server).createTeamFolder(7, CreateFolderRequest(parentId = null, folderName = "Docs"))
            val createFolder = server.takeRequest()
            assertEquals("/teams/7/files/folder", createFolder.path)
            assertEquals("""{"parentId":null,"folderName":"Docs"}""", createFolder.body.readUtf8())

            api(server).renameTeamFile(7, 9, RenameRequest("New.txt"))
            val rename = server.takeRequest()
            assertEquals("/teams/7/files/9", rename.path)
            assertEquals("PUT", rename.method)
            assertEquals("""{"newName":"New.txt"}""", rename.body.readUtf8())

            api(server).moveTeamFile(7, 9, MoveRequest(null))
            val move = server.takeRequest()
            assertEquals("/teams/7/files/9/move", move.path)
            assertEquals("""{"targetParentId":null}""", move.body.readUtf8())

            api(server).deleteTeamFiles(7, BatchDeleteRequest(listOf(9, 10)))
            val delete = server.takeRequest()
            assertEquals("/teams/7/files", delete.path)
            assertEquals("DELETE", delete.method)
            assertEquals("""{"fileIds":[9,10]}""", delete.body.readUtf8())

            api(server).downloadTeamFile(7, 9, "bytes=0-99")
            val download = server.takeRequest()
            assertEquals("/teams/7/download/9", download.path)
            assertEquals("bytes=0-99", download.getHeader("Range"))

            api(server).createTeamDownloadTicket(7, 9)
            assertEquals("/teams/7/download/9/ticket", server.takeRequest().path)

            api(server).previewTeamFile(7, 9)
            assertEquals("/teams/7/preview/9", server.takeRequest().path)
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
