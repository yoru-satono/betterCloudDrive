package com.betterclouddrive.mobile.feature.transfers

import com.betterclouddrive.mobile.core.database.TransferTaskDao
import com.betterclouddrive.mobile.core.database.TransferTaskEntity
import com.betterclouddrive.mobile.core.network.NetworkTestFacade
import com.betterclouddrive.mobile.core.platform.FileDigest
import com.betterclouddrive.mobile.core.platform.PickedDocument
import com.betterclouddrive.mobile.core.platform.TransferFileAccess
import com.betterclouddrive.mobile.core.platform.TransferScheduleSpec
import com.betterclouddrive.mobile.core.platform.TransferScheduler
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respondOk
import io.ktor.client.request.forms.InputProvider
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.io.Buffer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TransferManagerTest {
    @Test
    fun uploadBatchKeepsFirstHundredValidDocumentsAndUsesOneSchedule() = runTest {
        val fixture = fixture()
        val documents = (0 until 103).map { index ->
            PickedDocument("uri:$index", "file-$index.bin", 10L + index, "application/octet-stream")
        }
        fixture.files.unreadable += "uri:2"

        val result = fixture.manager.enqueueUploads(
            documents = documents,
            scope = TransferScope.Personal(parentId = 77),
            rejectedCount = 1,
            overflowCount = 2,
        )

        assertEquals(99, result.tasks.size)
        assertEquals(2, result.rejectedCount)
        assertEquals(5, result.overflowCount)
        assertEquals((0 until 100).filterNot { it == 2 }.map { "file-$it.bin" }, result.tasks.map { it.fileName })
        assertTrue(result.tasks.all { it.scope == TransferScope.Personal(77) })
        assertEquals(1, fixture.scheduler.specs.size)
        assertEquals(result.tasks.map { it.id }, fixture.scheduler.specs.single().taskIds)
        assertEquals(result.tasks.sumOf { it.bytesTotal }, fixture.scheduler.specs.single().uploadBytes)
        assertEquals(0, fixture.scheduler.specs.single().downloadBytes)
    }

    @Test
    fun schedulingFailureLeavesExplainableTerminalTasks() = runTest {
        val fixture = fixture(scheduleSucceeds = false)

        fixture.manager.enqueueUploads(listOf(PickedDocument("uri:one", "one.bin", 12, null)))

        val task = fixture.repository.all().single()
        assertEquals(TransferStatus.FAILED, task.status)
        assertEquals("error.backgroundScheduling", task.errorKey)
    }

    @Test
    fun clearingFinishedKeepsResumableTasksAndReleasesTerminalHandles() = runTest {
        val fixture = fixture()
        val completed = fixture.repository.enqueueUploads(
            fixture.backend.serverBaseUrl,
            listOf(PickedDocument("uri:done", "done.bin", 1, null)),
            TransferScope.Personal(),
        ).single()
        val paused = fixture.repository.enqueueDownload(
            fixture.backend.serverBaseUrl, 9, "paused.bin", 2, "uri:target",
            TransferScope.Personal(), null,
        )
        fixture.repository.update(completed.id) { it.copy(status = TransferStatus.SUCCEEDED) }
        fixture.repository.update(paused.id) { it.copy(status = TransferStatus.PAUSED) }

        assertEquals(1, fixture.manager.clearFinished())

        assertEquals(listOf(paused.id), fixture.repository.all().map { it.id })
        assertEquals(listOf("uri:done"), fixture.files.releasedReads)
        assertTrue(fixture.files.releasedWrites.isEmpty())
        assertEquals(listOf(completed.id), fixture.files.deletedTemps)
        assertEquals(1, fixture.purgeCalls())
    }

    @Test
    fun pauseCancelAndUnknownTaskOperationsAreSafe() = runTest {
        val fixture = fixture()
        val task = fixture.repository.enqueueDownload(
            fixture.backend.serverBaseUrl, 9, "report.pdf", 42, "uri:target",
            TransferScope.Team(teamId = 7), "application/pdf",
        )

        fixture.manager.pause("missing")
        fixture.manager.cancel("missing")
        fixture.manager.resume("missing")
        fixture.manager.pause(task.id)
        assertEquals(TransferStatus.PAUSED, fixture.manager.find(task.id)?.status)

        fixture.manager.cancel(task.id)
        assertEquals(TransferStatus.CANCELED, fixture.manager.find(task.id)?.status)
        assertEquals(listOf("uri:target"), fixture.files.releasedWrites)
        assertEquals(listOf(task.id), fixture.files.deletedTemps)
    }

    private fun fixture(scheduleSucceeds: Boolean = true): Fixture {
        val dao = ManagerFakeDao()
        var purgeCalls = 0
        val repository = TransferRepository(dao, purgeDeletedContent = { purgeCalls++ }, now = { 1_000 })
        val scheduler = ManagerFakeScheduler(scheduleSucceeds)
        val files = ManagerFakeFiles()
        val backend = NetworkTestFacade(HttpClient(MockEngine { respondOk() }), initialServerBaseUrl = "http://test")
        return Fixture(
            backend, repository, scheduler, files,
            TransferManager(backend, repository, scheduler, files),
            purgeCalls = { purgeCalls },
        )
    }
}

