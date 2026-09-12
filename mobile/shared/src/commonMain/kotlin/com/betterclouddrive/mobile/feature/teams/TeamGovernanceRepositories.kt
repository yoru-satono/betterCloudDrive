package com.betterclouddrive.mobile.feature.teams

import com.betterclouddrive.mobile.core.model.ApiException
import com.betterclouddrive.mobile.core.model.PageResult
import com.betterclouddrive.mobile.core.model.TeamOperationLog
import com.betterclouddrive.mobile.core.model.TeamTask
import com.betterclouddrive.mobile.core.network.TeamAuditApi
import com.betterclouddrive.mobile.core.network.TeamTasksApi
import com.betterclouddrive.mobile.core.platform.PickedDestination
import com.betterclouddrive.mobile.core.platform.TransferFileAccess
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode

data class TeamAuditFilter(
    val actorUserId: Long? = null,
    val action: String? = null,
    val targetType: String? = null,
    val result: Int? = null,
    val startAt: String? = null,
    val endAt: String? = null,
)

interface TeamAuditRepository {
    suspend fun list(teamId: Long, filter: TeamAuditFilter, page: Int, size: Int): PageResult<TeamOperationLog>
    suspend fun queueExport(teamId: Long, filter: TeamAuditFilter): TeamTask
}

class TeamAuditRepositoryImpl(private val api: TeamAuditApi) : TeamAuditRepository {
    override suspend fun list(teamId: Long, filter: TeamAuditFilter, page: Int, size: Int) = api.list(
        teamId, filter.actorUserId, filter.action, filter.targetType, filter.result,
        filter.startAt, filter.endAt, page, size,
    )

    override suspend fun queueExport(teamId: Long, filter: TeamAuditFilter) = api.queueExport(
        teamId, filter.actorUserId, filter.action, filter.targetType, filter.result,
        filter.startAt, filter.endAt,
    )
}

interface TeamTasksRepository {
    suspend fun list(teamId: Long, status: String?, type: String?, page: Int, size: Int): PageResult<TeamTask>
    suspend fun retry(teamId: Long, taskId: Long): TeamTask
    suspend fun download(
        teamId: Long,
        task: TeamTask,
        destination: PickedDestination,
        onProgress: suspend (downloaded: Long, total: Long?) -> Unit,
    ): Long
}

class TeamTasksRepositoryImpl(
    private val api: TeamTasksApi,
    private val files: TransferFileAccess,
) : TeamTasksRepository {
    override suspend fun list(teamId: Long, status: String?, type: String?, page: Int, size: Int) =
        api.list(teamId, status, type, page, size)

    override suspend fun retry(teamId: Long, taskId: Long) = api.retry(teamId, taskId)

    override suspend fun download(
        teamId: Long,
        task: TeamTask,
        destination: PickedDestination,
        onProgress: suspend (Long, Long?) -> Unit,
    ): Long {
        val tempId = "team-task-result-$teamId-${task.id}"
        try {
            files.validateWritable(destination.handle)
            files.deleteTemp(tempId)
            files.resetTemp(tempId)
            var expected = task.resultSizeBytes
            api.download(teamId, task.id) { status, headers, channel ->
                if (status != HttpStatusCode.OK) {
                    throw ApiException(status.value, if (status.value >= 500) "error.internal" else "error.downloadFailed")
                }
                headers[HttpHeaders.ContentLength]?.toLongOrNull()?.let { headerLength ->
                    expected?.let { if (it != headerLength) throw ApiException(422, "error.downloadIncomplete") }
                    expected = headerLength
                }
                files.appendTemp(tempId, channel) { count ->
                    onProgress(files.tempLength(tempId), expected)
                }
            }
            val length = files.tempLength(tempId)
            expected?.let { if (length != it) throw ApiException(422, "error.downloadIncomplete") }
            files.commitTemp(tempId, destination.handle)
            return length
        } finally {
            files.deleteTemp(tempId)
            files.releaseWritable(destination.handle)
        }
    }
}
