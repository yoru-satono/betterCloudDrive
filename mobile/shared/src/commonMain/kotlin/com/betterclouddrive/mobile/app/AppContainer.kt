package com.betterclouddrive.mobile.app

import com.betterclouddrive.mobile.core.database.MobileDatabase
import com.betterclouddrive.mobile.core.localization.PlatformLocaleProvider
import com.betterclouddrive.mobile.core.platform.ApplicationLocaleController
import com.betterclouddrive.mobile.core.platform.ThemePreferenceStore
import com.betterclouddrive.mobile.core.platform.ClipboardService
import com.betterclouddrive.mobile.core.platform.LocalDateTimeService
import com.betterclouddrive.mobile.core.network.AuthenticatedHttpClient
import com.betterclouddrive.mobile.core.platform.DocumentPicker
import com.betterclouddrive.mobile.core.platform.OfficeRenderer
import com.betterclouddrive.mobile.core.platform.MediaPreviewRenderer
import com.betterclouddrive.mobile.feature.auth.AuthRepository
import com.betterclouddrive.mobile.feature.files.PersonalFilesRepository
import com.betterclouddrive.mobile.feature.files.FavoritesRepository
import com.betterclouddrive.mobile.feature.files.TagsRepository
import com.betterclouddrive.mobile.feature.files.PersonalSharesRepository
import com.betterclouddrive.mobile.feature.files.PreviewRepository
import com.betterclouddrive.mobile.feature.files.RecycleBinRepository
import com.betterclouddrive.mobile.feature.files.PersonalVersionsRepository
import com.betterclouddrive.mobile.feature.transfers.TransferEngine
import com.betterclouddrive.mobile.feature.transfers.TransferManager
import com.betterclouddrive.mobile.feature.transfers.TransferQueueStore
import com.betterclouddrive.mobile.feature.auth.AuthStore
import com.betterclouddrive.mobile.feature.files.PersonalBrowserStore
import com.betterclouddrive.mobile.feature.files.PersonalSearchStore
import com.betterclouddrive.mobile.feature.files.PersonalPreviewStore
import com.betterclouddrive.mobile.feature.organization.FavoritesStore
import com.betterclouddrive.mobile.feature.organization.TagsStore
import com.betterclouddrive.mobile.feature.shares.PersonalSharesStore
import com.betterclouddrive.mobile.feature.diagnostics.DiagnosticsStore
import com.betterclouddrive.mobile.feature.recovery.PersonalRecycleBinStore
import com.betterclouddrive.mobile.feature.recovery.PersonalVersionsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

class AppContainer(
    val http: AuthenticatedHttpClient,
    val auth: AuthRepository,
    val personalFiles: PersonalFilesRepository,
    val favorites: FavoritesRepository,
    val tags: TagsRepository,
    val shares: PersonalSharesRepository,
    val preview: PreviewRepository,
    val recycleBin: RecycleBinRepository,
    val personalVersions: PersonalVersionsRepository,
    val database: MobileDatabase,
    val transfers: TransferManager,
    val transferEngine: TransferEngine,
    val documentPicker: DocumentPicker,
    val officeRenderer: OfficeRenderer,
    val mediaPreviewRenderer: MediaPreviewRenderer,
    val localeProvider: PlatformLocaleProvider,
    val localeController: ApplicationLocaleController,
    val themePreferences: ThemePreferenceStore,
    val clipboard: ClipboardService,
    val localDateTime: LocalDateTimeService,
    private val applicationScope: CoroutineScope,
) {
    val transferQueue = TransferQueueStore(transfers, applicationScope)
    val favoritesStore = FavoritesStore(favorites, applicationScope)
    val tagsStore = TagsStore(tags, applicationScope)
    val browserStore = PersonalBrowserStore(personalFiles, transferQueue, applicationScope) {
        favoritesStore.invalidate()
        tagsStore.invalidateFiles()
    }
    val searchStore = PersonalSearchStore(personalFiles, applicationScope)
    val previewStore = PersonalPreviewStore(preview, applicationScope)
    val sharesStore = PersonalSharesStore(shares, applicationScope)
    val diagnosticsStore = DiagnosticsStore(preview, applicationScope)
    private fun invalidatePersonalContent() {
        favoritesStore.invalidate()
        tagsStore.invalidateFiles()
        searchStore.reset()
        previewStore.reset()
        sharesStore.reset()
        browserStore.refresh()
    }
    val recycleBinStore = PersonalRecycleBinStore(recycleBin, applicationScope, ::invalidatePersonalContent)
    val versionsStore = PersonalVersionsStore(personalVersions, transferQueue, applicationScope, ::invalidatePersonalContent)
    val authStore = AuthStore(auth, applicationScope, localeController, themePreferences)
    val coordinator = AppCoordinator(
        authStore,
        auth,
        browserStore,
        transferQueue,
        listOf(
            browserStore, searchStore, favoritesStore, tagsStore, sharesStore, previewStore,
            recycleBinStore, versionsStore, diagnosticsStore, transferQueue,
        ),
        applicationScope,
    )

    fun reconcileTransfers() {
        applicationScope.launch { transfers.reconcile() }
    }
}
