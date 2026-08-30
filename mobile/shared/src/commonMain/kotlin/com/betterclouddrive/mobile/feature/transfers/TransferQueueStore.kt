package com.betterclouddrive.mobile.feature.transfers

import com.betterclouddrive.mobile.app.ResettableStore
import com.betterclouddrive.mobile.app.TransferSessionPort
import com.betterclouddrive.mobile.app.storeChildScope
import com.betterclouddrive.mobile.core.platform.UploadPickResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

interface PersonalTransferOperations {
    val uploadFoldersSettled: Flow<Long?>
    suspend fun enqueueUploads(result: UploadPickResult, parentId: Long?): UploadEnqueueResult
    suspend fun enqueueDownload(
        fileId: Long,
        fileName: String,
        fileSize: Long,
        mimeType: String?,
        destinationHandle: String,
        expectedMd5: String?,
    ): TransferTask
}

class TransferQueueStore(
    private val manager: TransferManager,
    parentScope: CoroutineScope,
) : ResettableStore, TransferSessionPort, PersonalTransferOperations {
    private val scope = parentScope.storeChildScope()
    private val mutableState = MutableStateFlow<List<TransferTask>>(emptyList())
    val state: StateFlow<List<TransferTask>> = mutableState.asStateFlow()
    private val mutableUploadFoldersSettled = MutableSharedFlow<Long?>(extraBufferCapacity = 16)
    override val uploadFoldersSettled: Flow<Long?> = mutableUploadFoldersSettled.asSharedFlow()
    private var previous = emptyMap<String, TransferTask>()
    private var observed = false
    private val dirtyFolders = mutableSetOf<Long?>()

    init {
        scope.launch {
            manager.tasks.collectLatest { tasks ->
                tasks.filter { task ->
                    observed && task.direction == TransferDirection.UPLOAD && task.scope is TransferScope.Personal &&
                        task.status == TransferStatus.SUCCEEDED && previous[task.id]?.status != TransferStatus.SUCCEEDED
                }.forEach { dirtyFolders += (it.scope as TransferScope.Personal).parentId }
                observed = true
                previous = tasks.associateBy(TransferTask::id)
                mutableState.value = tasks
                dirtyFolders.filter { folderId -> tasks.none { task ->
                    task.direction == TransferDirection.UPLOAD && task.scope is TransferScope.Personal &&
                        task.scope.parentId == folderId && task.status in ACTIVE_UPLOAD_STATUSES
                } }.forEach { folderId ->
                    dirtyFolders.remove(folderId)
                    mutableUploadFoldersSettled.tryEmit(folderId)
                }
            }
        }
    }

    override suspend fun enqueueUploads(result: UploadPickResult, parentId: Long?): UploadEnqueueResult = manager.enqueueUploads(
        result.documents, TransferScope.Personal(parentId), result.rejectedCount, result.overflowCount,
    )

    override suspend fun enqueueDownload(
        fileId: Long, fileName: String, fileSize: Long, mimeType: String?, destinationHandle: String, expectedMd5: String?,
    ) = manager.enqueueDownload(fileId, fileName, fileSize, mimeType, destinationHandle, expectedMd5 = expectedMd5)

    fun pause(taskId: String) { scope.launch { manager.pause(taskId) } }
    fun resume(taskId: String) { scope.launch { manager.resume(taskId) } }
    fun cancel(taskId: String) { scope.launch { manager.cancel(taskId) } }
    fun retry(taskId: String) { scope.launch { manager.retry(taskId) } }
    fun clearFinished() { scope.launch { manager.clearFinished() } }
    override suspend fun resumeWaitingForLogin() = manager.resumeWaitingForLogin()
    override suspend fun clearForLogout() = manager.clearForLogout()
    override suspend fun waitForLogin() = manager.waitForLogin()

    override fun reset() {
        previous = emptyMap()
        observed = false
        dirtyFolders.clear()
        mutableState.value = emptyList()
    }

    private companion object {
        val ACTIVE_UPLOAD_STATUSES = setOf(TransferStatus.QUEUED, TransferStatus.PREPARING, TransferStatus.RUNNING)
    }
}
