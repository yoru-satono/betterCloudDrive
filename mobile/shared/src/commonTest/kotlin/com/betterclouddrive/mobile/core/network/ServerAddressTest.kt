package com.betterclouddrive.mobile.core.network

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ServerAddressTest {
    @Test
    fun normalizesServerRoot() {
        assertEquals("https://drive.example.com", ServerAddress.normalize("https://drive.example.com/").getOrThrow())
        assertEquals("http://10.0.2.2:8080", ServerAddress.normalize(" http://10.0.2.2:8080/ ").getOrThrow())
    }

    @Test
    fun rejectsUnsupportedSchemes() {
        assertTrue(ServerAddress.normalize("ftp://drive.example.com").isFailure)
    }
}
