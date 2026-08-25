package com.betterclouddrive.mobile.feature.transfers

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class TransferBatchExecutorTest {
    @Test
    fun permanentFailurePauseAndCancelDoNotBlockLaterTasks() = runTest {
        val tasks = linkedMapOf(
            "failed" to task("failed", TransferStatus.QUEUED),
            "paused" to task("paused", TransferStatus.PAUSED),
            "canceled" to task("canceled", TransferStatus.CANCELED),
            "next" to task("next", TransferStatus.QUEUED),
        )
        val executed = mutableListOf<String>()
        val settled = mutableListOf<String>()
        val executor = TransferBatchExecutor(
            findTask = tasks::get,
            runTask = { id ->
                executed += id
                tasks[id] = tasks.getValue(id).copy(
                    status = if (id == "failed") TransferStatus.FAILED else TransferStatus.SUCCEEDED,
                )
                TransferRunResult.COMPLETE
            },
            onTaskSettled = { settled += it },
        )

        assertEquals(TransferRunResult.COMPLETE, executor.execute(listOf("failed", "paused", "canceled", "next", "next")))
        assertEquals(listOf("failed", "next"), executed)
        assertEquals(executed, settled)
    }

    @Test
    fun temporaryFailureStopsBatchAtCurrentTask() = runTest {
        val tasks = mapOf("first" to task("first", TransferStatus.QUEUED), "second" to task("second", TransferStatus.QUEUED))
        val executed = mutableListOf<String>()
        val executor = TransferBatchExecutor(tasks::get, runTask = { id ->
            executed += id
            TransferRunResult.RETRY
        })

        assertEquals(TransferRunResult.RETRY, executor.execute(tasks.keys.toList()))
        assertEquals(listOf("first"), executed)
    }

    @Test
    fun waitingForLoginStopsBeforeRunningAnotherTask() = runTest {
        val tasks = mapOf(
            "waiting" to task("waiting", TransferStatus.WAITING_FOR_LOGIN),
            "next" to task("next", TransferStatus.QUEUED),
        )
        val executed = mutableListOf<String>()
        val executor = TransferBatchExecutor(tasks::get, runTask = { id -> executed += id; TransferRunResult.COMPLETE })

        assertEquals(TransferRunResult.PAUSED, executor.execute(tasks.keys.toList()))
        assertEquals(emptyList(), executed)
    }

    private fun task(id: String, status: TransferStatus) = TransferTask(
        id = id,
        direction = TransferDirection.UPLOAD,
        status = status,
        serverBaseUrl = "https://drive.example.com",
        scope = TransferScope.Personal(),
        fileName = "$id.bin",
        createdAt = 1,
        updatedAt = 1,
    )
}
