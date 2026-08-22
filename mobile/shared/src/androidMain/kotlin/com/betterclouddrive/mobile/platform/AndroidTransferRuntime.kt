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
import com.betterclouddrive.mobile.core.platform.TransferScheduler
import com.betterclouddrive.mobile.core.platform.TransferScheduleSpec
import com.betterclouddrive.mobile.feature.transfers.DEFAULT_CHUNK_SIZE
import com.betterclouddrive.mobile.feature.transfers.TransferRunResult
import com.betterclouddrive.mobile.feature.transfers.TransferDirection
import com.betterclouddrive.mobile.feature.transfers.TransferStatus
import com.betterclouddrive.mobile.feature.transfers.TransferTask
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

object AndroidRuntime {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    lateinit var container: AppContainer
        private set

    fun install(value: AppContainer) { container = value }

    suspend fun execute(taskId: String, onProgress: suspend (TransferTask) -> Unit = {}): TransferRunResult = coroutineScope {
        container.backend.initialize()
        val progressLimiter = TransferProgressLimiter()
        val observer = launch {
            container.transfers.tasks
                .map { tasks -> tasks.firstOrNull { it.id == taskId } }
                .filterNotNull()
                .distinctUntilChanged()
                .collect { task ->
                    if (progressLimiter.shouldPublish(task)) onProgress(task)
                }
        }
        try {
            container.transferEngine.run(taskId)
        } finally {
            observer.cancel()
        }
    }

    fun pause(taskId: String) { scope.launch { container.transfers.pause(taskId) } }
    fun cancel(taskId: String) { scope.launch { container.transfers.cancel(taskId) } }
}

internal class TransferProgressLimiter(
    private val minimumIntervalMillis: Long = DEFAULT_NOTIFICATION_INTERVAL_MILLIS,
    private val nowMillis: () -> Long = SystemClock::elapsedRealtime,
) {
    private var lastPublishedAt: Long? = null
    private var lastPublishedTask: TransferTask? = null

    init {
        require(minimumIntervalMillis >= 0) { "通知刷新间隔不能为负数" }
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
        if (transferRuntimeForSdk(Build.VERSION.SDK_INT) == AndroidTransferRuntime.UIDT) {
            return runCatching { scheduleUidt(spec) }.getOrDefault(false)
        }
        return scheduleWorker(spec.taskId)
    }

    override suspend fun cancel(taskId: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            context.getSystemService(JobScheduler::class.java).cancel(jobId(taskId))
        }
        WorkManager.getInstance(context).cancelUniqueWork(workName(taskId)).result.get()
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
                .mapNotNullTo(result) { it.extras.getString(TASK_ID) }
        }
        WorkManager.getInstance(context).getWorkInfosByTag(WORK_TAG).get()
            .filterNot { it.state.isFinished }
            .mapNotNullTo(result) { it.tags.firstOrNull { tag -> tag.startsWith(TASK_TAG_PREFIX) }?.removePrefix(TASK_TAG_PREFIX) }
        result
    }

    private suspend fun scheduleWorker(taskId: String): Boolean = withContext(Dispatchers.IO) {
        val manager = WorkManager.getInstance(context)
        val existing = manager.getWorkInfosForUniqueWork(workName(taskId)).get()
        if (existing.any { !it.state.isFinished }) return@withContext true
        val work = OneTimeWorkRequestBuilder<AndroidTransferWorker>()
            .setInputData(workDataOf(TASK_ID to taskId))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).setRequiresStorageNotLow(true).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .addTag(WORK_TAG)
            .addTag("$TASK_TAG_PREFIX$taskId")
            .build()
        manager.enqueueUniqueWork(workName(taskId), ExistingWorkPolicy.REPLACE, work).result.get()
        true
    }

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private fun scheduleUidt(spec: TransferScheduleSpec): Boolean {
        val extras = PersistableBundle().apply { putString(TASK_ID, spec.taskId) }
        val network = NetworkRequest.Builder().addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build()
        val bytes = spec.remainingBytes.coerceAtLeast(0)
        val downloadBytes = if (spec.direction == TransferDirection.DOWNLOAD) bytes else 0
        val uploadBytes = if (spec.direction == TransferDirection.UPLOAD) bytes else 0
        val builder = JobInfo.Builder(jobId(spec.taskId), ComponentName(context, AndroidTransferJobService::class.java))
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
        const val TASK_ID = "transfer_task_id"
        const val WORK_TAG = "bcd-transfers"
        const val TASK_TAG_PREFIX = "bcd-transfer-task:"
        fun workName(taskId: String) = "bcd-transfer-$taskId"
        fun jobId(taskId: String): Int = ((taskId.hashCode() and Int.MAX_VALUE) % 900_000) + 10_000
    }
}

