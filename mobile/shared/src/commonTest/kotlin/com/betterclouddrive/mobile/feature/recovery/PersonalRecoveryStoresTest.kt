package com.betterclouddrive.mobile.feature.recovery

import com.betterclouddrive.mobile.core.model.FileItem
import com.betterclouddrive.mobile.core.model.PageResult
import com.betterclouddrive.mobile.core.model.PersonalFileVersion
import com.betterclouddrive.mobile.core.model.RecycleBinItem
import com.betterclouddrive.mobile.feature.files.PersonalVersionsRepository
import com.betterclouddrive.mobile.feature.files.RecycleBinRepository
import com.betterclouddrive.mobile.feature.transfers.TransferDirection
import com.betterclouddrive.mobile.feature.transfers.TransferScope
import com.betterclouddrive.mobile.feature.transfers.TransferStatus
import com.betterclouddrive.mobile.feature.transfers.TransferTask
import com.betterclouddrive.mobile.feature.transfers.VersionTransferOperations
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class PersonalRecoveryStoresTest {
    @Test
    fun recycleBinDebouncesPagesDeduplicatesMutatesAndResets() = runTest {
        val repository = FakeRecycle().apply {
            pages += PageResult(listOf(RECYCLE), 2, 1, 50, 2)
            pages += PageResult(listOf(RECYCLE, RECYCLE.copy(id = 2, fileName = "two")), 2, 2, 50, 2)
        }
        var invalidations = 0
        val store = PersonalRecycleBinStore(repository, backgroundScope) { invalidations++ }

        store.updateQuery(" old ")
        advanceTimeBy(299); runCurrent()
        assertTrue(repository.queries.isEmpty())
        advanceTimeBy(1); runCurrent()
        assertEquals("old", repository.queries.single())
        store.loadMore(); runCurrent()
        assertEquals(listOf(1L, 2L), store.state.value.items.map { it.id })

        store.restore(1); runCurrent()
        assertEquals(listOf(1L), repository.restored)
        assertEquals(1, invalidations)
        assertEquals(listOf(2L), store.state.value.items.map { it.id })
        store.reset()
        assertTrue(store.state.value.items.isEmpty())
        assertEquals("", store.state.value.query)
    }

    @Test
    fun versionsKeepIndependentOperationsAndQueueExactVersionDownload() = runTest {
        val repository = FakeVersions()
        val transfers = FakeVersionTransfers()
        var invalidations = 0
        val store = PersonalVersionsStore(repository, transfers, backgroundScope) { invalidations++ }

        store.open(9); runCurrent()
        assertEquals(3, store.state.value.versions.single().versionNumber)
        store.download(VERSION, "content://target"); runCurrent()
        assertEquals(Triple(9L, 3, "content://target"), transfers.last)
        assertNull(store.state.value.downloadingVersion)

        store.restore(3); runCurrent()
        assertEquals(listOf(3), repository.restored)
        assertEquals(1, invalidations)
        store.delete(3); runCurrent()
        assertEquals(listOf(3), repository.deleted)
        assertEquals(2, invalidations)
        store.reset()
        assertNull(store.state.value.fileId)
        assertTrue(store.state.value.versions.isEmpty())
    }

    private class FakeRecycle : RecycleBinRepository {
        val pages = ArrayDeque<PageResult<RecycleBinItem>>()
        val queries = mutableListOf<String?>()
        val restored = mutableListOf<Long>()
        override suspend fun list(query: String?, page: Int, size: Int): PageResult<RecycleBinItem> {
            queries += query
            return pages.removeFirst()
        }
        override suspend fun restore(fileId: Long) { restored += fileId }
        override suspend fun permanentlyDelete(fileId: Long) = Unit
        override suspend fun empty() = Unit
    }

    private class FakeVersions : PersonalVersionsRepository {
        val restored = mutableListOf<Int>()
        val deleted = mutableListOf<Int>()
        override suspend fun file(fileId: Long) = FILE
        override suspend fun list(fileId: Long) = listOf(VERSION)
        override suspend fun restore(fileId: Long, versionNumber: Int) = FILE.also { restored += versionNumber }
        override suspend fun delete(fileId: Long, versionNumber: Int) { deleted += versionNumber }
    }

    private class FakeVersionTransfers : VersionTransferOperations {
        var last: Triple<Long, Int, String>? = null
        override suspend fun enqueueVersionDownload(
            fileId: Long, versionNumber: Int, fileName: String, fileSize: Long, mimeType: String?,
            destinationHandle: String, expectedMd5: String?,
        ): TransferTask {
            last = Triple(fileId, versionNumber, destinationHandle)
            return TransferTask(
                "task", TransferDirection.DOWNLOAD, TransferStatus.QUEUED, "https://drive.example.com",
                TransferScope.Personal(null), remoteFileId = fileId, remoteVersionNumber = versionNumber,
                fileName = fileName, createdAt = 1, updatedAt = 1,
            )
        }
    }

    companion object {
        private val RECYCLE = RecycleBinItem(id = 1, fileName = "old", deletedAt = "2026-08-30T10:00:00")
        private val FILE = FileItem(id = 9, fileName = "doc.txt", mimeType = "text/plain")
        private val VERSION = PersonalFileVersion(id = 30, fileId = 9, versionNumber = 3, fileSize = 8, md5Hash = "abcd")
    }
}
