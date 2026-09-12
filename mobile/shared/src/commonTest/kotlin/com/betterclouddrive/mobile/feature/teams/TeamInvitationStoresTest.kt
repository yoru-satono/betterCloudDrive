package com.betterclouddrive.mobile.feature.teams

import com.betterclouddrive.mobile.core.model.NotificationItem
import com.betterclouddrive.mobile.core.model.PageResult
import com.betterclouddrive.mobile.core.model.TeamInvite
import com.betterclouddrive.mobile.core.model.TeamInviteStatus
import com.betterclouddrive.mobile.core.model.TeamRole
import com.betterclouddrive.mobile.core.localization.MobileString
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class TeamInvitationStoresTest {
    @Test
    fun deepLinksAndNotificationTargetsAreStrictlyWhitelisted() {
        assertEquals("abcdefghijklmnop", parseIncomingInviteDeepLink("betterclouddrive://team-invites/abcdefghijklmnop"))
        assertEquals("abcdefghijklmnop", parseIncomingInviteDeepLink(" betterclouddrive://team-invites/abcdefghijklmnop/ "))
        assertNull(parseIncomingInviteDeepLink("http://localhost:3000/team-invites/abcdefghijklmnop"))
        assertNull(parseIncomingInviteDeepLink("http://127.0.0.1:3000/team-invites/abcdefghijklmnop"))
        assertNull(parseIncomingInviteDeepLink("https://example.com/team-invites/abcdefghijklmnop"))
        assertNull(parseIncomingInviteDeepLink("betterclouddrive://other/abcdefghijklmnop"))
        assertNull(parseIncomingInviteDeepLink("betterclouddrive://team-invites/short"))
        assertNull(parseIncomingInviteDeepLink(null))
        assertIs<NotificationTarget.Invite>(parseNotificationTarget("/team-invites/abcdefghijklmnop"))
        assertEquals(NotificationTarget.TeamFolder(7, 8), parseNotificationTarget("/teams/7/files/8"))
        assertEquals(NotificationTarget.TeamMembers(7), parseNotificationTarget("/teams/7/settings"))
        assertEquals(NotificationTarget.Unsupported, parseNotificationTarget("https://evil.example/path"))
    }

    @Test
    fun teamInvitesPreserveTabAndEnforceAdministratorRoleMatrix() = runTest {
        val repository = FakeInvites()
        val store = TeamInvitesStore(repository, backgroundScope)
        store.activate(7, TeamRole.ADMIN)
        store.selectTab(TeamPeopleTab.INVITES)
        runCurrent()
        assertEquals(1, store.state.value.items.size)
        store.create("next@example.com", TeamRole.ADMIN)
        runCurrent()
        assertEquals(0, repository.creates)
        assertTrue(store.state.value.createError != null)
        store.clearCreateError()
        store.create("next@example.com", TeamRole.EDITOR)
        runCurrent()
        assertEquals(1, repository.creates)
        assertEquals(TeamPeopleTab.INVITES, store.state.value.selectedTab)
    }

    @Test
    fun incomingInviteNeverExposesTokenAndRequiresMatchingAccount() = runTest {
        val repository = FakeInvites()
        val store = IncomingInviteStore(repository, backgroundScope)
        store.open("abcdefghijklmnop")
        runCurrent()
        assertEquals("member@example.com", store.state.value.invite?.email)
        assertFalse(store.state.value.toString().contains("abcdefghijklmnop"))
        store.accept("other@example.com")
        assertTrue(store.state.value.error != null)
        store.accept("member@example.com")
        runCurrent()
        assertEquals(7, store.state.value.acceptedTeamId)
        assertEquals(1, repository.accepts)
        store.reset()
        assertFalse(store.state.value.active)
    }

    @Test
    fun notificationsConnectOnlyForForegroundSessionAndReconnectWithNewStream() = runTest {
        val repository = FakeNotifications(failFirstStream = true)
        val waits = mutableListOf<Long>()
        val store = NotificationsStore(repository, backgroundScope) { millis -> waits += millis; kotlinx.coroutines.delay(millis) }
        store.setForeground(true)
        runCurrent()
        assertEquals(0, repository.streams)
        store.startSession()
        runCurrent()
        assertEquals(1, repository.streams)
        advanceTimeBy(1_000); runCurrent()
        assertEquals(2, repository.streams)
        assertEquals(listOf(1_000L), waits)
        assertEquals(4, store.state.value.unreadCount)
        store.setForeground(false)
        runCurrent()
        advanceTimeBy(30_000); runCurrent()
        assertEquals(2, repository.streams)
        assertFalse(store.state.value.streamConnected)
    }

    @Test
    fun notificationNavigationContinuesWhenMarkReadFails() = runTest {
        val repository = FakeNotifications(markFailure = true)
        val store = NotificationsStore(repository, backgroundScope)
        var target: NotificationTarget? = null
        val item = NotificationItem(id = 2, link = "/teams/7/settings")
        store.markRead(item) { target = it }
        assertEquals(NotificationTarget.TeamMembers(7), target)
        runCurrent()
        assertTrue(store.state.value.feedback != null)
    }

    @Test
    fun unsupportedNotificationKeepsItsExplanationAfterBeingMarkedRead() = runTest {
        val store = NotificationsStore(FakeNotifications(), backgroundScope)

        store.markRead(NotificationItem(id = 2, link = "https://evil.example/path")) { }
        runCurrent()

        assertEquals(MobileString.NotificationUnsupportedLink, store.state.value.feedback?.key)
    }
}

private class FakeInvites : TeamInvitesRepository {
    var creates = 0
    var accepts = 0
    private val invite = TeamInvite(
        id = 1, teamId = 7, email = "member@example.com", role = TeamRole.VIEWER,
        status = TeamInviteStatus.PENDING,
    )
    override suspend fun list(teamId: Long, page: Int, size: Int) = PageResult(listOf(invite), 1, page, size, 1)
    override suspend fun create(teamId: Long, email: String, role: TeamRole) = invite.copy(email = email, role = role).also { creates++ }
    override suspend fun resend(teamId: Long, inviteId: Long) = invite
    override suspend fun cancel(teamId: Long, inviteId: Long) = Unit
    override suspend fun getIncoming(token: String) = invite
    override suspend fun accept(token: String) { accepts++ }
    override suspend fun reject(token: String) = Unit
}

private class FakeNotifications(
    private val failFirstStream: Boolean = false,
    private val markFailure: Boolean = false,
) : NotificationsRepository {
    var streams = 0
    override suspend fun list(unreadOnly: Boolean?, type: String?, page: Int, size: Int) =
        PageResult(listOf(NotificationItem(id = 2, isRead = false)), 1, page, size, 1)
    override suspend fun unreadCount() = 3L
    override suspend fun markRead(notificationId: Long) { if (markFailure) error("offline") }
    override suspend fun markAllRead() = Unit
    override suspend fun stream(onUnreadCount: suspend (Long) -> Unit) {
        streams++
        if (failFirstStream && streams == 1) error("disconnected")
        onUnreadCount(4)
        kotlinx.coroutines.awaitCancellation()
    }
}
