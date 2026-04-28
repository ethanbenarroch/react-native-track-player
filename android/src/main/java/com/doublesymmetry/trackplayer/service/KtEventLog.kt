package com.doublesymmetry.trackplayer.service

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

/**
 * Tiny append-only event log written to SharedPreferences.
 *
 * HeartbeatManager and HorraWebSocketClient write key lifecycle events here.
 * JS reads and clears the log on foreground via HeartbeatBridgeModule.drainKtEvents(),
 * then forwards each entry to plog() so they appear in the persistent log.
 *
 * Format: JSON array of objects, newest last, capped at MAX_ENTRIES.
 * Each entry: { "ts": <epoch_ms>, "tag": "<TAG>", "event": "<message>" }
 */
object KtEventLog {

    private const val PREFS_NAME  = "horra_heartbeat"
    private const val KEY_EVENTS  = "horra_kt_events"
    private const val MAX_ENTRIES = 30
    private const val TAG         = "KtEventLog"

    fun append(context: Context, tag: String, event: String) {
        try {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val raw   = prefs.getString(KEY_EVENTS, "[]") ?: "[]"
            val arr   = try { JSONArray(raw) } catch (_: Exception) { JSONArray() }

            val entry = JSONObject().apply {
                put("ts",    System.currentTimeMillis())
                put("tag",   tag)
                put("event", event)
            }
            arr.put(entry)

            // Trim oldest entries if over cap
            val trimmed = if (arr.length() > MAX_ENTRIES) {
                val start = arr.length() - MAX_ENTRIES
                JSONArray().also { out ->
                    for (i in start until arr.length()) out.put(arr.getJSONObject(i))
                }
            } else arr

            prefs.edit().putString(KEY_EVENTS, trimmed.toString()).apply()
        } catch (e: Exception) {
            Log.w(TAG, "append failed: ${e.message}")
        }
    }

    /** Returns the current log as a JSON string and clears it atomically. */
    fun drainAndClear(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val raw   = prefs.getString(KEY_EVENTS, "[]") ?: "[]"
        prefs.edit().putString(KEY_EVENTS, "[]").apply()
        return raw
    }
}
