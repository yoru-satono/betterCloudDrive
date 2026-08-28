package com.betterclouddrive.mobile.app

import com.betterclouddrive.mobile.core.model.FileItem
import com.betterclouddrive.mobile.core.model.ApiException
import com.betterclouddrive.mobile.core.model.OfficeConfig
import com.betterclouddrive.mobile.core.model.PageResult
import com.betterclouddrive.mobile.core.model.TextPreview
import com.betterclouddrive.mobile.core.model.SessionInvalidationReason
import com.betterclouddrive.mobile.core.model.SessionRestoreResult
import com.betterclouddrive.mobile.core.model.UserProfile
import com.betterclouddrive.mobile.core.localization.MobileString
import com.betterclouddrive.mobile.core.localization.MobileLocale
import com.betterclouddrive.mobile.core.localization.UiMessage
import com.betterclouddrive.mobile.core.model.RegistrationResult
import com.betterclouddrive.mobile.core.model.PersonalTag
import com.betterclouddrive.mobile.core.model.CreatePersonalShareRequest
import com.betterclouddrive.mobile.core.model.ShareExpirationFilter
import com.betterclouddrive.mobile.core.model.ShareLink
import com.betterclouddrive.mobile.core.model.UpdatePersonalShareRequest
import com.betterclouddrive.mobile.core.platform.ApplicationLocaleController
import com.betterclouddrive.mobile.core.platform.ThemeMode
import com.betterclouddrive.mobile.core.platform.ThemePreferenceStore
import com.betterclouddrive.mobile.core.platform.PickedDocument
import com.betterclouddrive.mobile.core.platform.UploadPickResult
import com.betterclouddrive.mobile.feature.transfers.UploadEnqueueResult
import com.betterclouddrive.mobile.feature.transfers.TransferTask
import com.betterclouddrive.mobile.feature.transfers.TransferDirection
import com.betterclouddrive.mobile.feature.transfers.TransferScope
import com.betterclouddrive.mobile.feature.transfers.TransferStatus
import com.betterclouddrive.mobile.feature.files.PersonalFileSort
import com.betterclouddrive.mobile.feature.files.SortDirection
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class AppControllerTest {
    @Test
    fun registrationUsesExactLocaleAndCooldownSurvivesControllerState() = runTest {
        val auth = FakeAuth(SessionRestoreResult.NoSession)
        val controller = AppController(auth, FakeFiles(), FakeTransfers(), backgroundScope)
        controller.start()
        runCurrent()
        controller.selectPublicLocale(MobileLocale.SIMPLIFIED_CHINESE_SG)

        controller.sendRegistrationCode("user@example.com")
        runCurrent()
        assertEquals("zh-SG", auth.registrationLocale)
        assertEquals(60, controller.state.value.account.registrationCodeSeconds)

        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(59, controller.state.value.account.registrationCodeSeconds)

        controller.register("alice", "user@example.com", "123456", "Password1", "Password1")
        runCurrent()
        assertEquals(PublicAuthPage.LOGIN, controller.state.value.account.publicPage)
        assertEquals("alice", controller.state.value.account.loginUsername)
        assertEquals("zh-SG", auth.registrationLocale)
    }

    @Test
    fun leavingRegistrationKeepsResendCountdownRunning() = runTest {
        val auth = FakeAuth(SessionRestoreResult.NoSession)
        val controller = AppController(auth, FakeFiles(), FakeTransfers(), backgroundScope)
        controller.start()
        runCurrent()

        controller.sendRegistrationCode("user@example.com")
        runCurrent()
        controller.showLogin()
        advanceTimeBy(1_000)
        runCurrent()

        assertEquals(PublicAuthPage.LOGIN, controller.state.value.account.publicPage)
        assertEquals(59, controller.state.value.account.registrationCodeSeconds)
    }

    @Test
    fun confirmedServerSwitchLogsOutClearsTransfersThenSavesNewServer() = runTest {
        val events = mutableListOf<String>()
        val auth = FakeAuth(SessionRestoreResult.Restored(USER), events)
        val files = FakeFiles().apply { responses += Result.success(page()) }
        val transfers = FakeTransfers(events)
        val controller = AppController(auth, files, transfers, backgroundScope)
        controller.start()
        runCurrent()

        controller.requestServerSwitch("https://other.example.com/")
        assertTrue(controller.state.value.account.serverSwitchConfirming)
        controller.confirmServerSwitch()
        runCurrent()

        assertEquals(
            listOf("logout", "clear-transfers", "switch:https://other.example.com"),
            events,
        )
        assertEquals(AppPhase.LOGGED_OUT, controller.state.value.phase)
        assertEquals("https://other.example.com", controller.state.value.server)
        assertTrue(controller.state.value.transfers.isEmpty())
    }

    @Test
    fun unchangedServerDoesNotLogOutOrClearTransfers() = runTest {
        val events = mutableListOf<String>()
        val auth = FakeAuth(SessionRestoreResult.Restored(USER), events)
        val files = FakeFiles().apply { responses += Result.success(page()) }
        val controller = AppController(auth, files, FakeTransfers(events), backgroundScope)
        controller.start()
        runCurrent()

        controller.requestServerSwitch("https://drive.example.com/")
        controller.confirmServerSwitch()
        runCurrent()

        assertTrue(events.isEmpty())
        assertFalse(controller.state.value.account.serverSwitchConfirming)
        assertEquals(AppPhase.LOGGED_IN, controller.state.value.phase)
    }

    @Test
    fun restoredHistoricalLocaleMapsToMainlandAndThemePersistsLocally() = runTest {
        val auth = FakeAuth(SessionRestoreResult.Restored(USER.copy(preferredLocale = "zh-Hans")))
        val locale = TestLocaleController()
        val themes = TestThemeStore()
        val files = FakeFiles().apply { responses += Result.success(page()) }
        val controller = AppController(auth, files, FakeTransfers(), backgroundScope, locale, themes)

        controller.start()
        runCurrent()
        assertEquals(MobileLocale.SIMPLIFIED_CHINESE_CN, locale.currentLocale())

        controller.setThemeMode(ThemeMode.DARK)
        runCurrent()
        assertEquals(ThemeMode.DARK, controller.state.value.account.themeMode)
    }

    @Test
    fun restoredSessionLoadsPersonalRootWithoutBlockingTransfers() = runTest {
        val auth = FakeAuth(restoreResult = SessionRestoreResult.Restored(USER))
        val files = FakeFiles().apply { responses += Result.success(page(FILE)) }
        val transfers = FakeTransfers()
        val controller = AppController(auth, files, transfers, backgroundScope)

        controller.start()
        runCurrent()

        assertEquals(AppPhase.LOGGED_IN, controller.state.value.phase)
        assertEquals(listOf(FILE), controller.state.value.personalFiles.current.items)
        assertTrue(controller.state.value.personalFiles.current.hasLoaded)

        val task = TransferTask(
            id = "task",
            direction = TransferDirection.DOWNLOAD,
            status = TransferStatus.RUNNING,
            serverBaseUrl = "https://drive.example.com",
            scope = TransferScope.Personal(),
            fileName = "sample.bin",
            createdAt = 1,
            updatedAt = 1,
        )
        transfers.mutableTasks.value = listOf(task)
        runCurrent()

        assertEquals(listOf(task), controller.state.value.transfers)
        assertEquals(listOf(FILE), controller.state.value.personalFiles.current.items)
    }

    @Test
    fun initialFailureCanRetryAndRefreshFailureKeepsExistingContent() = runTest {
        val auth = FakeAuth(restoreResult = SessionRestoreResult.Restored(USER))
        val files = FakeFiles().apply {
            responses += Result.failure(IllegalStateException("offline"))
            responses += Result.success(page(FILE))
            responses += Result.failure(IllegalStateException("offline again"))
        }
        val controller = AppController(auth, files, FakeTransfers(), backgroundScope)

        controller.start()
        runCurrent()
        assertFalse(controller.state.value.personalFiles.current.hasLoaded)
        assertEquals(UiMessage(MobileString.ErrorNetwork), controller.state.value.personalFiles.current.error)

        controller.refreshFiles()
        runCurrent()
        assertEquals(listOf(FILE), controller.state.value.personalFiles.current.items)
        assertNull(controller.state.value.personalFiles.current.error)

        controller.refreshFiles()
        runCurrent()
        assertEquals(listOf(FILE), controller.state.value.personalFiles.current.items)
        assertTrue(controller.state.value.personalFiles.current.hasLoaded)
        assertEquals(UiMessage(MobileString.ErrorNetwork), controller.state.value.personalFiles.current.error)
    }

    @Test
    fun loginLogoutAndSessionInvalidationStayInSessionState() = runTest {
        val auth = FakeAuth(restoreResult = SessionRestoreResult.NoSession)
        val files = FakeFiles().apply { responses += Result.success(page()) }
        val transfers = FakeTransfers()
        val controller = AppController(auth, files, transfers, backgroundScope)

        controller.start()
        runCurrent()
        assertEquals(AppPhase.LOGGED_OUT, controller.state.value.phase)

        controller.login("https://drive.example.com", "user", "password")
        runCurrent()
        assertEquals(AppPhase.LOGGED_IN, controller.state.value.phase)
        assertTrue(controller.state.value.personalFiles.current.hasLoaded)

        auth.invalidations.emit(SessionInvalidationReason.REVOKED)
        runCurrent()
        assertEquals(AppPhase.LOGGED_OUT, controller.state.value.phase)
        assertEquals(UiMessage(MobileString.ErrorSessionExpired), controller.state.value.sessionError)

        controller.logout()
        runCurrent()
        assertTrue(auth.logoutCalls > 0)
        assertTrue(transfers.clearCalls > 0)
    }

    @Test
    fun routeAvailabilityExcludesDiagnosticsFromRelease() {
        assertEquals(PersonalRoute, topLevelRoutes.first())
        assertEquals(4, topLevelRoutes.size)
        assertFalse(DiagnosticsRoute in availableRoutes(debugToolsEnabled = false))
        assertTrue(DiagnosticsRoute in availableRoutes(debugToolsEnabled = true))
        assertIs<Set<AppRoute>>(availableRoutes(debugToolsEnabled = true))
    }

    @Test
    fun folderNavigationRebuildsBreadcrumbAndRestoresCachedParent() = runTest {
        val folder = FileItem(id = 10, fileName = "一级", fileType = "folder")
        val child = FileItem(id = 11, parentId = 10, fileName = "二级", fileType = "folder")
        val files = FakeFiles().apply {
            responses += Result.success(page(folder))
            responses += Result.success(page(child))
        }
        val controller = AppController(
            FakeAuth(SessionRestoreResult.Restored(USER)), files, FakeTransfers(), backgroundScope,
        )
        controller.start()
        runCurrent()

        controller.openPersonalFolder(folder)
        runCurrent()
        assertEquals(10L, controller.state.value.personalFiles.current.folderId)
        assertEquals(listOf("", "一级"), controller.state.value.personalFiles.breadcrumb.map { it.name })

        controller.activatePersonalRoute(null)
        runCurrent()
        assertEquals(listOf(folder), controller.state.value.personalFiles.current.items)
        assertEquals(1, files.requests.count { it.parentId == null })
    }

    @Test
    fun pagingDeduplicatesItemsAndRetriesFooterFailureWithoutLosingContent() = runTest {
        val first = FileItem(id = 1, fileName = "a.txt")
        val second = FileItem(id = 2, fileName = "b.txt")
        val files = FakeFiles().apply {
            responses += Result.success(PageResult(listOf(first), total = 2, page = 1, size = 50, pages = 2))
            responses += Result.failure(IllegalStateException("offline"))
            responses += Result.success(PageResult(listOf(first, second), total = 2, page = 2, size = 50, pages = 2))
        }
        val controller = AppController(
            FakeAuth(SessionRestoreResult.Restored(USER)), files, FakeTransfers(), backgroundScope,
        )
        controller.start()
        runCurrent()
        controller.loadMoreFiles()
        runCurrent()

        assertEquals(listOf(first), controller.state.value.personalFiles.current.items)
        assertEquals(UiMessage(MobileString.ErrorNetwork), controller.state.value.personalFiles.current.loadMoreError)

        controller.loadMoreFiles()
        runCurrent()

        assertEquals(listOf(1L, 2L), controller.state.value.personalFiles.current.items.map { it.id })
        assertFalse(controller.state.value.personalFiles.current.hasMore)
        assertNull(controller.state.value.personalFiles.current.loadMoreError)
        assertEquals(listOf(1, 2, 2), files.requests.map { it.page })
    }

    @Test
    fun restoredFolderRouteRebuildsAncestorsFromMetadata() = runTest {
        val parent = FileItem(id = 10, parentId = null, fileName = "一级", fileType = "folder")
        val child = FileItem(id = 11, parentId = 10, fileName = "二级", fileType = "folder")
        val files = FakeFiles().apply {
            responses += Result.success(page())
            responses += Result.success(page(FILE))
            metadata[parent.id] = parent
            metadata[child.id] = child
        }
        val controller = AppController(
            FakeAuth(SessionRestoreResult.Restored(USER)), files, FakeTransfers(), backgroundScope,
        )
        controller.start()
        runCurrent()

        controller.activatePersonalRoute(child.id)
        runCurrent()

        assertEquals(listOf("", "一级", "二级"), controller.state.value.personalFiles.breadcrumb.map { it.name })
        assertEquals(child.id, controller.state.value.personalFiles.current.folderId)
        assertEquals(listOf(FILE), controller.state.value.personalFiles.current.items)
    }

    @Test
    fun failedSortRestoresPreviousSortAndContent() = runTest {
        val files = FakeFiles().apply {
            responses += Result.success(page(FILE))
            responses += Result.failure(IllegalStateException("offline"))
        }
        val controller = AppController(
            FakeAuth(SessionRestoreResult.Restored(USER)), files, FakeTransfers(), backgroundScope,
        )
        controller.start()
        runCurrent()
        controller.changeFileSort(PersonalFileSort.SIZE, SortDirection.DESCENDING)
        runCurrent()

        assertEquals(PersonalFileSort.NAME, controller.state.value.personalFiles.sort)
        assertEquals(SortDirection.ASCENDING, controller.state.value.personalFiles.direction)
        assertEquals(listOf(FILE), controller.state.value.personalFiles.current.items)
        assertEquals(UiMessage(MobileString.ErrorNetwork), controller.state.value.personalFiles.current.error)
    }

    @Test
    fun selectingFileRefreshesReadOnlyMetadata() = runTest {
        val detailed = FILE.copy(versionCount = 3, md5Hash = "abc123", createdAt = "2026-08-22T10:00:00")
        val files = FakeFiles().apply {
            responses += Result.success(page(FILE))
            metadata[FILE.id] = detailed
        }
        val controller = AppController(
            FakeAuth(SessionRestoreResult.Restored(USER)), files, FakeTransfers(), backgroundScope,
        )
        controller.start()
        runCurrent()

        controller.selectPersonalFile(FILE)
        runCurrent()

        assertEquals(detailed, controller.state.value.personalFiles.selectedFile)
    }

    @Test
    fun selectionSurvivesViewAndPagingButRefreshClearsIt() = runTest {
        val second = FileItem(id = 9, fileName = "second.txt")
        val files = FakeFiles().apply {
            responses += Result.success(PageResult(listOf(FILE), total = 2, page = 1, size = 50, pages = 2))
            responses += Result.success(PageResult(listOf(second), total = 2, page = 2, size = 50, pages = 2))
            responses += Result.success(page(FILE, second))
        }
        val controller = AppController(FakeAuth(SessionRestoreResult.Restored(USER)), files, FakeTransfers(), backgroundScope)
        controller.start()
        runCurrent()

        controller.togglePersonalSelection(FILE.id)
        controller.changeFileViewMode(com.betterclouddrive.mobile.feature.files.FileViewMode.GRID)
        controller.loadMoreFiles()
        runCurrent()
        assertEquals(setOf(FILE.id), controller.state.value.personalFiles.selectedIds)

        controller.refreshFiles()
        runCurrent()
        assertTrue(controller.state.value.personalFiles.selectedIds.isEmpty())
    }

    @Test
    fun createFolderReloadsLoadedPagesAndReportsSuccess() = runTest {
        val folder = FileItem(id = 10, fileName = "Docs", fileType = "folder")
        val files = FakeFiles().apply {
            responses += Result.success(page())
            responses += Result.success(page(folder))
        }
        val controller = AppController(FakeAuth(SessionRestoreResult.Restored(USER)), files, FakeTransfers(), backgroundScope)
        controller.start()
        runCurrent()

        controller.createPersonalFolder(" Docs ")
        runCurrent()

        assertEquals(listOf(" Docs "), files.createdNames)
        assertEquals(listOf(folder), controller.state.value.personalFiles.current.items)
        assertEquals(UiMessage(MobileString.FolderCreated), controller.state.value.personalFiles.feedback?.message)
        assertNull(controller.state.value.personalFiles.mutation)
    }

    @Test
    fun mutationSemanticErrorsRemainExplainableAndKeepSelection() = runTest {
        val files = FakeFiles().apply {
            responses += Result.success(page(FILE))
            mutationFailure = ApiException(409001, "error.fileNameConflict")
        }
        val controller = AppController(FakeAuth(SessionRestoreResult.Restored(USER)), files, FakeTransfers(), backgroundScope)
        controller.start()
        runCurrent()
        controller.togglePersonalSelection(FILE.id)

        controller.renamePersonalFile(FILE, "duplicate")
        runCurrent()

        assertEquals(setOf(FILE.id), controller.state.value.personalFiles.selectedIds)
        assertEquals(UiMessage(MobileString.ErrorNameConflict), controller.state.value.personalFiles.mutationError)
    }

    @Test
    fun destinationPickerLoadsFolderPagesAndHidesSourceFolder() = runTest {
        val source = FileItem(id = 10, fileName = "Source", fileType = "folder")
        val target = FileItem(id = 11, fileName = "Target", fileType = "folder")
        val files = FakeFiles().apply {
            responses += Result.success(page(source, target))
            responses += Result.success(PageResult(listOf(source, target), total = 3, page = 1, size = 100, pages = 2))
            responses += Result.success(PageResult(listOf(FileItem(id = 12, fileName = "a.txt")), total = 3, page = 2, size = 100, pages = 2))
        }
        val controller = AppController(FakeAuth(SessionRestoreResult.Restored(USER)), files, FakeTransfers(), backgroundScope)
        controller.start()
        runCurrent()

        controller.openFolderPicker(source, FileMutationKind.COPY)
        runCurrent()

        val picker = controller.state.value.personalFiles.folderPicker
        assertEquals(listOf(target), picker?.folders)
        assertTrue(picker?.canConfirm == true)
        assertEquals(listOf(1, 1, 2), files.requests.map { it.page })
    }

    @Test
    fun multiUploadFeedbackPreservesTargetFolderAndReportsPartialSelection() = runTest {
        val transfers = FakeTransfers().apply {
            enqueueResult = UploadEnqueueResult(
                tasks = listOf(transferTask("queued", TransferStatus.QUEUED, parentId = 44)),
                rejectedCount = 2,
                overflowCount = 3,
            )
        }
        val files = FakeFiles().apply { responses += Result.success(page()) }
        val controller = AppController(FakeAuth(SessionRestoreResult.Restored(USER)), files, transfers, backgroundScope)
        controller.start()
        runCurrent()

        controller.upload(
            44,
            UploadPickResult(listOf(PickedDocument("uri:one", "one.bin", 8, null)), rejectedCount = 1, overflowCount = 1),
        )
        runCurrent()

        assertEquals(listOf<Long?>(44L), transfers.uploadParents)
        assertEquals(
            UiMessage(MobileString.UploadQueuedSummary, listOf(1, 2, 3)),
            controller.state.value.personalFiles.transferFeedback?.message,
        )
        assertTrue(controller.state.value.personalFiles.transferFeedback?.canOpenTransfers == true)
    }

    @Test
    fun visibleDirectoryRefreshesOnceAfterItsUploadBatchFinishes() = runTest {
        val uploaded = FileItem(id = 99, fileName = "uploaded.bin", fileSize = 10)
        val files = FakeFiles().apply {
            responses += Result.success(page(FILE))
            responses += Result.success(page(FILE, uploaded))
        }
        val transfers = FakeTransfers()
        val controller = AppController(FakeAuth(SessionRestoreResult.Restored(USER)), files, transfers, backgroundScope)
        controller.start()
        runCurrent()

        transfers.mutableTasks.value = listOf(transferTask("batch", TransferStatus.RUNNING))
        runCurrent()
        transfers.mutableTasks.value = listOf(transferTask("batch", TransferStatus.SUCCEEDED))
        runCurrent()

        assertEquals(2, files.requests.size)
        assertEquals(listOf(FILE, uploaded), controller.state.value.personalFiles.current.items)
    }

    @Test
    fun searchDebouncesPagesAndDeduplicatesWithoutLosingResults() = runTest {
        val second = FileItem(id = 9, fileName = "sample-two.txt")
        val files = FakeFiles().apply {
            responses += Result.success(page())
            searchResponses += Result.success(PageResult(listOf(FILE), 2, 1, 50, 2))
            searchResponses += Result.success(PageResult(listOf(FILE, second), 2, 2, 50, 2))
        }
        val controller = AppController(FakeAuth(SessionRestoreResult.Restored(USER)), files, FakeTransfers(), backgroundScope)
        controller.start()
        runCurrent()

        controller.updateSearchQuery(" sample ")
        advanceTimeBy(299)
        runCurrent()
        assertTrue(files.searchRequests.isEmpty())
        advanceTimeBy(1)
        runCurrent()
        assertEquals(listOf(Triple("sample", 1, 50)), files.searchRequests)

        controller.loadMoreSearch()
        runCurrent()
        assertEquals(listOf(8L, 9L), controller.state.value.search.items.map(FileItem::id))
        assertFalse(controller.state.value.search.hasMore)
    }

    @Test
    fun searchCancelsStaleQueryAndKeepsItemsWhenLoadMoreRetryIsNeeded() = runTest {
        val fresh = FileItem(id = 20, fileName = "fresh.txt")
        val next = FileItem(id = 21, fileName = "fresh-next.txt")
        val files = FakeFiles().apply {
            responses += Result.success(page())
            searchHandler = { query, page, _ ->
                when {
                    query == "stale" -> {
                        delay(1_000)
                        PageResult(listOf(FILE), 1, 1, 50, 1)
                    }
                    page == 1 -> PageResult(listOf(fresh), 2, 1, 50, 2)
                    searchPageTwoFailures++ == 0 -> throw IllegalStateException("offline")
                    else -> PageResult(listOf(next), 2, 2, 50, 2)
                }
            }
        }
        val controller = AppController(FakeAuth(SessionRestoreResult.Restored(USER)), files, FakeTransfers(), backgroundScope)
        controller.start()
        runCurrent()

        controller.updateSearchQuery("stale")
        advanceTimeBy(300)
        runCurrent()
        controller.updateSearchQuery("fresh")
        advanceTimeBy(300)
        runCurrent()

        assertEquals(listOf(fresh), controller.state.value.search.items)
        controller.loadMoreSearch()
        runCurrent()
        assertEquals(listOf(fresh), controller.state.value.search.items)
        assertEquals(UiMessage(MobileString.ErrorNetwork), controller.state.value.search.loadMoreError)

        controller.loadMoreSearch()
        runCurrent()
        assertEquals(listOf(fresh, next), controller.state.value.search.items)
        assertNull(controller.state.value.search.loadMoreError)
        assertFalse(controller.state.value.search.hasMore)
    }

    @Test
    fun previewLoadsBoundedTextAndRefreshesMediaTicketOnceAfterRendererFailure() = runTest {
        val image = FileItem(id = 10, fileName = "photo.png", mimeType = "image/png")
        val files = FakeFiles().apply {
            responses += Result.success(page())
            metadata[FILE.id] = FILE
            metadata[image.id] = image
            textPreview = TextPreview("hello", true)
        }
        val controller = AppController(FakeAuth(SessionRestoreResult.Restored(USER)), files, FakeTransfers(), backgroundScope)
        controller.start()
        runCurrent()

        controller.openPreview(FILE.id)
        runCurrent()
        assertEquals("hello", controller.state.value.preview.text)
        assertTrue(controller.state.value.preview.textTruncated)

        controller.closePreview()
        controller.openPreview(image.id)
        runCurrent()
        assertEquals("https://ticket/1", controller.state.value.preview.mediaUrl)
        controller.reportPreviewError(UiMessage(MobileString.ErrorPreviewFailed))
        runCurrent()
        assertEquals("https://ticket/2", controller.state.value.preview.mediaUrl)
        assertEquals(1, controller.state.value.preview.ticketRetryCount)
    }

    @Test
    fun favoritesPageDeduplicatesPagingAndToggleRemovesVisibleItem() = runTest {
        val first = FileItem(id = 21, fileName = "one.txt")
        val second = FileItem(id = 22, fileName = "two.txt")
        val files = FakeFiles().apply {
            responses += Result.success(page())
            favoriteResponses += Result.success(PageResult(listOf(first, second), 3, 1, 50, 2))
            favoriteResponses += Result.success(PageResult(listOf(second), 3, 2, 50, 2))
            favoriteIds += setOf(first.id, second.id)
        }
        val controller = AppController(FakeAuth(SessionRestoreResult.Restored(USER)), files, FakeTransfers(), backgroundScope)
        controller.start()
        runCurrent()

        controller.activateFavorites()
        runCurrent()
        controller.loadMoreFavorites()
        runCurrent()

        assertEquals(listOf(first, second), controller.state.value.favorites.files.items)
        assertEquals(listOf(1, 2), files.favoriteRequests.map { it.second })
        controller.toggleFavorite(first)
        runCurrent()
        assertFalse(first.id in files.favoriteIds)
        assertEquals(listOf(second), controller.state.value.favorites.files.items)
        assertEquals(UiMessage(MobileString.FavoriteRemoved), controller.state.value.favorites.feedback?.message)
    }

    @Test
    fun favoritesSearchDebouncesAndKeepsOnlyLatestQuery() = runTest {
        val files = FakeFiles().apply {
            responses += Result.success(page())
            favoriteResponses += Result.success(PageResult(emptyList(), 0, 1, 50, 1))
            favoriteResponses += Result.success(PageResult(listOf(FILE), 1, 1, 50, 1))
        }
        val controller = AppController(FakeAuth(SessionRestoreResult.Restored(USER)), files, FakeTransfers(), backgroundScope)
        controller.start()
        runCurrent()
        controller.activateFavorites()
        runCurrent()

        controller.updateFavoritesQuery("sam")
        controller.updateFavoritesQuery("sample")
        advanceTimeBy(299)
        runCurrent()
        assertEquals(1, files.favoriteRequests.size)
        advanceTimeBy(1)
        runCurrent()

        assertEquals("sample", files.favoriteRequests.last().first)
        assertEquals(listOf(FILE), controller.state.value.favorites.files.items)
    }

    @Test
    fun tagFilesPagingDeduplicatesAndUsesActiveTag() = runTest {
        val first = FileItem(id = 41, fileName = "one.txt")
        val second = FileItem(id = 42, fileName = "two.txt")
        val tag = PersonalTag(id = 5, tagName = "work", fileCount = 2)
        val files = FakeFiles().apply {
            responses += Result.success(page())
            personalTags += tag
            tagFileResponses += Result.success(PageResult(listOf(first, second), 3, 1, 50, 2))
            tagFileResponses += Result.success(PageResult(listOf(second), 3, 2, 50, 2))
        }
        val controller = AppController(FakeAuth(SessionRestoreResult.Restored(USER)), files, FakeTransfers(), backgroundScope)
        controller.start()
        runCurrent()

        controller.activateTagFiles(tag.id)
        runCurrent()
        controller.loadMoreTagFiles()
        runCurrent()

        assertEquals(listOf(first, second), controller.state.value.tags.tagFiles.items)
        assertEquals(listOf(1, 2), files.tagFileRequests.map { it[2] })
        assertEquals(tag.id, controller.state.value.tags.activeTagId)
    }

    @Test
    fun fileTagEditorLoadsAssignmentsAndAtomicallyReplacesSelection() = runTest {
        val work = PersonalTag(id = 1, tagName = "work", color = "#00d4aa")
        val archive = PersonalTag(id = 2, tagName = "archive", color = "#60a5fa")
        val files = FakeFiles().apply {
            responses += Result.success(page())
            personalTags += listOf(work, archive)
            fileTags[FILE.id] = mutableSetOf(work.id)
        }
        val controller = AppController(FakeAuth(SessionRestoreResult.Restored(USER)), files, FakeTransfers(), backgroundScope)
        controller.start()
        runCurrent()

        controller.openFileTagEditor(FILE)
        runCurrent()
        assertEquals(setOf(work.id), controller.state.value.fileTagEditor?.selectedIds)
        controller.toggleFileTag(archive.id)
        controller.saveFileTags()
        runCurrent()

        assertEquals(setOf(work.id, archive.id), files.fileTags[FILE.id]?.toSet())
        assertNull(controller.state.value.fileTagEditor)
    }

    @Test
    fun tagConflictKeepsMutationStateExplainable() = runTest {
        val files = FakeFiles().apply {
            responses += Result.success(page())
            mutationFailure = ApiException(409002, "error.tagNameConflict")
        }
        val controller = AppController(FakeAuth(SessionRestoreResult.Restored(USER)), files, FakeTransfers(), backgroundScope)
        controller.start()
        runCurrent()

        controller.createPersonalTag("work", "#00d4aa")
        runCurrent()

        assertNull(controller.state.value.tags.mutation)
        assertEquals(UiMessage(MobileString.ErrorTagNameConflict), controller.state.value.tags.mutationError)
        assertTrue(controller.state.value.tags.feedback?.isError == true)
    }

    @Test
    fun personalSharesPageDeduplicatesAndPasswordIsClearedWhenDetailCloses() = runTest {
        val first = ShareLink(id = 1, fileId = 8, shareCode = "one", shareUrl = "https://drive/s/one", hasPassword = true)
        val second = ShareLink(id = 2, fileId = 9, shareCode = "two", shareUrl = "https://drive/s/two")
        val third = ShareLink(id = 3, fileId = 10, shareCode = "three", shareUrl = "https://drive/s/three")
        val files = FakeFiles().apply {
            responses += Result.success(page())
            shareResponses += Result.success(PageResult(listOf(first, second), 3, 1, 50, 2))
            shareResponses += Result.success(PageResult(listOf(second, third), 3, 2, 50, 2))
            shares[first.id] = first
            sharePassword = "secret"
        }
        val controller = AppController(FakeAuth(SessionRestoreResult.Restored(USER)), files, FakeTransfers(), backgroundScope)
        controller.start()
        runCurrent()

        controller.activateShares()
        runCurrent()
        controller.loadMoreShares()
        runCurrent()
        assertEquals(listOf(1L, 2L, 3L), controller.state.value.shares.items.map(ShareLink::id))

        controller.openShareDetail(first.id)
        runCurrent()
        controller.toggleSharePassword()
        runCurrent()
        assertEquals("secret", controller.state.value.shares.revealedPassword)
        controller.closeShareDetail()
        assertNull(controller.state.value.shares.revealedPassword)
        assertNull(controller.state.value.shares.selected)
    }

    @Test
    fun shareUpdatePreservesExplicitClearSemantics() = runTest {
        val share = ShareLink(id = 4, fileId = 8, shareCode = "four", shareUrl = "https://drive/s/four", hasPassword = true)
        val files = FakeFiles().apply {
            responses += Result.success(page())
            shares[share.id] = share
        }
        val controller = AppController(FakeAuth(SessionRestoreResult.Restored(USER)), files, FakeTransfers(), backgroundScope)
        controller.start()
        runCurrent()
        controller.openShareDetail(share.id)
        runCurrent()

        controller.updatePersonalShare(UpdatePersonalShareRequest(
            clearPassword = true, clearExpireAt = true, clearMaxVisits = true,
        ))
        runCurrent()

        assertEquals(UpdatePersonalShareRequest(
            clearPassword = true, clearExpireAt = true, clearMaxVisits = true,
        ), files.lastShareUpdate)
        assertEquals(UiMessage(MobileString.ShareUpdated), controller.state.value.shares.feedback?.message)
    }

    private companion object {
        val USER = UserProfile(id = 7, username = "user", email = "user@example.com")
        val FILE = FileItem(id = 8, fileName = "sample.txt", fileSize = 12)
        fun page(vararg files: FileItem) = PageResult(
            records = files.toList(), total = files.size.toLong(), page = 1, size = 50, pages = 1,
        )
        fun transferTask(id: String, status: TransferStatus, parentId: Long? = null) = TransferTask(
            id = id,
            direction = TransferDirection.UPLOAD,
            status = status,
            serverBaseUrl = "https://drive.example.com",
            scope = TransferScope.Personal(parentId),
            fileName = "$id.bin",
            bytesTotal = 10,
            createdAt = 1,
            updatedAt = 1,
        )
    }
}

