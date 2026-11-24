package com.kr1901.tennisvideo

import androidx.work.WorkInfo

/**
 * Detects requests that remain queued/processing after WorkManager stops making progress.
 */
internal object AnalysisWorkRecovery {
    const val QUEUED_TIMEOUT_MS = 2 * 60 * 1000L
    const val RUNNING_STALL_TIMEOUT_MS = 10 * 60 * 1000L

    fun shouldOfferRetry(
        projectStatus: String,
        workState: WorkInfo.State?,
        workQuerySucceeded: Boolean,
        queuedAtMs: Long,
        lastActivityAtMs: Long,
        nowMs: Long
    ): Boolean {
        if (projectStatus != "queued" && projectStatus != "processing") return false
        if (!workQuerySucceeded) return false

        return when (workState) {
            null -> true
            WorkInfo.State.ENQUEUED,
            WorkInfo.State.BLOCKED -> {
                val referenceTime = if (projectStatus == "processing" && lastActivityAtMs > 0L) {
                    lastActivityAtMs
                } else {
                    queuedAtMs
                }
                referenceTime <= 0L || nowMs - referenceTime >= QUEUED_TIMEOUT_MS
            }
            WorkInfo.State.RUNNING -> {
                lastActivityAtMs > 0L &&
                    nowMs - lastActivityAtMs >= RUNNING_STALL_TIMEOUT_MS
            }
            else -> true
        }
    }
}
