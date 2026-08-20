package com.betterclouddrive.android.ui

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import com.betterclouddrive.android.MainDispatcherRule
import com.betterclouddrive.android.data.local.ServerConfigStore
import com.betterclouddrive.android.data.local.ThemePreferenceStore
import com.betterclouddrive.android.data.local.TokenManager
import com.betterclouddrive.android.data.remote.ApiService
import com.betterclouddrive.android.data.repository.TransferRepository
import com.betterclouddrive.android.domain.model.ThemeMode
import com.betterclouddrive.android.util.NetworkResult
import com.betterclouddrive.android.util.MessageResolver
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.TestScope
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.mockito.Answers
import org.mockito.Mockito
import org.mockito.kotlin.mock
import org.mockito.kotlin.any
import org.mockito.kotlin.whenever
import java.lang.reflect.Method
import java.lang.reflect.ParameterizedType
import java.lang.reflect.Type
import java.lang.reflect.WildcardType
import kotlin.coroutines.Continuation

/** Ensures every ViewModel action can handle a failed dependency without crashing. */
class ViewModelSurfaceTest {
    @get:Rule val main = MainDispatcherRule()
    private val androidContext: Context = mock<Context>().also {
        whenever(it.applicationContext).thenReturn(it)
        whenever(it.getString(any())).thenReturn("error")
        whenever(it.getString(any(), any())).thenReturn("error")
    }

    private val classNames = listOf(
        "com.betterclouddrive.android.ui.auth.AuthViewModel",
        "com.betterclouddrive.android.ui.files.FilesViewModel",
        "com.betterclouddrive.android.ui.favorites.FavoritesViewModel",
        "com.betterclouddrive.android.ui.navigation.PreviewRouterViewModel",
        "com.betterclouddrive.android.ui.navigation.TeamPreviewRouterViewModel",
        "com.betterclouddrive.android.ui.office.OfficeEditorViewModel",
        "com.betterclouddrive.android.ui.preview.TextPreviewViewModel",
        "com.betterclouddrive.android.ui.recyclebin.RecycleBinViewModel",
        "com.betterclouddrive.android.ui.search.SearchViewModel",
        "com.betterclouddrive.android.ui.shares.SharesViewModel",
        "com.betterclouddrive.android.ui.tags.TagsViewModel",
        "com.betterclouddrive.android.ui.tags.TagFilesViewModel",
        "com.betterclouddrive.android.ui.teams.NotificationsViewModel",
        "com.betterclouddrive.android.ui.teams.TeamFilesViewModel",
        "com.betterclouddrive.android.ui.teams.TeamInviteViewModel",
        "com.betterclouddrive.android.ui.teams.TeamMembersViewModel",
        "com.betterclouddrive.android.ui.teams.TeamRecycleBinViewModel",
        "com.betterclouddrive.android.ui.teams.TeamSettingsViewModel",
        "com.betterclouddrive.android.ui.teams.TeamTasksViewModel",
        "com.betterclouddrive.android.ui.teams.TeamsViewModel",
        "com.betterclouddrive.android.ui.theme.ThemeViewModel",
        "com.betterclouddrive.android.ui.transfer.TransferQueueViewModel",
        "com.betterclouddrive.android.ui.versions.VersionsViewModel",
    )

    @Test fun everyViewModelActionSurvivesDependencyErrors() = runTest(main.dispatcher) {
        exerciseViewModels(success = false)
    }

    @Test fun everyViewModelActionHandlesSuccessfulDependencies() = runTest(main.dispatcher) {
        exerciseViewModels(success = true)
    }

    private suspend fun TestScope.exerciseViewModels(success: Boolean) {
        MessageResolver.initialize(androidContext)
        var invoked = 0
        classNames.forEach { name ->
            val type = Class.forName(name).asSubclass(ViewModel::class.java)
            val constructor = type.declaredConstructors.minBy { it.parameterCount }
            constructor.isAccessible = true
            val viewModel = constructor.newInstance(*constructor.parameterTypes.map { dependencyFor(it, success) }.toTypedArray())
            type.declaredMethods.filter(::isAction).forEach { method ->
                runCatching {
                    method.isAccessible = true
                    method.invoke(viewModel, *method.parameterTypes.map(::valueFor).toTypedArray())
                    invoked++
                }
                advanceUntilIdle()
            }
        }
        assertTrue("Expected registered ViewModel actions to be exercised, invoked $invoked actions", invoked >= 70)
    }

