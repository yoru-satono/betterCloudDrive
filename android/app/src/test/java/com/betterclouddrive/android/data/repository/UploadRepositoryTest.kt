package com.betterclouddrive.android.data.repository

import android.content.Context
import com.betterclouddrive.android.data.remote.ApiService
import com.betterclouddrive.android.data.remote.dto.ApiResponse
import com.betterclouddrive.android.data.remote.dto.UploadChunkResult
import com.betterclouddrive.android.util.NetworkResult
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

class UploadRepositoryTest {
    private val api: ApiService = mock()
    private val repository = UploadRepository(api, mock<Context>())

    @Test fun computesStableMd5() {
        assertEquals("d41d8cd98f00b204e9800998ecf8427e", repository.computeMd5(byteArrayOf()))
        assertEquals("900150983cd24fb0d6963f7d28e17f72", repository.computeMd5("abc".toByteArray()))
    }

    @Test fun uploadChunkMapsSuccessAndSemanticFailure() = runTest {
        whenever(api.uploadChunk(eq("session"), any(), eq(2)))
            .thenReturn(ApiResponse(200, data = UploadChunkResult(2)))
            .thenReturn(ApiResponse(409001, messageKey = "error.conflict"))

        val success = repository.uploadChunk("session", 2, byteArrayOf(1, 2, 3))
        assertEquals(2, (success as NetworkResult.Success).data.chunkNumber)
        val failure = repository.uploadChunk("session", 2, byteArrayOf(1))
        assertEquals(409001, (failure as NetworkResult.Error).code)
    }

    @Test fun uploadChunkMapsThrownTransportFailure() = runTest {
        whenever(api.uploadChunk(eq("session"), any(), eq(0))).thenThrow(IllegalStateException("offline"))
        val result = repository.uploadChunk("session", 0, byteArrayOf())
        assertEquals(-1, (result as NetworkResult.Error).code)
        assertEquals("error.uploadFailed", result.messageKey)
    }
}
