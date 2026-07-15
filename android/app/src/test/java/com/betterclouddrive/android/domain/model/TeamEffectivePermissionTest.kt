package com.betterclouddrive.android.domain.model

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TeamEffectivePermissionTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `decodes base role permission source`() {
        val permission = decode("BASE_ROLE", "BASE_ROLE", inherited = false)
        assertEquals("BASE_ROLE", permission.sourceType)
        assertEquals("EDITOR", permission.sourceChain.single().subjectRole)
    }

    @Test
    fun `decodes direct user override`() {
        val permission = decode("USER_OVERRIDE", "USER_OVERRIDE", inherited = false)
        assertEquals("USER_OVERRIDE", permission.sourceChain.single().type)
        assertFalse(permission.sourceChain.single().inherited)
    }

    @Test
    fun `decodes direct role override`() {
        val permission = decode("ROLE_OVERRIDE", "ROLE_OVERRIDE", inherited = false)
        assertEquals("ROLE_OVERRIDE", permission.sourceChain.single().type)
    }

    @Test
    fun `decodes inherited parent permission`() {
        val permission = decode("USER_OVERRIDE", "USER_OVERRIDE", inherited = true)
        assertTrue(permission.sourceChain.single().inherited)
        assertEquals("Parent", permission.sourceChain.single().fileName)
    }

    private fun decode(sourceType: String, type: String, inherited: Boolean): TeamEffectivePermission =
        json.decodeFromString(
            """{"canRead":true,"sourceType":"$sourceType","sourceChain":[{"type":"$type","fileId":7,"fileName":"Parent","subjectRole":"EDITOR","inherited":$inherited,"canRead":true}]}""",
        )
}
