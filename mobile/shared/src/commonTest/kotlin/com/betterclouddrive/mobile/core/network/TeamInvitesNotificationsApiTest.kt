package com.betterclouddrive.mobile.core.network

import com.betterclouddrive.mobile.core.model.StoredSession
import com.betterclouddrive.mobile.core.model.TeamRole
import com.betterclouddrive.mobile.core.platform.SecureSessionStore
import com.betterclouddrive.mobile.core.platform.ServerSettingsStore
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TeamInvitesNotificationsApiTest {
    @Test
    fun invitationAndNotificationEndpointsKeepStableContracts() = runTest {
        val requests = mutableListOf<Triple<HttpMethod, String, String?>>()
        val engine = MockEngine { request ->
            requests += Triple(request.method, request.url.toString(), request.headers[HttpHeaders.Authorization])
            when (request.url.encodedPath) {
                "/api/v1/team-invites/abcdefghijklmnop" -> json("""{"code":200,"data":{"id":2,"teamId":7,"email":"member@example.com","role":"VIEWER","status":"PENDING"}}""")
                "/api/v1/team-invites/abcdefghijklmnop/accept",
                "/api/v1/team-invites/abcdefghijklmnop/reject",
                "/api/v1/teams/7/invites/2",
                "/api/v1/notifications/2/read",
                "/api/v1/notifications/read-all" -> json("""{"code":200}""")
                "/api/v1/teams/7/invites" -> if (request.method == HttpMethod.Get) json("""{"code":200,"data":{"records":[{"id":2,"teamId":7,"email":"member@example.com","role":"VIEWER","status":"PENDING"}],"total":1,"page":1,"size":50,"pages":1}}""")
                    else json("""{"code":200,"data":{"id":2,"teamId":7,"email":"member@example.com","role":"EDITOR","status":"PENDING"}}""")
                "/api/v1/teams/7/invites/2/resend" -> json("""{"code":200,"data":{"id":2,"teamId":7,"email":"member@example.com","role":"VIEWER","status":"PENDING"}}""")
                "/api/v1/notifications" -> json("""{"code":200,"data":{"records":[{"id":2,"type":"TEAM_INVITE","titleKey":"notification.teamInvite.title","contentKey":"notification.teamInvite.content","messageParams":{"teamName":"Docs"},"isRead":false}],"total":1,"page":1,"size":50,"pages":1}}""")
                "/api/v1/notifications/unread-count" -> json("""{"code":200,"data":3}""")
                else -> error("unexpected ${request.method} ${request.url}")
            }
        }
        val store = MemorySession(StoredSession("access", "refresh", Long.MAX_VALUE, "https://drive.example.com"))
        val client = NetworkTestFacade(HttpClient(engine), "https://drive.example.com", store, MemoryServer())
        client.initialize()

        assertEquals(7, client.getIncomingInvite("abcdefghijklmnop").teamId)
        assertEquals(1, client.listTeamInvites(7, 1, 50).total)
        assertEquals(TeamRole.EDITOR, client.createTeamInvite(7, "member@example.com", TeamRole.EDITOR).role)
        client.resendTeamInvite(7, 2)
        client.cancelTeamInvite(7, 2)
        client.acceptIncomingInvite("abcdefghijklmnop")
        client.rejectIncomingInvite("abcdefghijklmnop")
        assertEquals(1, client.listNotifications(true, "TEAM_INVITE", 1, 50).total)
        assertEquals(3, client.notificationUnreadCount())
        client.markNotificationRead(2)
        client.markAllNotificationsRead()

        val incomingGet = requests.first { it.second.endsWith("/team-invites/abcdefghijklmnop") }
        assertNull(incomingGet.third)
        assertTrue(requests.any { it.second.endsWith("/teams/7/invites?page=1&size=50") && it.third == "Bearer access" })
        assertTrue(requests.any { it.second.endsWith("/notifications?unreadOnly=true&type=TEAM_INVITE&page=1&size=50") })
    }

    private fun io.ktor.client.engine.mock.MockRequestHandleScope.json(value: String) = respond(
        value, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
    )
}

private class MemorySession(private var value: StoredSession?) : SecureSessionStore {
    override suspend fun read(serverBaseUrl: String) = value
    override suspend fun write(session: StoredSession) { value = session }
    override suspend fun clear() { value = null }
}

private class MemoryServer : ServerSettingsStore {
    private val state = MutableStateFlow("https://drive.example.com")
    override val serverBaseUrl: Flow<String> = state
    override suspend fun read() = state.value
    override suspend fun write(value: String) { state.value = value }
}
