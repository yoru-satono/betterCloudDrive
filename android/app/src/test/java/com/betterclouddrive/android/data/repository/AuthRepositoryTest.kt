package com.betterclouddrive.android.data.repository

import com.betterclouddrive.android.data.local.TokenManager
import com.betterclouddrive.android.data.remote.ApiService
import com.betterclouddrive.android.util.NetworkResult
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class AuthRepositoryTest {
    private val api: ApiService = mock()
    private val tokens: TokenManager = mock()
    private val repository = AuthRepository(api, tokens)

    @Test
    fun refreshFailsLocallyWhenNoRefreshTokenExists() = runTest {
        whenever(tokens.getRefreshToken()).thenReturn(null)

        val result = repository.refresh()

        assertTrue(result is NetworkResult.Error)
        assertEquals(401, (result as NetworkResult.Error).code)
    }

    @Test
    fun tokenPersistenceDelegatesToTokenManager() = runTest {
        repository.saveTokens("access", "refresh")
        repository.clearTokens()

        verify(tokens).saveTokens("access", "refresh")
        verify(tokens).clearAll()
    }
}
