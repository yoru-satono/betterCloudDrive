package com.betterclouddrive.mobile.platform

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.Configuration
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.betterclouddrive.mobile.core.model.StoredSession
import com.betterclouddrive.mobile.core.model.SessionStorageInvalidException
import com.betterclouddrive.mobile.core.database.MobileDatabase
import com.betterclouddrive.mobile.core.database.TransferTaskEntity
import com.betterclouddrive.mobile.core.platform.TransferScheduleSpec
import com.betterclouddrive.mobile.feature.transfers.TransferDirection
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.Assume.assumeTrue
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AndroidSecurityDeviceTest {
    @Test
    fun encryptedSessionRoundTripsWithoutPlaintextAndCorruptionLogsOut() = runTest {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val unique = System.nanoTime()
        val store = AndroidSecureSessionStore(
            createPreferencesStore(context, "device-test-$unique"),
            context.packageName,
            "betterclouddrive.mobile.device-test.$unique",
        )
        val session = StoredSession(
            accessToken = "plain-access-token-marker",
            refreshToken = "plain-refresh-token-marker",
            expiresAtEpochMillis = 123456,
            serverBaseUrl = "https://drive.example.com",
        )

        store.write(session)

        assertEquals(session, store.read(session.serverBaseUrl))
        val raw = store.rawPayloadForTest().orEmpty()
        assertFalse(raw.contains(session.accessToken))
        assertFalse(raw.contains(session.refreshToken))

        store.corruptPayloadForTest()
        assertThrows(SessionStorageInvalidException::class.java) {
            kotlinx.coroutines.runBlocking { store.read(session.serverBaseUrl) }
        }
        assertNull(store.rawPayloadForTest())
    }

    @Test
    fun schedulerStrategyUsesUidtFromApi34() {
        assertEquals(AndroidTransferRuntime.WORK_MANAGER, transferRuntimeForSdk(33))
        assertEquals(AndroidTransferRuntime.UIDT, transferRuntimeForSdk(34))
    }

    @Test
    fun roomTransferStateSurvivesDatabaseReopen() = runTest {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val name = "device-transfer-${System.nanoTime()}.db"
        context.deleteDatabase(name)
        val value = TransferTaskEntity(
            id = "persisted-task", direction = "UPLOAD", status = "PAUSED",
            serverBaseUrl = "https://drive.example.com", teamId = null, remoteParentId = null,
            remoteFileId = null, remoteResultFileId = null, fileName = "large.bin", mimeType = null,
            bytesDone = 5, bytesTotal = 10, sourceHandle = "content://source", destinationHandle = null,
            sessionId = "session-1", chunkSize = 5L * 1024 * 1024, totalChunks = 2,
            md5Hash = "md5", retryCount = 1, errorKey = null, createdAt = 1, updatedAt = 2,
        )
        val first = openDatabase(context, name)
        first.transferTaskDao().upsert(value)
        first.close()

        val second = openDatabase(context, name)
        val restored = second.transferTaskDao().find(value.id)
        second.close()

        assertEquals(value, restored)
        context.deleteDatabase(name)
    }

    @Test
    fun transferTempsArePrivateAndCanBeCleaned() = runTest {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val files = AndroidTransferFileAccess(context)
        val taskId = "device-temp-${System.nanoTime()}"
        files.appendTemp(taskId, ByteReadChannel("abc".encodeToByteArray())) { }
        assertEquals(3, files.tempLength(taskId))

        files.deleteAllTemps()

        assertEquals(0, files.tempLength(taskId))
    }

    @Test
    fun fileAccessRejectsHandleWithoutPersistedPermission() = runTest {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val files = AndroidTransferFileAccess(context)
        val untrustedHandle = "content://com.betterclouddrive.invalid/${System.nanoTime()}"

        assertThrows(IllegalStateException::class.java) {
            kotlinx.coroutines.runBlocking { files.validateReadable(untrustedHandle) }
        }
        assertThrows(IllegalStateException::class.java) {
            kotlinx.coroutines.runBlocking { files.validateWritable(untrustedHandle) }
        }
    }

    @Test
    fun transferNotificationExposesPauseAndCancelActions() {
        val base = ApplicationProvider.getApplicationContext<android.content.Context>()
        val context = base.createConfigurationContext(
            android.content.res.Configuration(base.resources.configuration).apply {
                setLocales(android.os.LocaleList.forLanguageTags("zh-CN"))
            },
        )
        val notification = transferNotification(context, "notification-task", "正在传输")
        assertEquals(2, notification.actions.size)
        assertEquals("暂停", notification.actions[0].title.toString())
        assertEquals("取消", notification.actions[1].title.toString())
        assertTrue(notification.flags and android.app.Notification.FLAG_ONGOING_EVENT != 0)
    }

    @Test
    fun workManagerUsesNetworkConstraintUniqueWorkAndCanCancel() = runTest {
        assumeTrue(android.os.Build.VERSION.SDK_INT < 34)
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val configuration = Configuration.Builder()
            .setExecutor(SynchronousExecutor())
            .setTaskExecutor(SynchronousExecutor())
            .build()
        WorkManagerTestInitHelper.initializeTestWorkManager(context, configuration)
        val scheduler = AndroidTransferScheduler(context)
        val spec = TransferScheduleSpec("wm-${System.nanoTime()}", TransferDirection.UPLOAD, 1024)

        assertTrue(scheduler.schedule(spec))
        assertTrue(scheduler.schedule(spec))
        val manager = WorkManager.getInstance(context)
        val infos = manager.getWorkInfosForUniqueWork(AndroidTransferScheduler.workName(spec.taskId)).get()
        assertEquals(1, infos.count { !it.state.isFinished })
        assertTrue(infos.single().constraints.requiredNetworkType == androidx.work.NetworkType.CONNECTED)

        assertTrue(WorkManagerTestInitHelper.getTestDriver(context) != null)
        scheduler.cancel(spec.taskId)
        assertTrue(manager.getWorkInfosForUniqueWork(AndroidTransferScheduler.workName(spec.taskId)).get().all { it.state.isFinished })
    }

    private fun openDatabase(context: android.content.Context, name: String): MobileDatabase =
        Room.databaseBuilder<MobileDatabase>(context, context.getDatabasePath(name).absolutePath)
            .setDriver(BundledSQLiteDriver())
            .setQueryCoroutineContext(Dispatchers.IO)
            .build()
}
