package com.betterclouddrive.android.data.repository

import com.betterclouddrive.android.util.Constants
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TransferRepositoryTest {
    @Test
    fun calculateUploadTotalChunksAllowsZeroByteFiles() {
        assertEquals(0, calculateUploadTotalChunks(0))
        assertEquals(1, calculateUploadTotalChunks(1))
        assertEquals(1, calculateUploadTotalChunks(Constants.CHUNK_SIZE))
        assertEquals(2, calculateUploadTotalChunks(Constants.CHUNK_SIZE + 1))
    }

    @Test
    fun zeroByteUploadDoesNotScheduleChunkNumbers() {
        assertTrue(uploadChunkNumbers(calculateUploadTotalChunks(0)).toList().isEmpty())
        assertEquals(listOf(0, 1), uploadChunkNumbers(2).toList())
    }
}
