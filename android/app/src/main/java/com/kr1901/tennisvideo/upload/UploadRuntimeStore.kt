package com.kr1901.tennisvideo.upload

import android.content.Context
import android.net.Uri

object UploadRuntimeStore {
    private const val CONNECTION_PREFS = "tvi_connection"
    private const val RUNTIME_PREFS = "tvi_upload_runtime"

    private const val KEY_BASE_URL = "base_url"
    private const val KEY_API_TOKEN = "api_token"
    private const val KEY_URI = "selected_uri"
    private const val KEY_NAME = "selected_name"
    private const val KEY_STATUS = "status"
    private const val KEY_UPLOADED = "uploaded_bytes"
    private const val KEY_TOTAL = "total_bytes"
    private const val KEY_VIDEO_ID = "video_id"
    private const val KEY_ERROR = "error"

    data class Connection(val baseUrl: String, val apiToken: String)

    data class TransferState(
        val uri: Uri?,
        val displayName: String,
        val status: String,
        val uploadedBytes: Long,
        val totalBytes: Long,
        val videoId: String?,
        val error: String?
    )

    fun saveConnection(context: Context, baseUrl: String, apiToken: String) {
        context.getSharedPreferences(CONNECTION_PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_BASE_URL, baseUrl.trim().trimEnd('/'))
            .putString(KEY_API_TOKEN, apiToken.trim())
            .apply()
    }

    fun connection(context: Context): Connection {
        val prefs = context.getSharedPreferences(CONNECTION_PREFS, Context.MODE_PRIVATE)
        return Connection(
            prefs.getString(KEY_BASE_URL, "") ?: "",
            prefs.getString(KEY_API_TOKEN, "") ?: ""
        )
    }

    fun saveSelection(context: Context, uri: Uri, displayName: String) {
        context.getSharedPreferences(RUNTIME_PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_URI, uri.toString())
            .putString(KEY_NAME, displayName)
            .apply()
    }

    fun markQueued(context: Context, uri: Uri, totalBytes: Long) {
        context.getSharedPreferences(RUNTIME_PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_URI, uri.toString())
            .putString(KEY_STATUS, "QUEUED")
            .putLong(KEY_UPLOADED, 0L)
            .putLong(KEY_TOTAL, totalBytes)
            .remove(KEY_VIDEO_ID)
            .remove(KEY_ERROR)
            .apply()
    }

    fun updateProgress(context: Context, uploaded: Long, total: Long) {
        context.getSharedPreferences(RUNTIME_PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_STATUS, "UPLOADING")
            .putLong(KEY_UPLOADED, uploaded.coerceAtLeast(0L))
            .putLong(KEY_TOTAL, total.coerceAtLeast(0L))
            .apply()
    }

    fun markComplete(context: Context, videoId: String, total: Long) {
        context.getSharedPreferences(RUNTIME_PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_STATUS, "COMPLETED")
            .putLong(KEY_UPLOADED, total)
            .putLong(KEY_TOTAL, total)
            .putString(KEY_VIDEO_ID, videoId)
            .remove(KEY_ERROR)
            .apply()
    }

    fun markPaused(context: Context) {
        context.getSharedPreferences(RUNTIME_PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_STATUS, "PAUSED")
            .apply()
    }

    fun markFailed(context: Context, error: String) {
        context.getSharedPreferences(RUNTIME_PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_STATUS, "FAILED")
            .putString(KEY_ERROR, error.take(500))
            .apply()
    }

    fun state(context: Context): TransferState {
        val prefs = context.getSharedPreferences(RUNTIME_PREFS, Context.MODE_PRIVATE)
        val uri = prefs.getString(KEY_URI, null)?.let(Uri::parse)
        return TransferState(
            uri = uri,
            displayName = prefs.getString(KEY_NAME, "Selected video") ?: "Selected video",
            status = prefs.getString(KEY_STATUS, "IDLE") ?: "IDLE",
            uploadedBytes = prefs.getLong(KEY_UPLOADED, 0L),
            totalBytes = prefs.getLong(KEY_TOTAL, 0L),
            videoId = prefs.getString(KEY_VIDEO_ID, null),
            error = prefs.getString(KEY_ERROR, null)
        )
    }
}
