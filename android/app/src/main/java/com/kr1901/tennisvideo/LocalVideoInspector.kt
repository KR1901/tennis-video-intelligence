package com.kr1901.tennisvideo

import android.content.Context
import android.os.Build
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns

internal object LocalVideoInspector {
    fun inspect(context: Context, uri: Uri): LocalVideoMetadata {
        val resolver = context.contentResolver
        var displayName = uri.lastPathSegment?.substringAfterLast('/') ?: "Selected video"
        var sizeBytes = -1L
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (nameIndex >= 0) displayName = cursor.getString(nameIndex) ?: displayName
                if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) sizeBytes = cursor.getLong(sizeIndex)
            }
        }
        if (sizeBytes <= 0L) {
            resolver.openFileDescriptor(uri, "r")?.use { descriptor ->
                if (descriptor.statSize > 0L) sizeBytes = descriptor.statSize
            }
        }

        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, uri)
            val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0L
            val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                ?.toIntOrNull() ?: 0
            val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                ?.toIntOrNull() ?: 0
            val rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
                ?.toIntOrNull() ?: 0
            if (duration <= 0L || width <= 0 || height <= 0) {
                throw IllegalArgumentException("Android could not read the video's duration or dimensions")
            }
            val mime = resolver.getType(uri) ?: "video/*"
            return LocalVideoMetadata(displayName, sizeBytes, duration, width, height, rotation, mime)
        } finally {
            retriever.release()
        }
    }

    fun frameAt(context: Context, uri: Uri, timeMs: Long): android.graphics.Bitmap? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            val timeUs = timeMs.coerceAtLeast(0L) * 1000L
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                retriever.getScaledFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST, 1280, 720)
            } else {
                val original = retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST)
                if (original != null && original.width > 1280) {
                    val scaledHeight = (original.height * 1280f / original.width).toInt().coerceAtLeast(1)
                    val scaled = android.graphics.Bitmap.createScaledBitmap(original, 1280, scaledHeight, true)
                    original.recycle()
                    scaled
                } else {
                    original
                }
            }
        } finally {
            retriever.release()
        }
    }
}
