package com.betterclouddrive.mobile.platform

import android.content.Context
import android.content.pm.ApplicationInfo
import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.betterclouddrive.mobile.app.AppContainer
import com.betterclouddrive.mobile.core.database.MobileDatabase
import com.betterclouddrive.mobile.core.database.MobileDatabaseMigration1To2
import com.betterclouddrive.mobile.core.localization.PlatformLocaleProvider
import com.betterclouddrive.mobile.core.localization.MobileStrings
import com.betterclouddrive.mobile.core.localization.loadMobileStrings
import com.betterclouddrive.mobile.core.localization.resolveMobileLocale
import com.betterclouddrive.mobile.core.localization.MobileLocale
import com.betterclouddrive.mobile.core.platform.ApplicationLocaleController
import com.betterclouddrive.mobile.core.network.AuthenticatedHttpClient
import com.betterclouddrive.mobile.core.network.AuthApi
import com.betterclouddrive.mobile.core.network.PersonalFilesApi
import com.betterclouddrive.mobile.core.network.PersonalOrganizationApi
import com.betterclouddrive.mobile.core.network.PersonalSharesApi
import com.betterclouddrive.mobile.core.network.PreviewApi
import com.betterclouddrive.mobile.core.network.TransferApi
import com.betterclouddrive.mobile.core.network.PersonalRecoveryApi
import com.betterclouddrive.mobile.core.network.TeamRecoveryApi
import com.betterclouddrive.mobile.core.network.TeamPreviewApi
import com.betterclouddrive.mobile.core.network.TeamWorkspaceApi
import com.betterclouddrive.mobile.core.network.TeamOrganizationApi
import com.betterclouddrive.mobile.core.network.TeamMembersApi
import com.betterclouddrive.mobile.core.network.TeamPermissionsApi
import com.betterclouddrive.mobile.core.network.TeamSharesApi
import com.betterclouddrive.mobile.core.network.TeamInvitesApi
import com.betterclouddrive.mobile.core.network.NotificationsApi
import com.betterclouddrive.mobile.feature.auth.AuthRepository
import com.betterclouddrive.mobile.feature.files.PersonalFilesRepositoryImpl
import com.betterclouddrive.mobile.feature.files.FavoritesRepositoryImpl
import com.betterclouddrive.mobile.feature.files.TagsRepositoryImpl
import com.betterclouddrive.mobile.feature.files.PersonalSharesRepositoryImpl
import com.betterclouddrive.mobile.feature.files.PersonalPreviewRepositoryImpl
import com.betterclouddrive.mobile.feature.files.RecycleBinRepositoryImpl
import com.betterclouddrive.mobile.feature.files.PersonalVersionsRepositoryImpl
import com.betterclouddrive.mobile.feature.recovery.TeamRecycleBinRepositoryImpl
import com.betterclouddrive.mobile.feature.recovery.TeamVersionsRepositoryImpl
import com.betterclouddrive.mobile.feature.transfers.TransferEngine
import com.betterclouddrive.mobile.feature.transfers.TransferManager
import com.betterclouddrive.mobile.feature.transfers.TransferRepository
import com.betterclouddrive.mobile.feature.teams.TeamPreviewRepositoryImpl
import com.betterclouddrive.mobile.feature.teams.TeamWorkspaceRepositoryImpl
import com.betterclouddrive.mobile.feature.teams.TeamOrganizationRepositoryImpl
import com.betterclouddrive.mobile.feature.teams.TeamMembersRepositoryImpl
import com.betterclouddrive.mobile.feature.teams.TeamPermissionsRepositoryImpl
import com.betterclouddrive.mobile.feature.shares.TeamSharesRepositoryImpl
import com.betterclouddrive.mobile.feature.teams.TeamInvitesRepositoryImpl
import com.betterclouddrive.mobile.feature.teams.NotificationsRepositoryImpl
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import java.util.Locale
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import android.content.ClipData
import android.content.ClipboardManager
import com.betterclouddrive.mobile.core.platform.ClipboardService
import com.betterclouddrive.mobile.core.platform.LocalDateTimeService
import java.text.DateFormat
import java.util.Calendar
import java.util.Date
import java.util.TimeZone

