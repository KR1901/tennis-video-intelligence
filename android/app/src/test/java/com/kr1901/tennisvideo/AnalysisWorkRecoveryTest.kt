package com.kr1901.tennisvideo

import androidx.work.WorkInfo
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AnalysisWorkRecoveryTest {
    private val now = 1_000_000L

    @Test
    fun queuedWorkIsNotOfferedForRetryImmediately() {
        assertFalse(
            AnalysisWorkRecovery.shouldOfferRetry(
                "queued", WorkInfo.State.ENQUEUED, true,
                now - 30_000L, 0L, now
            )
        )
    }

    @Test
    fun enqueuedWorkOlderThanTwoMinutesCanBeRetried() {
        assertTrue(
            AnalysisWorkRecovery.shouldOfferRetry(
                "queued", WorkInfo.State.ENQUEUED, true,
                now - AnalysisWorkRecovery.QUEUED_TIMEOUT_MS - 1L, 0L, now
            )
        )
    }

    @Test
    fun continuationQueuedSoonAfterProgressIsNotOfferedForRetry() {
        assertFalse(
            AnalysisWorkRecovery.shouldOfferRetry(
                "processing", WorkInfo.State.ENQUEUED, true,
                now - 30 * 60_000L, now - 30_000L, now
            )
        )
    }

    @Test
    fun continuationQueuedWithoutRecentProgressCanBeRetried() {
        assertTrue(
            AnalysisWorkRecovery.shouldOfferRetry(
                "processing", WorkInfo.State.ENQUEUED, true,
                now - 30 * 60_000L,
                now - AnalysisWorkRecovery.QUEUED_TIMEOUT_MS - 1L,
                now
            )
        )
    }

    @Test
    fun missingWorkCanBeRetried() {
        assertTrue(
            AnalysisWorkRecovery.shouldOfferRetry(
                "processing", null, true,
                now - 60_000L, now - 60_000L, now
            )
        )
    }

    @Test
    fun recentlyRunningWorkIsNotOfferedForRetry() {
        assertFalse(
            AnalysisWorkRecovery.shouldOfferRetry(
                "processing", WorkInfo.State.RUNNING, true,
                now - 5 * 60_000L, now - 60_000L, now
            )
        )
    }

    @Test
    fun runningWorkWithoutHeartbeatForTenMinutesCanBeRetried() {
        assertTrue(
            AnalysisWorkRecovery.shouldOfferRetry(
                "processing", WorkInfo.State.RUNNING, true,
                now - 20 * 60_000L,
                now - AnalysisWorkRecovery.RUNNING_STALL_TIMEOUT_MS - 1L,
                now
            )
        )
    }

    @Test
    fun terminalProjectStatusDoesNotOfferRetry() {
        assertFalse(
            AnalysisWorkRecovery.shouldOfferRetry(
                "completed", WorkInfo.State.SUCCEEDED, true,
                now - 20 * 60_000L, now - 20 * 60_000L, now
            )
        )
    }

    @Test
    fun failedWorkQueryDoesNotOfferRetryPrematurely() {
        assertFalse(
            AnalysisWorkRecovery.shouldOfferRetry(
                "queued", null, false, now, 0L, now
            )
        )
    }
}
