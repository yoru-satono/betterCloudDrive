package com.betterclouddrive.android.data.repository

import android.content.Context
import android.content.ContentResolver
import android.net.Uri
import com.betterclouddrive.android.data.remote.ApiService
import com.betterclouddrive.android.data.remote.dto.ApiResponse
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

class TransferStateTest {
    private val api: ApiService = mock(defaultAnswer = { ApiResponse<Any>(code = 500, messageKey = "error.test") })
    private val context: Context = mock()
    private val resolver: ContentResolver = mock()
    private val repository = TransferRepository(api, context)

    init {
        whenever(context.contentResolver).thenReturn(resolver)
    }

    @Test fun downloadQueueCanPauseCancelAndClearTerminalTasks() {
        val target: Uri = mock()
        val task = repository.enqueueDownload(9, "report.pdf", 42, target, teamId = 7)
        assertEquals(TransferDirection.DOWNLOAD, task.direction)
        assertEquals(TransferStatus.PENDING, repository.tasks.value.single().status)

        repository.pause(task.id)
        assertEquals(TransferStatus.PAUSED, repository.tasks.value.single().status)
        repository.cancel(task.id)
        assertEquals(TransferStatus.CANCELED, repository.tasks.value.single().status)
        repository.clearFinished()
        assertTrue(repository.tasks.value.isEmpty())
    }

    @Test fun unknownTasksAreIgnoredAndBoundJobsAreCanceled() = runTest {
        repository.pause("missing")
        repository.cancel("missing")
        repository.runTask("missing")
        val task = repository.enqueueDownload(1, "a.txt", 1, mock())
        val job = Job()
        repository.bindJob(task.id, job)
        repository.cancel(task.id)
        assertTrue(job.isCancelled)
    }

    @Test fun uploadTransportFailureMovesTaskToError() = runTest {
        val source: Uri = mock()
        whenever(source.lastPathSegment).thenReturn("upload.bin")
        whenever(resolver.query(source, null, null, null, null)).thenReturn(null)
        whenever(resolver.openInputStream(source)).thenReturn(null)

        val task = repository.enqueueUpload(parentId = null, uri = source)
        repository.runTask(task.id)

        val failed = repository.tasks.value.single()
        assertEquals(TransferStatus.ERROR, failed.status)
        assertNotNull(failed.error)
    }
}
