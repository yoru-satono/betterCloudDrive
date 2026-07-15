package com.betterclouddrive.android.data.repository

import com.betterclouddrive.android.data.remote.dto.ApiResponse
import com.betterclouddrive.android.util.NetworkResult
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class ApiCallHelperTest {
    @Test fun mapsPayloadAndPayloadlessSuccess() = runTest {
        assertEquals("ok", (apiCall { ApiResponse(200, data = "ok") } as NetworkResult.Success).data)
        assertEquals(Unit, (apiCall<Unit> { ApiResponse(200, data = null) } as NetworkResult.Success).data)
    }

    @Test fun preservesSemanticErrorAndMapsExceptions() = runTest {
        val semantic = apiCall<String> { ApiResponse(403001, messageKey = "error.denied", requestId = "r1") }
        assertEquals(403001, (semantic as NetworkResult.Error).code)
        assertEquals("r1", semantic.requestId)
        val network = apiCall<String> { error("offline") }
        assertEquals(-1, (network as NetworkResult.Error).code)
        assertEquals("error.network", network.messageKey)
    }
}
