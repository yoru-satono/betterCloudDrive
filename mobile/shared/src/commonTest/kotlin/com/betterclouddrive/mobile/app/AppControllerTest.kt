package com.betterclouddrive.mobile.app

import com.betterclouddrive.mobile.core.model.FileItem
import com.betterclouddrive.mobile.core.model.ApiException
import com.betterclouddrive.mobile.core.model.OfficeConfig
import com.betterclouddrive.mobile.core.model.PageResult
import com.betterclouddrive.mobile.core.model.SessionInvalidationReason
import com.betterclouddrive.mobile.core.model.SessionRestoreResult
import com.betterclouddrive.mobile.core.model.UserProfile
import com.betterclouddrive.mobile.core.localization.MobileString
import com.betterclouddrive.mobile.core.localization.UiMessage
import com.betterclouddrive.mobile.core.platform.PickedDocument
import com.betterclouddrive.mobile.feature.transfers.TransferTask
import com.betterclouddrive.mobile.feature.transfers.TransferDirection
import com.betterclouddrive.mobile.feature.transfers.TransferScope
import com.betterclouddrive.mobile.feature.transfers.TransferStatus
import com.betterclouddrive.mobile.feature.files.PersonalFileSort
import com.betterclouddrive.mobile.feature.files.SortDirection
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
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

    private companion object {
        val USER = UserProfile(id = 7, username = "user", email = "user@example.com")
        val FILE = FileItem(id = 8, fileName = "sample.txt", fileSize = 12)
        fun page(vararg files: FileItem) = PageResult(
            records = files.toList(), total = files.size.toLong(), page = 1, size = 50, pages = 1,
        )
    }
}

private class FakeAuth(
    var restoreResult: SessionRestoreResult,
) : AppAuthOperations {
    override var serverBaseUrl: String = "https://drive.example.com"
    val invalidations = MutableSharedFlow<SessionInvalidationReason>(extraBufferCapacity = 1)
    override val sessionInvalidations: Flow<SessionInvalidationReason> = invalidations
    var logoutCalls = 0

    override suspend fun restore(): SessionRestoreResult = restoreResult
    override suspend fun switchServer(value: String): Result<String> {
        serverBaseUrl = value
        return Result.success(value)
    }
    override fun normalizeServer(value: String): Result<String> = Result.success(value.trimEnd('/'))
    override suspend fun login(username: String, password: String): UserProfile =
        UserProfile(id = 7, username = username, email = "$username@example.com")
    override suspend fun logout() { logoutCalls++ }
}

private class FakeFiles : AppFileOperations {
    data class Request(val parentId: Long?, val page: Int, val sortBy: String, val order: String)
    val responses = ArrayDeque<Result<PageResult<FileItem>>>()
    val requests = mutableListOf<Request>()
    val metadata = mutableMapOf<Long, FileItem>()
    val createdNames = mutableListOf<String>()
    var mutationFailure: Throwable? = null
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
    override suspend fun loadTeamOffice(teamId: Long, fileId: Long): OfficeConfig = error("unused")
}

private class FakeTransfers : AppTransferOperations {
    val mutableTasks = MutableStateFlow<List<TransferTask>>(emptyList())
    override val tasks: Flow<List<TransferTask>> = mutableTasks
    var clearCalls = 0

    override suspend fun enqueueUpload(document: PickedDocument) = Unit
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
        mutableTasks.value = emptyList()
    }
}
