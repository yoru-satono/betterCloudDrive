package com.betterclouddrive.mobile.platform

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.PersistableBundle
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.betterclouddrive.mobile.app.AppContainer
import com.betterclouddrive.mobile.core.localization.MobileString
import com.betterclouddrive.mobile.core.localization.MobileStrings
import com.betterclouddrive.mobile.core.platform.TransferScheduler
import com.betterclouddrive.mobile.core.platform.TransferScheduleSpec
import com.betterclouddrive.mobile.feature.transfers.DEFAULT_CHUNK_SIZE
import com.betterclouddrive.mobile.feature.transfers.TransferRunResult
import com.betterclouddrive.mobile.feature.transfers.TransferBatchExecutor
import com.betterclouddrive.mobile.feature.transfers.TransferDirection
import com.betterclouddrive.mobile.feature.transfers.TransferStatus
import com.betterclouddrive.mobile.feature.transfers.TransferTask
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

object AndroidRuntime {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    lateinit var container: AppContainer
        private set

    fun install(value: AppContainer) { container = value }

    suspend fun executeBatch(taskIds: List<String>, onProgress: suspend (TransferTask) -> Unit = {}): TransferRunResult {
        container.backend.initialize()
        return TransferBatchExecutor(
            findTask = container.transfers::find,
            runTask = { taskId -> executeTask(taskId, onProgress) },
            onTaskSettled = container.transfers::onTaskSettled,
        ).execute(taskIds)
    }

    private suspend fun executeTask(taskId: String, onProgress: suspend (TransferTask) -> Unit): TransferRunResult = coroutineScope {
        val progressLimiter = TransferProgressLimiter()
        val execution = async { container.transferEngine.run(taskId) }
        val observer = launch {
            container.transfers.tasks
                .map { tasks -> tasks.firstOrNull { it.id == taskId } }
                .filterNotNull()
                .distinctUntilChanged()
                .collect { task ->
                    if (progressLimiter.shouldPublish(task)) onProgress(task)
                    if (task.status == TransferStatus.PAUSED || task.status == TransferStatus.CANCELED) execution.cancel()
                }
        }
        try {
            execution.await()
        } catch (cancelled: CancellationException) {
            currentCoroutineContext().ensureActive()
            val status = container.transfers.tasks.valueOrNull(taskId)?.status
            if (status in setOf(TransferStatus.PAUSED, TransferStatus.CANCELED)) {
                TransferRunResult.COMPLETE
            } else {
                throw cancelled
            }
        } finally {
            observer.cancel()
        }
    }

    fun pause(taskId: String) { scope.launch { container.transfers.pause(taskId) } }
    fun cancel(taskId: String) { scope.launch { container.transfers.cancel(taskId) } }
}

private suspend fun kotlinx.coroutines.flow.Flow<List<TransferTask>>.valueOrNull(taskId: String): TransferTask? =
    first().firstOrNull { it.id == taskId }

internal class TransferProgressLimiter(
    private val minimumIntervalMillis: Long = DEFAULT_NOTIFICATION_INTERVAL_MILLIS,
    private val nowMillis: () -> Long = SystemClock::elapsedRealtime,
) {
    private var lastPublishedAt: Long? = null
    private var lastPublishedTask: TransferTask? = null

    init {
        require(minimumIntervalMillis >= 0) { "notification.negativeInterval" }
    }

    fun shouldPublish(task: TransferTask): Boolean {
        val previous = lastPublishedTask
        if (previous == task) return false
        val now = nowMillis()
        val terminal = task.status !in ACTIVE_NOTIFICATION_STATUSES
        val due = lastPublishedAt?.let { now - it >= minimumIntervalMillis } ?: true
        if (!terminal && !due) return false
        lastPublishedTask = task
        lastPublishedAt = now
        return true
    }

    private companion object {
        val ACTIVE_NOTIFICATION_STATUSES = setOf(
            TransferStatus.QUEUED,
            TransferStatus.PREPARING,
            TransferStatus.RUNNING,
        )
    }
}

class AndroidTransferScheduler(private val context: Context) : TransferScheduler {
    override suspend fun schedule(spec: TransferScheduleSpec): Boolean {
        if (spec.taskIds.isEmpty()) return false
        if (transferRuntimeForSdk(Build.VERSION.SDK_INT) == AndroidTransferRuntime.UIDT) {
            return runCatching { scheduleUidt(spec) }.getOrDefault(false)
        }
        return scheduleWorker(spec)
    }

