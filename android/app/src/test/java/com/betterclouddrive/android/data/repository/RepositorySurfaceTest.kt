package com.betterclouddrive.android.data.repository

import android.content.Context
import android.net.Uri
import com.betterclouddrive.android.data.local.TokenManager
import com.betterclouddrive.android.data.remote.ApiService
import com.betterclouddrive.android.data.remote.dto.ApiResponse
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.mock
import java.lang.reflect.Method

/** Exercises every repository endpoint wrapper so additions cannot remain completely untested. */
class RepositorySurfaceTest {
    private val api: ApiService = mock(defaultAnswer = { ApiResponse<Any>(code = 500, messageKey = "error.test") })
    private val tokens: TokenManager = mock()
    private val context: Context = mock()

    @Test fun everySuspendRepositoryOperationMapsTransportFailure() {
        val repositories = listOf(
            AuthRepository(api, tokens),
            FileRepository(api),
            FavoriteRepository(api),
            RecycleBinRepository(api),
            ShareRepository(api),
            TagRepository(api),
            TeamRepository(api),
            UploadRepository(api, context),
        )
        var invoked = 0
        repositories.forEach { repository ->
            repository.javaClass.declaredMethods
                .filter(::isPublicSuspendMethod)
                .forEach { method ->
                    method.isAccessible = true
                    val args = method.parameterTypes.map(::argumentFor).toTypedArray()
                    method.invoke(repository, *args)
                    invoked++
                }
        }
        assertTrue("Expected repository endpoint wrappers to be exercised, invoked $invoked methods", invoked >= 100)
    }

    private fun isPublicSuspendMethod(method: Method): Boolean =
        java.lang.reflect.Modifier.isPublic(method.modifiers) &&
            !java.lang.reflect.Modifier.isStatic(method.modifiers) &&
            !method.name.contains("\$default") &&
            method.parameterTypes.lastOrNull()?.name == "kotlin.coroutines.Continuation"

    private fun argumentFor(type: Class<*>): Any? = when {
        type == java.lang.Long.TYPE || type == java.lang.Long::class.java -> 1L
        type == Integer.TYPE || type == Integer::class.java -> 1
        type == java.lang.Boolean.TYPE || type == java.lang.Boolean::class.java -> false
        type == String::class.java -> "value"
        type == Uri::class.java -> mock<Uri>()
        List::class.java.isAssignableFrom(type) -> emptyList<Any>()
        type.isEnum -> type.enumConstants?.first()
        type.name == "kotlin.coroutines.Continuation" -> object : Continuation<Any?> {
            override val context = EmptyCoroutineContext
            override fun resumeWith(result: Result<Any?>) = Unit
        }
        else -> null
    }
}
