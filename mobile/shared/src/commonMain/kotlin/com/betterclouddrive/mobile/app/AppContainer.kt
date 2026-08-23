package com.betterclouddrive.mobile.app

import com.betterclouddrive.mobile.core.database.MobileDatabase
import com.betterclouddrive.mobile.core.localization.PlatformLocaleProvider
import com.betterclouddrive.mobile.core.network.BackendClient
import com.betterclouddrive.mobile.core.platform.DocumentPicker
import com.betterclouddrive.mobile.core.platform.OfficeRenderer
import com.betterclouddrive.mobile.feature.auth.AuthRepository
import com.betterclouddrive.mobile.feature.files.FileRepository
import com.betterclouddrive.mobile.feature.transfers.TransferEngine
import com.betterclouddrive.mobile.feature.transfers.TransferManager
import com.betterclouddrive.mobile.core.model.SessionInvalidationReason
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

class AppContainer(
    val backend: BackendClient,
    val auth: AuthRepository,
    val files: FileRepository,
    val database: MobileDatabase,
    val transfers: TransferManager,
    val transferEngine: TransferEngine,
    val documentPicker: DocumentPicker,
    val officeRenderer: OfficeRenderer,
    val localeProvider: PlatformLocaleProvider,
    private val applicationScope: CoroutineScope,
) {
    val controller: AppController by lazy { AppController(auth, files, transfers, applicationScope) }

    init {
        applicationScope.launch {
            backend.sessionInvalidations.collect { reason ->
                if (reason in setOf(
                        SessionInvalidationReason.EXPIRED,
                        SessionInvalidationReason.REVOKED,
                        SessionInvalidationReason.CORRUPT_STORAGE,
                    )
                ) {
                    transfers.waitForLogin()
                }
            }
        }
    }

    fun reconcileTransfers() {
        applicationScope.launch { transfers.reconcile() }
    }
}