    override suspend fun cancelAll() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val scheduler = context.getSystemService(JobScheduler::class.java)
            scheduler.allPendingJobs.filter { it.service.className == AndroidTransferJobService::class.java.name }
                .forEach { scheduler.cancel(it.id) }
        }
        WorkManager.getInstance(context).cancelAllWorkByTag(WORK_TAG).result.get()
    }

    override suspend fun scheduledTaskIds(): Set<String> = withContext(Dispatchers.IO) {
        val result = mutableSetOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            context.getSystemService(JobScheduler::class.java).allPendingJobs
                .filter { it.service.className == AndroidTransferJobService::class.java.name }
                .flatMap { it.extras.getStringArray(TASK_IDS)?.asList().orEmpty() }
                .toCollection(result)
        }
        WorkManager.getInstance(context).getWorkInfosByTag(WORK_TAG).get()
            .filterNot { it.state.isFinished }
            .flatMap { info -> info.tags.filter { it.startsWith(TASK_TAG_PREFIX) }.map { it.removePrefix(TASK_TAG_PREFIX) } }
            .toCollection(result)
        result
    }

    private suspend fun scheduleWorker(spec: TransferScheduleSpec): Boolean = withContext(Dispatchers.IO) {
        val manager = WorkManager.getInstance(context)
        val existing = manager.getWorkInfosForUniqueWork(workName(spec.batchId)).get()
        if (existing.any { !it.state.isFinished }) return@withContext true
        val builder = OneTimeWorkRequestBuilder<AndroidTransferWorker>()
            .setInputData(workDataOf(BATCH_ID to spec.batchId, TASK_IDS to spec.taskIds.toTypedArray()))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).setRequiresStorageNotLow(true).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .addTag(WORK_TAG)
        spec.taskIds.forEach { builder.addTag("$TASK_TAG_PREFIX$it") }
        manager.enqueueUniqueWork(workName(spec.batchId), ExistingWorkPolicy.KEEP, builder.build()).result.get()
        true
    }

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private fun scheduleUidt(spec: TransferScheduleSpec): Boolean {
        val extras = PersistableBundle().apply {
            putString(BATCH_ID, spec.batchId)
            putStringArray(TASK_IDS, spec.taskIds.toTypedArray())
        }
        val network = NetworkRequest.Builder().addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build()
        val downloadBytes = spec.downloadBytes.coerceAtLeast(0)
        val uploadBytes = spec.uploadBytes.coerceAtLeast(0)
        val bytes = if (Long.MAX_VALUE - downloadBytes < uploadBytes) Long.MAX_VALUE else downloadBytes + uploadBytes
        val builder = JobInfo.Builder(jobId(spec.batchId), ComponentName(context, AndroidTransferJobService::class.java))
            .setExtras(extras)
            .setRequiredNetwork(network)
            .setPersisted(true)
            .setUserInitiated(true)
            .setEstimatedNetworkBytes(downloadBytes, uploadBytes)
            .setBackoffCriteria(30_000, JobInfo.BACKOFF_POLICY_EXPONENTIAL)
        if (bytes >= DEFAULT_CHUNK_SIZE) builder.setMinimumNetworkChunkBytes(DEFAULT_CHUNK_SIZE)
        val info = builder.build()
        return context.getSystemService(JobScheduler::class.java).schedule(info) == JobScheduler.RESULT_SUCCESS
    }

    companion object {
        const val BATCH_ID = "transfer_batch_id"
        const val TASK_IDS = "transfer_task_ids"
        const val WORK_TAG = "bcd-transfers"
        const val TASK_TAG_PREFIX = "bcd-transfer-task:"
        fun workName(batchId: String) = "bcd-transfer-batch-$batchId"
        fun jobId(batchId: String): Int = ((batchId.hashCode() and Int.MAX_VALUE) % 900_000) + 10_000
    }
}

enum class AndroidTransferRuntime { UIDT, WORK_MANAGER }
fun transferRuntimeForSdk(sdk: Int): AndroidTransferRuntime =
    if (sdk >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) AndroidTransferRuntime.UIDT else AndroidTransferRuntime.WORK_MANAGER

class AndroidTransferWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val batchId = inputData.getString(AndroidTransferScheduler.BATCH_ID) ?: return Result.failure()
        val taskIds = inputData.getStringArray(AndroidTransferScheduler.TASK_IDS)?.asList().orEmpty()
        if (taskIds.isEmpty()) return Result.failure()
        val strings = androidMobileStrings(applicationContext)
        setForeground(transferForegroundInfo(applicationContext, batchId, strings[MobileString.NotificationPreparing]))
        return when (AndroidRuntime.executeBatch(taskIds) { task ->
            setForeground(transferForegroundInfo(applicationContext, batchId, task.notificationText(strings), task))
        }) {
            TransferRunResult.RETRY -> Result.retry()
            else -> Result.success()
        }
    }
}

