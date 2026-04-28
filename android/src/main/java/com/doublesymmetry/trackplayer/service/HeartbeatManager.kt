package com.doublesymmetry.trackplayer.service

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class HeartbeatManager(private val context: Context) {

    private val handler = Handler(Looper.getMainLooper())
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    private val prefs: SharedPreferences
        get() = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val tickRunnable = object : Runnable {
        override fun run() {
            tryHeartbeat()
            handler.postDelayed(this, INTERVAL_MS)
        }
    }

    fun start() {
        Log.i(TAG, "start")
        handler.postDelayed(tickRunnable, INTERVAL_MS)
    }

    fun stop() {
        Log.i(TAG, "stop")
        handler.removeCallbacks(tickRunnable)
    }

    private fun tryHeartbeat() {
        val p = prefs
        val token = p.getString(KEY_TOKEN, null)
        val zoneId = p.getString(KEY_ZONE_ID, null)
        val songId = p.getString(KEY_SONG_ID, null)
        val isJingle = p.getBoolean(KEY_IS_JINGLE, false)
        val lastSentAt = p.getLong(KEY_LAST_HB_SENT_AT, 0L)
        val now = System.currentTimeMillis()

        if (token.isNullOrEmpty()) {
            Log.d(TAG, "skip: no token")
            return
        }
        if (zoneId.isNullOrEmpty()) {
            Log.d(TAG, "skip: no zoneId")
            return
        }
        if (songId.isNullOrEmpty()) {
            Log.d(TAG, "skip: no songId")
            return
        }
        if (now - lastSentAt < DEDUP_WINDOW_MS) {
            Log.d(TAG, "skip: dedup age=${now - lastSentAt}ms")
            return
        }

        val baseUrl = p.getString(KEY_BASE_URL, DEFAULT_BASE_URL) ?: DEFAULT_BASE_URL
        Log.i(TAG, "send songId=$songId zoneId=$zoneId")
        sendHeartbeat(token, zoneId, songId, isJingle, baseUrl, now)
    }

    private fun sendHeartbeat(
        token: String,
        zoneId: String,
        songId: String,
        isJingle: Boolean,
        baseUrl: String,
        sentAt: Long,
    ) {
        val body = JSONObject().apply {
            put("song_id", songId)
            put("zone_id", zoneId)
            put("is_jingle", isJingle)
            put("platform", "android")
        }.toString().toRequestBody("application/json".toMediaType())

        val request = Request.Builder()
            .url("$baseUrl/streaming/next-track-alive")
            .post(body)
            .header("Authorization", "Bearer $token")
            .header("Content-Type", "application/json")
            .build()

        // Run HTTP on a background thread — Handler is on main, we must not block it.
        Thread {
            try {
                val response = client.newCall(request).execute()
                val code = response.code
                response.close()
                if (code in 200..299) {
                    prefs.edit().putLong(KEY_LAST_HB_SENT_AT, sentAt).apply()
                    Log.i(TAG, "success code=$code")
                } else {
                    Log.w(TAG, "http error code=$code")
                }
            } catch (e: Exception) {
                Log.e(TAG, "network error: ${e.message}")
            }
        }.start()
    }

    companion object {
        private const val TAG = "HeartbeatManager"
        private const val PREFS_NAME = "horra_heartbeat"
        private const val INTERVAL_MS = 60_000L
        private const val DEDUP_WINDOW_MS = 55_000L

        const val KEY_TOKEN = "horra_hb_token"
        const val KEY_ZONE_ID = "horra_hb_zone_id"
        const val KEY_SONG_ID = "horra_hb_song_id"
        const val KEY_IS_JINGLE = "horra_hb_is_jingle"
        const val KEY_LAST_HB_SENT_AT = "horra_last_hb_sent_at"
        const val KEY_BASE_URL = "horra_hb_base_url"

        // Fallback if JS hasn't written the base URL yet.
        private const val DEFAULT_BASE_URL = "https://app.horra.music/api"
    }
}
