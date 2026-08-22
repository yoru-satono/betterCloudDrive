package com.betterclouddrive.mobile.feature.transfers

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TransferModelsTest {
    @Test
    fun chunkCountSupportsZeroAndMultipleChunks() {
        assertEquals(0, chunkCount(0))
        assertEquals(1, chunkCount(1))
        assertEquals(1, chunkCount(DEFAULT_CHUNK_SIZE))
        assertEquals(2, chunkCount(DEFAULT_CHUNK_SIZE + 1))
        assertFailsWith<IllegalArgumentException> { chunkCount(-1) }
    }


    @Test
    fun rateLimitAndServerErrorsRetryButPermanentErrorsDoNot() {
        assertTrue(isRetryableTransferError(429))
        assertTrue(isRetryableTransferError(503))
        assertTrue(isRetryableTransferError(500001))
        assertFalse(isRetryableTransferError(419001))
        assertFalse(isRetryableTransferError(404))
    }
}