class AndroidTransferJobService : JobService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobs = mutableMapOf<Int, Job>()

    override fun onStartJob(params: JobParameters): Boolean {
        val batchId = params.extras.getString(AndroidTransferScheduler.BATCH_ID) ?: return false
        val taskIds = params.extras.getStringArray(AndroidTransferScheduler.TASK_IDS)?.asList().orEmpty()
        if (taskIds.isEmpty()) return false
        val strings = androidMobileStrings(this)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            setNotification(
                params,
                AndroidTransferScheduler.jobId(batchId),
                transferNotification(this, batchId, strings[MobileString.NotificationTransferring]),
                JOB_END_NOTIFICATION_POLICY_REMOVE,
            )
        }
        jobs[params.jobId] = scope.launch {
            val transferred = mutableMapOf<String, Pair<TransferDirection, Long>>()
            val result = AndroidRuntime.executeBatch(taskIds) { task ->
                setNotification(
                    params,
                    params.jobId,
                    transferNotification(this@AndroidTransferJobService, batchId, task.notificationText(strings), task),
                    JOB_END_NOTIFICATION_POLICY_REMOVE,
                )
                transferred[task.id] = task.direction to task.bytesDone.coerceAtLeast(0)
                val downloaded = transferred.values.filter { it.first == TransferDirection.DOWNLOAD }.sumBytes()
                val uploaded = transferred.values.filter { it.first == TransferDirection.UPLOAD }.sumBytes()
                updateTransferredNetworkBytes(params, downloaded, uploaded)
            }
            jobs.remove(params.jobId)
            jobFinished(params, result == TransferRunResult.RETRY)
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        jobs.remove(params.jobId)?.cancel()
        return params.stopReason !in setOf(JobParameters.STOP_REASON_USER, JobParameters.STOP_REASON_CANCELLED_BY_APP)
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}

class TransferActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val taskId = intent.getStringExtra(TASK_ID) ?: return
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                when (intent.action) {
                    ACTION_PAUSE -> AndroidRuntime.container.transfers.pause(taskId)
                    ACTION_CANCEL -> AndroidRuntime.container.transfers.cancel(taskId)
                }
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION_PAUSE = "com.betterclouddrive.mobile.PAUSE_TRANSFER"
        const val ACTION_CANCEL = "com.betterclouddrive.mobile.CANCEL_TRANSFER"
        const val TASK_ID = "transfer_action_task_id"
    }
}

private fun transferForegroundInfo(context: Context, batchId: String, text: String, task: TransferTask? = null): ForegroundInfo = ForegroundInfo(
    AndroidTransferScheduler.jobId(batchId),
    transferNotification(context, batchId, text, task),
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0,
)

fun transferNotification(context: Context, batchId: String, text: String, task: TransferTask? = null): Notification {
    createTransferChannel(context)
    val strings = androidMobileStrings(context)
    val progress = task?.takeIf { it.bytesTotal > 0 }?.progressPercent() ?: 0
    val builder = NotificationCompat.Builder(context, CHANNEL_ID)
        .setSmallIcon(android.R.drawable.stat_sys_upload)
        .setContentTitle(strings[MobileString.NotificationTransferTitle])
        .setContentText(text)
        .setProgress(100, progress, task?.bytesTotal?.let { it <= 0 } ?: true)
        .setOngoing(true)
    task?.takeIf { it.status in setOf(TransferStatus.QUEUED, TransferStatus.PREPARING, TransferStatus.RUNNING) }?.let {
        builder.addAction(0, strings[MobileString.Pause], actionIntent(context, batchId, it.id, TransferActionReceiver.ACTION_PAUSE, 1))
        builder.addAction(0, strings[MobileString.Cancel], actionIntent(context, batchId, it.id, TransferActionReceiver.ACTION_CANCEL, 2))
    }
    return builder.build()
}

private fun createTransferChannel(context: Context) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, androidMobileStrings(context)[MobileString.NotificationChannel], NotificationManager.IMPORTANCE_LOW),
        )
    }
}

private fun actionIntent(context: Context, batchId: String, taskId: String, action: String, offset: Int): PendingIntent {
    val intent = Intent(context, TransferActionReceiver::class.java).setAction(action)
        .putExtra(TransferActionReceiver.TASK_ID, taskId)
    return PendingIntent.getBroadcast(
        context,
        AndroidTransferScheduler.jobId(batchId) + offset,
        intent,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
}

private const val CHANNEL_ID = "bcd_transfers"
private const val DEFAULT_NOTIFICATION_INTERVAL_MILLIS = 1_000L

private fun TransferTask.notificationText(strings: MobileStrings): String {
    val progress = if (bytesTotal > 0) " (${progressPercent()}%)" else ""
    val key = if (direction == TransferDirection.UPLOAD) MobileString.NotificationUploading else MobileString.NotificationDownloading
    return strings.format(key, fileName, progress)
}

private fun TransferTask.progressPercent(): Int =
    ((bytesDone.coerceIn(0, bytesTotal).toDouble() / bytesTotal.toDouble()) * 100).toInt()

private fun List<Pair<TransferDirection, Long>>.sumBytes(): Long = fold(0L) { total, value ->
    val bytes = value.second
    if (Long.MAX_VALUE - total < bytes) Long.MAX_VALUE else total + bytes
}