enum class AndroidTransferRuntime { UIDT, WORK_MANAGER }
fun transferRuntimeForSdk(sdk: Int): AndroidTransferRuntime =
    if (sdk >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) AndroidTransferRuntime.UIDT else AndroidTransferRuntime.WORK_MANAGER

class AndroidTransferWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val taskId = inputData.getString(AndroidTransferScheduler.TASK_ID) ?: return Result.failure()
        setForeground(transferForegroundInfo(applicationContext, taskId, "正在准备传输"))
        return when (AndroidRuntime.execute(taskId) { task ->
            setForeground(transferForegroundInfo(applicationContext, taskId, task.notificationText(), task))
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
        val taskId = params.extras.getString(AndroidTransferScheduler.TASK_ID) ?: return false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            setNotification(
                params,
                AndroidTransferScheduler.jobId(taskId),
                transferNotification(this, taskId, "正在传输"),
                JOB_END_NOTIFICATION_POLICY_REMOVE,
            )
        }
        jobs[params.jobId] = scope.launch {
            val result = AndroidRuntime.execute(taskId) { task ->
                setNotification(
                    params,
                    params.jobId,
                    transferNotification(this@AndroidTransferJobService, taskId, task.notificationText(), task),
                    JOB_END_NOTIFICATION_POLICY_REMOVE,
                )
                val downloaded = if (task.direction == TransferDirection.DOWNLOAD) task.bytesDone else 0
                val uploaded = if (task.direction == TransferDirection.UPLOAD) task.bytesDone else 0
                updateTransferredNetworkBytes(params, downloaded, uploaded)
            }
            jobs.remove(params.jobId)
            jobFinished(params, result == TransferRunResult.RETRY)
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        jobs.remove(params.jobId)?.cancel()
        val taskId = params.extras.getString(AndroidTransferScheduler.TASK_ID)
        if (params.stopReason == JobParameters.STOP_REASON_USER && taskId != null) AndroidRuntime.pause(taskId)
        return params.stopReason !in setOf(JobParameters.STOP_REASON_USER, JobParameters.STOP_REASON_CANCELLED_BY_APP)
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}

class TransferActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val taskId = intent.getStringExtra(AndroidTransferScheduler.TASK_ID) ?: return
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
    }
}

private fun transferForegroundInfo(context: Context, taskId: String, text: String, task: TransferTask? = null): ForegroundInfo = ForegroundInfo(
    AndroidTransferScheduler.jobId(taskId),
    transferNotification(context, taskId, text, task),
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0,
)

fun transferNotification(context: Context, taskId: String, text: String, task: TransferTask? = null): Notification {
    createTransferChannel(context)
    val progress = task?.takeIf { it.bytesTotal > 0 }?.progressPercent() ?: 0
    return NotificationCompat.Builder(context, CHANNEL_ID)
        .setSmallIcon(android.R.drawable.stat_sys_upload)
        .setContentTitle("BetterCloudDrive 文件传输")
        .setContentText(text)
        .setProgress(100, progress, task?.bytesTotal?.let { it <= 0 } ?: true)
        .setOngoing(true)
        .addAction(0, "暂停", actionIntent(context, taskId, TransferActionReceiver.ACTION_PAUSE, 1))
        .addAction(0, "取消", actionIntent(context, taskId, TransferActionReceiver.ACTION_CANCEL, 2))
        .build()
}

private fun createTransferChannel(context: Context) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "文件传输", NotificationManager.IMPORTANCE_LOW),
        )
    }
}

private fun actionIntent(context: Context, taskId: String, action: String, offset: Int): PendingIntent {
    val intent = Intent(context, TransferActionReceiver::class.java).setAction(action)
        .putExtra(AndroidTransferScheduler.TASK_ID, taskId)
    return PendingIntent.getBroadcast(
        context,
        AndroidTransferScheduler.jobId(taskId) + offset,
        intent,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
}

private const val CHANNEL_ID = "bcd_transfers"
private const val DEFAULT_NOTIFICATION_INTERVAL_MILLIS = 1_000L

private fun TransferTask.notificationText(): String {
    val action = if (direction == TransferDirection.UPLOAD) "正在上传" else "正在下载"
    return if (bytesTotal > 0) "$action $fileName（${progressPercent()}%）" else "$action $fileName"
}

private fun TransferTask.progressPercent(): Int =
    ((bytesDone.coerceIn(0, bytesTotal).toDouble() / bytesTotal.toDouble()) * 100).toInt()