private class FakeAuth(
    var restoreResult: SessionRestoreResult,
    private val events: MutableList<String>? = null,
) : AppAuthOperations {
    override var serverBaseUrl: String = "https://drive.example.com"
    val invalidations = MutableSharedFlow<SessionInvalidationReason>(extraBufferCapacity = 1)
    override val sessionInvalidations: Flow<SessionInvalidationReason> = invalidations
    var logoutCalls = 0
    var registrationLocale: String? = null
    var profile = UserProfile(id = 7, username = "user", email = "user@example.com")

    override suspend fun restore(): SessionRestoreResult = restoreResult
    override suspend fun switchServer(value: String): Result<String> {
        events?.add("switch:$value")
        serverBaseUrl = value
        return Result.success(value)
    }
    override fun normalizeServer(value: String): Result<String> = Result.success(value.trimEnd('/'))
    override suspend fun login(username: String, password: String): UserProfile =
        UserProfile(id = 7, username = username, email = "$username@example.com")
    override suspend fun sendRegistrationCode(email: String, preferredLocale: String) {
        registrationLocale = preferredLocale
    }
    override suspend fun register(
        username: String, password: String, email: String, verificationCode: String, preferredLocale: String,
    ): RegistrationResult {
        registrationLocale = preferredLocale
        return RegistrationResult(9, username)
    }
    override suspend fun forgotPassword(email: String) = Unit
    override suspend fun resetPassword(email: String, code: String, newPassword: String) = Unit
    override suspend fun getCurrentUser(): UserProfile = profile
    override suspend fun updatePreferredLocale(locale: String): UserProfile = profile.copy(preferredLocale = locale).also {
        profile = it
    }
    override suspend fun logout() {
        logoutCalls++
        events?.add("logout")
    }
}