private data class Fixture(
    val backend: NetworkTestFacade,
    val repository: TransferRepository,
    val scheduler: ManagerFakeScheduler,
    val files: ManagerFakeFiles,
    val manager: TransferManager,
    val purgeCalls: () -> Int,
)

private class ManagerFakeScheduler(private val succeeds: Boolean) : TransferScheduler {
    val specs = mutableListOf<TransferScheduleSpec>()
    override suspend fun schedule(spec: TransferScheduleSpec): Boolean {
        specs += spec
        return succeeds
    }
    override suspend fun cancelAll() = Unit
    override suspend fun scheduledTaskIds(): Set<String> = specs.flatMap { it.taskIds }.toSet()
}

private class ManagerFakeFiles : TransferFileAccess {
    val unreadable = mutableSetOf<String>()
    val releasedReads = mutableListOf<String>()
    val releasedWrites = mutableListOf<String>()
    val deletedTemps = mutableListOf<String>()
    override suspend fun validateReadable(sourceHandle: String) {
        if (sourceHandle in unreadable) error("unreadable")
    }
    override suspend fun validateWritable(destinationHandle: String) = Unit
    override suspend fun releaseReadable(sourceHandle: String) { releasedReads += sourceHandle }
    override suspend fun releaseWritable(destinationHandle: String) { releasedWrites += destinationHandle }
    override suspend fun computeDigest(sourceHandle: String): FileDigest = FileDigest("md5", 0)
    override fun uploadPart(sourceHandle: String, offset: Long, length: Long, onBytes: (Long) -> Unit): InputProvider =
        InputProvider(length) { Buffer() }
    override suspend fun tempLength(taskId: String): Long = 0
    override suspend fun tempMd5(taskId: String): String = "md5"
    override suspend fun resetTemp(taskId: String) = Unit
    override suspend fun appendTemp(taskId: String, channel: ByteReadChannel, onBytes: suspend (Long) -> Unit) = Unit
    override suspend fun commitTemp(taskId: String, destinationHandle: String) = Unit
    override suspend fun deleteTemp(taskId: String) { deletedTemps += taskId }
    override suspend fun deleteAllTemps() = Unit
}

private class ManagerFakeDao : TransferTaskDao {
    private val values = MutableStateFlow<List<TransferTaskEntity>>(emptyList())
    override fun observeAll(): Flow<List<TransferTaskEntity>> = values
    override suspend fun count(): Int = values.value.size
    override suspend fun find(id: String): TransferTaskEntity? = values.value.firstOrNull { it.id == id }
    override suspend fun all(): List<TransferTaskEntity> = values.value
    override suspend fun upsert(value: TransferTaskEntity) {
        values.value = values.value.filterNot { it.id == value.id } + value
    }
    override suspend fun delete(id: String) { values.value = values.value.filterNot { it.id == id } }
    override suspend fun deleteAll() { values.value = emptyList() }
}
