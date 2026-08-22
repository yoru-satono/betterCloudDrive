package com.betterclouddrive.mobile.platform

import android.content.Context
import android.content.pm.ApplicationInfo
import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.betterclouddrive.mobile.app.AppContainer
import com.betterclouddrive.mobile.core.database.MobileDatabase
import com.betterclouddrive.mobile.core.network.BackendClient
import com.betterclouddrive.mobile.feature.auth.AuthRepository
import com.betterclouddrive.mobile.feature.files.FileRepository
import com.betterclouddrive.mobile.feature.transfers.TransferEngine
import com.betterclouddrive.mobile.feature.transfers.TransferManager
import com.betterclouddrive.mobile.feature.transfers.TransferRepository
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

object AndroidAppContainerFactory {
    fun create(context: Context): AppContainer {
        val appContext = context.applicationContext
        val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val settings = AndroidServerSettingsStore(createPreferencesStore(appContext, "bcd_settings"))
        val sessions = AndroidSecureSessionStore(
            createPreferencesStore(appContext, "bcd_session"),
            appContext.packageName,
        )
        val debuggable = appContext.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
        val backend = BackendClient(
            HttpClient(OkHttp),
            secureSessionStore = sessions,
            serverSettingsStore = settings,
            allowCleartextServer = debuggable,
        )
        val database = Room.databaseBuilder<MobileDatabase>(
            context = appContext,
            name = appContext.getDatabasePath("bcd-mobile.db").absolutePath,
        ).setDriver(BundledSQLiteDriver())
            .setQueryCoroutineContext(Dispatchers.IO)
            .build()
        val fileAccess = AndroidTransferFileAccess(appContext)
        val repository = TransferRepository(database.transferTaskDao())
        val scheduler = AndroidTransferScheduler(appContext)
        val engine = TransferEngine(backend, repository, fileAccess)
        val manager = TransferManager(backend, repository, scheduler, fileAccess)
        return AppContainer(
            backend = backend,
            auth = AuthRepository(backend),
            files = FileRepository(backend),
            database = database,
            transfers = manager,
            transferEngine = engine,
            documentPicker = AndroidDocumentPicker(appContext),
            officeRenderer = AndroidOfficeRenderer(),
            applicationScope = applicationScope,
        )
    }
}
