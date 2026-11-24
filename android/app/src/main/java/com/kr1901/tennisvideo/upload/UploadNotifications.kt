package com.kr1901.tennisvideo.upload

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import java.util.Locale

object UploadNotifications {
    const val CHANNEL_ID = "video_uploads"
    const val CHANNEL_NAME = "Video uploads"
    const val NOTIFICATION_ID = 4107

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = context.getSystemService(NotificationManager::class.java)
            if (manager.getNotificationChannel(CHANNEL_ID) == null) {
                manager.createNotificationChannel(
                    NotificationChannel(
                        CHANNEL_ID,
                        CHANNEL_NAME,
                        NotificationManager.IMPORTANCE_LOW
                    ).apply {
                        description = "Shows progress while match videos upload in the background."
                        setShowBadge(false)
                    }
                )
            }
        }
    }

    fun build(context: Context, uploadedBytes: Long, totalBytes: Long, title: String = "Uploading match video"): Notification {
        ensureChannel(context)
        val progress = if (totalBytes > 0L) {
            ((uploadedBytes.coerceAtLeast(0L).toDouble() / totalBytes.toDouble()) * 100.0)
                .toInt()
                .coerceIn(0, 100)
        } else {
            0
        }
        val text = if (totalBytes > 0L) {
            "${formatBytes(uploadedBytes)} of ${formatBytes(totalBytes)} · $progress%"
        } else {
            "Preparing secure upload…"
        }
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle(title)
            .setContentText(text)
            .setProgress(100, progress, totalBytes <= 0L)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    fun complete(context: Context, videoId: String) {
        ensureChannel(context)
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.notify(
            NOTIFICATION_ID,
            NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_upload_done)
                .setContentTitle("Match video uploaded")
                .setContentText("Upload complete. Video ID: $videoId")
                .setAutoCancel(true)
                .setOngoing(false)
                .build()
        )
    }

    fun failed(context: Context, message: String) {
        ensureChannel(context)
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.notify(
            NOTIFICATION_ID,
            NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_notify_error)
                .setContentTitle("Video upload needs attention")
                .setContentText(message.take(180))
                .setAutoCancel(true)
                .setOngoing(false)
                .build()
        )
    }

    private fun formatBytes(bytes: Long): String {
        val units = listOf("B", "KB", "MB", "GB")
        var value = bytes.coerceAtLeast(0L).toDouble()
        var unit = 0
        while (value >= 1024.0 && unit < units.lastIndex) {
            value /= 1024.0
            unit += 1
        }
        return if (unit == 0) "$bytes B" else String.format(Locale.US, "%.1f %s", value, units[unit])
    }
}
