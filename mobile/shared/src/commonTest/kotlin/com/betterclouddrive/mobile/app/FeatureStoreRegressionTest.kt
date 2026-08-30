package com.betterclouddrive.mobile.app

import com.betterclouddrive.mobile.core.localization.MobileString
import com.betterclouddrive.mobile.core.localization.UiMessage
import com.betterclouddrive.mobile.core.model.ApiException
import com.betterclouddrive.mobile.core.model.CreatePersonalShareRequest
import com.betterclouddrive.mobile.core.model.FileItem
import com.betterclouddrive.mobile.core.model.OfficeConfig
import com.betterclouddrive.mobile.core.model.PageResult
import com.betterclouddrive.mobile.core.model.PersonalTag
import com.betterclouddrive.mobile.core.model.ShareExpirationFilter
import com.betterclouddrive.mobile.core.model.ShareLink
import com.betterclouddrive.mobile.core.model.TextPreview
import com.betterclouddrive.mobile.core.model.UpdatePersonalShareRequest
import com.betterclouddrive.mobile.core.platform.PickedDocument
import com.betterclouddrive.mobile.core.platform.UploadPickResult
import com.betterclouddrive.mobile.feature.files.FavoritesRepository
import com.betterclouddrive.mobile.feature.files.FileMutationKind
import com.betterclouddrive.mobile.feature.files.FileViewMode
import com.betterclouddrive.mobile.feature.files.PersonalBrowserStore
import com.betterclouddrive.mobile.feature.files.PersonalFileSort
import com.betterclouddrive.mobile.feature.files.PersonalFilesRepository
import com.betterclouddrive.mobile.feature.files.PersonalPreviewStore
import com.betterclouddrive.mobile.feature.files.PersonalSearchStore
import com.betterclouddrive.mobile.feature.files.PersonalSharesRepository
import com.betterclouddrive.mobile.feature.files.PreviewRepository
import com.betterclouddrive.mobile.feature.files.SortDirection
import com.betterclouddrive.mobile.feature.files.TagsRepository
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class FeatureStoreRegressionTest {
    @Test
    fun initialFailureCanRetryAndRefreshFailureKeepsExistingContent() = runTest {
        val files = ScriptedFiles().apply {
            listResponses += Result.failure(IllegalStateException("offline"))
            listResponses += Result.success(page(FILE))
            listResponses += Result.failure(IllegalStateException("offline again"))
        }
        val store = PersonalBrowserStore(files, ScriptedTransfers(), backgroundScope)
        store.activate(null); runCurrent()
        assertFalse(store.state.value.current.hasLoaded)
        assertEquals(UiMessage(MobileString.ErrorNetwork), store.state.value.current.error)

        store.refresh(); runCurrent()
        assertEquals(listOf(FILE), store.state.value.current.items)
        assertNull(store.state.value.current.error)
        store.refresh(); runCurrent()
        assertEquals(listOf(FILE), store.state.value.current.items)
        assertEquals(UiMessage(MobileString.ErrorNetwork), store.state.value.current.error)
    }

    @Test
    fun folderNavigationRestoresCacheAndRebuildsAncestorsFromMetadata() = runTest {
        val parent = FileItem(id = 10, fileName = "parent", fileType = "folder")
        val child = FileItem(id = 11, parentId = 10, fileName = "child", fileType = "folder")
        val files = ScriptedFiles().apply {
            listResponses += Result.success(page(parent))
            listResponses += Result.success(page(FILE))
            metadata[parent.id] = parent
            metadata[child.id] = child
        }
        val store = PersonalBrowserStore(files, ScriptedTransfers(), backgroundScope)
        store.activate(null); runCurrent()
        store.activate(child.id); runCurrent()
        assertEquals(listOf("", "parent", "child"), store.state.value.breadcrumb.map { it.name })
        assertEquals(listOf(FILE), store.state.value.current.items)

        store.activate(null); runCurrent()
        assertEquals(listOf(parent), store.state.value.current.items)
        assertEquals(1, files.listRequests.count { it.parentId == null })
    }

    @Test
    fun pagingFailureRetainsItemsAndRetryDeduplicates() = runTest {
        val second = FileItem(id = 9, fileName = "second.txt")
        val files = ScriptedFiles().apply {
            listResponses += Result.success(PageResult(listOf(FILE), 2, 1, 50, 2))
            listResponses += Result.failure(IllegalStateException("offline"))
            listResponses += Result.success(PageResult(listOf(FILE, second), 2, 2, 50, 2))
        }
        val store = PersonalBrowserStore(files, ScriptedTransfers(), backgroundScope)
        store.activate(null); runCurrent(); store.loadMore(); runCurrent()
        assertEquals(listOf(FILE), store.state.value.current.items)
        assertEquals(UiMessage(MobileString.ErrorNetwork), store.state.value.current.loadMoreError)
        store.loadMore(); runCurrent()
        assertEquals(listOf(8L, 9L), store.state.value.current.items.map(FileItem::id))
        assertFalse(store.state.value.current.hasMore)
        assertEquals(listOf(1, 2, 2), files.listRequests.map { it.page })
    }

    @Test
    fun sortMetadataSelectionAndMutationFailuresRemainFeatureLocal() = runTest {
        val detailed = FILE.copy(versionCount = 3, md5Hash = "abc123")
        val files = ScriptedFiles().apply {
            listResponses += Result.success(page(FILE))
            listResponses += Result.failure(IllegalStateException("offline"))
            metadata[FILE.id] = detailed
        }
        val store = PersonalBrowserStore(files, ScriptedTransfers(), backgroundScope)
        store.activate(null); runCurrent()
        store.changeSort(PersonalFileSort.SIZE, SortDirection.DESCENDING); runCurrent()
        assertEquals(PersonalFileSort.NAME, store.state.value.sort)
        assertEquals(listOf(FILE), store.state.value.current.items)
        store.selectFile(FILE); runCurrent()
        assertEquals(detailed, store.state.value.selectedFile)

        store.toggleSelection(FILE.id)
        store.changeViewMode(FileViewMode.GRID)
        files.mutationFailure = ApiException(409001, "error.fileNameConflict")
        store.rename(FILE, "duplicate"); runCurrent()
        assertEquals(setOf(FILE.id), store.state.value.selectedIds)
        assertEquals(UiMessage(MobileString.ErrorNameConflict), store.state.value.mutationError)
    }

    @Test
    fun folderPickerLoadsAllFolderPagesAndHidesSource() = runTest {
        val source = FileItem(id = 10, fileName = "Source", fileType = "folder")
        val target = FileItem(id = 11, fileName = "Target", fileType = "folder")
        val files = ScriptedFiles().apply {
            listResponses += Result.success(page(source, target))
            listResponses += Result.success(PageResult(listOf(source, target), 3, 1, 100, 2))
            listResponses += Result.success(PageResult(listOf(FileItem(id = 12, fileName = "a.txt")), 3, 2, 100, 2))
        }
        val store = PersonalBrowserStore(files, ScriptedTransfers(), backgroundScope)
        store.activate(null); runCurrent()
        store.openFolderPicker(source, FileMutationKind.COPY); runCurrent()
        assertEquals(listOf(target), store.state.value.folderPicker?.folders)
        assertTrue(store.state.value.folderPicker?.canConfirm == true)
        assertEquals(listOf(1, 1, 2), files.listRequests.map { it.page })
    }

    @Test
    fun uploadFeedbackKeepsTargetAndSettledEventRefreshesOnce() = runTest {
        val uploaded = FileItem(id = 99, fileName = "uploaded.bin")
        val transfers = ScriptedTransfers().apply {
            enqueueResult = UploadEnqueueResult(listOf(task("queued", 44)), 2, 3)
        }
        val files = ScriptedFiles().apply {
            listResponses += Result.success(page(FILE))
            listResponses += Result.success(page(FILE, uploaded))
        }
        val store = PersonalBrowserStore(files, transfers, backgroundScope)
        store.activate(null); runCurrent()
        store.upload(44, UploadPickResult(listOf(PickedDocument("uri:one", "one.bin", 8, null)), 1, 1)); runCurrent()
        assertEquals(listOf<Long?>(44L), transfers.uploadParents)
        assertEquals(UiMessage(MobileString.UploadQueuedSummary, listOf(1, 2, 3)), store.state.value.transferFeedback?.message)
        transfers.settled.emit(null); runCurrent()
        assertEquals(2, files.listRequests.size)
        assertEquals(listOf(FILE, uploaded), store.state.value.current.items)
    }

    @Test
    fun searchDebouncesCancelsStaleAndRetriesFooterWithoutLosingResults() = runTest {
        val fresh = FileItem(id = 20, fileName = "fresh.txt")
        val next = FileItem(id = 21, fileName = "fresh-next.txt")
        var pageTwoFailures = 0
        val files = ScriptedFiles().apply {
            searchHandler = { query, page, _ -> when {
                query == "stale" -> { delay(1_000); page(FILE) }
                page == 1 -> PageResult(listOf(fresh), 2, 1, 50, 2)
                pageTwoFailures++ == 0 -> throw IllegalStateException("offline")
                else -> PageResult(listOf(fresh, next), 2, 2, 50, 2)
            } }
        }
        val store = PersonalSearchStore(files, backgroundScope)
        store.updateQuery("stale"); advanceTimeBy(300); runCurrent()
        store.updateQuery(" fresh "); advanceTimeBy(300); runCurrent()
        assertEquals(listOf(fresh), store.state.value.items)
        store.loadMore(); runCurrent()
        assertEquals(listOf(fresh), store.state.value.items)
        assertEquals(UiMessage(MobileString.ErrorNetwork), store.state.value.loadMoreError)
        store.loadMore(); runCurrent()
        assertEquals(listOf(20L, 21L), store.state.value.items.map(FileItem::id))
    }

    @Test
    fun previewLoadsBoundedTextAndRefreshesMediaTicketOnlyOnce() = runTest {
        val repository = ScriptedPreview()
        val store = PersonalPreviewStore(repository, backgroundScope)
        store.open(FILE.id); runCurrent()
        assertEquals("hello", store.state.value.text)
        assertTrue(store.state.value.textTruncated)

        store.close(); store.open(IMAGE.id); runCurrent()
        assertEquals("https://ticket/1", store.state.value.mediaUrl)
        store.reportRendererError(UiMessage(MobileString.ErrorPreviewFailed)); runCurrent()
        assertEquals("https://ticket/2", store.state.value.mediaUrl)
        assertEquals(1, store.state.value.ticketRetryCount)
        store.reportRendererError(UiMessage(MobileString.ErrorPreviewFailed)); runCurrent()
        assertNull(store.state.value.mediaUrl)
        assertEquals(2, repository.ticketCalls)
    }

    @Test
    fun favoritesPagingSearchAndTogglePreserveOldBehavior() = runTest {
        val first = FileItem(id = 21, fileName = "one.txt")
        val second = FileItem(id = 22, fileName = "two.txt")
        val repository = ScriptedOrganization().apply {
            favoriteResponses += Result.success(PageResult(listOf(first, second), 3, 1, 50, 2))
            favoriteResponses += Result.success(PageResult(listOf(second), 3, 2, 50, 2))
            favoriteIds += setOf(first.id, second.id)
            favoriteResponses += Result.success(page(FILE))
        }
        val store = FavoritesStore(repository, backgroundScope)
        store.activate(); runCurrent(); store.loadMore(); runCurrent()
        assertEquals(listOf(first, second), store.state.value.files.items)
        store.toggle(first); runCurrent()
        assertEquals(listOf(second), store.state.value.files.items)
        assertEquals(UiMessage(MobileString.FavoriteRemoved), store.state.value.feedback?.message)
        store.updateQuery("sam"); store.updateQuery(" sample "); advanceTimeBy(300); runCurrent()
        assertEquals("sample", repository.favoriteRequests.last().first)
        assertEquals(listOf(FILE), store.state.value.files.items)
    }

    @Test
    fun tagsPagingAtomicEditorAndConflictRemainExplainable() = runTest {
        val work = PersonalTag(id = 1, tagName = "work", color = "#00d4aa")
        val archive = PersonalTag(id = 2, tagName = "archive", color = "#60a5fa")
        val repository = ScriptedOrganization().apply {
            tags += listOf(work, archive)
            assigned[FILE.id] = mutableSetOf(work.id)
            tagFileResponses += Result.success(PageResult(listOf(FILE), 2, 1, 50, 2))
            tagFileResponses += Result.success(PageResult(listOf(FILE, IMAGE), 2, 2, 50, 2))
        }
        val store = TagsStore(repository, backgroundScope)
        store.activateFiles(work.id); runCurrent(); store.loadMoreFiles(); runCurrent()
        assertEquals(listOf(8L, 10L), store.state.value.tagFiles.items.map(FileItem::id))
        store.openEditor(FILE); runCurrent()
        assertEquals(setOf(work.id), store.editor.value?.selectedIds)
        store.toggleEditorTag(archive.id); store.saveEditor(); runCurrent()
        assertEquals(setOf(work.id, archive.id), repository.assigned[FILE.id].orEmpty().toSet())

        repository.mutationFailure = ApiException(409002, "error.tagNameConflict")
        store.create("work", "#00d4aa"); runCurrent()
        assertEquals(UiMessage(MobileString.ErrorTagNameConflict), store.state.value.mutationError)
        assertTrue(store.state.value.feedback?.isError == true)
    }

    @Test
    fun sharesPagingPasswordAndExplicitClearSemanticsRemainIntact() = runTest {
        val first = SHARE.copy(id = 1, hasPassword = true)
        val second = SHARE.copy(id = 2, shareCode = "two")
        val third = SHARE.copy(id = 3, shareCode = "three")
        val repository = ScriptedShares().apply {
            responses += Result.success(PageResult(listOf(first, second), 3, 1, 50, 2))
            responses += Result.success(PageResult(listOf(second, third), 3, 2, 50, 2))
            shares[first.id] = first
        }
        val store = PersonalSharesStore(repository, backgroundScope)
        store.activate(); runCurrent(); store.loadMore(); runCurrent()
        assertEquals(listOf(1L, 2L, 3L), store.state.value.items.map(ShareLink::id))
        store.openDetail(first.id); runCurrent(); store.togglePassword(); runCurrent()
        assertEquals("secret", store.state.value.revealedPassword)
        val update = UpdatePersonalShareRequest(clearPassword = true, clearExpireAt = true, clearMaxVisits = true)
        store.update(update); runCurrent()
        assertEquals(update, repository.lastUpdate)
        assertNull(store.state.value.revealedPassword)
        assertEquals(UiMessage(MobileString.ShareUpdated), store.state.value.feedback?.message)
    }

    private data class ListRequest(val parentId: Long?, val page: Int, val sortBy: String, val order: String)

    private class ScriptedFiles : PersonalFilesRepository {
        val listResponses = ArrayDeque<Result<PageResult<FileItem>>>()
        val listRequests = mutableListOf<ListRequest>()
        val metadata = mutableMapOf<Long, FileItem>()
        var mutationFailure: Throwable? = null
        var searchHandler: suspend (String, Int, Int) -> PageResult<FileItem> = { _, _, _ -> page() }
        override suspend fun listPersonalFiles(parentId: Long?, page: Int, size: Int, sortBy: String, order: String): PageResult<FileItem> {
            listRequests += ListRequest(parentId, page, sortBy, order)
            return listResponses.removeFirst().getOrThrow()
        }
        override suspend fun getPersonalFile(fileId: Long) = metadata.getValue(fileId)
        override suspend fun searchPersonalFiles(query: String, page: Int, size: Int) = searchHandler(query, page, size)
        override suspend fun createPersonalFolder(parentId: Long?, folderName: String) = FileItem(1000, parentId, folderName, "folder")
        override suspend fun renamePersonalFile(fileId: Long, newName: String): FileItem {
            mutationFailure?.let { throw it }; return metadata[fileId]?.copy(fileName = newName) ?: FILE.copy(fileName = newName)
        }
        override suspend fun movePersonalFile(fileId: Long, targetParentId: Long?) { mutationFailure?.let { throw it } }
        override suspend fun copyPersonalFile(fileId: Long, targetParentId: Long?) { mutationFailure?.let { throw it } }
        override suspend fun deletePersonalFiles(fileIds: List<Long>) { mutationFailure?.let { throw it } }
    }

    private class ScriptedTransfers : PersonalTransferOperations {
        val settled = MutableSharedFlow<Long?>(extraBufferCapacity = 1)
        override val uploadFoldersSettled = settled
        var enqueueResult = UploadEnqueueResult(emptyList(), 0, 0)
        val uploadParents = mutableListOf<Long?>()
        override suspend fun enqueueUploads(result: UploadPickResult, parentId: Long?): UploadEnqueueResult {
            uploadParents += parentId; return enqueueResult
        }
        override suspend fun enqueueDownload(fileId: Long, fileName: String, fileSize: Long, mimeType: String?, destinationHandle: String, expectedMd5: String?) = task("download", null)
    }

    private class ScriptedPreview : PreviewRepository {
        var ticketCalls = 0
        override suspend fun getPersonalFile(fileId: Long) = if (fileId == IMAGE.id) IMAGE else FILE
        override suspend fun createPersonalDownloadTicket(fileId: Long) = "https://ticket/${++ticketCalls}"
        override suspend fun loadPersonalText(file: FileItem) = TextPreview("hello", true)
        override suspend fun loadPersonalOffice(fileId: Long) = OfficeConfig()
        override suspend fun loadTeamOffice(teamId: Long, fileId: Long) = OfficeConfig()
    }

    private class ScriptedOrganization : FavoritesRepository, TagsRepository {
        val favoriteResponses = ArrayDeque<Result<PageResult<FileItem>>>()
        val favoriteRequests = mutableListOf<Triple<String?, Int, Int>>()
        val favoriteIds = mutableSetOf<Long>()
        val tags = mutableListOf<PersonalTag>()
        val assigned = mutableMapOf<Long, MutableSet<Long>>()
        val tagFileResponses = ArrayDeque<Result<PageResult<FileItem>>>()
        var mutationFailure: Throwable? = null
        override suspend fun listPersonalFavorites(query: String?, page: Int, size: Int): PageResult<FileItem> {
            favoriteRequests += Triple(query, page, size); return favoriteResponses.removeFirst().getOrThrow()
        }
        override suspend fun isPersonalFavorite(fileId: Long) = fileId in favoriteIds
        override suspend fun addPersonalFavorite(fileId: Long) { favoriteIds += fileId }
        override suspend fun removePersonalFavorite(fileId: Long) { favoriteIds -= fileId }
        override suspend fun listPersonalTags(query: String?) = tags.filter { query.isNullOrBlank() || it.tagName.contains(query, true) }
        override suspend fun createPersonalTag(name: String, color: String): PersonalTag {
            mutationFailure?.let { throw it }
            return PersonalTag(id = 99, tagName = name, color = color).also(tags::add)
        }
        override suspend fun updatePersonalTag(tagId: Long, name: String, color: String) = tags.first { it.id == tagId }.copy(tagName = name, color = color)
        override suspend fun deletePersonalTag(tagId: Long) { tags.removeAll { it.id == tagId } }
        override suspend fun listPersonalFilesByTag(tagId: Long, query: String?, page: Int, size: Int) = tagFileResponses.removeFirst().getOrThrow()
        override suspend fun listPersonalFileTags(fileId: Long) = tags.filter { it.id in assigned[fileId].orEmpty() }
        override suspend fun replacePersonalFileTags(fileId: Long, tagIds: List<Long>): List<PersonalTag> {
            assigned[fileId] = tagIds.toMutableSet(); return listPersonalFileTags(fileId)
        }
        override suspend fun removePersonalFileTag(tagId: Long, fileId: Long) { assigned[fileId]?.remove(tagId) }
    }

    private class ScriptedShares : PersonalSharesRepository {
        val responses = ArrayDeque<Result<PageResult<ShareLink>>>()
        val shares = mutableMapOf<Long, ShareLink>()
        var lastUpdate: UpdatePersonalShareRequest? = null
        override suspend fun createPersonalShare(request: CreatePersonalShareRequest) = SHARE
        override suspend fun listPersonalShares(query: String?, passwordProtected: Boolean?, expiration: ShareExpirationFilter, page: Int, size: Int) = responses.removeFirst().getOrThrow()
        override suspend fun getPersonalShare(shareId: Long) = shares.getValue(shareId)
        override suspend fun getPersonalSharePassword(shareId: Long) = "secret"
        override suspend fun updatePersonalShare(shareId: Long, request: UpdatePersonalShareRequest): ShareLink {
            lastUpdate = request
            return shares.getValue(shareId).copy(hasPassword = !request.clearPassword).also { shares[shareId] = it }
        }
        override suspend fun cancelPersonalShare(shareId: Long) { shares.remove(shareId) }
    }

    private companion object {
        val FILE = FileItem(id = 8, fileName = "sample.txt", mimeType = "text/plain", fileSize = 12)
        val IMAGE = FileItem(id = 10, fileName = "photo.png", mimeType = "image/png")
        val SHARE = ShareLink(id = 4, fileId = 8, shareCode = "code", shareUrl = "https://drive/s/code")
        fun page(vararg items: FileItem) = PageResult(items.toList(), items.size.toLong(), 1, 50, 1)
        fun task(id: String, parentId: Long?) = TransferTask(
            id = id,
            direction = TransferDirection.UPLOAD,
            status = TransferStatus.QUEUED,
            serverBaseUrl = "https://drive.example.com",
            scope = TransferScope.Personal(parentId),
            fileName = "$id.bin",
            createdAt = 1,
            updatedAt = 1,
        )
    }
}