private class TestLocaleController : ApplicationLocaleController {
    private var value = MobileLocale.ENGLISH_US
    override fun currentLocale(): MobileLocale = value
    override fun applyLocale(locale: MobileLocale) { value = locale }
}

private class TestThemeStore : ThemePreferenceStore {
    private val state = MutableStateFlow(ThemeMode.SYSTEM)
    override val themeMode: Flow<ThemeMode> = state
    override suspend fun setThemeMode(mode: ThemeMode) { state.value = mode }
}

private class FakeFiles : AppFileOperations {
    data class Request(val parentId: Long?, val page: Int, val sortBy: String, val order: String)
    val responses = ArrayDeque<Result<PageResult<FileItem>>>()
    val requests = mutableListOf<Request>()
    val searchResponses = ArrayDeque<Result<PageResult<FileItem>>>()
    val searchRequests = mutableListOf<Triple<String, Int, Int>>()
    var searchHandler: (suspend (String, Int, Int) -> PageResult<FileItem>)? = null
    var searchPageTwoFailures = 0
    val metadata = mutableMapOf<Long, FileItem>()
    val createdNames = mutableListOf<String>()
    var mutationFailure: Throwable? = null
    var ticketCalls = 0
    var textPreview = TextPreview("", false)
    val favoriteResponses = ArrayDeque<Result<PageResult<FileItem>>>()
    val favoriteRequests = mutableListOf<Triple<String?, Int, Int>>()
    val favoriteIds = mutableSetOf<Long>()
    val personalTags = mutableListOf<PersonalTag>()
    val tagFileResponses = ArrayDeque<Result<PageResult<FileItem>>>()
    val tagFileRequests = mutableListOf<List<Any?>>()
    val fileTags = mutableMapOf<Long, MutableSet<Long>>()
    val shareResponses = ArrayDeque<Result<PageResult<ShareLink>>>()
    val shareRequests = mutableListOf<List<Any?>>()
    val shares = mutableMapOf<Long, ShareLink>()
    var sharePassword: String? = null
    var lastShareUpdate: UpdatePersonalShareRequest? = null
    override suspend fun listPersonalFiles(
        parentId: Long?, page: Int, size: Int, sortBy: String, order: String,
    ): PageResult<FileItem> {
        requests += Request(parentId, page, sortBy, order)
        return responses.removeFirst().getOrThrow()
    }
    override suspend fun getPersonalFile(fileId: Long): FileItem = metadata.getValue(fileId)
    override suspend fun createPersonalFolder(parentId: Long?, folderName: String): FileItem {
        mutationFailure?.let { throw it }
        createdNames += folderName
        return FileItem(id = 1000, parentId = parentId, fileName = folderName, fileType = "folder")
    }
    override suspend fun renamePersonalFile(fileId: Long, newName: String): FileItem {
        mutationFailure?.let { throw it }
        return metadata[fileId]?.copy(fileName = newName) ?: FileItem(id = fileId, fileName = newName)
    }
    override suspend fun movePersonalFile(fileId: Long, targetParentId: Long?) { mutationFailure?.let { throw it } }
    override suspend fun copyPersonalFile(fileId: Long, targetParentId: Long?) { mutationFailure?.let { throw it } }
    override suspend fun deletePersonalFiles(fileIds: List<Long>) { mutationFailure?.let { throw it } }
    override suspend fun listPersonalFavorites(query: String?, page: Int, size: Int): PageResult<FileItem> {
        favoriteRequests += Triple(query, page, size)
        return favoriteResponses.removeFirst().getOrThrow()
    }
    override suspend fun isPersonalFavorite(fileId: Long): Boolean = fileId in favoriteIds
    override suspend fun addPersonalFavorite(fileId: Long) { favoriteIds += fileId }
    override suspend fun removePersonalFavorite(fileId: Long) { favoriteIds -= fileId }
    override suspend fun listPersonalTags(query: String?): List<PersonalTag> = personalTags.filter {
        query.isNullOrBlank() || it.tagName.contains(query, ignoreCase = true)
    }
    override suspend fun createPersonalTag(name: String, color: String): PersonalTag {
        mutationFailure?.let { throw it }
        return PersonalTag(id = (personalTags.maxOfOrNull { it.id } ?: 0) + 1, tagName = name.trim(), color = color)
            .also(personalTags::add)
    }
    override suspend fun updatePersonalTag(tagId: Long, name: String, color: String): PersonalTag {
        mutationFailure?.let { throw it }
        val updated = personalTags.first { it.id == tagId }.copy(tagName = name.trim(), color = color)
        personalTags.replaceAll { if (it.id == tagId) updated else it }
        return updated
    }
    override suspend fun deletePersonalTag(tagId: Long) {
        mutationFailure?.let { throw it }
        personalTags.removeAll { it.id == tagId }
        fileTags.values.forEach { it.remove(tagId) }
    }
    override suspend fun listPersonalFilesByTag(
        tagId: Long, query: String?, page: Int, size: Int,
    ): PageResult<FileItem> {
        tagFileRequests += listOf(tagId, query, page, size)
        return tagFileResponses.removeFirst().getOrThrow()
    }
    override suspend fun listPersonalFileTags(fileId: Long): List<PersonalTag> =
        personalTags.filter { it.id in fileTags[fileId].orEmpty() }
    override suspend fun replacePersonalFileTags(fileId: Long, tagIds: List<Long>): List<PersonalTag> {
        fileTags[fileId] = tagIds.toMutableSet()
        return listPersonalFileTags(fileId)
    }
    override suspend fun removePersonalFileTag(tagId: Long, fileId: Long) { fileTags[fileId]?.remove(tagId) }
    override suspend fun createPersonalShare(request: CreatePersonalShareRequest): ShareLink =
        ShareLink(id = 100, fileId = request.fileId, shareCode = "created", shareUrl = "https://drive/s/created")
            .also { shares[it.id] = it }
    override suspend fun listPersonalShares(
        query: String?, passwordProtected: Boolean?, expiration: ShareExpirationFilter, page: Int, size: Int,
    ): PageResult<ShareLink> {
        shareRequests += listOf(query, passwordProtected, expiration, page, size)
        return shareResponses.removeFirst().getOrThrow()
    }
    override suspend fun getPersonalShare(shareId: Long): ShareLink = shares.getValue(shareId)
    override suspend fun getPersonalSharePassword(shareId: Long): String? = sharePassword
    override suspend fun updatePersonalShare(shareId: Long, request: UpdatePersonalShareRequest): ShareLink {
        lastShareUpdate = request
        return shares.getValue(shareId).copy(
            hasPassword = if (request.clearPassword) false else shares.getValue(shareId).hasPassword,
            expireAtEpochMs = if (request.clearExpireAt) null else request.expireAt,
            maxVisits = if (request.clearMaxVisits) null else request.maxVisits,
        ).also { shares[shareId] = it }
    }
    override suspend fun cancelPersonalShare(shareId: Long) { shares.remove(shareId) }
    override suspend fun searchPersonalFiles(query: String, page: Int, size: Int): PageResult<FileItem> {
        searchRequests += Triple(query, page, size)
        searchHandler?.let { return it(query, page, size) }
        return searchResponses.removeFirst().getOrThrow()
    }
    override suspend fun createPersonalDownloadTicket(fileId: Long): String = "https://ticket/${++ticketCalls}"
    override suspend fun loadPersonalText(file: FileItem): TextPreview = textPreview
    override suspend fun loadPersonalOffice(fileId: Long): OfficeConfig = OfficeConfig()
    override suspend fun loadTeamOffice(teamId: Long, fileId: Long): OfficeConfig = error("unused")
}

private class FakeTransfers(private val events: MutableList<String>? = null) : AppTransferOperations {
    val mutableTasks = MutableStateFlow<List<TransferTask>>(emptyList())
    override val tasks: Flow<List<TransferTask>> = mutableTasks
    var clearCalls = 0
    var enqueueResult = UploadEnqueueResult(emptyList(), 0, 0)
    val uploadParents = mutableListOf<Long?>()

    override suspend fun enqueueUploads(result: UploadPickResult, parentId: Long?): UploadEnqueueResult {
        uploadParents += parentId
        return enqueueResult
    }
    override suspend fun enqueueDownload(
        fileId: Long,
        fileName: String,
        fileSize: Long,
        mimeType: String?,
        destinationHandle: String,
        expectedMd5: String?,
    ) = Unit
    override suspend fun pause(taskId: String) = Unit
    override suspend fun resume(taskId: String) = Unit
    override suspend fun cancel(taskId: String) = Unit
    override suspend fun retry(taskId: String) = Unit
    override suspend fun resumeWaitingForLogin() = Unit
    override suspend fun clearForLogout() {
        clearCalls++
        events?.add("clear-transfers")
        mutableTasks.value = emptyList()
    }
    override suspend fun clearFinished(): Int = 0
}
