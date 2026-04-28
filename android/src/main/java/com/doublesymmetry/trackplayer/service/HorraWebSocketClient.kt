package com.doublesymmetry.trackplayer.service

import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.doublesymmetry.trackplayer.model.Track
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Native WebSocket client that keeps schedule sync alive when the JS thread is dead.
 *
 * When the user swipe-kills the app with CONTINUE_PLAYBACK, MainActivity and the JS
 * thread die but MusicService survives as a ForegroundService. This client connects
 * to the Horra backend WS, re-authenticates, and handles:
 *   - streaming_started / next_batch  → adds tracks to the player queue
 *   - check_next_schedule             → persists the new schedule_id for JS to pick up on resume
 *   - time_based_jingle               → inserts a jingle immediately after the current track
 *
 * Lifecycle:
 *   MusicService.onTaskRemoved (CONTINUE_PLAYBACK) → start()
 *   MusicService.onCreate / onDestroy              → stop()
 *   JS calls notifyJsAlive() on foreground         → stop() (self-triggered via jsAlive check)
 */
class HorraWebSocketClient(
    private val context: Context,
    private val service: MusicService,
) {
    // Dispatches TrackPlayer operations onto the main thread.
    // OkHttp delivers callbacks on its own thread — we must never call RNTP from there.
    private val mainHandler = Handler(Looper.getMainLooper())

    // Single OkHttpClient shared for the WS connection.
    // readTimeout 0 = no timeout on reads (required for long-lived WebSockets).
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS)
        .build()

    private val prefs: SharedPreferences
        get() = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Current WebSocket connection. Nulled out on stop() or failure. */
    @Volatile private var socket: WebSocket? = null

    /** Set to true by stop() — prevents any reconnect attempt after a voluntary close. */
    @Volatile private var stopped = false

    /** Jingle ids inserted this session. Cleared on start() to avoid cross-session leaks. */
    private val insertedJingleIds = mutableSetOf<String>()

    // Exponential backoff: 3s → 6s → 12s → 24s → 60s (cap)
    private var reconnectDelayMs = RECONNECT_DELAY_INITIAL_MS

    /**
     * Runnable posted by scheduleReconnect(). Before reconnecting, checks if JS is alive —
     * if it came back to the foreground, we hand off and stop ourselves.
     */
    private val reconnectRunnable = Runnable {
        if (stopped) return@Runnable

        // If JS called notifyJsAlive() recently, it has taken over — stop native WS.
        val jsAliveAt = prefs.getLong(KEY_JS_ALIVE_AT, 0L)
        if (jsAliveAt > 0L && System.currentTimeMillis() - jsAliveAt < JS_ALIVE_THRESHOLD_MS) {
            Log.i(TAG, "JS is alive again — handing off and stopping native WS")
            stop()
            return@Runnable
        }

        Log.i(TAG, "reconnecting after ${reconnectDelayMs}ms delay")
        connect()
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Opens the WebSocket connection.
     * Clears jingle dedup state from any previous session.
     * Safe to call from any thread.
     */
    fun start() {
        stopped = false
        insertedJingleIds.clear()
        prefs.edit().putBoolean(KEY_WS_KOTLIN_ACTIVE, true).apply()
        Log.i(TAG, "start — connecting to WS")
        connect()
    }

    /**
     * Closes the socket and cancels any pending reconnect.
     * Marks horra_ws_kotlin_active = false so JS knows it can call next_batch again.
     * Safe to call from any thread.
     */
    fun stop() {
        stopped = true
        prefs.edit().putBoolean(KEY_WS_KOTLIN_ACTIVE, false).apply()
        mainHandler.removeCallbacks(reconnectRunnable)
        socket?.close(CLOSE_NORMAL, "stop requested")
        socket = null
        Log.i(TAG, "stopped")
    }

    // ── Connection management ─────────────────────────────────────────────────

    private fun connect() {
        val wsUrl = prefs.getString(KEY_WS_URL, null)
        if (wsUrl.isNullOrEmpty()) {
            Log.w(TAG, "connect: no WS URL in SharedPreferences — JS must call updateWsUrl() first")
            return
        }
        val request = Request.Builder().url(wsUrl).build()
        socket = httpClient.newWebSocket(request, Listener())
    }

    /**
     * Schedules the next reconnect attempt with exponential backoff.
     * Each call doubles the delay up to RECONNECT_DELAY_MAX_MS.
     */
    private fun scheduleReconnect() {
        if (stopped) return
        Log.i(TAG, "will reconnect in ${reconnectDelayMs}ms")
        mainHandler.postDelayed(reconnectRunnable, reconnectDelayMs)
        reconnectDelayMs = minOf(reconnectDelayMs * 2, RECONNECT_DELAY_MAX_MS)
    }

    /** Resets backoff to initial delay on a successful connection. */
    private fun resetBackoff() {
        reconnectDelayMs = RECONNECT_DELAY_INITIAL_MS
    }

    // ── Outgoing messages ─────────────────────────────────────────────────────

    private fun sendAuthenticate() {
        val token = prefs.getString(KEY_TOKEN, null)
        if (token.isNullOrEmpty()) {
            Log.w(TAG, "sendAuthenticate: no token in SharedPreferences")
            return
        }
        send(JSONObject().apply {
            put("type", "authenticate")
            put("token", token)
        })
        Log.i(TAG, "sent: authenticate")
    }

    private fun sendStartStreaming() {
        val venueId = prefs.getString(KEY_VENUE_ID, null)
        val zoneId  = prefs.getString(KEY_ZONE_ID, null)
        if (venueId.isNullOrEmpty() || zoneId.isNullOrEmpty()) {
            Log.w(TAG, "sendStartStreaming: missing venue_id or zone_id")
            return
        }
        val scheduleId = prefs.getString(KEY_WS_SCHEDULE_ID, null)
        send(JSONObject().apply {
            put("type", "start_streaming")
            put("venue_id", venueId)
            put("zone_id", zoneId)
            // Include schedule_id only if we have one — lets the server resume the right slot.
            if (!scheduleId.isNullOrEmpty()) put("schedule_id", scheduleId)
        })
        Log.i(TAG, "sent: start_streaming venue=$venueId zone=$zoneId schedule=$scheduleId")
    }

    private fun sendRequestNextBatch() {
        val scheduleId = prefs.getString(KEY_WS_SCHEDULE_ID, null)
        val position   = prefs.getLong(KEY_WS_QUEUE_POSITION, 0L)
        send(JSONObject().apply {
            put("type", "request_next_batch")
            put("position", position)
            if (!scheduleId.isNullOrEmpty()) put("sequence_id", scheduleId)
        })
        Log.i(TAG, "sent: request_next_batch position=$position schedule=$scheduleId")
    }

    /** Sends a JSON message on the current socket. No-op if socket is not open. */
    private fun send(json: JSONObject) {
        val ws = socket
        if (ws == null) {
            Log.w(TAG, "send: socket is null, dropping ${json.optString("type")}")
            return
        }
        ws.send(json.toString())
    }

    // ── Incoming message dispatch ─────────────────────────────────────────────

    /**
     * Central dispatcher for all server → client messages.
     * Called on OkHttp's thread — TrackPlayer operations are forwarded to the main thread.
     */
    private fun handleMessage(text: String) {
        val json = try {
            JSONObject(text)
        } catch (e: Exception) {
            Log.w(TAG, "handleMessage: invalid JSON — ${e.message}")
            return
        }

        val type = json.optString("type")
        Log.i(TAG, "recv: $type")

        when (type) {
            "authenticated" -> {
                // Server confirmed auth — initiate streaming immediately.
                sendStartStreaming()
            }

            "streaming_started" -> {
                // Server returned the initial queue for this schedule slot.
                val queue      = json.optJSONArray("queue") ?: JSONArray()
                val position   = json.optLong("queue_position", 0L)
                val scheduleId = json.optString("schedule_id", "")
                persistQueueState(position, scheduleId)
                mainHandler.post { addTracksToPlayer(queue) }
            }

            "next_batch" -> {
                // Server returned the next page of tracks.
                val queue      = json.optJSONArray("queue") ?: JSONArray()
                val position   = json.optLong("queue_position", 0L)
                val scheduleId = json.optString("schedule_id", "")
                persistQueueState(position, scheduleId)
                mainHandler.post { addTracksToPlayer(queue) }
            }

            "check_next_schedule" -> {
                // Server signals a schedule change. Persist for JS to pick up on resume.
                // upcoming=true  → schedule_id is the active slot
                // upcoming=false → fallback_schedule_id is the last known good slot
                val upcoming           = json.optBoolean("upcoming", false)
                val scheduleId         = json.optString("schedule_id", "")
                val fallbackScheduleId = json.optString("fallback_schedule_id", "")

                val idToStore = when {
                    upcoming && scheduleId.isNotEmpty()         -> scheduleId
                    !upcoming && fallbackScheduleId.isNotEmpty() -> fallbackScheduleId
                    else                                         -> null
                }
                if (idToStore != null) {
                    prefs.edit().putString(KEY_WS_SCHEDULE_ID, idToStore).apply()
                    Log.i(TAG, "check_next_schedule: stored schedule_id=$idToStore (upcoming=$upcoming)")
                }
            }

            "time_based_jingle" -> {
                val jingle = json.optJSONObject("jingle") ?: run {
                    Log.w(TAG, "time_based_jingle: missing 'jingle' object in payload")
                    return
                }
                mainHandler.post { insertJingleNext(jingle) }
            }

            "authentication_required" -> {
                // Session expired mid-stream — re-authenticate immediately.
                Log.i(TAG, "re-authenticating on server request")
                sendAuthenticate()
            }

            "error" -> {
                Log.e(TAG, "server error: ${json.optString("message")}")
            }

            else -> {
                // New server message types can arrive — log and ignore safely.
                Log.d(TAG, "unhandled message type: $type")
            }
        }
    }

    // ── TrackPlayer operations — MUST run on main thread ─────────────────────

    /**
     * Appends new tracks from the server queue array to the player.
     *
     * Filters:
     *   - type must be "track" or "jingle"
     *   - url must be non-empty
     *   - URL must not already be in the player queue (dedup by URL)
     *
     * Uses MusicService.add(List<Track>) which goes through the proper RNTP pipeline.
     */
    private fun addTracksToPlayer(queue: JSONArray) {
        // Build a set of URLs already in the queue so we don't add duplicates.
        val existingUrls: Set<String> = try {
            service.tracks.mapNotNull { it.uri?.toString() }.toSet()
        } catch (e: Exception) {
            Log.e(TAG, "addTracksToPlayer: failed to read existing tracks — ${e.message}")
            emptySet()
        }

        val toAdd = mutableListOf<Track>()
        for (i in 0 until queue.length()) {
            val obj  = queue.optJSONObject(i) ?: continue
            val type = obj.optString("type", "track")
            if (type != "track" && type != "jingle") continue

            val url = obj.optString("url", "")
            if (url.isEmpty() || url in existingUrls) continue

            val track = buildTrack(obj)
            toAdd.add(track)
        }

        if (toAdd.isEmpty()) {
            Log.d(TAG, "addTracksToPlayer: no new tracks to add")
            return
        }

        try {
            service.add(toAdd)
            Log.i(TAG, "addTracksToPlayer: added ${toAdd.size} tracks")
        } catch (e: Exception) {
            Log.e(TAG, "addTracksToPlayer: service.add() failed — ${e.message}")
        }
    }

    /**
     * Inserts a time-based jingle immediately after the currently playing track.
     *
     * Deduplicates by jingle id (in-memory, reset on start()).
     * Uses MusicService.add(List<Track>, atIndex) for correct RNTP integration.
     */
    private fun insertJingleNext(jingle: JSONObject) {
        val jingleId = jingle.optString("id", "").ifEmpty {
            jingle.optString("jingle_id", "")
        }
        if (jingleId.isEmpty()) {
            Log.w(TAG, "insertJingleNext: jingle has no id — skipping")
            return
        }
        if (jingleId in insertedJingleIds) {
            Log.d(TAG, "insertJingleNext: jingle $jingleId already inserted this session — skip")
            return
        }

        val url = jingle.optString("url", "")
        if (url.isEmpty()) {
            Log.w(TAG, "insertJingleNext: jingle $jingleId has no url — skipping")
            return
        }

        try {
            val insertAt = service.getCurrentTrackIndex() + 1
            service.add(listOf(buildTrack(jingle)), insertAt)
            insertedJingleIds.add(jingleId)
            Log.i(TAG, "insertJingleNext: jingle $jingleId inserted at index $insertAt")
        } catch (e: Exception) {
            Log.e(TAG, "insertJingleNext: failed — ${e.message}")
        }
    }

    /**
     * Builds a Track from a JSON object returned by the server.
     *
     * Maps server field names to the Bundle keys expected by Track's constructor.
     * ratingType = 0 (RatingCompat.RATING_NONE) — Horra does not use ratings.
     */
    private fun buildTrack(obj: JSONObject): Track {
        val bundle = Bundle().apply {
            putString("url",      obj.optString("url", ""))
            putString("title",    obj.optString("title", ""))
            putString("artist",   obj.optString("artist", ""))
            putString("artwork",  obj.optString("image", "").ifEmpty {
                obj.optString("album_cover", "").ifEmpty {
                    obj.optString("image_url", "")
                }
            })
            // Preserve both id fields so downstream code (heartbeat, logging) can find the id.
            putString("id",       obj.optString("id", obj.optString("track_id", "")))
            putString("track_id", obj.optString("track_id", obj.optString("id", "")))
            // Mark jingles so JS can detect them after resume
            putBoolean("is_jingle", obj.optBoolean("is_jingle", false))
        }
        return Track(context, bundle, 0)
    }

    // ── SharedPreferences helpers ─────────────────────────────────────────────

    /**
     * Persists queue_position and schedule_id so JS can resync without
     * requesting a full restart when it returns to the foreground.
     */
    private fun persistQueueState(position: Long, scheduleId: String) {
        prefs.edit().apply {
            putLong(KEY_WS_QUEUE_POSITION, position)
            if (scheduleId.isNotEmpty()) putString(KEY_WS_SCHEDULE_ID, scheduleId)
        }.apply()
    }

    // ── OkHttp WebSocket listener ─────────────────────────────────────────────

    private inner class Listener : WebSocketListener() {

        override fun onOpen(webSocket: WebSocket, response: Response) {
            Log.i(TAG, "onOpen — authenticating")
            resetBackoff()
            sendAuthenticate()
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            // Called on OkHttp's thread. handleMessage dispatches TrackPlayer calls
            // to the main thread via mainHandler — do NOT touch TrackPlayer here.
            handleMessage(text)
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            Log.i(TAG, "onClosing code=$code reason=$reason")
            // Acknowledge the close handshake.
            webSocket.close(CLOSE_NORMAL, null)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            Log.i(TAG, "onClosed code=$code")
            if (!stopped) scheduleReconnect()
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            Log.e(TAG, "onFailure: ${t.message}")
            socket = null
            if (!stopped) scheduleReconnect()
        }
    }

    // ── Constants ─────────────────────────────────────────────────────────────

    companion object {
        private const val TAG = "HorraWsClient"

        /**
         * SharedPreferences file shared with HeartbeatManager and HeartbeatBridgeModule.
         * All three files read/write this same store — the name MUST NOT differ.
         */
        private const val PREFS_NAME = "horra_heartbeat"

        // Keys shared with HeartbeatManager.kt
        private const val KEY_TOKEN   = "horra_hb_token"
        private const val KEY_ZONE_ID = "horra_hb_zone_id"

        // Keys shared with HeartbeatBridgeModule.kt
        private const val KEY_JS_ALIVE_AT       = "horra_js_alive_at"
        private const val KEY_WS_URL            = "horra_ws_url"
        private const val KEY_VENUE_ID          = "horra_ws_venue_id"
        private const val KEY_WS_KOTLIN_ACTIVE  = "horra_ws_kotlin_active"
        private const val KEY_WS_SCHEDULE_ID    = "horra_ws_schedule_id"
        private const val KEY_WS_QUEUE_POSITION = "horra_ws_queue_position"

        /** If JS called notifyJsAlive() within this window, we consider it alive. */
        private const val JS_ALIVE_THRESHOLD_MS = 10_000L

        /** Normal WebSocket close code (RFC 6455). */
        private const val CLOSE_NORMAL = 1000

        // Exponential backoff parameters
        private const val RECONNECT_DELAY_INITIAL_MS = 3_000L
        private const val RECONNECT_DELAY_MAX_MS     = 60_000L
    }
}
