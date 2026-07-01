package com.betterclouddrive.android.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DeepLinkParserTest {
    @Test
    fun parsesCustomTeamInviteScheme() {
        assertEquals("abc123", DeepLinkParser.teamInviteToken("betterclouddrive://team-invites/abc123"))
    }

    @Test
    fun parsesLocalWebTeamInviteLinks() {
        assertEquals("abc123", DeepLinkParser.teamInviteToken("http://localhost:3000/team-invites/abc123"))
        assertEquals("abc123", DeepLinkParser.teamInviteToken("http://127.0.0.1:3000/team-invites/abc123"))
    }

    @Test
    fun ignoresUnsupportedLinks() {
        assertNull(DeepLinkParser.teamInviteToken("http://localhost:3000/files"))
        assertNull(DeepLinkParser.teamInviteToken("https://example.com/team-invites/abc123"))
        assertNull(DeepLinkParser.teamInviteToken("betterclouddrive://shares/abc123"))
        assertNull(DeepLinkParser.teamInviteToken(null as String?))
    }
}