    private fun isAction(method: Method): Boolean =
        !method.isSynthetic && !method.name.startsWith("get") && !method.name.startsWith("access\$") &&
            !method.name.contains("\$default")

    private fun dependencyFor(type: Class<*>, success: Boolean): Any = when (type) {
        Context::class.java -> androidContext
        ApiService::class.java -> mock<ApiService>()
        TransferRepository::class.java -> TransferRepository(mock(), mock())
        TokenManager::class.java -> mock<TokenManager>()
        ServerConfigStore::class.java -> mock<ServerConfigStore>().also {
            whenever(it.serverBaseUrlFlow).thenReturn(flowOf("http://localhost:8080"))
        }
        ThemePreferenceStore::class.java -> mock<ThemePreferenceStore>().also {
            whenever(it.themeModeFlow).thenReturn(flowOf(ThemeMode.SYSTEM))
        }
        else -> Mockito.mock(type) { invocation ->
            if (invocation.method.parameterTypes.lastOrNull()?.name == Continuation::class.java.name) {
                if (success) NetworkResult.Success(dummyNetworkData(invocation.method))
                else NetworkResult.Error(-1, "error.test")
            } else Answers.RETURNS_DEFAULTS.answer(invocation)
        }
    }

    private fun dummyNetworkData(method: Method): Any? {
        val continuation = method.genericParameterTypes.lastOrNull() as? ParameterizedType ?: return Unit
        val wildcard = continuation.actualTypeArguments.firstOrNull() as? WildcardType
        val network = wildcard?.lowerBounds?.firstOrNull() as? ParameterizedType ?: return Unit
        return dummyFor(network.actualTypeArguments.firstOrNull())
    }

    private fun dummyFor(type: Type?): Any? {
        val resolved = when (type) {
            is WildcardType -> type.upperBounds.firstOrNull()
            else -> type
        }
        if (resolved is ParameterizedType) {
            val raw = resolved.rawType as? Class<*> ?: return null
            if (List::class.java.isAssignableFrom(raw)) return emptyList<Any>()
            return construct(raw)
        }
        val clazz = resolved as? Class<*> ?: return null
        return when {
            clazz == Unit::class.java -> Unit
            clazz == String::class.java -> "value"
            clazz == java.lang.Boolean::class.java || clazz == java.lang.Boolean.TYPE -> false
            clazz == java.lang.Integer::class.java || clazz == java.lang.Integer.TYPE -> 1
            clazz == java.lang.Long::class.java || clazz == java.lang.Long.TYPE -> 1L
            List::class.java.isAssignableFrom(clazz) -> emptyList<Any>()
            clazz.isEnum -> clazz.enumConstants?.first()
            else -> construct(clazz)
        }
    }

    private fun construct(type: Class<*>): Any? = runCatching {
        val constructor = type.declaredConstructors.minBy { it.parameterCount }
        constructor.isAccessible = true
        constructor.newInstance(*constructor.parameterTypes.map(::valueFor).toTypedArray())
    }.getOrElse { runCatching { Mockito.mock(type) }.getOrNull() }

    private fun valueFor(type: Class<*>): Any? = when {
        type == java.lang.Long.TYPE || type == java.lang.Long::class.java -> 1L
        type == Integer.TYPE || type == java.lang.Integer::class.java -> 1
        type == java.lang.Boolean.TYPE || type == java.lang.Boolean::class.java -> false
        type == java.lang.Float.TYPE || type == java.lang.Float::class.java -> 0f
        type == String::class.java -> "value"
        type == Uri::class.java -> mock<Uri>()
        List::class.java.isAssignableFrom(type) -> emptyList<Any>()
        type.isEnum -> type.enumConstants?.first()
        type.name == "kotlin.jvm.functions.Function0" -> object : kotlin.jvm.functions.Function0<Unit> {
            override fun invoke() = Unit
        }
        type.name == "kotlin.jvm.functions.Function1" -> object : kotlin.jvm.functions.Function1<Any?, Unit> {
            override fun invoke(value: Any?) = Unit
        }
        else -> construct(type)
    }
}