object AndroidAppContainerFactory {
    fun create(context: Context): AppContainer {
        val appContext = context.applicationContext
        val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val settings = AndroidServerSettingsStore(createPreferencesStore(appContext, "bcd_settings"))
        val appearance = AndroidThemePreferenceStore(createPreferencesStore(appContext, "bcd_appearance"))
        val sessions = AndroidSecureSessionStore(
            createPreferencesStore(appContext, "bcd_session"),
            appContext.packageName,
        )
        val debuggable = appContext.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
        val http = AuthenticatedHttpClient(
            HttpClient(OkHttp),
            secureSessionStore = sessions,
            serverSettingsStore = settings,
            allowCleartextServer = debuggable,
        )
        val database = Room.databaseBuilder<MobileDatabase>(
            context = appContext,
            name = appContext.getDatabasePath("bcd-mobile.db").absolutePath,
        ).setDriver(BundledSQLiteDriver())
            .addMigrations(MobileDatabaseMigration1To2)
            .setQueryCoroutineContext(Dispatchers.IO)
            .build()
        val fileAccess = AndroidTransferFileAccess(appContext)
        val repository = TransferRepository(
            database.transferTaskDao(),
            purgeDeletedContent = database::purgeDeletedTransferContent,
        )
        val scheduler = AndroidTransferScheduler(appContext)
        val authApi = AuthApi(http)
        val filesApi = PersonalFilesApi(http)
        val organizationApi = PersonalOrganizationApi(http)
        val sharesApi = PersonalSharesApi(http)
        val previewApi = PreviewApi(http)
        val transferApi = TransferApi(http)
        val recoveryApi = PersonalRecoveryApi(http)
        val teamRecoveryApi = TeamRecoveryApi(http)
        val teamWorkspaceApi = TeamWorkspaceApi(http)
        val teamPreviewApi = TeamPreviewApi(http)
        val teamOrganizationApi = TeamOrganizationApi(http)
        val teamMembersApi = TeamMembersApi(http)
        val teamPermissionsApi = TeamPermissionsApi(http)
        val teamSharesApi = TeamSharesApi(http)
        val teamInvitesApi = TeamInvitesApi(http)
        val notificationsApi = NotificationsApi(http)
        val engine = TransferEngine(transferApi, repository, fileAccess)
        val manager = TransferManager(transferApi, repository, scheduler, fileAccess)
        return AppContainer(
            http = http,
            auth = AuthRepository(authApi),
            personalFiles = PersonalFilesRepositoryImpl(filesApi),
            favorites = FavoritesRepositoryImpl(organizationApi),
            tags = TagsRepositoryImpl(organizationApi),
            shares = PersonalSharesRepositoryImpl(sharesApi),
            teamShares = TeamSharesRepositoryImpl(teamSharesApi),
            preview = PersonalPreviewRepositoryImpl(filesApi, previewApi),
            recycleBin = RecycleBinRepositoryImpl(recoveryApi),
            personalVersions = PersonalVersionsRepositoryImpl(filesApi, recoveryApi),
            teamRecycleBin = TeamRecycleBinRepositoryImpl(teamRecoveryApi),
            teamVersions = TeamVersionsRepositoryImpl(teamWorkspaceApi, teamRecoveryApi),
            teamWorkspace = TeamWorkspaceRepositoryImpl(teamWorkspaceApi),
            teamPreview = TeamPreviewRepositoryImpl(teamWorkspaceApi, teamPreviewApi),
            teamOrganization = TeamOrganizationRepositoryImpl(teamOrganizationApi),
            teamMembers = TeamMembersRepositoryImpl(teamMembersApi),
            teamPermissions = TeamPermissionsRepositoryImpl(teamPermissionsApi),
            teamInvites = TeamInvitesRepositoryImpl(teamInvitesApi),
            notifications = NotificationsRepositoryImpl(notificationsApi),
            database = database,
            transfers = manager,
            transferEngine = engine,
            documentPicker = AndroidDocumentPicker(appContext),
            officeRenderer = AndroidOfficeRenderer(),
            mediaPreviewRenderer = AndroidMediaPreviewRenderer(),
            localeProvider = AndroidLocaleProvider(appContext),
            localeController = AndroidApplicationLocaleController(appContext),
            themePreferences = appearance,
            clipboard = AndroidClipboardService(appContext),
            localDateTime = AndroidLocalDateTimeService(),
            applicationScope = applicationScope,
        )
    }
}

internal class AndroidClipboardService(private val context: Context) : ClipboardService {
    override fun copyText(label: String, value: String): Boolean = runCatching {
        val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        manager.setPrimaryClip(ClipData.newPlainText(label, value))
    }.isSuccess
}

internal class AndroidLocalDateTimeService : LocalDateTimeService {
    override fun nowEpochMillis(): Long = System.currentTimeMillis()

    override fun localDateTimeToEpochMillis(selectedUtcDateMillis: Long, hour: Int, minute: Int): Long {
        val utc = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { timeInMillis = selectedUtcDateMillis }
        return Calendar.getInstance().apply {
            clear()
            set(utc.get(Calendar.YEAR), utc.get(Calendar.MONTH), utc.get(Calendar.DAY_OF_MONTH), hour, minute)
        }.timeInMillis
    }

    override fun formatDateTime(epochMillis: Long): String =
        DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(epochMillis))

    override fun hourOfDay(epochMillis: Long): Int = Calendar.getInstance().apply { timeInMillis = epochMillis }
        .get(Calendar.HOUR_OF_DAY)

    override fun minuteOfHour(epochMillis: Long): Int = Calendar.getInstance().apply { timeInMillis = epochMillis }
        .get(Calendar.MINUTE)
}

internal class AndroidApplicationLocaleController(private val context: Context) : ApplicationLocaleController {
    override fun currentLocale(): MobileLocale = resolveMobileLocale(AndroidLocaleProvider(context).preferredLanguageTags())

    override fun applyLocale(locale: MobileLocale) {
        if (currentLocale() == locale) return
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(locale.languageTag))
    }
}

internal class AndroidLocaleProvider(private val context: Context) : PlatformLocaleProvider {
    override fun preferredLanguageTags(): List<String> {
        val locales = context.resources.configuration.locales
        return (0 until locales.size()).map { locales[it].toLanguageTag() }
    }
}

private var cachedAndroidStrings: MobileStrings? = null

internal fun androidMobileStrings(context: Context): MobileStrings {
    val tags = AndroidLocaleProvider(context).preferredLanguageTags()
    val locale = resolveMobileLocale(tags)
    synchronized(AndroidLocaleProvider::class) {
        cachedAndroidStrings?.takeIf { it.locale == locale }?.let { return it }
        val previousLocale = Locale.getDefault()
        return try {
            Locale.setDefault(Locale.forLanguageTag(locale.languageTag))
            runBlocking { loadMobileStrings(listOf(locale.languageTag)) }.also { cachedAndroidStrings = it }
        } finally {
            Locale.setDefault(previousLocale)
        }
    }
}
