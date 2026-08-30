package com.betterclouddrive.mobile.app

import com.betterclouddrive.mobile.core.model.*
import com.betterclouddrive.mobile.feature.files.*
import com.betterclouddrive.mobile.feature.auth.AuthDataSource
import com.betterclouddrive.mobile.feature.auth.AuthStore
import com.betterclouddrive.mobile.feature.auth.AppPhase
import com.betterclouddrive.mobile.feature.auth.PublicAuthPage
import com.betterclouddrive.mobile.feature.diagnostics.DiagnosticsStore
import com.betterclouddrive.mobile.feature.organization.FavoritesStore
import com.betterclouddrive.mobile.feature.organization.TagsStore
import com.betterclouddrive.mobile.feature.shares.PersonalSharesStore
import com.betterclouddrive.mobile.feature.transfers.PersonalTransferOperations
import com.betterclouddrive.mobile.feature.transfers.TransferDirection
import com.betterclouddrive.mobile.feature.transfers.TransferScope
import com.betterclouddrive.mobile.feature.transfers.TransferStatus
import com.betterclouddrive.mobile.feature.transfers.TransferTask
import com.betterclouddrive.mobile.feature.transfers.UploadEnqueueResult
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import com.betterclouddrive.mobile.core.localization.MobileLocale
import com.betterclouddrive.mobile.core.localization.MobileString
import com.betterclouddrive.mobile.core.localization.UiMessage
import com.betterclouddrive.mobile.core.platform.ApplicationLocaleController
import com.betterclouddrive.mobile.core.platform.ThemeMode
import com.betterclouddrive.mobile.core.platform.ThemePreferenceStore
import com.betterclouddrive.mobile.core.platform.UploadPickResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class StoreBoundaryTest {
    @Test
    fun authRegistrationKeepsExactLocaleAndCountdownAcrossPageChange() = runTest {
        val source = FakeAuthSource().apply { restoreResult = SessionRestoreResult.NoSession }
        val locale = FakeLocaleController()
        val store = AuthStore(source, backgroundScope, locale, FakeThemes())
        store.restore()
        store.selectPublicLocale(MobileLocale.SIMPLIFIED_CHINESE_SG)
        store.sendRegistrationCode("user@example.com")
        runCurrent()
        assertEquals("zh-SG", source.registrationLocale)
        assertEquals(60, store.state.value.account.registrationCodeSeconds)

        store.showLogin()
        advanceTimeBy(1_000); runCurrent()
        assertEquals(PublicAuthPage.LOGIN, store.state.value.account.publicPage)
        assertEquals(59, store.state.value.account.registrationCodeSeconds)

        store.register("alice", "user@example.com", "123456", "Password1", "Password1")
        runCurrent()
        assertEquals("alice", store.state.value.account.loginUsername)
        assertEquals(0, store.state.value.account.registrationCodeSeconds)
        assertEquals("zh-SG", source.registrationLocale)
    }

    @Test
    fun authRestoreMapsHistoricalLocaleAndResetClearsUser() = runTest {
        val source = FakeAuthSource().apply {
            restoreResult = SessionRestoreResult.Restored(UserProfile(username = "alice", preferredLocale = "zh-Hans"))
        }
        val locale = FakeLocaleController()
        val store = AuthStore(source, backgroundScope, locale, FakeThemes())
        assertTrue(store.restore())
        assertEquals(AppPhase.LOGGED_IN, store.state.value.phase)
        assertEquals(MobileLocale.SIMPLIFIED_CHINESE_CN, locale.currentLocale())
        store.reset()
        assertNull(store.state.value.user)
    }

    @Test
    fun coordinatorResetsEveryFeatureExactlyOnceForSessionInvalidationAndLogout() = runTest {
        val source = FakeAuthSource()
        val auth = AuthStore(source, backgroundScope, FakeLocaleController(), FakeThemes())
        val browser = FakeBrowserPort()
        val transfers = FakeTransferPort()
        val first = CountingStore()
        val second = CountingStore()
        val coordinator = AppCoordinator(auth, source, browser, transfers, listOf(first, second), backgroundScope)

        coordinator.start(); runCurrent()
        assertEquals(1, browser.roots)
        source.invalidations.emit(SessionInvalidationReason.EXPIRED); runCurrent()
        assertEquals(1, first.resets)
        assertEquals(1, second.resets)
        assertEquals(1, transfers.waits)

        coordinator.logout(); runCurrent()
        assertEquals(2, first.resets)
        assertEquals(2, second.resets)
        assertEquals(1, transfers.clears)
    }

    @Test
    fun serverSwitchPreservesLogoutCleanupSaveOrderAndUnchangedServerIsNoOp() = runTest {
        val events = mutableListOf<String>()
        val source = FakeAuthSource(events = events)
        val auth = AuthStore(source, backgroundScope, FakeLocaleController(), FakeThemes())
        val transfers = FakeTransferPort(events)
        val coordinator = AppCoordinator(auth, source, FakeBrowserPort(), transfers, emptyList(), backgroundScope)
        auth.restore()

        auth.requestServerSwitch("https://drive.example.com/")
        coordinator.confirmServerSwitch(); runCurrent()
        assertTrue(events.isEmpty())
        assertEquals(AppPhase.LOGGED_IN, auth.state.value.phase)

        auth.requestServerSwitch("https://other.example.com/")
        assertTrue(auth.state.value.account.serverSwitchConfirming)
        coordinator.confirmServerSwitch(); runCurrent()
        assertEquals(listOf("logout", "clear-transfers", "switch:https://other.example.com"), events)
        assertEquals(AppPhase.LOGGED_OUT, auth.state.value.phase)
        assertEquals("https://other.example.com", auth.state.value.server)
    }

    @Test
    fun searchDebouncesAndResetCancelsSessionState() = runTest {
        val repository = FakePersonalFiles()
        val store = PersonalSearchStore(repository, backgroundScope)
        store.updateQuery(" first ")
        store.updateQuery(" latest ")
        advanceTimeBy(301); runCurrent()
        assertEquals(listOf("latest"), repository.searches)
        store.updateScrollAnchor(7)
        store.reset()
        assertEquals("", store.state.value.query)
        assertNull(store.state.value.scrollAnchorId)
    }

    @Test
    fun favoritesOwnTheirPagingAndResetState() = runTest {
        val repository = FakeOrganization()
        val store = FavoritesStore(repository, backgroundScope)
        store.activate(); runCurrent()
        assertEquals(listOf(ITEM), store.state.value.files.items)
        store.loadStatus(ITEM.id); runCurrent()
        assertEquals(true, store.state.value.favoriteStatus[ITEM.id])
        store.reset()
        assertTrue(store.state.value.files.items.isEmpty())
        assertTrue(store.state.value.favoriteStatus.isEmpty())
    }

    @Test
    fun tagsClearEditorAndFilesOnReset() = runTest {
        val repository = FakeOrganization()
        val store = TagsStore(repository, backgroundScope)
        store.activate(); runCurrent()
        store.openEditor(ITEM); runCurrent()
        assertEquals(ITEM.id, store.editor.value?.file?.id)
        store.activateFiles(TAG.id); runCurrent()
        assertEquals(TAG.id, store.state.value.activeTagId)
        store.reset()
        assertNull(store.editor.value)
        assertNull(store.state.value.activeTagId)
    }

    @Test
    fun sharePasswordNeverSurvivesCloseOrReset() = runTest {
        val repository = FakeShares()
        val store = PersonalSharesStore(repository, backgroundScope)
        store.openDetail(SHARE.id); runCurrent()
        store.togglePassword(); runCurrent()
        assertEquals("secret", store.state.value.revealedPassword)
        store.closeDetail()
        assertNull(store.state.value.revealedPassword)
        store.openDetail(SHARE.id); runCurrent(); store.togglePassword(); runCurrent(); store.reset()
        assertNull(store.state.value.revealedPassword)
        assertNull(store.state.value.selected)
    }

    @Test
    fun previewTicketAndPlaybackPositionAreFeatureLocal() = runTest {
        val store = PersonalPreviewStore(FakePreview(), backgroundScope)
        store.open(ITEM.id); runCurrent()
        assertEquals("https://example.invalid/ticket", store.state.value.mediaUrl)
        store.updatePosition(4_200)
        assertEquals(4_200, store.state.value.playbackPositionMillis)
        store.reset()
        assertNull(store.state.value.mediaUrl)
        assertEquals(0, store.state.value.playbackPositionMillis)
    }

    @Test
    fun browserOwnsPagingSelectionAndResetWithoutConcreteTransferStore() = runTest {
        val repository = FakePersonalFiles().apply {
            pages += PageResult(listOf(ITEM, ITEM.copy(id = 8)), 3, 1, 2, 2)
            pages += PageResult(listOf(ITEM.copy(id = 8), ITEM.copy(id = 9)), 3, 2, 2, 2)
        }
        val store = PersonalBrowserStore(repository, FakePersonalTransfers(), backgroundScope)
        store.activate(null); runCurrent()
        store.toggleSelection(ITEM.id)
        store.loadMore(); runCurrent()
        assertEquals(listOf(7L, 8L, 9L), store.state.value.current.items.map { it.id })
        assertEquals(setOf(ITEM.id), store.state.value.selectedIds)
        store.reset()
        assertTrue(store.state.value.current.items.isEmpty())
        assertTrue(store.state.value.selectedIds.isEmpty())
    }

    @Test
    fun diagnosticsResetClearsOfficeConfigurationAndStatus() = runTest {
        val store = DiagnosticsStore(FakePreview(), backgroundScope)
        store.updateStatus(UiMessage(MobileString.OfficeConfigLoaded))
        store.reset()
        assertNull(store.state.value.office)
        assertEquals(UiMessage(MobileString.OfficeReady), store.state.value.status)
    }

    private class FakePersonalFiles : PersonalFilesRepository {
        val searches = mutableListOf<String>()
        val pages = ArrayDeque<PageResult<FileItem>>()
        override suspend fun searchPersonalFiles(query: String, page: Int, size: Int): PageResult<FileItem> {
            searches += query; return page(ITEM)
        }
        override suspend fun listPersonalFiles(parentId: Long?, page: Int, size: Int, sortBy: String, order: String) =
            pages.removeFirstOrNull() ?: page(ITEM)
        override suspend fun getPersonalFile(fileId: Long) = ITEM
        override suspend fun createPersonalFolder(parentId: Long?, folderName: String) = ITEM
        override suspend fun renamePersonalFile(fileId: Long, newName: String) = ITEM.copy(fileName = newName)
        override suspend fun movePersonalFile(fileId: Long, targetParentId: Long?) = Unit
        override suspend fun copyPersonalFile(fileId: Long, targetParentId: Long?) = Unit
        override suspend fun deletePersonalFiles(fileIds: List<Long>) = Unit
    }

    private class FakeOrganization : FavoritesRepository, TagsRepository {
        override suspend fun listPersonalFavorites(query: String?, page: Int, size: Int) = page(ITEM)
        override suspend fun isPersonalFavorite(fileId: Long) = true
        override suspend fun addPersonalFavorite(fileId: Long) = Unit
        override suspend fun removePersonalFavorite(fileId: Long) = Unit
        override suspend fun listPersonalTags(query: String?) = listOf(TAG)
        override suspend fun createPersonalTag(name: String, color: String) = TAG.copy(tagName = name, color = color)
        override suspend fun updatePersonalTag(tagId: Long, name: String, color: String) = TAG.copy(tagName = name, color = color)
        override suspend fun deletePersonalTag(tagId: Long) = Unit
        override suspend fun listPersonalFilesByTag(tagId: Long, query: String?, page: Int, size: Int) = page(ITEM)
        override suspend fun listPersonalFileTags(fileId: Long) = listOf(TAG)
        override suspend fun replacePersonalFileTags(fileId: Long, tagIds: List<Long>) = listOf(TAG)
        override suspend fun removePersonalFileTag(tagId: Long, fileId: Long) = Unit
    }

    private class FakeShares : PersonalSharesRepository {
        override suspend fun createPersonalShare(request: CreatePersonalShareRequest) = SHARE
        override suspend fun listPersonalShares(query: String?, passwordProtected: Boolean?, expiration: ShareExpirationFilter, page: Int, size: Int) = page(SHARE)
        override suspend fun getPersonalShare(shareId: Long) = SHARE
        override suspend fun getPersonalSharePassword(shareId: Long) = "secret"
        override suspend fun updatePersonalShare(shareId: Long, request: UpdatePersonalShareRequest) = SHARE
        override suspend fun cancelPersonalShare(shareId: Long) = Unit
    }

    private class FakePreview : PreviewRepository {
        override suspend fun getPersonalFile(fileId: Long) = ITEM.copy(fileName = "clip.mp4", mimeType = "video/mp4")
        override suspend fun createPersonalDownloadTicket(fileId: Long) = "https://example.invalid/ticket"
        override suspend fun loadPersonalText(file: FileItem) = TextPreview("", false)
        override suspend fun loadPersonalOffice(fileId: Long) = error("unused")
        override suspend fun loadTeamOffice(teamId: Long, fileId: Long) = error("unused")
    }

    private class FakeAuthSource(private val events: MutableList<String>? = null) : AuthDataSource {
        val invalidations = MutableSharedFlow<SessionInvalidationReason>(extraBufferCapacity = 2)
        override val sessionInvalidations: Flow<SessionInvalidationReason> = invalidations
        override var serverBaseUrl = "https://drive.example.com"
        var restoreResult: SessionRestoreResult = SessionRestoreResult.Restored(UserProfile(username = "alice"))
        var registrationLocale: String? = null
        override suspend fun restore() = restoreResult
        override suspend fun switchServer(value: String) = Result.success(value.trimEnd('/')).also {
            serverBaseUrl = it.getOrThrow()
            events?.add("switch:$serverBaseUrl")
        }
        override fun normalizeServer(value: String) = Result.success(value.trimEnd('/'))
        override suspend fun login(username: String, password: String) = UserProfile(username = username)
        override suspend fun sendRegistrationCode(email: String, preferredLocale: String) { registrationLocale = preferredLocale }
        override suspend fun register(username: String, password: String, email: String, verificationCode: String, preferredLocale: String) =
            RegistrationResult(1, username).also { registrationLocale = preferredLocale }
        override suspend fun forgotPassword(email: String) = Unit
        override suspend fun resetPassword(email: String, code: String, newPassword: String) = Unit
        override suspend fun getCurrentUser() = UserProfile(username = "alice")
        override suspend fun updatePreferredLocale(locale: String) = UserProfile(username = "alice", preferredLocale = locale)
        override suspend fun logout() {
            events?.add("logout")
            invalidations.emit(SessionInvalidationReason.LOGOUT)
        }
    }

    private class FakeLocaleController : ApplicationLocaleController {
        private var value = MobileLocale.ENGLISH_US
        override fun currentLocale() = value
        override fun applyLocale(locale: MobileLocale) { value = locale }
    }
    private class FakeThemes : ThemePreferenceStore {
        private val value = MutableStateFlow(ThemeMode.SYSTEM)
        override val themeMode: Flow<ThemeMode> = value
        override suspend fun setThemeMode(mode: ThemeMode) { value.value = mode }
    }
    private class FakeBrowserPort : BrowserSessionPort {
        var roots = 0
        override fun loadRoot() { roots++ }
    }
    private class FakeTransferPort(private val events: MutableList<String>? = null) : TransferSessionPort {
        var resumes = 0; var clears = 0; var waits = 0
        override suspend fun resumeWaitingForLogin() { resumes++ }
        override suspend fun clearForLogout() { clears++; events?.add("clear-transfers") }
        override suspend fun waitForLogin() { waits++ }
    }
    private class CountingStore : ResettableStore {
        var resets = 0
        override fun reset() { resets++ }
    }
    private class FakePersonalTransfers : PersonalTransferOperations {
        override val uploadFoldersSettled = MutableSharedFlow<Long?>()
        override suspend fun enqueueUploads(result: UploadPickResult, parentId: Long?) =
            UploadEnqueueResult(emptyList(), 0, 0)
        override suspend fun enqueueDownload(
            fileId: Long,
            fileName: String,
            fileSize: Long,
            mimeType: String?,
            destinationHandle: String,
            expectedMd5: String?,
        ) = TransferTask(
            id = "download",
            direction = TransferDirection.DOWNLOAD,
            status = TransferStatus.QUEUED,
            serverBaseUrl = "https://drive.example.com",
            scope = TransferScope.Personal(),
            fileName = fileName,
            createdAt = 1,
            updatedAt = 1,
        )
    }

    companion object {
        private val ITEM = FileItem(id = 7, fileName = "photo.png", fileType = "file", mimeType = "image/png", fileSize = 10)
        private val TAG = PersonalTag(id = 3, tagName = "Important", color = "#1890ff", fileCount = 1)
        private val SHARE = ShareLink(
            id = 4, fileId = 7, shareCode = "code", shareUrl = "https://example.invalid/s/code", hasPassword = true,
        )
        private fun <T> page(item: T) = PageResult(listOf(item), 1, 50, 1, 1)
    }
}
