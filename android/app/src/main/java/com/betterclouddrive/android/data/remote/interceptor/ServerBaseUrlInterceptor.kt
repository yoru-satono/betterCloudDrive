package com.betterclouddrive.android.data.remote.interceptor

import com.betterclouddrive.android.data.local.ServerConfigStore
import com.betterclouddrive.android.util.ServerUrlUtil
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.Response
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ServerBaseUrlInterceptor @Inject constructor(
    private val serverConfigStore: ServerConfigStore,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val apiBaseUrl = runBlocking { ServerUrlUtil.apiBaseUrl(serverConfigStore.getServerBaseUrl()) }
        val newUrl = request.url.newBuilder()
            .scheme(apiBaseUrl.scheme)
            .host(apiBaseUrl.host)
            .port(apiBaseUrl.port)
            .build()
        return chain.proceed(request.newBuilder().url(newUrl).build())
    }
}
