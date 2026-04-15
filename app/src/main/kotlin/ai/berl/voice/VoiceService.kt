package ai.berl.voice

import android.app.*
import android.content.Intent
import android.media.MediaRecorder
import android.os.IBinder
import android.speech.tts.TextToSpeech
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import okhttp3.*
import okio.ByteString.Companion.toByteString
import org.json.JSONObject
import java.io.File
import java.util.Locale
import java.util.concurrent.TimeUnit

class VoiceService : Service() {

    companion object {
        const val TAG = "VoiceService"
        const val WS_URL = "wss://voice.berl.ai"
        const val CHANNEL_ID = "berlai_voice"
        const val NOTIF_ID = 1
        const val ACTION_STOP = "ai.berl.voice.STOP"
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var webSocket: WebSocket? = null
    private var tts: TextToSpeech? = null
    private var isRunning = false
    private val client = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS) // no timeout for WS
        .build()

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        initTts()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        startForeground(NOTIF_ID, buildNotification("Connecting..."))
        isRunning = true
        scope.launch { connectAndRecord() }
        return START_STICKY
    }

    override fun onDestroy() {
        isRunning = false
        scope.cancel()
        webSocket?.close(1000, "stopped")
        tts?.shutdown()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ── WebSocket + record loop ─────────────────────────────────────────────

    private suspend fun connectAndRecord() {
        while (isRunning) {
            Log.i(TAG, "Connecting to $WS_URL")
            updateNotification("Connecting...")

            val latch = CompletableDeferred<Boolean>()
            val request = Request.Builder().url(WS_URL).build()

            val ws = client.newWebSocket(request, object : WebSocketListener() {
                override fun onOpen(ws: WebSocket, response: Response) {
                    Log.i(TAG, "Connected")
                    updateNotification("Listening…")
                    webSocket = ws
                    latch.complete(true)
                }

                override fun onMessage(ws: WebSocket, text: String) {
                    try {
                        val json = JSONObject(text)
                        when (json.optString("type")) {
                            "reply" -> {
                                val reply = json.getString("text")
                                Log.i(TAG, "BerlAI: $reply")
                                speak(reply)
                            }
                            "transcript" -> Log.i(TAG, "You: ${json.optString("text")}")
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Message parse error: ${e.message}")
                    }
                }

                override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                    Log.w(TAG, "WS failure: ${t.message}")
                    webSocket = null
                    if (!latch.isCompleted) latch.complete(false)
                }

                override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                    webSocket = null
                }
            })

            val connected = withTimeoutOrNull(10_000) { latch.await() } ?: false

            if (!connected) {
                Log.w(TAG, "Connection failed — retrying in 3s")
                updateNotification("Disconnected — retrying…")
                delay(3_000)
                continue
            }

            // Record loop while connected
            while (isRunning && webSocket != null) {
                try {
                    val bytes = recordChunk()
                    if (bytes != null && bytes.size > 100) {
                        webSocket?.send(bytes.toByteString())
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Record error: ${e.message}")
                    break
                }
            }

            ws.cancel()
            webSocket = null

            if (isRunning) {
                updateNotification("Reconnecting…")
                delay(3_000)
            }
        }
    }

    // ── Audio recording ─────────────────────────────────────────────────────

    private fun recordChunk(): ByteArray? {
        val file = File(cacheDir, "chunk_${System.currentTimeMillis()}.aac")
        return try {
            @Suppress("DEPRECATION")
            val recorder = MediaRecorder().apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.AAC_ADTS)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioSamplingRate(16_000)
                setAudioEncodingBitRate(32_000)
                setOutputFile(file.absolutePath)
                prepare()
                start()
            }
            Thread.sleep(2_000)
            try { recorder.stop() } catch (_: Exception) {}
            recorder.release()

            if (file.exists() && file.length() > 100) file.readBytes() else null
        } catch (e: Exception) {
            Log.w(TAG, "Recorder error: ${e.message}")
            null
        } finally {
            file.delete()
        }
    }

    // ── TTS ─────────────────────────────────────────────────────────────────

    private fun initTts() {
        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale.US
                tts?.setSpeechRate(1.05f)
            }
        }
    }

    private fun speak(text: String) {
        val clean = text
            .replace(Regex("\\*\\*?(.+?)\\*\\*?"), "$1")
            .replace(Regex("#+\\s*"), "")
            .replace(Regex("\\n+"), " ")
            .trim()
        tts?.speak(clean, TextToSpeech.QUEUE_FLUSH, null, "berlai")
    }

    // ── Notification ─────────────────────────────────────────────────────────

    private fun createNotificationChannel() {
        val chan = NotificationChannel(
            CHANNEL_ID, "BerlAI Voice",
            NotificationManager.IMPORTANCE_LOW
        ).apply { description = "BerlAI voice assistant" }
        getSystemService(NotificationManager::class.java).createNotificationChannel(chan)
    }

    private fun buildNotification(status: String): Notification {
        val stopIntent = PendingIntent.getService(
            this, 0,
            Intent(this, VoiceService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("BerlAI Voice")
            .setContentText(status)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_delete, "Stop", stopIntent)
            .build()
    }

    private fun updateNotification(status: String) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIF_ID, buildNotification(status))
    }
}
