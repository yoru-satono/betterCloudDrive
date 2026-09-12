package com.betterclouddrive.mobile.core.network

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ServerAddressTest {
    @Test
    fun normalizesServerRoot() {
        assertEquals("https://drive.example.com", ServerAddress.normalize("https://drive.example.com/").getOrThrow())
        assertEquals("https://drive.example.com", ServerAddress.normalize("https://drive.example.com/path/").getOrThrow())
        assertEquals("http://10.0.2.2:8080", ServerAddress.normalize(" http://10.0.2.2:8080/ ").getOrThrow())
        assertEquals(
            "http://10.0.2.2:8080/api/v1/files?page=2",
            ServerAddress.apiUrl("http://10.0.2.2:8080/old/path", "files?page=2"),
        )
    }

    @Test
    fun rejectsUnsupportedSchemes() {
        assertTrue(ServerAddress.normalize("").isFailure)
        assertTrue(ServerAddress.normalize("not a url").isFailure)
        assertTrue(ServerAddress.normalize("ftp://drive.example.com").isFailure)
        assertTrue(ServerAddress.normalize("http://drive.example.com", allowHttp = false).isFailure)
    }
}
