package com.betterclouddrive.mobile.platform

import com.betterclouddrive.mobile.feature.transfers.TransferDirection
import com.betterclouddrive.mobile.feature.transfers.TransferScope
import com.betterclouddrive.mobile.feature.transfers.TransferStatus
import com.betterclouddrive.mobile.feature.transfers.TransferTask
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TransferProgressLimiterTest {
    @Test
    fun firstProgressIsImmediateAndActiveUpdatesAreLimitedToOncePerSecond() {
        var now = 10_000L
        val limiter = TransferProgressLimiter(minimumIntervalMillis = 1_000L, nowMillis = { now })

        assertTrue(limiter.shouldPublish(task(bytesDone = 1)))
        now += 999
        assertFalse(limiter.shouldPublish(task(bytesDone = 2)))
        now += 1
        assertTrue(limiter.shouldPublish(task(bytesDone = 3)))
        assertFalse(limiter.shouldPublish(task(bytesDone = 3)))
    }

    @Test
    fun terminalStateBypassesTheActiveProgressInterval() {
        var now = 20_000L
        val limiter = TransferProgressLimiter(minimumIntervalMillis = 1_000L, nowMillis = { now })

        assertTrue(limiter.shouldPublish(task(bytesDone = 1)))
        now += 10
        assertTrue(limiter.shouldPublish(task(bytesDone = 1, status = TransferStatus.FAILED)))
    }

    @Test
    fun limiterInstancesDoNotShareStateAcrossExecutions() {
        val first = TransferProgressLimiter(nowMillis = { 1L })
        val second = TransferProgressLimiter(nowMillis = { 1L })
        val task = task(bytesDone = 1)

        assertTrue(first.shouldPublish(task))
        assertTrue(second.shouldPublish(task))
    }

    private fun task(bytesDone: Long, status: TransferStatus = TransferStatus.RUNNING) = TransferTask(
        id = "download-1",
        direction = TransferDirection.DOWNLOAD,
        status = status,
        serverBaseUrl = "https://drive.example.com",
        scope = TransferScope.Personal(),
        fileName = "large.bin",
        bytesDone = bytesDone,
        bytesTotal = 10_000,
        destinationHandle = "content://destination",
        createdAt = 1,
        updatedAt = bytesDone,
    )
}
