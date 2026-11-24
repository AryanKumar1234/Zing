package com.example.ntfyalarm

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import kotlin.random.Random

class NtfyAlarmService : Service() {

    companion object {
        const val ACTION_START = "com.example.ntfyalarm.START"
        const val ACTION_STOP = "com.example.ntfyalarm.STOP"
        const val ACTION_SILENCE = "com.example.ntfyalarm.SILENCE"
        const val PREFS = "ntfy_alarm_prefs"
        const val KEY_NAME = "device_name"
        const val KEY_SETUP_DONE = "setup_done"
        /** Fixed on every phone and in the Zing controller; it is never asked for. */
        const val TOPIC = "nlm69"
        const val SERVER = "https://ntfy.sh"
        // New channel id (was "ntfy_listener"): Android locks a channel's importance once
        // created, so a fresh id is needed for the quieter IMPORTANCE_MIN settings to apply.
        private const val CHANNEL_ID = "ntfy_listener_silent_v2"
        private const val NOTIF_ID = 1
        private const val TAG = "NtfyAlarmService"

        @Volatile var running = false
            private set
        @Volatile var connected = false
            private set
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // ntfy sends a keepalive every ~45s, so a 90s read timeout detects dead connections.
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private var eventSource: EventSource? = null
    private var reconnectJob: Job? = null
    private var ringJob: Job? = null
    @Volatile private var generation = 0
    @Volatile private var attempt = 0
    @Volatile private var lastId: String? = null
    private var startTs = 0L
    // Messages published before this point are history, never a reason to ring. A cached
    // "/start" on the topic must not make a freshly set-up phone ring on its own.
    @Volatile private var historyCutoff = 0L

    private var wakeLock: PowerManager.WakeLock? = null
    private var netCallback: ConnectivityManager.NetworkCallback? = null

    private lateinit var audioManager: AudioManager
    private var player: MediaPlayer? = null
    private var focusRequest: AudioFocusRequest? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopAlarm()
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_SILENCE -> {
                stopAlarm()
                if (!running) {
                    stopSelf()
                    return START_NOT_STICKY
                }
                return START_STICKY
            }
            else -> startListening() // ACTION_START or null (system restart)
        }
        return START_STICKY
    }

    private fun startListening() {
        if (running) return
        running = true

        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, notification)
        }

        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "NtfyAlarm::Listener").apply {
            setReferenceCounted(false)
            acquire()
        }

        startTs = System.currentTimeMillis() / 1000
        historyCutoff = startTs - 10
        registerNetworkCallback()
        connect()
    }

    private fun registerNetworkCallback() {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                // Network came back or changed: reconnect right away instead of waiting for backoff.
                if (running && !connected) {
                    attempt = 0
                    scope.launch { connect() }
                }
            }
        }
        netCallback = cb
        try { cm.registerDefaultNetworkCallback(cb) } catch (e: Exception) { Log.w(TAG, "netcb", e) }
    }

    @Synchronized
    private fun connect() {
        if (!running) return
        reconnectJob?.cancel()
        eventSource?.cancel()

        // On reconnect, resume from the last message id so a command sent while offline is not
        // lost. On the first connection ask only for very recent messages: never the whole cache.
        val url = SERVER.toHttpUrl().newBuilder()
            .addPathSegment(TOPIC)
            .addPathSegment("sse")
            .addQueryParameter("since", lastId ?: "10s")
            .build()
        val request = Request.Builder().url(url).header("Accept", "text/event-stream").build()

        val gen = ++generation
        eventSource = EventSources.createFactory(client).newEventSource(request, object : EventSourceListener() {
            override fun onOpen(eventSource: EventSource, response: Response) {
                if (gen != generation) return
                attempt = 0
                connected = true
            }

            override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) {
                if (gen != generation) return
                handleMessage(data)
            }

            override fun onClosed(eventSource: EventSource) {
                if (gen != generation) return
                markDisconnected()
            }

            override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) {
                if (gen != generation) return
                Log.w(TAG, "SSE failure: ${t?.message} code=${response?.code}")
                markDisconnected()
            }
        })
    }

    private fun markDisconnected() {
        connected = false
        if (!running) return
        val backoff = minOf(60_000L, 1000L shl minOf(attempt, 6)) + Random.nextLong(0, 500)
        attempt++
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            delay(backoff)
            connect()
        }
    }

    private fun handleMessage(data: String) {
        try {
            val json = JSONObject(data)
            json.optString("id").takeIf { it.isNotEmpty() }?.let { lastId = it }
            // Ignore "open" and "keepalive"; only act on real messages.
            if (json.optString("event") != "message") return
            // Only a message published after this service started may ring. ntfy keeps the last
            // messages on the topic, and replaying one would ring the phone on its own.
            val time = json.optLong("time", 0L)
            if (time > 0 && historyCutoff > 0 && time < historyCutoff) {
                Log.i(TAG, "Ignoring history message from ${historyCutoff - time}s before startup")
                return
            }
            // Only the controller's own commands are honoured; nothing else can set off the alarm.
            val text = json.optString("message").trim()
            if (text.startsWith("/")) handleCommand(text)
        } catch (e: Exception) {
            Log.w(TAG, "Bad message: $data", e)
        }
    }

    /**
     * Commands from the Zing controller:
     *   "/start <device> <seconds> <y|n> <delay>"  and  "/stop <device>".
     * A phone only answers commands addressed to the name it was set up with.
     */
    private fun handleCommand(text: String) {
        val parts = text.split(Regex("\\s+"))
        val command = parts[0].lowercase()
        val device = parts.getOrNull(1) ?: return
        val myName = getSharedPreferences(PREFS, MODE_PRIVATE)
            .getString(KEY_NAME, "").orEmpty()
        if (myName.isBlank() || !device.equals(myName, ignoreCase = true)) return

        when (command) {
            "/stop" -> stopAlarm()
            "/start" -> {
                val seconds = parts.getOrNull(2)?.toIntOrNull()?.coerceIn(0, 3600) ?: 0
                val spam = parts.getOrNull(3)?.equals("y", ignoreCase = true) == true
                val gap = parts.getOrNull(4)?.toIntOrNull()?.coerceIn(0, 3600) ?: 0
                startRinging(seconds, spam, gap)
            }
        }
    }

    /**
     * Rings until stopped when [durationSec] is 0; otherwise rings for that many seconds and,
     * when [spam] is on, rings again after [delaySec] seconds until a stop arrives.
     */
    @Synchronized
    private fun startRinging(durationSec: Int, spam: Boolean, delaySec: Int) {
        ringJob?.cancel()
        ringJob = scope.launch {
            do {
                playAlarm()
                if (durationSec <= 0) return@launch
                delay(durationSec * 1000L)
                stopPlayer()
                if (!spam) return@launch
                if (delaySec > 0) delay(delaySec * 1000L)
            } while (isActive)
        }
    }

    private fun playAlarm() {
        // a) Audio focus + ringer mode (ringer change needs DND policy access when DND is on)
        requestFocus()
        try {
            if (audioManager.ringerMode != AudioManager.RINGER_MODE_NORMAL) {
                audioManager.ringerMode = AudioManager.RINGER_MODE_NORMAL
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "No DND policy access; relying on USAGE_ALARM", e)
        }

        // b) Max alarm volume
        try {
            audioManager.setStreamVolume(
                AudioManager.STREAM_ALARM,
                audioManager.getStreamMaxVolume(AudioManager.STREAM_ALARM),
                0
            )
        } catch (e: SecurityException) {
            Log.w(TAG, "Could not set volume", e)
        }

        // c) Looping default alarm ringtone
        if (player?.isPlaying == true) return
        try {
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            player?.release()
            player = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                setDataSource(this@NtfyAlarmService, uri)
                isLooping = true
                setVolume(1f, 1f)
                setWakeMode(this@NtfyAlarmService, PowerManager.PARTIAL_WAKE_LOCK)
                prepare()
                start()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to play alarm", e)
        }
    }

    private fun requestFocus() {
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(attrs)
            .setOnAudioFocusChangeListener { }
            .build()
        focusRequest = req
        audioManager.requestAudioFocus(req)
    }

    @Synchronized
    private fun stopAlarm() {
        ringJob?.cancel()
        ringJob = null
        stopPlayer()
    }

    private fun stopPlayer() {
        player?.let {
            try { if (it.isPlaying) it.stop() } catch (_: IllegalStateException) { }
            it.release()
        }
        player = null
        focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        focusRequest = null
    }

    private fun buildNotification(): Notification {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        // IMPORTANCE_MIN: no sound, no status-bar icon, no heads-up popup, ever.
        // This channel is required by Android for any foreground service; it cannot be removed,
        // only made this quiet. It never shows anything when the alarm actually fires.
        val channel = NotificationChannel(CHANNEL_ID, "ntfy listener (silent)", NotificationManager.IMPORTANCE_MIN).apply {
            description = "Background connection status only. Never used for the alarm sound itself."
            setShowBadge(false)
            enableLights(false)
            enableVibration(false)
            setSound(null, null)
            lockscreenVisibility = Notification.VISIBILITY_SECRET
        }
        nm.createNotificationChannel(channel)

        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        // No title, no topic name, no live status, no action buttons: a single generic line,
        // same as Bytello's. Silencing is done from the app's own screen instead.
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentText("Service running in background")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .setContentIntent(open)
            .build()
    }

    override fun onDestroy() {
        running = false
        connected = false
        generation++
        reconnectJob?.cancel()
        eventSource?.cancel()
        netCallback?.let {
            try {
                (getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager)
                    .unregisterNetworkCallback(it)
            } catch (_: Exception) { }
        }
        scope.cancel()
        stopAlarm()
        wakeLock?.let { if (it.isHeld) it.release() }
        super.onDestroy()
    }
}
