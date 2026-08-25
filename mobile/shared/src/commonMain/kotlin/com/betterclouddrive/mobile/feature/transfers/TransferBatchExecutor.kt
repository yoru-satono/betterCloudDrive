package com.betterclouddrive.mobile.feature.transfers

class TransferBatchExecutor(
    private val findTask: suspend (String) -> TransferTask?,
    private val runTask: suspend (String) -> TransferRunResult,
    private val onTaskSettled: suspend (String) -> Unit = {},
) {
    suspend fun execute(taskIds: List<String>): TransferRunResult {
        for (taskId in taskIds.distinct()) {
            val task = findTask(taskId) ?: continue
            if (task.status in SKIPPED) continue
            if (task.status == TransferStatus.WAITING_FOR_LOGIN) return TransferRunResult.PAUSED

            val result = runTask(taskId)
            onTaskSettled(taskId)
            if (result == TransferRunResult.RETRY) return result
            if (findTask(taskId)?.status == TransferStatus.WAITING_FOR_LOGIN) {
                return TransferRunResult.PAUSED
            }
        }
        return TransferRunResult.COMPLETE
    }

    private companion object {
        val SKIPPED = setOf(
            TransferStatus.SUCCEEDED,
            TransferStatus.FAILED,
            TransferStatus.CANCELED,
            TransferStatus.PAUSED,
        )
    }
}
